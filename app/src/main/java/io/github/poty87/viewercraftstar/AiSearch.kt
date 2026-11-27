package io.github.poty87.viewercraftstar

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer
import java.security.MessageDigest

/**
 * Ricerca per soggetti ("gatti", "auto d'epoca"): il catalogo lo prepara PresetCraftAppLicator sul PC di casa (motore
 * IA) e lo lascia nella cartella delle librerie sul NAS, in <libreria>/ia/:
 *   manifest.json   modello, numero di foto, file del telefono (con dimensione e SHA-256)
 *   visivo.bin      un'impronta per foto: "VCSV", formato, dimensione, quante, tipo (1 = float16), id (int32), vettori
 *   telefono/       la parte "testo" del modello (ONNX, pesi compressi) + tokenizer.json
 *   descrizioni.json.gz  (facoltativo) una o due frasi e le parole chiave per foto, scritte da Qwen3-VL sul PC
 * Sul telefono: la frase diventa un'impronta (ONNX Runtime) e si confronta con quelle delle foto.
 */
object AiStore {
    private fun libDir(context: Context, lib: String) =
        File(context.filesDir, "libraries/" + lib.replace(Regex("[\\\\/:*?\"<>|]"), "_") + "/ia")

    private fun modelRoot(context: Context) = File(context.filesDir, "ia-model")

    private fun join(vararg parts: String) = parts.filter { it.isNotEmpty() }.joinToString("\\") { it.trim('\\', '/') }

