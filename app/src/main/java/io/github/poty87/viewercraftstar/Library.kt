package io.github.poty87.viewercraftstar

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.smbj.share.DiskShare
import org.json.JSONObject
import java.io.File
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/** Una foto della libreria LightCraft di casa, come la descrive l'indice di PresetCraftAppLicator. */
data class LibPhoto(
    val id: Int,
    val name: String,
    val path: String,
    val captured: String,
    val imported: String,
    val rating: Int,
    val flag: String,
    val label: String,
    val kind: String,
    val width: Int,
    val height: Int,
    val title: String = "",
    val caption: String = "",
    val creator: String = "",
    val copyright: String = "",
    val camera: String = "",
    val lens: String = "",
    val shutter: String = "",
    val focal: Double? = null,
    val aperture: Double? = null,
    val iso: Int? = null,
    val keywords: List<String> = emptyList(),
    val copyOf: Int? = null,
    val copyName: String = "",
    val edited: Boolean = false,
)

/** Una pila: la prima foto è la copertina. */
data class LibStack(val id: Int, val photos: List<Int>, val collapsed: Boolean)

class LibIndex(
    val library: String,
    val updated: String,
    /** Unità del PC → percorso di rete ("Y:" → "\\\\NAS\\Foto"): per trovare i file sul NAS. */
    val drives: Map<String, String>,
    val photos: List<LibPhoto>,
    val stacks: List<LibStack>,
) {
    val byId = photos.associateBy { it.id }
    /** Foto → la sua pila. */
    val stackOf: Map<Int, LibStack> = buildMap { stacks.forEach { s -> s.photos.forEach { put(it, s) } } }

    /** Dove sta il file sul NAS: (cartella condivisa, percorso dentro la condivisione), o null se non si sa. */
    fun locate(photo: LibPhoto): Pair<String, String>? {
        val p = photo.path
        val unc: String
        val rest: String
        if (p.startsWith("\\\\")) {
            unc = p
            rest = ""
        } else {
            if (p.length < 3 || p[1] != ':') return null
            unc = drives[p.substring(0, 2).uppercase()] ?: return null
            rest = p.substring(3)
        }
        val parts = unc.trimStart('\\').split('\\').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val inside = (parts.drop(2) + rest.split('\\').filter { it.isNotEmpty() }).joinToString("\\")
        return parts[1] to inside
    }
}

/** Le librerie pubblicate sul NAS: elenco, copia dell'indice sul telefono, lettura. */
object LibraryStore {
    const val INDEX = "index.json.gz"
    /** Le anteprime sviluppate da PresetCraftAppLicator (foto ritoccate in LightCraft): {id: firma}. */
    const val PREVIEWS = "previews.json"
    /** Sottocartella delle librerie nella cartella di servizio (PresetCraftAppLicator 1.19 e successivi). */
    const val LIBRARIES_DIR = "libraries"

    private fun dir(context: Context, name: String) = File(context.filesDir, "libraries/" + name.replace(Regex("[\\\\/:*?\"<>|]"), "_"))

