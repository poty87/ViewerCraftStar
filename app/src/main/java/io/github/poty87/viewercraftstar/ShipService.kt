package io.github.poty87.viewercraftstar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Dopo "Spedisci al NAS": in background, con una notifica, invia i metadati man mano che Synology Drive carica i DNG
 * e si ferma da solo quando tutte le foto spedite sono confermate a casa da PresetCraftAppLicator. Non serve tenere
 * aperta l'app. Si arrende se il NAS resta irraggiungibile per 15 minuti o dopo 3 ore in tutto.
 */
class ShipService : Service() {
    companion object {
        const val NAMES = "names"
        private const val CHANNEL = "ship"
        private const val ID_RUN = 1
        private const val ID_END = 2
        private const val ROUND_MS = 30_000L
        private const val UNREACHABLE_MS = 15 * 60_000L
        private const val TOTAL_MS = 3 * 60 * 60_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val targets = LinkedHashSet<String>()
    private var startedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        channel()
        intent?.getStringArrayListExtra(NAMES)?.let { targets.addAll(it) }
        startForeground(ID_RUN, note(L.t("Spedizione al NAS", "Sending to the NAS"),
            L.t("Preparo l'invio…", "Getting ready…"), ongoing = true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        startedAt = System.currentTimeMillis() // una nuova spedizione fa ripartire il tempo massimo
        if (job?.isActive != true) job = scope.launch { work() }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        Shipper.running.value = false
        scope.cancel()
        super.onDestroy()
    }

    /** Android ferma i servizi "dataSync" dopo qualche ora: si chiude in ordine. */
    override fun onTimeout(startId: Int, fgsType: Int) = finish(
        L.t("Invio interrotto da Android: riapri l'app per riprovare.", "Sending stopped by Android: open the app to retry."))

    private suspend fun work() {
        Shipper.running.value = true
        val settings = Settings(this)
        var unreachableSince = 0L
        while (scope.isActive) {
            val cfg = settings.nas
            if (!cfg.complete) return finish(L.t("Imposta il NAS nelle Impostazioni.", "Set up the NAS in Settings."))
            val photos = Shipper.query(this, settings.phoneFolder, false)
            val onPhone = photos.associateBy { it.name }
            val store = MetaStore.of(this)
            val mine = targets.mapNotNull { onPhone[it] }
            if (mine.isEmpty()) return finish(L.t("Nessuna foto spedita da seguire.", "No sent photos to follow."))
            fun levels() = HomeStatus.load(this).let { home -> NasState.load(this).let { nas ->
                mine.map { levelOf(it, store.get(it.name), home, nas) } } }
            val now = System.currentTimeMillis()
            if (now - startedAt > TOTAL_MS) {
                val notGreen = levels().count { it != Level.GREEN }
                return finish(L.t("Dopo 3 ore $notGreen foto non sono ancora in LightCraft: riapri l'app per riprendere.",
                    "After 3 hours $notGreen photos aren't in LightCraft yet: open the app to resume."))
            }
            if (!Shipper.reachable(cfg.host)) {
                if (unreachableSince == 0L) unreachableSince = now
                if (now - unreachableSince > UNREACHABLE_MS) return finish(L.t(
                    "NAS non raggiungibile da 15 minuti: invio sospeso. I metadati restano in coda; tocca ▶ (Riprendi l'invio) nella sezione Spedite quando sei a casa o con la VPN.",
                    "NAS unreachable for 15 minutes: sending paused. The metadata stays queued; tap ▶ (Resume sending) in the Sent section at home or with the VPN."))
                val lv = levels()
                update(lv.count { it == Level.GREEN }, lv.count { it >= Level.BLUE }, mine.size,
                    L.t("NAS non raggiungibile: se sei fuori casa, attiva la VPN.", "NAS not reachable: if you're away from home, turn on the VPN."),
                    vpnAction = true)
            } else {
                unreachableSince = 0L
                // tutte le foto spedite con metadati da inviare (anche di spedizioni precedenti)
                val todo = photos.map { it.name }.filter { store.get(it).pending }.associateWith { store.get(it) }
                // DNG presenti, resoconto e battito del PC, poi l'invio di quel che serve
                val outcome = runCatching {
                    val there = Shipper.refresh(this, cfg)
                    if (todo.isNotEmpty()) Shipper.send(this, cfg, todo, onPhone.keys)
                    there
                }
                val nas = NasState.load(this)
                val lv = levels()
                val green = lv.count { it == Level.GREEN }
                val onNas = lv.count { it >= Level.BLUE }
                // senza seguire LightCraft (impostazione predefinita) si finisce quando foto e metadati sono sul NAS
                if (!settings.notifyLightCraft && onNas == mine.size) return finish(
                    if (green == mine.size) L.t(
                        "${mine.size} foto al sicuro, già in LightCraft: puoi toglierle dal telefono dalla sezione Spedite.",
                        "${mine.size} photos safe, already in LightCraft: you can remove them from the phone in the Sent section.")
                    else L.t(
                        "Tutte le ${mine.size} foto sono al sicuro sul NAS con i metadati: puoi toglierle dal telefono. Entreranno in LightCraft quando il PC di casa è acceso.",
                        "All ${mine.size} photos are safe on the NAS with their metadata: you can remove them from the phone. They will enter LightCraft when the home PC is on."),
                    title = L.t("Spedizione completata", "Sending complete"))
                if (green == mine.size) return finish(L.t(
                    "${mine.size} foto in LightCraft con i tuoi metadati: puoi toglierle dal telefono dalla sezione Spedite.",
                    "${mine.size} photos in LightCraft with your metadata: you can remove them from the phone in the Sent section."),
                    title = L.t("Spedizione completata", "Sending complete"))
                if (onNas == mine.size && !nas.pcOn()) return finish(L.t(
                    "Tutte le ${mine.size} foto sono al sicuro sul NAS con i metadati: puoi toglierle dal telefono. Il PC di casa sembra spento: entreranno in LightCraft alla sua accensione.",
                    "All ${mine.size} photos are safe on the NAS with their metadata: you can remove them from the phone. The home PC seems off: they will enter LightCraft when it starts."),
                    title = L.t("Spedizione completata", "Sending complete"))
                val missing = outcome.getOrNull()?.let { there -> mine.count { it.name.lowercase() !in there } } ?: 0
                val detail = when {
                    outcome.isFailure -> L.t("Il NAS non ha risposto, riprovo tra poco.", "The NAS didn't answer, retrying shortly.")
                    missing > 0 -> L.t("In attesa che Synology Drive carichi le foto ($missing di ${mine.size} non ancora sul NAS).",
                        "Waiting for Synology Drive to upload the photos ($missing of ${mine.size} not on the NAS yet).")
                    onNas < mine.size -> L.t("Invio dei metadati al NAS…", "Sending the metadata to the NAS…")
                    nas.pcPhase == "waiting" -> L.t("LightCraft è aperto sul PC di casa: le foto entrano in libreria quando lo chiudi.",
                        "LightCraft is open on the home PC: photos enter the library when you close it.")
                    else -> L.t("Tutte al sicuro sul NAS: in attesa che PresetCraftAppLicator le importi in LightCraft.",
                        "All safe on the NAS: waiting for PresetCraftAppLicator to import them into LightCraft.")
                }
                update(green, onNas, mine.size, detail)
            }
            delay(ROUND_MS)
        }
    }

    private fun update(green: Int, onNas: Int, total: Int, detail: String, vpnAction: Boolean = false) {
        val follow = Settings(this).notifyLightCraft
        // barra: foto arrivate sul NAS con i metadati; seguendo LightCraft ogni foto conta due passi
        val title = if (follow) L.t("Spedizione: $onNas di $total sul NAS · $green in LightCraft",
            "Sending: $onNas of $total on the NAS · $green in LightCraft")
        else L.t("Spedizione: $onNas di $total sul NAS", "Sending: $onNas of $total on the NAS")
        getSystemService(NotificationManager::class.java).notify(ID_RUN, note(title, detail, ongoing = true,
            vpnAction = vpnAction, progress = if (follow) (onNas + green) to 2 * total else onNas to total))
    }

    private fun finish(text: String, title: String = L.t("Spedizione al NAS", "Sending to the NAS")) {
        if (Settings(this).notifyEnd) getSystemService(NotificationManager::class.java).notify(ID_END, note(title, text, ongoing = false))
        Shipper.running.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        scope.cancel()
    }

    private fun channel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, L.t("Spedizione al NAS", "Sending to the NAS"), NotificationManager.IMPORTANCE_LOW))
    }

    private fun note(title: String, text: String, ongoing: Boolean, vpnAction: Boolean = false,
                     progress: Pair<Int, Int>? = null): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
        progress?.let { (done, max) -> if (max > 0) b.setProgress(max, done.coerceIn(0, max), false) }
        if (vpnAction) packageManager.getLaunchIntentForPackage("net.openvpn.openvpn")?.let { vpn ->
            val pi = PendingIntent.getActivity(this, 1, vpn, PendingIntent.FLAG_IMMUTABLE)
            b.addAction(Notification.Action.Builder(null, L.t("Apri VPN", "Open VPN"), pi).build())
        }
        return b.build()
    }
}
