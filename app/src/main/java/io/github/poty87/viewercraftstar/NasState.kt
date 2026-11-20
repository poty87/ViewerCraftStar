package io.github.poty87.viewercraftstar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Quello che l'app sa del NAS dall'ultimo controllo: quali DNG ci sono nella cartella, e il battito del PC di casa
 * (scritto da PresetCraftAppLicator in heartbeat.json, nella cartella di servizio, a ogni suo controllo). Tenuto nel telefono.
 */
class NasState(val names: Set<String>, val pcTs: Long, val pcInterval: Int, val pcPhase: String) {
    fun has(item: PhotoItem) = item.name.lowercase() in names

    /** Il PC di casa ha dato segno di vita da poco (entro 3 dei suoi intervalli di controllo, almeno 10 minuti). */
    fun pcOn(now: Long = System.currentTimeMillis()) =
        pcTs > 0 && now - pcTs < maxOf(10 * 60_000L, 3L * pcInterval * 60_000L)

    companion object {
        private fun file(context: Context) = File(context.filesDir, "nas.json")

        @Synchronized
        fun save(context: Context, names: Set<String>, heartbeat: String?) {
            val old = load(context)
            val hb = heartbeat?.let { runCatching { JSONObject(it) }.getOrNull() }
            val o = JSONObject()
                .put("names", JSONArray(names.toList()))
                .put("pcTs", hb?.optLong("ts")?.times(1000) ?: old.pcTs)
                .put("pcInterval", hb?.optInt("interval", 2) ?: old.pcInterval)
                .put("pcPhase", hb?.optString("phase") ?: old.pcPhase)
            val tmp = File(file(context).path + ".tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(file(context))
        }

        fun load(context: Context): NasState = runCatching {
            val o = JSONObject(file(context).readText())
            val a = o.getJSONArray("names")
            NasState((0 until a.length()).map { a.getString(it) }.toSet(), o.optLong("pcTs"), o.optInt("pcInterval", 2),
                o.optString("pcPhase"))
        }.getOrDefault(NasState(emptySet(), 0, 2, ""))
    }
}

/**
 * Dove si trova una foto spedita. Verde: in LightCraft con i metadati di qui. Blu: DNG e ultimi metadati al sicuro sul
 * NAS (entrerà in LightCraft quando il PC di casa è acceso). Rossa: il DNG non è ancora sul NAS, o i metadati non sono
 * ancora arrivati / sono da aggiornare. Verdi e blu si possono togliere dal telefono.
 */
enum class Level { NONE, RED, BLUE, GREEN }

fun levelOf(p: PhotoItem, m: PhotoMeta, home: HomeStatus?, nas: NasState): Level = when {
    p.inWork -> Level.NONE
    home?.safe(p, m) == true -> Level.GREEN
    !m.pending && m.error.isEmpty() && nas.has(p) -> Level.BLUE
    else -> Level.RED
}
