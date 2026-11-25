package io.github.poty87.viewercraftstar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Lo scarico dalla fotocamera, che continua anche passando a un'altra app: il lavoro gira fuori dalla pagina (che lo
 * mostra se è aperta) e, finché dura, [CameraImportService] tiene l'app in primo piano per Android con una notifica
 * di avanzamento (senza, Android congela l'app in background dopo pochi secondi e lo scarico si ferma).
 */
object CameraImport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** (foto in corso, totale, avanzamento della foto in corso 0–1) mentre scarica; null fermo. */
    var progress by mutableStateOf<Triple<Int, Int, Float>?>(null)
    /** Byte della foto in corso: (arrivati, totali). */
    var bytesNow by mutableStateOf<Pair<Long, Long>?>(null)
    var running by mutableStateOf(false); private set
    /** Foto scaricate (percorso sulla fotocamera) in questo collegamento: la spunta verde. */
    var done by mutableStateOf(setOf<String>())

    /** Cosa fare a lavoro finito se la pagina è stata chiusa nel frattempo (staccarsi dalla fotocamera). */
    private val afterwards = ArrayList<() -> Unit>()

    fun start(context: Context, work: suspend () -> Unit) {
        if (running) return
        running = true
        val app = context.applicationContext
        runCatching { app.startForegroundService(Intent(app, CameraImportService::class.java)) }
        scope.launch {
            try { work() } finally {
                running = false
                progress = null
                bytesNow = null
                afterwards.toList().also { afterwards.clear() }.forEach { runCatching { it() } }
            }
        }
    }

    /** La pagina si chiude: se sta scaricando, ci si stacca dalla fotocamera solo alla fine. */
    fun closeWhenDone(release: () -> Unit) {
        if (running) afterwards += release else release()
    }

    /** Collegamento nuovo: niente spunte di quello prima. */
    fun reset() { if (!running) done = emptySet() }
}

/** Il servizio in primo piano che tiene viva l'app mentre scarica dalla fotocamera, con la notifica di avanzamento. */
class CameraImportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, L.t("Scarico dalla fotocamera", "Camera download"), NotificationManager.IMPORTANCE_LOW))
        startForeground(ID, note(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        scope.launch {
            snapshotFlow { CameraImport.progress }.collect { p ->
                getSystemService(NotificationManager::class.java).notify(ID, note(p))
            }
        }
        // finito il lavoro il servizio si ferma da solo (sempre dopo startForeground: Android lo pretende)
        scope.launch {
            snapshotFlow { CameraImport.running }.collect { on ->
                if (!on) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun note(p: Triple<Int, Int, Float>?): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (p == null) L.t("Scarico dalla fotocamera", "Downloading from the camera")
                else L.t("Scarico dalla fotocamera: foto ${p.first} di ${p.second}", "Downloading from the camera: photo ${p.first} of ${p.second}"))
            .setContentText(L.t("Continua anche se passi a un'altra app.", "It goes on even if you switch to another app."))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (p != null) {
            val total = (((p.first - 1 + p.third) / p.second) * 1000).toInt().coerceIn(0, 1000)
            b.setProgress(1000, total, false)
        } else b.setProgress(0, 0, true)
        return b.build()
    }

    companion object {
        private const val CHANNEL = "camera"
        private const val ID = 21
    }
}
