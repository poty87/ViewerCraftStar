package io.github.poty87.viewercraftstar

import android.content.Context
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.EnumSet

/**
 * Una modifica fatta sul telefono a una foto della libreria di casa: solo i campi cambiati (null = non toccato).
 * `at` è quando è stata fatta, `sent` quando è stata lasciata sul NAS (0 = non ancora).
 */
data class LibEdit(
    val rating: Int? = null,
    val flag: String? = null,
    val label: String? = null,
    val title: String? = null,
    val caption: String? = null,
    val keywords: List<String>? = null,
    /** true = nel cestino di LightCraft, false = ripristinare, null = non toccato. */
    val trash: Boolean? = null,
    /** In una pila con copertina questa foto (id); 0 = tolta dalla sua pila; null = non toccato. */
    val stack: Int? = null,
    /** Nomi dati ai volti sul telefono: riquadro (LibFace.key) → nome ("" = togli il nome); null = non toccato. */
    val faces: Map<String, String>? = null,
    val at: Long = 0,
    val sent: Long = 0,
) {
    /** La foto come la vede il telefono, con la modifica sopra. */
    fun applyTo(p: LibPhoto): LibPhoto {
        // un nome dato o confermato qui non è più "automatico"
        val newFaces = faces?.let { f -> p.faces.map { face -> f[face.key]?.let { face.copy(name = it, auto = false) } ?: face } }
            ?: p.faces
        // persone: quelle dei volti dopo la modifica, più quelle che non vengono dai volti
        val people = if (faces == null) p.people else
            ((p.people - p.faces.map { it.name }.toSet()) + newFaces.map { it.name }.filter { it.isNotEmpty() }).distinct()
        return p.copy(rating = rating ?: p.rating, flag = flag ?: p.flag, label = label ?: p.label,
            title = title ?: p.title, caption = caption ?: p.caption, keywords = keywords ?: p.keywords,
            faces = newFaces, people = people)
    }

    /** La libreria di casa ha già questi valori: la modifica è arrivata in LightCraft. */
    fun settledIn(p: LibPhoto, home: LibStack?) = trash != true &&
        // pila: la copertina indicata è nella pila di casa; se indica se stessa («Rendi copertina») deve esserne la prima
        (stack == null || (stack == 0 && home == null) || (stack != 0 && home?.photos?.contains(stack) == true && (stack != p.id || home.photos.first() == p.id))) && (rating == null || rating == p.rating) && (flag == null || flag == p.flag) &&
        (label == null || label.equals(p.label, ignoreCase = true)) && (title == null || title == p.title) &&
        (caption == null || caption == p.caption) && (keywords == null || keywords == p.keywords) &&
        (faces == null || faces.all { (k, n) -> p.faces.firstOrNull { it.key == k }?.let { it.name == n && !it.auto } ?: true })
}

/**
 * Le modifiche alla libreria di casa: tenute sul telefono finché la libreria non le conferma, e lasciate sul NAS in
 * <cartella>/<libreria>/changes/ (un file .json per richiesta), dove PresetCraftAppLicator le applica a LightCraft (con LightCraft chiuso).
 */
object LibraryEdits {
    private fun file(context: Context, lib: String) =
        File(context.filesDir, "libraries/" + lib.replace(Regex("[\\\\/:*?\"<>|]"), "_") + "/edits.json")

    fun load(context: Context, lib: String): Map<Int, LibEdit> = runCatching {
        val o = JSONObject(file(context, lib).readText())
        o.keys().asSequence().associate { k ->
            val e = o.getJSONObject(k)
            k.toInt() to LibEdit(
                rating = if (e.has("rating")) e.getInt("rating") else null,
                flag = e.optString("flag").takeIf { e.has("flag") },
                label = e.optString("label").takeIf { e.has("label") },
                title = e.optString("title").takeIf { e.has("title") },
                caption = e.optString("caption").takeIf { e.has("caption") },
                keywords = e.optJSONArray("keywords")?.let { a -> (0 until a.length()).map { a.getString(it) } },
                trash = if (e.has("trash")) e.getBoolean("trash") else null,
                stack = if (e.has("stack")) e.getInt("stack") else null,
                faces = e.optJSONArray("faces")?.let { a ->
                    (0 until a.length()).associate { i ->
                        val f = a.getJSONObject(i)
                        val r = f.getJSONArray("rect")
                        LibFace(r.getDouble(0).toFloat(), r.getDouble(1).toFloat(), r.getDouble(2).toFloat(),
                            r.getDouble(3).toFloat(), "").key to f.optString("name")
                    }
                },
                at = e.optLong("at"), sent = e.optLong("sent"))
        }
    }.getOrDefault(emptyMap())

    @Synchronized
    fun save(context: Context, lib: String, edits: Map<Int, LibEdit>) {
        val o = JSONObject()
        edits.forEach { (id, e) -> o.put(id.toString(), json(e).put("sent", e.sent)) }
        val f = file(context, lib)
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(f)
    }

    private fun json(e: LibEdit): JSONObject = JSONObject().apply {
        put("at", e.at)
        e.rating?.let { put("rating", it) }
        e.flag?.let { put("flag", it) }
        e.label?.let { put("label", it) }
        e.title?.let { put("title", it) }
        e.caption?.let { put("caption", it) }
        e.keywords?.let { put("keywords", JSONArray(it)) }
        e.trash?.let { put("trash", it) }
        e.stack?.let { put("stack", it) }
        e.faces?.let { f ->
            put("faces", JSONArray(f.map { (k, n) -> JSONObject().put("rect", JSONArray(LibFace.rectOf(k))).put("name", n) }))
        }
    }

    /** Lascia sul NAS una richiesta con le modifiche indicate (file nascosto finché non è completo). */
    suspend fun send(cfg: NasConfig, share: String, folder: String, lib: String, edits: Map<Int, LibEdit>) {
        val now = System.currentTimeMillis()
        val body = JSONObject().put("version", 1).put("device", "ViewerCraftStar").put("created", now)
            .put("changes", JSONArray(edits.map { (id, e) -> json(e).put("id", id) })).toString().toByteArray(Charsets.UTF_8)
        Remote.use(cfg, share) { s ->
            var dir = folder
            for (part in listOf(lib, "changes")) {
                dir = if (dir.isEmpty()) part else "$dir\\$part"
                if (!s.folderExists(dir)) s.mkdir(dir)
            }
            val name = "$now-${(1000..9999).random()}.json"
            val tmp = "$dir\\.$name.tmp"
            s.openFile(tmp, EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.DELETE), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF, null).use { f ->
                f.outputStream.use { it.write(body) }
                f.rename("$dir\\$name", true)
            }
        }
    }
}
