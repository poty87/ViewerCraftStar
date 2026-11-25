package io.github.poty87.viewercraftstar

import android.content.Context
import android.graphics.Bitmap
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/**
 * Il motore di sviluppo di LightCraft sul telefono (libreria nativa `vcs_develop`, compilata dal codice di LightCraft:
 * native/vcs-develop). Sviluppa una smart preview con le impostazioni di LightCraft (lo stesso JSON del PC): il
 * risultato è quello di LightCraft (prova del 25/11/2026: differenza media 0,05/255).
 */
object DevelopEngine {
    val available: Boolean by lazy { runCatching { System.loadLibrary("vcs_develop"); true }.getOrDefault(false) }

    /** La versione di LightCraft con cui è compilato il motore. */
    @JvmStatic external fun version(): String
    /** Registra un profilo LUT (`lut:…`) dal testo del suo file .cube. */
    @JvmStatic external fun registerLut(id: String, cube: String): Boolean
    /** Apre una smart preview (.lcsp): un riferimento, 0 se non si legge. */
    @JvmStatic external fun open(bytes: ByteArray): Long
    /** Sviluppa: [larghezza, altezza, pixel ARGB…], null se le impostazioni non si leggono. */
    @JvmStatic external fun render(handle: Long, settings: String, maxEdge: Int): IntArray?
    @JvmStatic external fun close(handle: Long)

    fun bitmap(a: IntArray): Bitmap {
        val w = a[0]
        val h = a[1]
        return Bitmap.createBitmap(a, 2, w, w, h, Bitmap.Config.ARGB_8888)
    }

    private val luts = HashSet<String>()

    @Synchronized
    fun registerOnce(id: String, cube: () -> String?): Boolean {
        if (id in luts) return true
        val text = cube() ?: return false
        return registerLut(id, text).also { if (it) luts += id }
    }
}

/**
 * Le impostazioni di sviluppo delle foto, pubblicate da PresetCraftAppLicator (1.29) in develop.json.gz:
 * {"version", "base": {...}, "photos": {id: [firma, {"sezione.campo": valore}, [campi da togliere]?]},
 *  "smart": {id: nome del file della smart preview}}. Si legge a flusso (Canon: 17.000 foto, 8 MB di testo).
 */
object DevelopStore {
    const val FILE = "develop.json.gz"

    class Entry(val sig: String, val settings: JSONObject, val smart: String?)

    fun file(context: Context, lib: String) =
        File(context.filesDir, "libraries/" + lib.replace(Regex("[\\\\/:*?\"<>|]"), "_") + "/" + FILE)

    private var sigCache: Triple<String, Long, Map<Int, String>>? = null

    /** id → firma dello sviluppo (per sapere quando il PC ha applicato una modifica del telefono). */
    @Synchronized
    fun sigs(context: Context, lib: String): Map<Int, String> {
        val f = file(context, lib)
        if (!f.exists()) return emptyMap()
        sigCache?.let { (l, t, m) -> if (l == lib && t == f.lastModified()) return m }
        val out = HashMap<Int, String>()
        runCatching {
            reader(f).use { r ->
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() == "photos") {
                        r.beginObject()
                        while (r.hasNext()) {
                            val id = r.nextName().toInt()
                            r.beginArray()
                            out[id] = r.nextString()
                            while (r.hasNext()) r.skipValue()
                            r.endArray()
                        }
                        r.endObject()
                    } else r.skipValue()
                }
            }
        }
        sigCache = Triple(lib, f.lastModified(), out)
        return out
    }

    /** Le impostazioni complete di una foto, la sua firma e la sua smart preview (null se non ci sono). */
    fun entry(context: Context, lib: String, id: Int): Entry? = runCatching {
        var base: JSONObject? = null
        var sig: String? = null
        var diff: JSONObject? = null
        var drop: JSONArray? = null
        var smart: String? = null
        val key = id.toString()
        reader(file(context, lib)).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "base" -> base = readValue(r) as JSONObject
                    "photos" -> {
                        r.beginObject()
                        while (r.hasNext()) {
                            if (r.nextName() == key) {
                                val a = readValue(r) as JSONArray
                                sig = a.getString(0)
                                diff = a.getJSONObject(1)
                                drop = a.optJSONArray(2)
                            } else r.skipValue()
                        }
                        r.endObject()
                    }
                    "smart" -> {
                        r.beginObject()
                        while (r.hasNext()) if (r.nextName() == key) smart = r.nextString() else r.skipValue()
                        r.endObject()
                    }
                    else -> r.skipValue()
                }
            }
        }
        val b = base ?: return null
        val s = sig ?: return null
        val settings = JSONObject(b.toString())
        diff?.let { d -> d.keys().forEach { k -> setPath(settings, k, d.get(k)) } }
        drop?.let { a -> for (i in 0 until a.length()) removePath(settings, a.getString(i)) }
        Entry(s, settings, smart)
    }.getOrNull()

    private fun reader(f: File) = JsonReader(InputStreamReader(GZIPInputStream(f.inputStream().buffered(1 shl 16)), Charsets.UTF_8))

    private fun readValue(r: JsonReader): Any = when (r.peek()) {
        JsonToken.BEGIN_OBJECT -> JSONObject().also { o -> r.beginObject(); while (r.hasNext()) o.put(r.nextName(), readValue(r)); r.endObject() }
        JsonToken.BEGIN_ARRAY -> JSONArray().also { a -> r.beginArray(); while (r.hasNext()) a.put(readValue(r)); r.endArray() }
        JsonToken.STRING -> r.nextString()
        JsonToken.BOOLEAN -> r.nextBoolean()
        JsonToken.NULL -> { r.nextNull(); JSONObject.NULL }
        else -> { val t = r.nextString(); if (t.contains('.') || t.contains('e') || t.contains('E')) t.toDouble() else t.toLongOrNull() ?: t.toDouble() }
    }

    /** Imposta "a.b.c" in un JSON annidato. */
    fun setPath(o: JSONObject, path: String, v: Any?) {
        val parts = path.split('.')
        var cur = o
        for (p in parts.dropLast(1)) cur = cur.optJSONObject(p) ?: JSONObject().also { cur.put(p, it) }
        cur.put(parts.last(), v ?: JSONObject.NULL)
    }

    fun getPath(o: JSONObject, path: String): Any? {
        val parts = path.split('.')
        var cur: JSONObject = o
        for (p in parts.dropLast(1)) cur = cur.optJSONObject(p) ?: return null
        return cur.opt(parts.last())
    }

    private fun removePath(o: JSONObject, path: String) {
        val parts = path.split('.')
        var cur: JSONObject = o
        for (p in parts.dropLast(1)) cur = cur.optJSONObject(p) ?: return
        cur.remove(parts.last())
    }
}

