package io.github.poty87.viewercraftstar

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.delay

/**
 * AdGuard (blocco della pubblicità) lavora come una VPN e Android ne tiene accesa una sola: prima di accendere la VPN
 * di casa la si mette in pausa, e quando la VPN di casa si spegne la si riaccende. Si usa l'automazione di AdGuard
 * (comandi «stop» e «start» al suo AutomationReceiver, con la password che AdGuard mostra nelle sue impostazioni).
 * Si riaccende solo se l'aveva fermata ViewerCraftStar.
 */
object Adguard {
    const val PKG = "com.adguard.android"
    private const val RECEIVER = "com.adguard.android.receiver.AutomationReceiver"

    fun installed(context: Context) = runCatching { context.packageManager.getPackageInfo(PKG, 0); true }.getOrDefault(false)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Solo nella modalità automatica (sperimentale) e con la password di automazione. */
    fun enabled(context: Context, s: Settings) = s.vpnMode == "auto" && s.adguardPassword.isNotEmpty() && installed(context)

    /** Apre AdGuard (per spegnerlo a mano); se non c'è, le impostazioni VPN di Android. */
    fun open(context: Context) {
        val i = context.packageManager.getLaunchIntentForPackage(PKG)
            ?: Intent(android.provider.Settings.ACTION_VPN_SETTINGS)
        runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun send(context: Context, s: Settings, action: String) {
        val i = Intent(action).setClassName(PKG, RECEIVER)
            .putExtra("password", s.adguardPassword)
            .putExtra("quiet", true)
        runCatching { context.applicationContext.sendBroadcast(i) }
    }

    /**
     * Prima di accendere la VPN di casa: se c'è un'altra VPN accesa (AdGuard) la ferma e aspetta che liberi il posto
     * (al massimo qualche secondo). Segna che l'ha fermata lei, per riaccenderla dopo.
     */
    suspend fun pauseIfNeeded(context: Context, s: Settings) {
        if (!enabled(context, s) || !Shipper.vpnOn(context)) return
        send(context, s, "stop")
        prefs(context).edit().putBoolean("adguard_paused_by_us", true).apply()
        repeat(20) { if (!Shipper.vpnOn(context)) return; delay(250) }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val RESUME = Any()

    /**
     * Dopo che la VPN di casa si è spenta (o non è partita): riaccende AdGuard, se l'aveva fermata ViewerCraftStar.
     * Aspetta qualche secondo e controlla che la VPN di casa sia ancora spenta: se ripartisse subito (cambio di rete,
     * app riaperta) AdGuard, accendendosi dopo, le ruberebbe il posto (Android tiene solo l'ultima VPN accesa).
     */
    fun resumeIfPaused(context: Context, s: Settings) {
        val app = context.applicationContext
        if (!prefs(app).getBoolean("adguard_paused_by_us", false)) return
        main.removeCallbacksAndMessages(RESUME)
        main.postAtTime({
            if (Vpn.state == Vpn.State.ON || Vpn.state == Vpn.State.CONNECTING || Vpn.starting) return@postAtTime
            val p = prefs(app)
            if (!p.getBoolean("adguard_paused_by_us", false)) return@postAtTime
            p.edit().putBoolean("adguard_paused_by_us", false).apply()
            if (s.adguardPassword.isNotEmpty() && installed(app)) send(app, s, "start")
        }, RESUME, android.os.SystemClock.uptimeMillis() + 10_000)
    }

    /** La VPN di casa sta ripartendo: niente riaccensione di AdGuard in sospeso. */
    fun cancelResume() = main.removeCallbacksAndMessages(RESUME)
}
