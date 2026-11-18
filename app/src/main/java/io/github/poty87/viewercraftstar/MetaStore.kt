package io.github.poty87.viewercraftstar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Metadati di ogni foto, salvati nel telefono (chiave: nome del file, uguale sul NAS). Restano anche dopo l'invio al NAS:
 * servono a mostrare stelle e bandierine e a rifare l'invio se cambia qualcosa.
 */
class MetaStore(context: Context) {
    private val file = File(context.filesDir, "metadata.json")
    private val data = HashMap<String, PhotoMeta>()

    init {
        runCatching {
            if (file.exists()) {
                val root = JSONObject(file.readText())
                val photos = root.optJSONObject("photos") ?: JSONObject()
                for (name in photos.keys()) data[name] = fromJson(photos.getJSONObject(name))
            }
        }
    }

    @Synchronized fun get(name: String): PhotoMeta = data[name] ?: PhotoMeta()

    @Synchronized fun all(): Map<String, PhotoMeta> = HashMap(data)

    @Synchronized fun put(name: String, meta: PhotoMeta) {
        data[name] = meta
        save()
    }

    @Synchronized fun putAll(changes: Map<String, PhotoMeta>) {
        data.putAll(changes)
        save()
    }

    @Synchronized fun removeAll(names: Collection<String>) {
        if (names.isEmpty()) return
        names.forEach { data.remove(it) }
        save()
    }

    companion object {
        @Volatile private var one: MetaStore? = null

        /** Uno solo per tutta l'app: l'app aperta e l'invio in background leggono e scrivono gli stessi dati. */
        fun of(context: Context): MetaStore =
            one ?: synchronized(this) { one ?: MetaStore(context.applicationContext).also { one = it } }
    }

    private fun save() {
        val photos = JSONObject()
        for ((name, m) in data) photos.put(name, toJson(m))
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSONObject().put("version", 1).put("photos", photos).toString())
        tmp.renameTo(file)
    }

    private fun toJson(m: PhotoMeta) = JSONObject()
        .put("flag", m.flag.xmp).put("rating", m.rating).put("label", m.label?.xmp ?: "")
        .put("title", m.title).put("caption", m.caption).put("keywords", JSONArray(m.keywords))
        .put("creator", m.creator).put("copyright", m.copyright)
        .put("presetId", m.presetId).put("presetName", m.presetName)
        .put("changed", m.changed).put("sent", m.sent).put("error", m.error).put("notOnNas", m.notOnNas)

    private fun fromJson(o: JSONObject): PhotoMeta {
        val kw = o.optJSONArray("keywords")
        return PhotoMeta(
            flag = Flag.parse(o.optString("flag")),
            rating = o.optInt("rating").coerceIn(0, 5),
            label = ColorLabel.parse(o.optString("label")),
            title = o.optString("title"),
            caption = o.optString("caption"),
            keywords = if (kw == null) emptyList() else List(kw.length()) { kw.getString(it) },
            creator = o.optString("creator"),
            copyright = o.optString("copyright"),
            presetId = o.optString("presetId"),
            presetName = o.optString("presetName"),
            changed = o.optLong("changed"),
            sent = o.optLong("sent"),
            error = o.optString("error"),
            notOnNas = o.optBoolean("notOnNas"),
        )
    }
}