    /**
     * Le librerie nella cartella di servizio sul NAS (sottocartelle con un indice) e la cartella che le contiene:
     * <cartella>\libraries, oppure la cartella stessa se PresetCraftAppLicator è di una versione precedente.
     */
    suspend fun list(cfg: NasConfig, share: String, folder: String): Pair<String, List<String>> = Remote.use(cfg, share) { s ->
        val sub = join(folder, LIBRARIES_DIR)
        val root = if (s.folderExists(sub)) sub else folder
        root to s.list(root).filter { e ->
            val isDir = (e.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
            isDir && !e.fileName.startsWith(".") && e.fileName != "." && e.fileName != ".." &&
                s.fileExists(join(root, e.fileName, INDEX))
        }.map { it.fileName }.sorted()
    }

    /** La cartella delle librerie quando non si è ancora parlato col NAS (PresetCraftAppLicator recente). */
    fun defaultRoot(folder: String) = join(folder, LIBRARIES_DIR)

    /** Le librerie già scaricate sul telefono (per consultarle anche senza NAS). */
    fun cached(context: Context): List<String> =
        File(context.filesDir, "libraries").listFiles()?.filter { File(it, INDEX).exists() }?.map { it.name }?.sorted()
            ?: emptyList()

    /**
     * Scarica l'indice (e l'elenco delle anteprime sviluppate) se sul NAS è cambiato o non c'è sul telefono. True se
     * è arrivato qualcosa di nuovo.
     */
    suspend fun download(context: Context, cfg: NasConfig, share: String, folder: String, name: String): Boolean =
        Remote.use(cfg, share) { s ->
            val index = fetch(s, join(folder, name, INDEX), File(dir(context, name), INDEX), "stamp")
            val previews = File(dir(context, name), PREVIEWS)
            val dev = if (s.fileExists(join(folder, name, PREVIEWS)))
                fetch(s, join(folder, name, PREVIEWS), previews, "stamp-previews")
            else previews.exists() && previews.delete()
            index || dev
        }

    /** Le anteprime sviluppate della libreria (id → firma), già sul telefono. */
    fun previews(context: Context, name: String): Map<Int, String> = runCatching {
        val o = JSONObject(File(dir(context, name), PREVIEWS).readText()).getJSONObject("photos")
        o.keys().asSequence().associate { it.toInt() to o.getString(it) }
    }.getOrDefault(emptyMap())

    private fun fetch(s: DiskShare, path: String, local: File, stampName: String): Boolean {
        val stampFile = File(local.parentFile, stampName)
        val info = s.getFileInformation(path)
        val stamp = "${info.basicInformation.lastWriteTime.toEpochMillis()}:${info.standardInformation.endOfFile}"
        if (local.exists() && stampFile.exists() && stampFile.readText() == stamp) return false
        local.parentFile?.mkdirs()
        val tmp = File(local.path + ".tmp")
        Remote.open(s, path).use { r ->
            tmp.outputStream().use { out ->
                var at = 0L
                while (at < r.size) {
                    val chunk = r.read(at, 256 * 1024)
                    if (chunk.isEmpty()) break
                    out.write(chunk)
                    at += chunk.size
                }
            }
        }
        tmp.renameTo(local)
        stampFile.writeText(stamp)
        return true
    }

    fun load(context: Context, name: String): LibIndex? {
        val f = File(dir(context, name), INDEX)
        return if (f.exists()) runCatching { parse(f) }.getOrNull() else null
    }

    private fun join(vararg parts: String) = parts.filter { it.isNotEmpty() }.joinToString("\\") { it.trim('\\', '/') }

    /** Lettura a flusso: anche 18.000 foto senza caricare tutto il testo in memoria. */
    fun parse(file: File): LibIndex {
        var library = ""
        var updated = ""
        val drives = HashMap<String, String>()
        val photos = ArrayList<LibPhoto>()
        val stacks = ArrayList<LibStack>()
        JsonReader(InputStreamReader(GZIPInputStream(file.inputStream().buffered()), Charsets.UTF_8)).use { r ->
            r.beginObject()
            while (r.hasNext()) when (r.nextName()) {
                "library" -> library = r.nextString()
                "updated" -> updated = r.nextString()
                "drives" -> {
                    r.beginObject()
                    while (r.hasNext()) drives[r.nextName().uppercase()] = r.nextString()
                    r.endObject()
                }
                "photos" -> {
                    r.beginArray()
                    while (r.hasNext()) photos += photo(r)
                    r.endArray()
                }
                "stacks" -> {
                    r.beginArray()
                    while (r.hasNext()) {
                        var id = 0
                        val members = ArrayList<Int>()
                        var collapsed = true
                        r.beginObject()
                        while (r.hasNext()) when (r.nextName()) {
                            "id" -> id = r.nextInt()
                            "photos" -> { r.beginArray(); while (r.hasNext()) members += r.nextInt(); r.endArray() }
                            "collapsed" -> collapsed = r.nextBoolean()
                            else -> r.skipValue()
                        }
                        r.endObject()
                        stacks += LibStack(id, members, collapsed)
                    }
                    r.endArray()
                }
                else -> r.skipValue()
            }
            r.endObject()
        }
        return LibIndex(library, updated, drives, photos, stacks)
    }

    private fun photo(r: JsonReader): LibPhoto {
        var id = 0; var name = ""; var path = ""; var captured = ""; var imported = ""; var rating = 0
        var flag = "none"; var label = ""; var kind = ""; var w = 0; var h = 0; var title = ""; var caption = ""
        var creator = ""; var copyright = ""; var camera = ""; var lens = ""; var shutter = ""
        var focal: Double? = null; var aperture: Double? = null; var iso: Int? = null
        val keywords = ArrayList<String>(); var copyOf: Int? = null; var copyName = ""; var edited = false
        r.beginObject()
        while (r.hasNext()) {
            val key = r.nextName()
            if (r.peek() == JsonToken.NULL) { r.skipValue(); continue }
            when (key) {
                "i" -> id = r.nextInt()
                "n" -> name = r.nextString()
                "p" -> path = r.nextString()
                "c" -> captured = r.nextString()
                "d" -> imported = r.nextString()
                "r" -> rating = r.nextInt()
                "f" -> flag = r.nextString()
                "l" -> label = r.nextString()
                "k" -> kind = r.nextString()
                "w" -> w = r.nextInt()
                "h" -> h = r.nextInt()
                "t" -> title = r.nextString()
                "a" -> caption = r.nextString()
                "cr" -> creator = r.nextString()
                "cp" -> copyright = r.nextString()
                "m" -> camera = r.nextString()
                "ln" -> lens = r.nextString()
                "sh" -> shutter = r.nextString()
                "fo" -> focal = r.nextDouble()
                "ap" -> aperture = r.nextDouble()
                "iso" -> iso = r.nextDouble().toInt()
                "kw" -> { r.beginArray(); while (r.hasNext()) keywords += r.nextString(); r.endArray() }
                "co" -> copyOf = r.nextInt()
                "cn" -> copyName = r.nextString()
                "e" -> { edited = r.nextInt() != 0 }
                else -> r.skipValue()
            }
        }
        r.endObject()
        return LibPhoto(id, name, path, captured, imported, rating, flag, label, kind, w, h, title, caption, creator,
            copyright, camera, lens, shutter, focal, aperture, iso, keywords, copyOf, copyName, edited)
    }
}
