package io.github.poty87.viewercraftstar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Notifica di Android finché la VPN di ViewerCraftStar è accesa: ricorda che è attiva (non si spegne da sola, se non
 * nella modalità sperimentale) e con «Spegni» la si stacca senza aprire l'app. Toccandola si apre l'app.
 */
object VpnNotice {
    private const val CHANNEL = "vpn"
    private const val ID = 21
    const val ACTION_OFF = "io.github.poty87.viewercraftstar.VPN_OFF"

    fun update(context: Context, on: Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (!on) { nm.cancel(ID); return }
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "VPN", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(context, 20, Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val off = PendingIntent.getBroadcast(context, 21, Intent(context, VpnOffReceiver::class.java).setAction(ACTION_OFF),
            PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(L.t("VPN di ViewerCraftStar accesa", "ViewerCraftStar VPN on"))
            .setContentText(L.t("Collegata al NAS di casa. Tocca «Spegni» quando non ti serve più.",
                "Connected to the home NAS. Tap “Turn off” when you no longer need it."))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, L.t("Spegni", "Turn off"), off).build())
            .build()
        runCatching { nm.notify(ID, n) }
    }
}

/** «Spegni» nella notifica: stacca la VPN (OpenVPN for Android ha già dato il consenso, nessuna schermata). */
class VpnOffReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VpnNotice.ACTION_OFF) return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.Main).launch {
            try {
                Vpn.disconnect(app)
                VpnNotice.update(app, false)
            } finally { pending.finish() }
        }
    }
}
