package io.github.poty87.viewercraftstar

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.MediaStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Il motore di invio dei metadati al NAS, usato sia dall'app aperta sia dall'invio in background dopo "Spedisci".
 * Un solo invio alla volta (lucchetto): i due non scrivono mai insieme gli stessi .xmp né il file dei metadati.
 */
object Shipper {
    private val lock = Mutex()

    /** Cambia ogni volta che metadati, resoconto o preset vengono aggiornati qui: l'app aperta li rilegge. */
    val changes = MutableStateFlow(0L)

    /** L'invio in background è in corso. */
    val running = MutableStateFlow(false)

    /** Esito di un giro di invio: risultato del NAS, foto tolte dalla coda (sparite da telefono e NAS), ancora in attesa. */
    class Outcome(val result: NasSync.Result, val gone: Set<String>, val waiting: Int)

    /** Le foto DNG di una cartella del telefono. */
    fun query(context: Context, folder: String, inWork: Boolean): List<PhotoItem> {
        val out = ArrayList<PhotoItem>()
        val cols = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_MODIFIED,
        )
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        context.contentResolver.query(
            uri, cols, "${MediaStore.Images.Media.RELATIVE_PATH} = ?", arrayOf(folder),
            "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media.DISPLAY_NAME} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (!name.endsWith(".dng", ignoreCase = true)) continue
                val id = c.getLong(0)
                out += PhotoItem(id, ContentUris.withAppendedId(uri, id), name, c.getLong(2), c.getLong(3), c.getLong(4), inWork)
            }
        }
        return out
    }

    /** Il NAS risponde sulla porta SMB (445) entro 3 secondi. */
    fun reachable(host: String): Boolean = host.isNotBlank() && runCatching {
        Socket().use { it.connect(InetSocketAddress(host, 445), 3000) }
        true
    }.getOrDefault(false)

    /** Una VPN è accesa sul telefono. */
    @Suppress("DEPRECATION")
    fun vpnOn(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }

    /** Apre OpenVPN Connect (false se non è installata). */
    fun openVpn(context: Context): Boolean {
        val i = context.packageManager.getLaunchIntentForPackage("net.openvpn.openvpn") ?: return false
        context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /**
     * Invia gli .xmp delle foto indicate e aggiorna i metadati salvati. `onPhone`: i nomi delle foto ancora sul telefono
     * (null se non si sa: allora nessuna foto esce dalla coda). Torna null se un altro invio è già in corso.
     */
    fun send(context: Context, cfg: NasConfig, todo: Map<String, PhotoMeta>, onPhone: Set<String>?): Outcome? {
        if (!lock.tryLock()) return null
        try {
            val store = MetaStore.of(context)
            val started = System.currentTimeMillis()
            val r = NasSync.send(cfg, todo)
            r.catalog?.let { Catalog.save(context, it) }
            r.status?.let { HomeStatus.save(context, it) }
            NasState.save(context, r.names, r.heartbeat)
            val updates = HashMap<String, PhotoMeta>()
            for ((name, m) in todo) {
                val now = store.get(name)
                if (now.changed != m.changed) continue // modificata di nuovo durante l'invio: resta in coda
                updates[name] = when (name) {
                    in r.sent -> now.copy(sent = started, error = "", notOnNas = false)
                    in r.failed -> now.copy(error = r.failed.getValue(name), notOnNas = false)
                    else -> now.copy(notOnNas = true) // DNG non sul NAS: resta in coda
                }
            }
            // non più sul telefono e nemmeno sul NAS (foto cancellate da entrambi): esce dalla coda per sempre
            val gone = if (onPhone == null) emptySet() else
                r.waiting.filter { it !in onPhone && updates.containsKey(it) }.toSet()
            store.putAll(updates - gone)
            store.removeAll(gone)
            changes.value = System.nanoTime()
            return Outcome(r, gone, r.waiting.size - gone.size)
        } finally {
            lock.unlock()
        }
    }

    /** Rilegge dal NAS preset e resoconto (senza inviare); torna i nomi in minuscolo dei DNG presenti sul NAS. */
    fun refresh(context: Context, cfg: NasConfig): Set<String> {
        val l = NasSync.look(cfg)
        l.catalog?.let { Catalog.save(context, it) }
        l.status?.let { HomeStatus.save(context, it) }
        NasState.save(context, l.names, l.heartbeat)
        changes.value = System.nanoTime()
        return l.names
    }

    /** Avvia (o allarga) l'invio in background per le foto appena spedite. */
    fun startBackground(context: Context, names: Collection<String>) {
        if (names.isEmpty()) return
        val i = Intent(context, ShipService::class.java).putExtra(ShipService.NAMES, ArrayList(names))
        context.startForegroundService(i)
    }
}
