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

    /** Cartella del modello del telefono descritto dal manifest (una per modello e compressione, condivisa tra librerie). */
    fun modelDir(context: Context, m: JSONObject): File? {
        val ph = m.optJSONObject("phone") ?: return null
        val data = ph.optJSONObject("files")?.optJSONObject(ph.optString("onnx") + ".data")?.optString("sha256") ?: ""
        return File(modelRoot(context), "${ph.optString("model")}-${ph.optInt("bits")}-${data.take(12)}")
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
                            val chunk = r.read(at, 1024 * 1024)
                            if (chunk.isEmpty()) break
                            out.write(chunk)
                            at += chunk.size
                        }
                    }
                }
                tmp.renameTo(vis)
                File(dir, "manifest.json").writeText(text)
                AiEngine.forget(vis)
            }
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
            val part = File(dir, "$name.part")
            Remote.use(cfg, share) { s ->
                Remote.open(s, join(folder, lib, "ia", "telefono", name)).use { r ->
                    RandomAccessFile(part, "rw").use { out ->
                        var at = minOf(part.length(), r.size)
                        out.seek(at)
                        while (at < r.size) {
                            val chunk = r.read(at, 1024 * 1024)
                            if (chunk.isEmpty()) break
                            out.write(chunk)
                            at += chunk.size
                            progress(before + at, total)
                        }
                    }
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
        val arr = LongArray(maxLen) { pad.toLong() }
        for (i in 0 until minOf(maxLen, ids.size)) arr[i] = ids[i].toLong()
        if (ids.size > maxLen) arr[maxLen - 1] = tokenizer.eos.toLong() // frase troppo lunga: si tronca, chiusa con </s>
        OnnxTensor.createTensor(env, LongBuffer.wrap(arr), longArrayOf(1, maxLen.toLong())).use { t ->
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

/** Modello e impronte tenuti in memoria tra una ricerca e l'altra (caricarli costa qualche secondo). */
object AiEngine {
    private var encoder: TextEncoder? = null
    private var encoderDir: File? = null
    private var index: VisualIndex? = null
    private var indexFile: File? = null
    private var indexStamp = 0L

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