    /** Il manifest della libreria già sul telefono, o null. */
    fun manifest(context: Context, lib: String): JSONObject? =
        File(libDir(context, lib), "manifest.json").takeIf { it.exists() }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }

    fun visualFile(context: Context, lib: String) = File(libDir(context, lib), "visivo.bin")

    fun descriptionsFile(context: Context, lib: String) = File(libDir(context, lib), "descrizioni.json.gz")

    /** Cartella del modello del telefono descritto dal manifest (una per modello e compressione, condivisa tra librerie). */
    fun modelDir(context: Context, m: JSONObject): File? {
        val ph = m.optJSONObject("phone") ?: return null
        val files = ph.optJSONObject("files")
        val data = files?.optJSONObject(ph.optString("onnx") + ".data")?.optString("sha256") ?: ""
        // anche il file .onnx: un modello nuovo può avere gli stessi pesi e un grafo diverso
        val onnx = files?.optJSONObject(ph.optString("onnx"))?.optString("sha256") ?: ""
        return File(modelRoot(context), "${ph.optString("model")}-${ph.optInt("bits")}-${data.take(12)}-${onnx.take(8)}")
    }

    /** Il modello del telefono per questa libreria è già scaricato e completo. */
    fun modelReady(context: Context, m: JSONObject?): Boolean {
        m ?: return false
        val dir = modelDir(context, m) ?: return false
        return File(dir, "ok").exists()
    }

    /** Dimensione in byte del modello del telefono indicato dal manifest. */
    fun modelSize(m: JSONObject?): Long {
        val files = m?.optJSONObject("phone")?.optJSONObject("files") ?: return 0
        return files.keys().asSequence().sumOf { files.getJSONObject(it).optLong("size") }
    }

    /**
     * Aggiorna manifest e impronte della libreria dal NAS (solo se cambiati). Ritorna il manifest, o null se la libreria
     * non ha (ancora) un catalogo IA.
     */
    suspend fun syncLibrary(context: Context, cfg: NasConfig, share: String, folder: String, lib: String): JSONObject? {
        val dir = libDir(context, lib)
        return Remote.use(cfg, share) { s ->
            val path = join(folder, lib, "ia", "manifest.json")
            if (!s.fileExists(path)) return@use null
            val text = Remote.open(s, path).use { r -> String(r.read(0, r.size.toInt()), Charsets.UTF_8) }
            val remote = JSONObject(text)
            val local = manifest(context, lib)
            val vis = File(dir, "visivo.bin")
            val same = local != null && local.optString("updated") == remote.optString("updated") &&
                local.optLong("visual_size") == remote.optLong("visual_size") && vis.length() == remote.optLong("visual_size")
            if (!same) {
                dir.mkdirs()
                val tmp = File(dir, "visivo.bin.tmp")
                Remote.open(s, join(folder, lib, "ia", remote.optString("visual", "visivo.bin"))).use { r ->
                    tmp.outputStream().use { out ->
                        var at = 0L
                        while (at < r.size) {
                            val chunk = r.read(at, Remote.BIG_READ)
                            if (chunk.isEmpty()) break
                            out.write(chunk)
                            at += chunk.size
                        }
                    }
                }
                tmp.renameTo(vis)
                AiEngine.forget(vis)
            }
            // descrizioni: si riscaricano quando cambiano (dimensione diversa da quella del manifest)
            val desc = remote.optJSONObject("descriptions")
            val descFile = File(dir, "descrizioni.json.gz")
            if (desc != null && descFile.length() != desc.optLong("size")) {
                val tmp = File(dir, "descrizioni.json.gz.tmp")
                Remote.open(s, join(folder, lib, "ia", desc.optString("file", "descrizioni.json.gz"))).use { r ->
                    tmp.outputStream().use { out ->
                        var at = 0L
                        while (at < r.size) {
                            val chunk = r.read(at, Remote.BIG_READ)
                            if (chunk.isEmpty()) break
                            out.write(chunk)
                            at += chunk.size
                        }
                    }
                }
                tmp.renameTo(descFile)
                AiEngine.forget(descFile)
            } else if (desc == null && descFile.exists()) {
                descFile.delete()
            }
            if (!same || local?.optJSONObject("descriptions")?.toString() != desc?.toString()) File(dir, "manifest.json").writeText(text)
            remote
        }
    }

    /**
     * Scarica il modello del telefono indicato dal manifest (riprende un download interrotto, controlla lo SHA-256).
     * `progress(fatti, totale)` in byte.
     */
    suspend fun downloadModel(context: Context, cfg: NasConfig, share: String, folder: String, lib: String,
                              m: JSONObject, progress: (Long, Long) -> Unit) {
        val ph = m.getJSONObject("phone")
        val files = ph.getJSONObject("files")
        val dir = modelDir(context, m) ?: error("manifest senza modello")
        dir.mkdirs()
        val total = modelSize(m)
        var before = 0L
        for (name in files.keys().asSequence().toList().sortedBy { files.getJSONObject(it).optLong("size") }) {
            val info = files.getJSONObject(name)
            val size = info.getLong("size")
            val sha = info.getString("sha256")
            val dest = File(dir, name)
            if (dest.exists() && dest.length() == size) { before += size; progress(before, total); continue }
            // lo stesso file già scaricato per un'altra versione del modello (di solito i pesi): si sposta, non si riscarica
            val same = modelRoot(context).listFiles()?.filter { it.isDirectory && it != dir }
                ?.map { File(it, name) }?.firstOrNull { it.length() == size && sha256(it) == sha }
            if (same != null && same.renameTo(dest)) { before += size; progress(before, total); continue }
            val part = File(dir, "$name.part")
            // su più connessioni insieme (in VPN rende quasi il doppio); riprende da dove si era fermato
            RandomAccessFile(part, "rw").use { out ->
                val start = minOf(part.length(), size)
                out.seek(start)
                Remote.download(cfg, share, modelPath(folder, lib, ph, name), start) { chunk, at ->
                    out.write(chunk)
                    progress(before + at, total)
                }
            }
            if (part.length() != size || sha256(part) != sha) {
                part.delete()
                error("$name: download non valido, riprova")
            }
            part.renameTo(dest)
            before += size
        }
        File(dir, "model.json").writeText(ph.toString())
        File(dir, "ok").writeText("ok")
        // modelli vecchi (altre versioni) non più usati: spazio liberato
        modelRoot(context).listFiles()?.filter { it.isDirectory && it != dir }?.forEach { it.deleteRecursively() }
    }

    /**
     * Dove sta sul NAS un file del modello: nella cartella condivisa da tutte le librerie (`phone.shared`, da
     * PresetCraftAppLicator 1.30.2: <cartella di servizio>/models/…) o, prima, in <libreria>/ia/telefono.
     */
    private fun modelPath(folder: String, lib: String, ph: JSONObject, name: String): String {
        val shared = ph.optString("shared")
        if (shared.isEmpty()) return join(folder, lib, "ia", "telefono", name)
        val f = folder.trimEnd('\\')
        val root = if (f.endsWith("libraries", ignoreCase = true)) f.substringBeforeLast('\\', "") else f
        return join(root, shared.replace('/', '\\'), name)
    }

    fun deleteModels(context: Context) {
        AiEngine.close()
        modelRoot(context).deleteRecursively()
    }

    fun modelsSize(context: Context): Long = modelRoot(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = inp.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Le impronte delle foto in memoria, compatte: un byte per numero (scala per foto), ~1,3 KB a foto con MetaCLIP 2
 * giant (24 MB per 18.000 foto). Il confronto con l'impronta della frase è un prodotto scalare.
 */
class VisualIndex(file: File) {
    val ids: IntArray
    val dim: Int
    private val q: ByteArray
    private val scale: FloatArray

    init {
        val raw = file.readBytes()
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val magic = String(raw, 0, 4, Charsets.US_ASCII)
        require(magic == "VCSV") { "visivo.bin non valido" }
        bb.position(4)
        require(bb.int == 1) { "formato di visivo.bin non supportato" }
        dim = bb.int
        val n = bb.int
        require(bb.int == 1) { "tipo di visivo.bin non supportato" }
        ids = IntArray(n) { bb.int }
        q = ByteArray(n * dim)
        scale = FloatArray(n)
        val row = FloatArray(dim)
        for (i in 0 until n) {
            var mx = 1e-8f
            for (k in 0 until dim) {
                val v = halfToFloat(bb.short)
                row[k] = v
                if (kotlin.math.abs(v) > mx) mx = kotlin.math.abs(v)
            }
            val s = mx / 127f
            scale[i] = s
            for (k in 0 until dim) q[i * dim + k] = Math.round(row[k] / s).coerceIn(-127, 127).toByte()
        }
    }

    /** Punteggio (somiglianza) di ogni foto con l'impronta della frase, nello stesso ordine di `ids`. */
    fun scores(query: FloatArray): FloatArray {
        val out = FloatArray(ids.size)
        for (i in ids.indices) {
            var acc = 0f
            val base = i * dim
            for (k in 0 until dim) acc += q[base + k] * query[k]
            out[i] = acc * scale[i]
        }
        return out
    }

    companion object {
        fun halfToFloat(h: Short): Float {
            val bits = h.toInt() and 0xFFFF
            val sign = bits ushr 15
            val exp = (bits ushr 10) and 0x1F
            val mant = bits and 0x3FF
            val f = when (exp) {
                0 -> if (mant == 0) 0f else (mant / 1024f) * Math.pow(2.0, -14.0).toFloat()
                31 -> if (mant == 0) Float.POSITIVE_INFINITY else Float.NaN
                else -> (1 + mant / 1024f) * Math.pow(2.0, (exp - 15).toDouble()).toFloat()
            }
            return if (sign == 1) -f else f
        }
    }
}

/** La parte "testo" del modello sul telefono: frase → impronta (normalizzata), con ONNX Runtime sulla CPU. */
class TextEncoder(dir: File) : AutoCloseable {
    private val info = JSONObject(File(dir, "model.json").readText())
    private val tokenizer = AiTokenizer.load(File(dir, info.optString("tokenizer", "tokenizer.json")))
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val maxLen = info.optInt("max_len", 77)
    private val pad = info.optInt("pad_id", 1)
    /** Modelli da PresetCraftAppLicator 1.18.0: la frase va passata lunga quanto è (molto più veloce). */
    private val dynamicLen = info.optBoolean("dynamic_len", false)
    private val input = info.optString("input", "input_ids")

    init {
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(minOf(4, Runtime.getRuntime().availableProcessors()))
        }
        session = env.createSession(File(dir, info.optString("onnx", "testo.onnx")).path, opts)
    }

    fun encode(text: String): FloatArray {
        val ids = tokenizer.encode(text)
        val arr = LongArray(if (dynamicLen) minOf(maxLen, ids.size) else maxLen) { pad.toLong() }
        for (i in 0 until minOf(maxLen, ids.size)) arr[i] = ids[i].toLong()
        if (ids.size > maxLen) arr[maxLen - 1] = tokenizer.eos.toLong() // frase troppo lunga: si tronca, chiusa con </s>
        OnnxTensor.createTensor(env, LongBuffer.wrap(arr), longArrayOf(1, arr.size.toLong())).use { t ->
            session.run(mapOf(input to t)).use { r ->
                @Suppress("UNCHECKED_CAST")
                val out = (r[0].value as Array<FloatArray>)[0]
                var n = 0f
                for (v in out) n += v * v
                n = kotlin.math.sqrt(n).coerceAtLeast(1e-8f)
                return FloatArray(out.size) { out[it] / n }
            }
        }
    }

    override fun close() {
        runCatching { session.close() }
    }
}

