package io.github.poty87.viewercraftstar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Scarica sul telefono foto della libreria di casa, in background con una notifica: l'originale (DNG, CR2, JPG…) o il
 * JPEG che la fotocamera ha incorporato (pronto da condividere). Vanno in Pictures/ViewerCraftStar Download, una
 * cartella a parte: non è né la cartella di lavoro né quella sincronizzata, quindi non vengono mai spedite.
 */
class DownloadService : Service() {
    companion object {
        const val FOLDER = "Pictures/ViewerCraftStar Download/"
        private const val CHANNEL = "download"
        private const val ID_RUN = 11
        private const val ID_END = 12

        /** Lavoro in corso: (fatte, totali), o null. */
        val progress = MutableStateFlow<Pair<Int, Int>?>(null)

        fun start(context: Context, library: String, ids: Collection<Int>, jpeg: Boolean) {
            if (ids.isEmpty()) return
            context.startForegroundService(Intent(context, DownloadService::class.java)
                .putExtra("library", library).putExtra("ids", ids.toIntArray()).putExtra("jpeg", jpeg))
        }

        private val MIME = mapOf("dng" to "image/x-adobe-dng", "orf" to "image/x-olympus-orf", "cr2" to "image/x-canon-cr2",
            "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "tif" to "image/tiff", "tiff" to "image/tiff", "png" to "image/png")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val queue = ArrayDeque<Triple<String, Int, Boolean>>()
    private var total = 0
    private var done = 0
    private var failed = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NasId.init(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, L.t("Download dalla libreria", "Library downloads"), NotificationManager.IMPORTANCE_LOW))
        val lib = intent?.getStringExtra("library")
        val ids = intent?.getIntArrayExtra("ids")
        val jpeg = intent?.getBooleanExtra("jpeg", false) ?: false
        if (lib != null && ids != null) synchronized(queue) { ids.forEach { queue += Triple(lib, it, jpeg) }; total += ids.size }
        startForeground(ID_RUN, note(L.t("Download dalla libreria", "Downloading from the library"), "$done / $total",
            ongoing = true, max = total, value = done), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (job?.isActive != true) job = scope.launch { work() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        progress.value = null
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun work() {
        val cfg = Settings(this).nas
        val indexes = HashMap<String, LibIndex?>()
        while (true) {
            val (lib, id, jpeg) = synchronized(queue) { queue.removeFirstOrNull() } ?: break
            progress.value = done to total
            update()
            val idx = indexes.getOrPut(lib) { LibraryStore.load(this, lib) }
            val photo = idx?.byId?.get(id)
            val ok = photo != null && runCatching { save(cfg, idx, photo, jpeg) }.getOrDefault(false)
            if (!ok) failed++
            done++
        }
        progress.value = null
        Vpn.scheduleStop(this) // con l'app chiusa la VPN non serve più
        val text = if (failed == 0) L.t("$done foto scaricate in ${FOLDER.trimEnd('/')}.", "$done photos downloaded to ${FOLDER.trimEnd('/')}.")
        else L.t("${done - failed} foto scaricate, $failed non riuscite (NAS non raggiungibile o file mancante).",
            "${done - failed} photos downloaded, $failed failed (NAS not reachable or file missing).")
        getSystemService(NotificationManager::class.java).notify(ID_END,
            note(L.t("Download completato", "Download complete"), text, ongoing = false))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Scarica una foto nella cartella dei download (Android rinomina da solo se il nome c'è già). */
    private suspend fun save(cfg: NasConfig, idx: LibIndex, photo: LibPhoto, jpeg: Boolean): Boolean {
        val (share, path) = idx.locate(photo) ?: return false
        val stem = photo.name.substringBeforeLast('.') + if (photo.copyName.isNotEmpty()) " (${photo.copyName})" else ""
        val ext = photo.name.substringAfterLast('.', "").lowercase()
        val name = if (jpeg) "$stem.jpg" else "$stem.${photo.name.substringAfterLast('.', "")}"
        val mime = if (jpeg) "image/jpeg" else MIME[ext] ?: "image/*"
        val cr = contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, FOLDER)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        val ok = runCatching {
            Remote.use(cfg, share) { s ->
                Remote.open(s, path).use { r ->
                    cr.openOutputStream(uri)?.use { out ->
                        if (jpeg) {
                            val picked = RemotePreview.pick(r, photo, RemotePreview.FULL) ?: error("no preview")
                            if (picked.isPlainJpeg) out.write(picked.jpeg)
                            else (RemotePreview.decode(picked, Int.MAX_VALUE) ?: error("no preview"))
                                .compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)
                        } else {
                            var at = 0L
                            while (at < r.size) {
                                val chunk = r.read(at, 1024 * 1024)
                                if (chunk.isEmpty()) break
                                out.write(chunk)
                                at += chunk.size
                            }
                        }
                    } ?: error("no output")
                }
            }
        }.isSuccess
        if (ok) cr.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        else runCatching { cr.delete(uri, null, null) }
        return ok
    }

    private fun update() {
        getSystemService(NotificationManager::class.java).notify(ID_RUN, note(
            L.t("Download dalla libreria: $done di $total", "Downloading from the library: $done of $total"),
            L.t("In ${FOLDER.trimEnd('/')} sul telefono.", "To ${FOLDER.trimEnd('/')} on the phone."),
            ongoing = true, max = total, value = done))
    }

    private fun note(title: String, text: String, ongoing: Boolean, max: Int = 0, value: Int = 0): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
        if (ongoing && max > 0) b.setProgress(max, value.coerceIn(0, max), false)
        return b.build()
    }
}
