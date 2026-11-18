package io.github.poty87.viewercraftstar

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Il resoconto che PresetCraftAppLicator scrive sul NAS (.viewercraftstar/status.json): per ogni foto della cartella
 * che è in libreria LightCraft, la versione dei metadati del telefono che ha applicato. Tenuto anche nel telefono.
 */
class HomeStatus(val photos: Map<String, Long>, val updated: String) {
    /** La foto è a casa: in libreria e con l'ultima versione dei metadati applicata (o mai modificata qui). */
    fun safe(item: PhotoItem, meta: PhotoMeta): Boolean {
        val applied = photos[item.stem.lowercase()] ?: return false
        if (meta.pending) return false
        return meta.changed == 0L || applied >= meta.changed
    }

    companion object {
        private fun file(context: Context) = File(context.filesDir, "status.json")

        fun parse(json: String): HomeStatus? = runCatching {
            val o = JSONObject(json)
            val ph = o.getJSONObject("photos")
            HomeStatus(ph.keys().asSequence().associateWith { k ->
                ph.getJSONObject(k).optString("rev").toLongOrNull() ?: 0L
            }, o.optString("updated"))
        }.getOrNull()

        fun save(context: Context, json: String) {
            if (parse(json) != null) file(context).writeText(json)
        }

        fun load(context: Context): HomeStatus? = runCatching { parse(file(context).readText()) }.getOrNull()
    }
}