/**
 * Le descrizioni delle foto scritte dal PC (Qwen3-VL): il testo per i dettagli della foto e, per la ricerca, le parole
 * normalizzate (minuscole, senza accenti, senza la vocale finale: «gatto» e «gatti» sono la stessa parola).
 */
class Descriptions(file: File) {
    val text: Map<Int, String>
    private val words = HashMap<Int, Set<String>>()

    init {
        val raw = java.util.zip.GZIPInputStream(file.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
        val items = JSONObject(raw).optJSONObject("items") ?: JSONObject()
        val m = HashMap<Int, String>(items.length() * 2)
        items.keys().forEach { k -> k.toIntOrNull()?.let { m[it] = items.getString(k) } }
        text = m
    }

    fun has(id: Int) = id in text

    /** Prepara in anticipo le parole di tutte le descrizioni (altrimenti si fa alla prima ricerca). */
    fun warm() {
        for (id in text.keys) wordsOf(id)
    }

    private fun wordsOf(id: Int): Set<String> = words.getOrPut(id) { stems(text[id] ?: "").toSet() }

    /** Tutte le parole delle descrizioni della libreria. */
    private val vocabulary: Set<String> by lazy { text.keys.flatMapTo(HashSet()) { wordsOf(it) } }

    /** La parola (o un suo sinonimo) compare in almeno una descrizione. */
    fun knows(word: String): Boolean = word in vocabulary || SINONIMI[word]?.any { it in vocabulary } == true

    /**
     * Quota delle parole della ricerca presenti nella descrizione (0..1), o null se la foto non ha descrizione.
     * Una parola conta anche se nella descrizione c'è un suo sinonimo (vedi SINONIMI).
     */
    fun match(id: Int, query: List<String>): Float? {
        if (id !in text) return null
        if (query.isEmpty()) return 0f
        val w = wordsOf(id)
        return query.count { q -> q in w || SINONIMI[q]?.any { it in w } == true }.toFloat() / query.size
    }

    companion object {
        private val STOP = ("di a da in con su per tra fra il lo la i gli le un uno una e o al allo alla ai agli alle del " +
            "dello della dei degli delle nel nello nella nei negli nelle sul sullo sulla sui sugli sulle col coi che " +
            "foto immagine").split(' ').toSet()

        /**
         * Sinonimi (già normalizzati come da stems): il modello delle descrizioni scrive spesso «auto classica» o
         * «vintage» invece di «d'epoca», «scooter» o «Vespa» invece di «motorino».
         */
        private val GRUPPI = listOf(
            listOf("epoc", "classic", "vintag", "storic", "retr", "antic"),
            listOf("motorin", "scooter", "vesp", "ciclomotor", "motociclett", "mot"),
            listOf("aut", "macchin", "automobil", "vettur"),
            listOf("bambin", "bimb", "ragazzin", "fanciull"),
            listOf("can", "cagnolin", "cucciol"),
            listOf("gatt", "gattin", "micin", "felin"),
            listOf("donn", "ragazz", "signor"),
            listOf("uom", "ragazz", "signor"),
            listOf("bici", "biciclett", "mountain", "bik"),
            listOf("mar", "spiagg", "litoral", "cost"),
            listOf("montagn", "mont", "vett", "alpin"),
            listOf("tort", "dolc", "compleann"),
            listOf("matrimoni", "spos", "nozz"),
        )
        private val SINONIMI: Map<String, Set<String>> = buildMap<String, MutableSet<String>> {
            for (g in GRUPPI) for (w in g) getOrPut(w) { mutableSetOf() }.addAll(g - w)
        }

        /** Le parole "significative" di un testo, normalizzate come sul PC (ricerca ibrida provata sulle foto vere). */
        fun stems(t: String): List<String> {
            val n = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "").replace('\'', ' ').replace('\u2019', ' ')
            return Regex("[a-z0-9]+").findAll(n).map { it.value }
                .filter { it.length > 2 && it !in STOP }
                .map { if (it.length >= 4 && it.last() in "aeiou") it.dropLast(1) else it }.toList()
        }
    }
}

