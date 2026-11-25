package io.github.poty87.viewercraftstar

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Collegamento diretto alla RICOH GR IV (al posto di GR World): Bluetooth per accendere il Wi-Fi della fotocamera e
 * leggerne nome e password, poi Wi-Fi verso il suo server web (192.168.0.1) per l'elenco delle foto e lo scarico.
 * Protocollo in _claude/gr-camera/PROTOCOLLO.md.
 */
object GrCamera {
    private const val TAG = "VcsGr"
    const val HOST = "http://192.168.0.1"

    private fun u(s: String): UUID = UUID.fromString(s)
    // informazioni
    val MODEL = u("35FE6272-6AA5-44D9-88E1-F09427F51A71")
    val FIRMWARE = u("B4EB8905-7411-40A6-A367-2834C2157EA7")
    // Wi-Fi della fotocamera
    val WIFI_ON = u("9111CDD0-9F01-45C4-A2D4-E09E8FB0424D")
    val SSID = u("90638E5A-E77D-409D-B550-78F7E1CA5AB4")
    val PASSWORD = u("0F38279C-FE9E-461B-8596-81287E8C9A81")
    // stato e comandi
    val POWER = u("B58CE84C-0666-4DE9-BEC8-2D27B27B3211")
    val DATETIME = u("FA46BBDD-8A8F-4796-8CF3-AA58949B130A")
    val TRANSFER = u("1452335A-EC7F-4877-B8AB-0F72E18BB295")

    /** La fotocamera GR già associata al telefono (dalle Impostazioni Bluetooth o da GR World), o null. */
    @SuppressLint("MissingPermission")
    fun bonded(context: Context): BluetoothDevice? {
        val bt = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        return bt.bondedDevices?.firstOrNull { d ->
            val n = d.name.orEmpty()
            n.startsWith("GR_", ignoreCase = true) || n.contains("RICOH", ignoreCase = true)
        }
    }

    /** Un collegamento GATT con operazioni una alla volta (Android ne accetta solo una per volta). */
    @SuppressLint("MissingPermission")
    class Gatt(private val context: Context, private val device: BluetoothDevice) {
        private var gatt: BluetoothGatt? = null
        private val lock = Mutex()
        private var connected = CompletableDeferred<Unit>()
        private var discovered = CompletableDeferred<Unit>()
        private var pendingRead: CompletableDeferred<ByteArray>? = null
        private var pendingWrite: CompletableDeferred<Unit>? = null

