package io.github.poty87.viewercraftstar

import android.app.Application
import android.content.ContentUris
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Filter { ALL, UNRATED, PICKED, REJECTED, STARRED, PENDING }

/** Lo stato della connessione al NAS. */
enum class Conn { UNKNOWN, CHECKING, NO_CONFIG, HOME, VPN, VPN_NO_NAS, AWAY }

/** Le sezioni: cartella di lavoro (da rivedere), cartella sincronizzata (spedite: in viaggio o al sicuro a casa). */
enum class Section { REVIEW, SENT }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val settings = Settings(app)
    private val store = MetaStore.of(app)

    var photos by mutableStateOf<List<PhotoItem>>(emptyList()); private set
    var metas by mutableStateOf<Map<String, PhotoMeta>>(store.all()); private set
    var filter by mutableStateOf(Filter.ALL)
    var loading by mutableStateOf(false); private set
    var syncing by mutableStateOf(false); private set
    var status by mutableStateOf(""); private set
    var statusLevel by mutableStateOf("info"); private set
    var presets by mutableStateOf(Catalog.load(app)); private set
    var home by mutableStateOf(HomeStatus.load(app)); private set
    var nas by mutableStateOf(NasState.load(app)); private set
    var section by mutableStateOf(Section.REVIEW)
    /** Problema con la cartella di lavoro (null se tutto a posto). */
    var workProblem by mutableStateOf<String?>(null); private set
    var moving by mutableStateOf(false); private set
    /** L'elenco delle foto del telefono è stato letto almeno una volta. */
    private var loaded = false
    var conn by mutableStateOf(Conn.UNKNOWN); private set
    /** L'invio in background dopo "Spedisci" è in corso. */
    var shipping by mutableStateOf(false); private set
    /** Foto appena spedite con il NAS irraggiungibile: si chiede se accendere la VPN. */
    var vpnAsk by mutableStateOf<List<String>?>(null); private set

    private val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
    private var connJob: Job? = null
    private val netCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) = checkConnectionSoon()
        override fun onLost(network: android.net.Network) = checkConnectionSoon()
    }

    init {
        // l'invio in background aggiorna metadati e resoconto: l'app aperta li rilegge
        viewModelScope.launch {
            Shipper.changes.collect {
                metas = store.all()
                home = HomeStatus.load(getApplication())
                presets = Catalog.load(getApplication())
                nas = NasState.load(getApplication())
                requeueUnconfirmed()
            }
        }
        viewModelScope.launch { Shipper.running.collect { shipping = it } }
        // reti che vanno e vengono (Wi-Fi, dati, VPN): si ricontrolla il NAS
        runCatching {
            cm.registerNetworkCallback(android.net.NetworkRequest.Builder()
                .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), netCallback)
        }
    }

    override fun onCleared() {
        runCatching { cm.unregisterNetworkCallback(netCallback) }
        super.onCleared()
    }

    private fun checkConnectionSoon() {
        connJob?.cancel()
        connJob = viewModelScope.launch { delay(1500); checkConnection() }
    }

    /** A casa (NAS raggiungibile senza VPN), via VPN, o fuori casa (NAS non raggiungibile). */
    fun checkConnection() {
        val cfg = settings.nas
        if (!cfg.complete) { conn = Conn.NO_CONFIG; return }
        viewModelScope.launch {
            if (conn == Conn.UNKNOWN) conn = Conn.CHECKING
            val (vpn, ok) = withContext(Dispatchers.IO) { Shipper.vpnOn(getApplication()) to Shipper.reachable(cfg.host) }
            conn = when {
                ok && vpn -> Conn.VPN
                ok -> Conn.HOME
                vpn -> Conn.VPN_NO_NAS
                else -> Conn.AWAY
            }
        }
    }

    fun openVpn() {
        if (!Shipper.openVpn(getApplication())) setStatus("warn", L.t("OpenVPN Connect non è installata.", "OpenVPN Connect isn't installed."))
    }

    /** Fa ripartire l'invio in background per le foto spedite non ancora in LightCraft (dopo una sospensione). */
    fun resume() {
        val names = photos.filter { !it.inWork && level(it) != Level.GREEN }.map { it.name }
        val cfg = settings.nas
        if (names.isEmpty() || !cfg.complete) return
        viewModelScope.launch {
            if (withContext(Dispatchers.IO) { Shipper.reachable(cfg.host) }) Shipper.startBackground(getApplication(), names)
            else vpnAsk = names
        }
    }

    /** Risposta alla domanda sulla VPN: si avvia l'invio in background (che aspetta il NAS) oppure si rimanda. */
    fun answerVpn(open: Boolean) {
        val names = vpnAsk ?: return
        vpnAsk = null
        if (open) {
            Shipper.startBackground(getApplication(), names)
            openVpn()
        } else setStatus("info", L.t("Metadati in coda: partiranno quando riapri l'app a casa o con la VPN.",
            "Metadata queued: it will be sent when you open the app at home or with the VPN."))
    }

    fun level(p: PhotoItem) = levelOf(p, meta(p.name), home, nas)

    /** Si può togliere dal telefono: al sicuro sul NAS (blu) o già in LightCraft (verde). */
    fun isSafe(p: PhotoItem) = level(p) >= Level.BLUE
    fun countLevel(l: Level) = photos.count { level(it) == l }
    fun countIn(s: Section) = photos.count { it.inWork == (s == Section.REVIEW) }

    fun meta(name: String) = metas[name] ?: PhotoMeta()

    val visible: List<PhotoItem>
        get() = photos.filter { it.inWork == (section == Section.REVIEW) }.filter { p ->
            val m = meta(p.name)
            when (filter) {
                Filter.ALL -> true
                Filter.UNRATED -> m.flag == Flag.NONE && m.rating == 0
                Filter.PICKED -> m.flag == Flag.PICK
                Filter.REJECTED -> m.flag == Flag.REJECT
                Filter.STARRED -> m.rating > 0
                Filter.PENDING -> m.pending
            }
        }

    fun count(f: Filter): Int {
        val old = filter
        filter = f
        val n = visible.size
        filter = old
        return n
    }

    /** Da inviare al NAS: solo le foto già spedite (quelle in lavorazione partono con "Spedisci al NAS"). */
    val pending: Int get() = photos.count { !it.inWork && meta(it.name).pending }

    // ---------------------------------------------------------------- foto della cartella

    fun reload() {
        viewModelScope.launch {
            loading = true
            val (problem, created) = withContext(Dispatchers.IO) { ensureWorkFolder() }
            workProblem = problem
            if (created) setStatus("ok", L.t("Creata la cartella di lavoro ${settings.workFolder}: fai arrivare lì i DNG nuovi.",
                "Created the work folder ${settings.workFolder}: have new DNG files arrive there."))
            photos = withContext(Dispatchers.IO) {
                val app = getApplication<Application>()
                val sync = Shipper.query(app, settings.phoneFolder, false)
                if (problem == null) Shipper.query(app, settings.workFolder, true) + sync else sync
            }
            metas = store.all()
            loading = false
            loaded = true
        }
    }

    /**
     * La cartella di lavoro: se c'è già (vuota o piena) non si tocca; se manca si crea. Non deve coincidere con la
     * cartella sincronizzata né stare dentro di lei (Drive la caricherebbe sul NAS). Torna (problema, creata).
     */
    private fun ensureWorkFolder(): Pair<String?, Boolean> {
        val work = settings.workFolder
        val sync = settings.phoneFolder
        if (work.isEmpty()) return L.t("Cartella di lavoro non impostata (Impostazioni).", "Work folder not set (Settings).") to false
        if (work.equals(sync, ignoreCase = true) || work.startsWith(sync, ignoreCase = true))
            return L.t("La cartella di lavoro deve stare fuori dalla cartella sincronizzata con il NAS (Impostazioni).",
                "The work folder must be outside the folder synced with the NAS (Settings).") to false
        val dir = java.io.File(android.os.Environment.getExternalStorageDirectory(), work.trimEnd('/'))
        if (dir.isDirectory) return null to false
        if (dir.exists()) return L.t("Esiste un file che si chiama come la cartella di lavoro: $work",
            "A file has the same name as the work folder: $work") to false
        return if (dir.mkdirs()) null to true
        else L.t("Non riesco a creare la cartella di lavoro $work", "Can't create the work folder $work") to false
    }

    // ---------------------------------------------------------------- metadati

    fun update(name: String, change: (PhotoMeta) -> PhotoMeta) {
        val old = meta(name)
        var new = change(old)
        if (new == old) return
        // autore e copyright predefiniti: alla prima modifica di una foto, se non ne ha
        if (new.creator.isEmpty() && old.isEmpty) new = new.copy(creator = settings.defaultCreator)
        if (new.copyright.isEmpty() && old.isEmpty) new = new.copy(copyright = settings.defaultCopyright)
        new = new.copy(changed = System.currentTimeMillis(), error = "")
        store.put(name, new)
        metas = metas + (name to new)
        scheduleSync()
    }

    /** Smette di riprovare l'invio di una foto (per esempio perché sul NAS non c'è più). */
    fun dropPending(name: String) {
        val m = meta(name).copy(sent = System.currentTimeMillis(), notOnNas = false, error = "")
        store.put(name, m)
        metas = metas + (name to m)
    }

    fun setFlag(name: String, flag: Flag) = update(name) { it.copy(flag = if (it.flag == flag) Flag.NONE else flag) }
    fun setRating(name: String, stars: Int) = update(name) { it.copy(rating = if (it.rating == stars) 0 else stars) }
    fun setLabel(name: String, label: ColorLabel) = update(name) { it.copy(label = if (it.label == label) null else label) }
    fun setPreset(name: String, preset: PresetInfo?) =
        update(name) { it.copy(presetId = preset?.id ?: "", presetName = preset?.name ?: "") }

    private fun gotCatalog(json: String?) {
        if (json == null) return
        Catalog.save(getApplication(), json)
        presets = Catalog.load(getApplication())
    }

    private fun gotStatus(json: String?) {
        if (json == null) return
        HomeStatus.save(getApplication(), json)
        home = HomeStatus.load(getApplication())
        requeueUnconfirmed()
    }

    /**
     * Auto-riparazione: una foto che a casa è in libreria ma senza l'ultima versione dei metadati (inviata con una
     * versione vecchia dell'app, o invio perso) torna in coda, se l'invio risale a più di 5 minuti fa.
     */
    private fun requeueUnconfirmed() {
        val h = home ?: return
        val now = System.currentTimeMillis()
        val again = HashMap<String, PhotoMeta>()
        for (p in photos) {
            if (p.inWork) continue
            val m = meta(p.name)
            val applied = h.photos[p.stem.lowercase()] ?: continue
            if (m.changed > 0 && !m.pending && applied < m.changed && now - m.sent > 5 * 60 * 1000) {
                again[p.name] = m.copy(sent = 0)
            }
        }
        if (again.isEmpty()) return
        store.putAll(again)
        metas = metas + again
        scheduleSync()
    }

    /** Rilegge dal NAS elenco dei preset e resoconto (senza inviare niente); poi `then`. */
    fun refreshHome(quiet: Boolean = true, then: (() -> Unit)? = null) {
        val cfg = settings.nas
        if (!cfg.complete) { then?.invoke(); return }
        viewModelScope.launch {
            // preset, resoconto, DNG presenti e battito del PC: li rilegge chi ascolta Shipper.changes
            val r = withContext(Dispatchers.IO) { runCatching { Shipper.refresh(getApplication(), cfg) } }
            r.onFailure { if (!quiet) setStatus("warn", L.t("NAS non raggiungibile: se sei fuori casa, attiva la VPN.",
                    "NAS not reachable: if you're away from home, turn on the VPN.")) }
            then?.invoke(r.isSuccess)
        }
    }

    private fun (() -> Unit).invoke(ok: Boolean) = if (ok) this() else Unit

    /** Le foto da spostare nel cestino: solo quelle che il resoconto appena riletto conferma a casa. */
    fun trashable(names: Collection<String>): List<PhotoItem> =
        photos.filter { it.name in names && isSafe(it) }

    fun trashed(n: Int) {
        reload()
        setStatus("ok", L.t("$n foto spostate nel cestino del telefono (recuperabili per 30 giorni).",
            "$n photos moved to the phone's bin (recoverable for 30 days)."))
    }

    // ---------------------------------------------------------------- spedizione (cartella di lavoro -> sincronizzata)

    /** Cosa fa "Spedisci al NAS": foto da spostare, scartate da mettere nel cestino, e quelle che restano (stesso nome già spedito). */
    data class Shipment(val move: List<PhotoItem>, val trash: List<PhotoItem>, val clash: List<PhotoItem>) {
        val all get() = move + trash
    }

    fun shippable(): Shipment {
        val there = photos.filter { !it.inWork }.map { it.name.lowercase() }.toSet()
        val work = photos.filter { it.inWork }
        val (trash, keep) = work.partition { meta(it.name).flag == Flag.REJECT }
        val (move, clash) = keep.partition { it.name.lowercase() !in there }
        return Shipment(move, trash, clash)
    }

    /**
     * Android ha già dato il permesso: le foto passano nella cartella sincronizzata (Synology Drive le carica sul NAS),
     * le scartate vanno nel cestino del telefono (recuperabili per 30 giorni) e sul NAS non arrivano mai.
     */
    fun ship(s: Shipment) {
        if (s.all.isEmpty() || moving) return
        val target = settings.phoneFolder
        viewModelScope.launch {
            moving = true
            val (moved, binned, failed) = withContext(Dispatchers.IO) {
                val cr = getApplication<Application>().contentResolver
                fun apply(p: PhotoItem, v: android.content.ContentValues) =
                    runCatching { cr.update(p.uri, v, null, null) }.getOrDefault(0) > 0
                val ok = s.move.count { apply(it, android.content.ContentValues().apply { put(MediaStore.Images.Media.RELATIVE_PATH, target) }) }
                val bin = s.trash.count { apply(it, android.content.ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }) }
                Triple(ok, bin, s.all.size - ok - bin)
            }
            moving = false
            reload()
            // metadati e conferma "al sicuro": in background se il NAS risponde, altrimenti si chiede della VPN
            val names = s.move.map { it.name }
            val cfg = settings.nas
            if (moved > 0 && cfg.complete) {
                if (withContext(Dispatchers.IO) { Shipper.reachable(cfg.host) }) Shipper.startBackground(getApplication(), names)
                else vpnAsk = names
            }
            val parts = mutableListOf(L.t("$moved foto spostate in $target: Synology Drive le caricherà sul NAS, poi l'app invierà i metadati.",
                "$moved photos moved to $target: Synology Drive will upload them to the NAS, then the app sends the metadata."))
            if (binned > 0) parts += L.t("$binned scartate nel cestino del telefono (recuperabili per 30 giorni).",
                "$binned rejected photos moved to the phone's bin (recoverable for 30 days).")
            if (s.clash.isNotEmpty()) parts += L.t("${s.clash.size} non spostate: in $target c'è già un file con lo stesso nome.",
                "${s.clash.size} not moved: $target already has a file with the same name.")
            if (failed > 0) parts += L.t("$failed non riuscite per un errore.", "$failed failed because of an error.")
            setStatus(if (s.clash.isEmpty() && failed == 0) "ok" else "warn", parts.joinToString(" "))
        }
    }

    // ---------------------------------------------------------------- invio al NAS

    private var autoJob: Job? = null

    /** Invio automatico qualche secondo dopo l'ultima modifica (le modifiche ravvicinate partono insieme). */
    private fun scheduleSync() {
        autoJob?.cancel()
        autoJob = viewModelScope.launch {
            delay(4000)
            sync(quiet = true)
        }
    }

    fun sync(quiet: Boolean = false) {
        if (syncing) return
        val cfg = settings.nas
        // solo le foto spedite: quelle nella cartella di lavoro sul NAS non ci sono ancora
        val todo = photos.filter { !it.inWork }.map { it.name }.filter { meta(it).pending }.associateWith { meta(it) } +
            metas.filter { it.value.pending && photos.none { p -> p.name == it.key } }
        if (!cfg.complete) {
            if (!quiet) setStatus("warn", L.t("Imposta il NAS nelle Impostazioni per inviare i metadati.",
                "Set up the NAS in Settings to send the metadata."))
            return
        }
        if (todo.isEmpty()) {
            // niente da inviare: si rilegge comunque il resoconto, per sapere quali foto sono al sicuro a casa
            refreshHome(quiet) {
                if (!quiet) setStatus("ok", L.t("Niente da inviare: il NAS è aggiornato.", "Nothing to send: the NAS is up to date."))
            }
            return
        }
        viewModelScope.launch {
            syncing = true
            // l'invio automatico si vede dalla rotellina sul bottone: la barra resta ai messaggi che contano
            if (!quiet) setStatus("info", L.t("Invio di ${todo.size} foto al NAS…", "Sending ${todo.size} photos to the NAS…"))
            // nomi delle foto sul telefono solo se l'elenco è già stato letto: all'avvio sembrerebbero sparite tutte
            val onPhone = if (loaded) photos.map { it.name }.toSet() else null
            val result = withContext(Dispatchers.IO) { runCatching { Shipper.send(getApplication(), cfg, todo, onPhone) } }
            syncing = false
            result.onSuccess { o ->
                if (o == null) return@onSuccess // c'è già l'invio in background: ci pensa lui
                val r = o.result
                val gone = o.gone
                metas = store.all()
                presets = Catalog.load(getApplication())
                home = HomeStatus.load(getApplication())
                requeueUnconfirmed()
                val waiting = o.waiting
                val parts = mutableListOf(L.t("Inviate ${r.sent.size}", "Sent ${r.sent.size}"))
                if (gone.isNotEmpty()) parts += L.t("${gone.size} tolte dalla coda (non più né sul telefono né sul NAS)",
                    "${gone.size} removed from the queue (no longer on the phone or the NAS)")
                if (waiting > 0) parts += L.t(
                    "$waiting non ancora sul NAS (Synology Drive non le ha ancora caricate)",
                    "$waiting not on the NAS yet (Synology Drive hasn't uploaded them yet)")
                if (r.failed.isNotEmpty()) parts += L.t("${r.failed.size} con errori", "${r.failed.size} with errors")
                // invio automatico senza niente di fatto: non copre i messaggi delle azioni dell'utente
                if (!(quiet && r.sent.isEmpty() && r.failed.isEmpty() && gone.isEmpty()))
                    setStatus(if (r.failed.isEmpty() && waiting == 0) "ok" else "warn", parts.joinToString(" · "))
            }.onFailure { e ->
                if (!quiet) setStatus("error", L.t("NAS non raggiungibile: ", "NAS not reachable: ") + (e.message ?: e.javaClass.simpleName))
                else setStatus("warn", L.t("${todo.size} foto in coda: il NAS non è raggiungibile adesso.",
                    "${todo.size} photos queued: the NAS can't be reached right now."))
            }
        }
    }

    /** File di preset scelti sul telefono: caricati sul NAS, PresetCraftAppLicator li importa in LightCraft. */
    fun uploadPresets(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        val cfg = settings.nas
        if (!cfg.complete) {
            setStatus("warn", L.t("Imposta il NAS nelle Impostazioni per importare i preset.", "Set up the NAS in Settings to import presets."))
            return
        }
        val exts = listOf(".xmp", ".lrtemplate", ".lcpreset", ".zip", ".lmp", ".mplumpack")
        viewModelScope.launch {
            setStatus("info", L.t("Carico ${uris.size} preset sul NAS…", "Uploading ${uris.size} presets to the NAS…"))
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val cr = getApplication<Application>().contentResolver
                    val files = uris.mapNotNull { uri ->
                        val name = cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return@mapNotNull null
                        if (exts.none { name.endsWith(it, ignoreCase = true) }) return@mapNotNull null
                        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return@mapNotNull null
                        if (bytes.size > 50 * 1024 * 1024) null else name to bytes
                    }
                    if (files.isEmpty()) 0 else NasSync.uploadPresets(cfg, files)
                }
            }
            result.onSuccess { n ->
                if (n == 0) setStatus("warn", L.t("Nessun file di preset riconosciuto (.xmp, .lrtemplate, .lcpreset, .zip…).",
                    "No preset file recognised (.xmp, .lrtemplate, .lcpreset, .zip…)."))
                else setStatus("ok", L.t("$n preset caricati: compariranno nell'elenco dopo che PresetCraftAppLicator li avrà importati in LightCraft (con LightCraft chiuso).",
                    "$n presets uploaded: they will appear in the list once PresetCraftAppLicator has imported them into LightCraft (with LightCraft closed)."))
            }.onFailure { e -> setStatus("error", L.t("Caricamento non riuscito: ", "Upload failed: ") + (e.message ?: e.javaClass.simpleName)) }
        }
    }

    fun testNas(cfg: NasConfig, done: (String, Boolean) -> Unit) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { NasSync.test(cfg) } }
            r.onSuccess { (dng, catalog) ->
                gotCatalog(catalog)
                val extra = if (catalog == null) L.t(" Elenco dei preset non ancora pubblicato da PresetCraftAppLicator.",
                    " Preset list not published by PresetCraftAppLicator yet.")
                else L.t(" Preset: ${presets.size}.", " Presets: ${presets.size}.")
                done(L.t("Connessione riuscita: $dng DNG nella cartella.", "Connected: $dng DNG files in the folder.") + extra, true)
            }
                .onFailure { done(L.t("Connessione non riuscita: ", "Connection failed: ") + (it.message ?: it.javaClass.simpleName), false) }
        }
    }

    private fun setStatus(level: String, text: String) {
        statusLevel = level
        status = text
    }
}