/** Modello e impronte tenuti in memoria tra una ricerca e l'altra (caricarli costa qualche secondo). */
object AiEngine {
    private var encoder: TextEncoder? = null
    private var encoderDir: File? = null
    private var index: VisualIndex? = null
    private var indexFile: File? = null
    private var indexStamp = 0L
    private var desc: Descriptions? = null
    private var descFile: File? = null
    private var descStamp = 0L

    /** Le descrizioni della libreria, o null se non ci sono. */
    @Synchronized
    fun descriptions(file: File): Descriptions? {
        if (!file.exists()) return null
        if (descFile != file || desc == null || descStamp != file.lastModified()) {
            desc = runCatching { Descriptions(file) }.getOrNull()
            descFile = file
            descStamp = file.lastModified()
        }
        return desc
    }

    private var warmed: File? = null

    /** Carica tutto quello che serve a cercare e fa girare il modello una volta (la prima esecuzione è più lenta). */
    fun warmUp(dir: File, visual: File, desc: File) {
        val enc = encoder(dir)
        index(visual)
        descriptions(desc)?.warm()
        synchronized(this) { if (warmed == dir) return }
        enc.encode("una foto di gatti")
        synchronized(this) { warmed = dir }
    }

    @Synchronized
    fun encoder(dir: File): TextEncoder {
        if (encoderDir != dir || encoder == null) {
            encoder?.close()
            encoder = TextEncoder(dir)
            encoderDir = dir
        }
        return encoder!!
    }

    @Synchronized
    fun index(file: File): VisualIndex {
        if (indexFile != file || index == null || indexStamp != file.lastModified()) {
            index = VisualIndex(file)
            indexFile = file
            indexStamp = file.lastModified()
        }
        return index!!
    }

    @Synchronized
    fun forget(file: File) {
        if (indexFile == file) { index = null; indexFile = null }
        if (descFile == file) { desc = null; descFile = null }
    }

    @Synchronized
    fun close() {
        encoder?.close()
        encoder = null
        encoderDir = null
        index = null
        indexFile = null
    }
}