/**
 * Le smart preview scaricate sul telefono: solo quando si apre «Modifica», tenute nella cache dell'app con un limite
 * (le meno usate di recente se ne vanno per prime) e svuotabili dalle Impostazioni.
 */
object SmartCache {
    const val LIMIT_BYTES = 1L shl 30 // ~1 GB, circa 500 foto

    private fun dir(context: Context) = File(context.cacheDir, "smart")

    /** La smart preview `name` (dal NAS se non è già sul telefono). */
    suspend fun get(context: Context, cfg: NasConfig, share: String, libDir: String, name: String): ByteArray {
        val local = File(dir(context), name)
        if (local.exists()) {
            local.setLastModified(System.currentTimeMillis())
            return local.readBytes()
        }
        val path = listOf(libDir, "smart", name.take(2), name).joinToString("\\")
        val bytes = Remote.use(cfg, share) { s -> Remote.open(s, path).use { r -> r.read(0, r.size.toInt()) } }
        runCatching {
            local.parentFile?.mkdirs()
            val tmp = File(local.path + ".tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(local)
            trim(context)
        }
        return bytes
    }

    fun size(context: Context): Long = dir(context).listFiles()?.sumOf { it.length() } ?: 0L

    fun clear(context: Context) = dir(context).deleteRecursively()

    private fun trim(context: Context) {
        val files = dir(context).listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= LIMIT_BYTES) break
            total -= f.length()
            f.delete()
        }
    }
}

/** I profili della libreria (profiles.json + profiles/ nella cartella di servizio, da PresetCraftAppLicator 1.29). */
object DevelopProfiles {
    class Profile(val id: String, val name: String, val kind: String, val file: String, val group: String)

    private fun dir(context: Context, lib: String) =
        File(context.filesDir, "libraries/" + lib.replace(Regex("[\\\\/:*?\"<>|]"), "_") + "/profiles")

    /** L'elenco dei profili (letto dal NAS, tenuto sul telefono per quando il NAS non risponde). */
    suspend fun list(context: Context, cfg: NasConfig, share: String, libDir: String, lib: String): List<Profile> {
        val local = File(dir(context, lib), "profiles.json")
        val text = runCatching {
            Remote.use(cfg, share) { s -> Remote.open(s, "$libDir\\profiles.json").use { r -> String(r.read(0, r.size.toInt())) } }
        }.onSuccess { t -> runCatching { local.parentFile?.mkdirs(); local.writeText(t) } }.getOrNull()
            ?: runCatching { local.readText() }.getOrNull() ?: return emptyList()
        val a = JSONObject(text).optJSONArray("profiles") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            Profile(it.getString("id"), it.optString("name"), it.optString("kind"), it.optString("file"),
                it.optString("group").ifEmpty { it.optString("cameraModel") })
        }
    }

    /** Prepara nel motore un profilo LUT della libreria (scaricandone il file la prima volta). True se pronto. */
    suspend fun ensure(context: Context, cfg: NasConfig, share: String, libDir: String, lib: String, profile: Profile): Boolean {
        if (profile.kind != "lut") return false
        val local = File(dir(context, lib), profile.file)
        if (!local.exists()) {
            val bytes = runCatching {
                Remote.use(cfg, share) { s -> Remote.open(s, "$libDir\\profiles\\${profile.file}").use { r -> r.read(0, r.size.toInt()) } }
            }.getOrNull() ?: return false
            local.parentFile?.mkdirs()
            local.writeBytes(bytes)
        }
        return DevelopEngine.registerOnce(profile.id) { runCatching { local.readText() }.getOrNull() }
    }
}

/** La foto com'è dopo una modifica di sviluppo fatta sul telefono, finché il PC non la applica. */
object DevelopPending {
    fun file(context: Context, lib: String, id: Int) =
        File(context.filesDir, "libraries/" + lib.replace(Regex("[\\\\/:*?\"<>|]"), "_") + "/develop-pending/$id.jpg")

    fun save(context: Context, lib: String, id: Int, bmp: Bitmap) {
        val f = file(context, lib, id)
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        tmp.renameTo(f)
    }

    fun forget(context: Context, lib: String, ids: Collection<Int>) = ids.forEach { file(context, lib, it).delete() }
}