        private val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                Log.i(TAG, "stato BLE $newState ($status)")
                if (newState == BluetoothProfile.STATE_CONNECTED) { connected.complete(Unit); g.discoverServices() }
                else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val e = IllegalStateException("Bluetooth scollegato ($status)")
                    connected.completeExceptionally(e); discovered.completeExceptionally(e)
                    pendingRead?.completeExceptionally(e); pendingWrite?.completeExceptionally(e)
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) discovered.complete(Unit)
                else discovered.completeExceptionally(IllegalStateException("servizi non trovati ($status)"))
            }

            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) pendingRead?.complete(value)
                else pendingRead?.completeExceptionally(IllegalStateException("lettura ${c.uuid} non riuscita ($status)"))
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) pendingWrite?.complete(Unit)
                else pendingWrite?.completeExceptionally(IllegalStateException("scrittura ${c.uuid} non riuscita ($status)"))
            }
        }

        suspend fun connect() {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            withTimeout(20_000) { connected.await(); discovered.await() }
        }

        private fun find(id: UUID): BluetoothGattCharacteristic =
            gatt?.services?.firstNotNullOfOrNull { s -> s.getCharacteristic(id) } ?: error("caratteristica $id non trovata")

        suspend fun read(id: UUID): ByteArray = lock.withLock {
            val d = CompletableDeferred<ByteArray>().also { pendingRead = it }
            check(gatt?.readCharacteristic(find(id)) == true) { "lettura $id rifiutata" }
            withTimeout(10_000) { d.await() }
        }

        suspend fun write(id: UUID, value: ByteArray) = lock.withLock {
            val d = CompletableDeferred<Unit>().also { pendingWrite = it }
            val r = gatt?.writeCharacteristic(find(id), value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            check(r == BluetoothGatt.GATT_SUCCESS) { "scrittura $id rifiutata ($r)" }
            withTimeout(10_000) { d.await() }
        }

        fun close() { runCatching { gatt?.disconnect(); gatt?.close() }; gatt = null }
    }

    /** Aspetta che la fotocamera segnali il Wi-Fi acceso (la caratteristica torna 1); true se è successo entro `ms`. */
    suspend fun waitWifiOn(g: Gatt, ms: Long = 10_000): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            val v = runCatching { g.read(WIFI_ON) }.getOrNull()
            if (v != null && v.isNotEmpty() && v[0].toInt() != 0) return true
            delay(400)
        }
        return false
    }

    /**
     * Le date di scatto delle foto (pagina di informazioni di ogni foto, quattro alla volta). `onProgress` riceve
     * quante sono pronte. Le foto senza data restano con taken = 0.
     */
    suspend fun withDates(network: Network, list: List<Photo>, onProgress: (Int) -> Unit): List<Photo> =
        kotlinx.coroutines.coroutineScope {
            val gate = kotlinx.coroutines.sync.Semaphore(4)
            var done = 0
            list.map { p ->
                async(kotlinx.coroutines.Dispatchers.IO) {
                    gate.withPermit {
                        val t = runCatching {
                            val dt = JSONObject(getText(network, p.infoQuery)).optString("datetime")
                            java.time.LocalDateTime.parse(dt).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                        }.getOrDefault(0L)
                        synchronized(this@GrCamera) { done++ }
                        onProgress(done)
                        p.copy(taken = t)
                    }
                }
            }.map { it.await() }
        }

    /** Testo terminato da zeri. */
    fun text(b: ByteArray): String = String(b.takeWhile { it != 0.toByte() }.toByteArray(), Charsets.UTF_8)

    /** Data e ora come le scrive GR World: anno (2 byte LE), mese, giorno, ore, minuti, secondi. */
    fun now(): ByteArray {
        val c = java.util.Calendar.getInstance()
        val y = c.get(java.util.Calendar.YEAR)
        return byteArrayOf((y and 0xFF).toByte(), (y shr 8).toByte(), (c.get(java.util.Calendar.MONTH) + 1).toByte(),
            c.get(java.util.Calendar.DAY_OF_MONTH).toByte(), c.get(java.util.Calendar.HOUR_OF_DAY).toByte(),
            c.get(java.util.Calendar.MINUTE).toByte(), c.get(java.util.Calendar.SECOND).toByte())
    }

    /**
     * Si collega alla rete Wi-Fi della fotocamera (Android chiede conferma la prima volta). Torna la rete e il
     * callback da annullare quando si ha finito (il telefono torna alla sua rete).
     */
    suspend fun joinWifi(context: Context, ssid: String, password: String): Pair<Network, ConnectivityManager.NetworkCallback> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val spec = WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(password).build()
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(spec).build()
        return suspendCancellableCoroutine { cont ->
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { if (cont.isActive) cont.resume(network to this) }
                override fun onUnavailable() {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("rete della fotocamera non raggiunta"))
                }
            }
            cm.requestNetwork(req, cb, 60_000)
            cont.invokeOnCancellation { runCatching { cm.unregisterNetworkCallback(cb) } }
        }
    }

    fun release(context: Context, cb: ConnectivityManager.NetworkCallback?) {
        if (cb != null) runCatching { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) }
    }

    private fun open(network: Network, path: String, timeout: Int = 15_000): HttpURLConnection =
        (network.openConnection(URL(HOST + path)) as HttpURLConnection).apply {
            connectTimeout = timeout; readTimeout = timeout
        }

    fun getText(network: Network, path: String): String = open(network, path).let { c ->
        try {
            check(c.responseCode == 200) { "HTTP ${c.responseCode} per $path" }
            c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally { c.disconnect() }
    }

    fun getBytes(network: Network, path: String): ByteArray = open(network, path).let { c ->
        try {
            check(c.responseCode == 200) { "HTTP ${c.responseCode} per $path" }
            c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }

    /** Le dimensioni di miniatura che la fotocamera conosce (da GR World), dalla più adatta alla griglia. */
    private val THUMB_SIZES = listOf("thumb", "xs", "view")
    /** La prima dimensione che ha funzionato: le altre miniature la usano subito. */
    @Volatile private var thumbSize: String? = null
    private val thumbLogged = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * La miniatura di una foto: prova le dimensioni note e, per un DNG senza miniatura, quella del JPEG con lo stesso
     * nome (scatti RAW+JPEG). Ogni tipo di errore finisce una volta nel log.
     */
    fun thumbnail(network: Network, photo: Photo, twins: Set<String>): android.graphics.Bitmap? {
        val candidates = buildList {
            add(photo)
            if (photo.name.endsWith(".dng", true)) {
                val jpg = photo.name.substringBeforeLast('.') + ".JPG"
                if (photo.storage + ":" + jpg.lowercase() in twins) add(photo.copy(name = jpg))
            }
        }
        val sizes = thumbSize?.let { listOf(it) + (THUMB_SIZES - it) } ?: THUMB_SIZES
        for (p in candidates) for (size in sizes) {
            val r = runCatching {
                val c = open(network, p.query(size))
                try {
                    val code = c.responseCode
                    if (code != 200) error("HTTP $code, ${c.contentType}")
                    val bytes = c.inputStream.use { it.readBytes() }
                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = if (size == "view") 4 else 1 }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                        ?: error("non è un'immagine (${c.contentType}, ${bytes.size} byte)")
                } finally { c.disconnect() }
            }
            r.onSuccess { thumbSize = size; return it }
            val key = "${p.name.substringAfterLast('.')}/$size/${r.exceptionOrNull()?.message?.take(40)}"
            if (thumbLogged.add(key)) Log.w(TAG, "miniatura ${p.name} ?size=$size: ${r.exceptionOrNull()?.message}")
        }
        return null
    }

    /**
     * Cerca le fotocamere GR che stanno chiedendo l'associazione (sulla fotocamera: impostazioni Bluetooth › associa).
     * `onFound` riceve ogni fotocamera trovata, una volta; la ricerca dura `seconds` o finché la coroutine è attiva.
     */
    @SuppressLint("MissingPermission")
    suspend fun scan(context: Context, seconds: Int, onFound: (BluetoothDevice) -> Unit) {
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
            ?: error(L.t("Bluetooth spento", "Bluetooth is off"))
        val seen = HashSet<String>()
        val cb = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(type: Int, r: android.bluetooth.le.ScanResult) {
                val name = r.scanRecord?.deviceName ?: r.device.name ?: return
                if (!(name.startsWith("GR_", true) || name.contains("RICOH", true))) return
                if (seen.add(r.device.address)) { Log.i(TAG, "trovata $name"); onFound(r.device) }
            }
        }
        val settings = android.bluetooth.le.ScanSettings.Builder().setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, cb)
        try { delay(seconds * 1000L) } finally { runCatching { scanner.stopScan(cb) } }
    }

    /**
     * Associa il telefono alla fotocamera come fa GR World: collegamento, poi richiesta di associazione dal telefono.
     * Android mostra un codice, uguale a quello sulla fotocamera, da confermare su tutti e due. Se la fotocamera ricorda
     * ancora questo telefono, o non è in «avvia associazione», chiude il collegamento (anche con GR World): va tolto il
     * telefono dall'elenco della fotocamera. `onConnected`: collegati. Torna quando
     * l'associazione è fatta; errore se viene rifiutata o non arriva in 2 minuti.
     */
    @SuppressLint("MissingPermission")
    suspend fun bond(context: Context, device: BluetoothDevice, onConnected: () -> Unit = {}) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return
        val done = CompletableDeferred<Boolean>()
        var started = false
        val rx = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: android.content.Intent) {
                val d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                if (d.address != device.address) return
                val st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                Log.i(TAG, "associazione: stato $st")
                when (st) {
                    BluetoothDevice.BOND_BONDING -> started = true
                    BluetoothDevice.BOND_BONDED -> done.complete(true)
                    BluetoothDevice.BOND_NONE -> if (started) done.complete(false)
                }
            }
        }
        context.registerReceiver(rx, android.content.IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), Context.RECEIVER_EXPORTED)
        val ready = CompletableDeferred<BluetoothGatt>()
        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                Log.i(TAG, "associazione: collegamento $newState ($status)")
                if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
                else if (newState == BluetoothProfile.STATE_DISCONNECTED && !done.isCompleted && !started)
                    ready.completeExceptionally(IllegalStateException(L.t("La fotocamera ha chiuso il collegamento ($status)",
                        "The camera closed the connection ($status)")))
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { ready.complete(g) }
        }
        val gatt = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
        try {
            withTimeout(120_000) {
                ready.await()
                onConnected()
                // come GR World (registrato il 20/11/2026): collegati, si leggono modello e firmware dal servizio standard e
                // poi è il telefono a chiedere l'associazione; la fotocamera deve essere in «avvia associazione" e non
                // ricordare già questo telefono, altrimenti chiude il collegamento
                delay(1500)
                if (!started && !done.isCompleted) {
                    Log.i(TAG, "associazione: la chiedo io, come GR World")
                    check(device.createBond()) { L.t("Android non ha avviato l'associazione", "Android didn't start pairing") }
                }
                val ok = done.await()
                check(ok) { L.t("Associazione rifiutata o annullata", "Pairing refused or cancelled") }
            }
        } finally {
            runCatching { context.unregisterReceiver(rx) }
            runCatching { gatt.disconnect(); gatt.close() }
        }
    }

    /**
     * Una foto sulla fotocamera: cartella (es. 100RICOH), nome del file e memoria ("in" interna, "sd1" scheda; vuota
     * se la fotocamera ne ha una sola). Le due memorie numerano le foto ognuna per conto suo: lo stesso nome può
     * esserci in tutte e due, quindi una foto si riconosce da [key] (memoria + percorso).
     */
    data class Photo(val dir: String, val name: String, val storage: String, val taken: Long = 0L) {
        val path: String get() = "/v1/photos/$dir/$name"
        val key: String get() = "$storage:$path"
        /** Il nome che avrà sul telefono (come GR World): millisecondi dello scatto + "_" + nome della fotocamera. */
        val phoneName: String get() = if (taken > 0) "${taken}_$name" else name
        private val storageArg: String get() = if (storage.isNotEmpty()) "storage=$storage" else ""
        fun query(size: String) = "$path?size=$size" + if (storageArg.isNotEmpty()) "&$storageArg" else ""
        val infoQuery: String get() = "$path/info" + if (storageArg.isNotEmpty()) "?$storageArg" else ""
    }

    /** Le memorie della GR, come le chiama GR World: interna e scheda SD. */
    const val INTERNAL = "in"
    const val SD = "sd1"

    /**
     * L'elenco delle foto di tutte e due le memorie (la più recente per prima). Senza indicare la memoria la GR risponde
     * con una sola (con la scheda inserita, la scheda): si chiedono quindi la memoria interna e la scheda una per una.
     * Se la fotocamera non distingue (stessa risposta per tutte e due) resta l'elenco unico. I JSON grezzi vanno nel log.
     */
    suspend fun photos(network: Network): List<Photo> {
        // il server della fotocamera può metterci qualche secondo a rispondere dopo l'accensione del Wi-Fi
        var last: Throwable? = null
        var plain: String? = null
        for (i in 0 until 10) {
            val r = runCatching { getText(network, "/v1/photos") }
            r.onSuccess { plain = it }
            if (plain != null) break
            last = r.exceptionOrNull()
            delay(1500)
        }
        val body = plain ?: throw last ?: IllegalStateException("la fotocamera non risponde")
        Log.i(TAG, "photos: " + body.take(3000))
        val byStorage = listOf(INTERNAL, SD).mapNotNull { st ->
            runCatching { getText(network, "/v1/photos?storage=$st") }
                .onSuccess { Log.i(TAG, "photos ($st): " + it.take(3000)) }
                .onFailure { Log.i(TAG, "photos ($st): ${it.message}") }
                .getOrNull()?.let { b -> st to parse(b, st) }
        }.filter { it.second.isNotEmpty() }
        val names = byStorage.map { (_, l) -> l.map { it.dir + "/" + it.name }.toSet() }
        return when {
            byStorage.isEmpty() -> parse(body, "")
            // la fotocamera ignora la richiesta della memoria: le due risposte sono uguali, quindi c'è un elenco solo
            byStorage.size == 2 && names[0] == names[1] -> parse(body, "")
            else -> byStorage.flatMap { it.second }
        }
    }

    fun parse(body: String, storage: String): List<Photo> {
        val o = JSONObject(body)
        val out = ArrayList<Photo>()
        val dirs = o.optJSONArray("dirs") ?: return out
        for (i in 0 until dirs.length()) {
            val d = dirs.getJSONObject(i)
            val name = d.optString("name")
            val files = d.optJSONArray("files") ?: continue
            for (j in 0 until files.length()) {
                val f = files.get(j)
                val n = if (f is JSONObject) f.optString("n", f.optString("name")) else f.toString()
                if (n.isNotEmpty()) out += Photo(name, n, d.optString("storage", storage))
            }
        }
        return out.sortedWith(compareByDescending<Photo> { it.dir }.thenByDescending { it.name })
    }

    /** Scarica una foto nella cartella del telefono (`folder`, es. "Pictures/VCS_Import_Photo/"). */
    /** La galleria del telefono: qui finiscono i JPEG della fotocamera (non vanno al PC). */
    const val GALLERY = "DCIM/Camera/"

    /** C'è già nel telefono un file con questo nome in questa cartella (per non scaricare due volte i JPEG). */
    fun existsOnPhone(context: Context, folder: String, name: String): Boolean = runCatching {
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND ${MediaStore.Images.Media.DISPLAY_NAME} = ?",
            arrayOf(folder, name), null)?.use { it.count > 0 } ?: false
    }.getOrDefault(false)

    fun download(context: Context, network: Network, photo: Photo, folder: String, onBytes: (Long, Long) -> Unit): Boolean {
        val ext = photo.name.substringAfterLast('.', "").lowercase()
        val mime = when (ext) { "dng" -> "image/x-adobe-dng"; "jpg", "jpeg" -> "image/jpeg"; else -> "image/*" }
        val cr = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, photo.name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, folder)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        val ok = runCatching {
            val c = open(network, photo.query("full"), 60_000)
            try {
                check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
                val total = c.contentLengthLong
                Log.i(TAG, "scarico ${photo.name}: ${c.contentType}, $total byte")
                cr.openOutputStream(uri)!!.use { out ->
                    c.inputStream.use { inp ->
                        val buf = ByteArray(256 * 1024)
                        var done = 0L
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            onBytes(done, total)
                        }
                    }
                }
            } finally { c.disconnect() }
        }.onFailure { Log.w(TAG, "scarico ${photo.name} non riuscito", it) }.isSuccess
        // il nome come lo dà GR World: i millisecondi dello scatto (data EXIF, nel fuso del telefono) + "_" + il nome della
        // fotocamera (es. _R000108.DNG → 1795184817000__R000108.DNG), così è coerente con le foto già sul NAS
        if (ok) runCatching {
            val taken = cr.openInputStream(uri)!!.use { android.media.ExifInterface(it).getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL) }
            val ms = java.time.LocalDateTime.parse(taken, java.time.format.DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            cr.update(uri, ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, "${ms}_${photo.name}") }, null, null)
        }.onFailure { Log.w(TAG, "data di scatto di ${photo.name} non letta: resta il nome della fotocamera", it) }
        if (ok) cr.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        else runCatching { cr.delete(uri, null, null) }
        return ok
    }
}
