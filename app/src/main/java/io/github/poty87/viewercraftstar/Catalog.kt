package io.github.poty87.viewercraftstar

import android.content.Context
import org.json.JSONObject
import java.io.File

/** L'elenco dei preset della libreria, ricevuto dal NAS e tenuto nel telefono (serve anche senza rete). */
object Catalog {
    private fun file(context: Context) = File(context.filesDir, "presets.json")

    fun save(context: Context, json: String) {
        runCatching { JSONObject(json) }.getOrNull() ?: return // solo se è un JSON valido
        file(context).writeText(json)
    }

    /** I preset per gruppo, con i "GR IV" (che cambiano solo il profilo) per primi. */
    fun load(context: Context): List<PresetInfo> = runCatching {
        val arr = JSONObject(file(context).readText()).getJSONArray("presets")
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            PresetInfo(o.getString("id"), o.optString("name"), o.optString("group"),
                o.optString("profile").ifEmpty { null }, o.optBoolean("profileOnly"))
        }.sortedWith(compareBy({ !it.profileOnly }, { it.group }, { it.name }))
    }.getOrDefault(emptyList())

    /** Le impostazioni di sviluppo di un preset (per l'anteprima), o null. */
    fun settings(context: Context, id: String): JSONObject? = runCatching {
        val arr = JSONObject(file(context).readText()).getJSONArray("presets")
        (0 until arr.length()).map(arr::getJSONObject).firstOrNull { it.getString("id") == id }?.optJSONObject("settings")
    }.getOrNull()
}
