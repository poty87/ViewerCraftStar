package io.github.poty87.viewercraftstar

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.cancellation.CancellationException
import java.time.LocalDate

/** Stelle: tutte, esattamente 5, almeno N, nessuna. */
enum class StarFilter(val min: Int, val exact: Boolean = false) {
    ANY(-1), FIVE(5, true), FOUR(4), THREE(3), TWO(2), ONE(1), NONE(0, true)
}

/**
 * La sezione Libreria: la libreria LightCraft di casa, dall'indice che PresetCraftAppLicator pubblica sul NAS.
 * Per ora in sola consultazione: griglia con le pile, filtri, visualizzatore.
 */
class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    val settings = Settings(app)

    var libraries by mutableStateOf(LibraryStore.cached(app)); private set
    var current by mutableStateOf(settings.library); private set
    var index by mutableStateOf<LibIndex?>(null); private set
    var loading by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set
    var messageLevel by mutableStateOf("info"); private set

    // filtri
    var stars by mutableStateOf(StarFilter.ANY)
    var flag by mutableStateOf("")            // "", pick, reject, none
    var labels by mutableStateOf(setOf<String>())
    var text by mutableStateOf("")
    /** Il campo di ricerca è aperto (resta così anche aprendo una foto e tornando indietro). */
    var searchOpen by mutableStateOf(false)
    var camera by mutableStateOf("")
    var dateFrom by mutableStateOf<LocalDate?>(null)
    var dateTo by mutableStateOf<LocalDate?>(null)
    /** Tipo di file: "" tutti, "raw", "jpeg", "tiff". */
    var fileType by mutableStateOf("")

    // ---- ricerca per soggetti (IA): catalogo preparato dal PC di casa, modello "testo" sul telefono
    /** Cercare per soggetto (IA) invece che nei nomi e nelle parole chiave. */
    var aiMode by mutableStateOf(true)
    /** Il manifest del catalogo IA della libreria (null = la libreria non è stata analizzata dal PC). */
    var aiManifest by mutableStateOf<JSONObject?>(null); private set
    var aiModelReady by mutableStateOf(false); private set
    /** Download del modello in corso: (byte arrivati, totale). */
    var aiDownload by mutableStateOf<Pair<Long, Long>?>(null); private set
    var aiBusy by mutableStateOf(false); private set
    private var aiScores by mutableStateOf<Map<Int, Float>?>(null)
    private var aiScoresFor by mutableStateOf("")
    private var aiJob: Job? = null
    /** Media delle 5 foto più somiglianti (solo per immagine) dell'ultima ricerca per soggetto. */
    @Volatile private var aiTop5 = 0f
    private val aiLock = Mutex() // un calcolo alla volta: il modello usa già più core

    /** Ricerca per soggetto in attesa: i risultati mostrati non sono ancora quelli della frase scritta. */
    val aiPending: Boolean get() = aiActive && aiScoresFor != text.trim()

    /** Frase troppo corta per cercare mentre si scrive (si cerca solo con "Cerca" sulla tastiera). */
    val aiTooShort: Boolean get() = text.trim().let { it.isNotEmpty() && it.codePointCount(0, it.length) < AI_MIN_CHARS }

    /** La libreria ha un catalogo IA sul telefono. */
    val aiAvailable: Boolean get() = aiManifest != null && index?.let { AiStore.visualFile(getApplication(), it.library).exists() } == true
    /** Si sta cercando per soggetto. */
    val aiActive: Boolean get() = aiMode && aiAvailable && aiModelReady && text.isNotBlank()
    val aiModelSize: Long get() = AiStore.modelSize(aiManifest)

    private fun loadAiLocal(lib: String) {
        val m = AiStore.manifest(getApplication(), lib)
        aiManifest = m
        aiModelReady = AiStore.modelReady(getApplication(), m)
        aiScores = null
        aiScoresFor = ""
        warmUpAi()
    }

    /**
     * Carica in anticipo, in background, modello, impronte e descrizioni della libreria e fa una frase di prova:
     * così anche la prima ricerca è immediata (altrimenti la prima costa circa un secondo in più).
     */
    private fun warmUpAi() {
        val idx = index ?: return
        val m = aiManifest ?: return
        if (!aiModelReady || !aiAvailable) return
        val dir = AiStore.modelDir(getApplication(), m) ?: return
        val file = AiStore.visualFile(getApplication(), idx.library)
        val descFile = AiStore.descriptionsFile(getApplication(), idx.library)
        viewModelScope.launch {
            runCatching {
                aiLock.withLock { withContext(Dispatchers.Default) {
                    val t0 = System.currentTimeMillis()
                    AiEngine.warmUp(dir, file, descFile)
                    android.util.Log.i("VcsAi", "pronto in ${System.currentTimeMillis() - t0} ms")
                } }
            }.onFailure { android.util.Log.w("VcsAi", "preparazione non riuscita", it) }
        }
    }

    /** Aggiorna dal NAS il catalogo IA della libreria (solo se cambiato). */
    private suspend fun syncAi(name: String) {
        val m = runCatching { AiStore.syncLibrary(getApplication(), settings.nas, share, libRoot, name) }.getOrNull()
        if (current == name) {
            val next = m ?: AiStore.manifest(getApplication(), name)
            val changed = next?.optString("updated") != aiManifest?.optString("updated") ||
                next?.optString("model") != aiManifest?.optString("model")
            aiManifest = next
            aiModelReady = AiStore.modelReady(getApplication(), aiManifest)
            if (changed) { // catalogo nuovo: la ricerca in corso si rifà con quello
                aiScores = null
                aiScoresFor = ""
                memo = null
            }
            warmUpAi()
        }
    }

    /**
     * Calcola i risultati della ricerca per soggetto per la frase scritta: subito con "Cerca" sulla tastiera
     * (`now`), altrimenti dopo una breve pausa di scrittura (una ricerca costa circa un decimo di secondo). Mentre si
     * scrive, una o due lettere non bastano: si aspetta la terza (con "Cerca" si cerca comunque).
     */
    fun requestAi(now: Boolean = false) {
        val q = text.trim()
        if (!aiMode || !aiAvailable || !aiModelReady || q.isEmpty() || q == aiScoresFor) return
        if (!now && aiTooShort) { aiJob?.cancel(); aiBusy = false; return }
        val idx = index ?: return
        val m = aiManifest ?: return
        val dir = AiStore.modelDir(getApplication(), m) ?: return
        val file = AiStore.visualFile(getApplication(), idx.library)
        aiJob?.cancel()
        aiJob = viewModelScope.launch {
            if (!now) delay(AI_PAUSE_MS)
            aiBusy = true
            val r = runCatching {
                aiLock.withLock { withContext(Dispatchers.Default) {
                    val t0 = System.currentTimeMillis()
                    val enc = AiEngine.encoder(dir)
                    val t1 = System.currentTimeMillis()
                    val vi = AiEngine.index(file)
                    val t2 = System.currentTimeMillis()
                    // un nome di persona (dato in LightCraft) nella frase: solo le sue foto, ordinate per il resto
                    val (person, phrase) = splitPerson(idx, q)
                    val sc = if (phrase.isEmpty()) FloatArray(vi.ids.size) { 1f } else vi.scores(enc.encode(aiPhrase(phrase)))
                    val t3 = System.currentTimeMillis()
                    if (person != null) {
                        val mine = idx.photos.filter { p -> p.people.any { it.equals(person, ignoreCase = true) } }
                            .map { it.id }.toHashSet()
                        for (i in sc.indices) if (vi.ids[i] !in mine) sc[i] = -1f
                    }
                    // la soglia "vicino alle migliori" si calcola sui punteggi visivi: le descrizioni possono solo far
                    // salire o scendere le foto, non spostare la soglia (altrimenti ne escludono di giuste)
                    aiTop5 = sc.filter { it > -1f }.sortedDescending().take(5).average().toFloat()
                    // descrizioni (se ci sono): sale chi ha le parole cercate, scende un po' chi è descritta ma non le ha
                    val desc = AiEngine.descriptions(AiStore.descriptionsFile(getApplication(), idx.library))
                    val qw = Descriptions.stems(phrase)
                    if (desc != null && qw.isNotEmpty()) for (i in sc.indices) {
                        if (sc[i] <= -1f) continue
                        val m = desc.match(vi.ids[i], qw) ?: continue
                        // tutte le parole (o sinonimi): sale; solo alcune: resta dov'è; nessuna: scende un po'
                        sc[i] += when { m >= 1f -> AI_DESC_BONUS; m > 0f -> 0f; else -> -AI_DESC_BONUS / 2 }
                    }
                    val t4 = System.currentTimeMillis()
                    val top = sc.indices.sortedByDescending { sc[it] }.take(8)
                        .joinToString { "${idx.byId[vi.ids[it]]?.name ?: vi.ids[it]} %.3f".format(sc[it]) }
                    val line = "«$q» (${idx.library}): modello ${t1 - t0} ms, impronte ${t2 - t1} ms (${vi.ids.size} foto), " +
                        "frase ${t3 - t2} ms, confronto ${t4 - t3} ms, descrizioni ${desc?.text?.size ?: 0}; sopra ${"%.3f".format(AI_MIN)}: ${sc.count { it >= AI_MIN }}, " +
                        "sopra ${"%.3f".format(AI_MIN_MORE)}: ${sc.count { it >= AI_MIN_MORE }}; primi: $top"
                    android.util.Log.i("VcsAi", line)
                    logSearch(line)
                    HashMap<Int, Float>(vi.ids.size * 2).also { map -> vi.ids.forEachIndexed { i, id -> map[id] = sc[i] } }
                } }
            }
            if (aiJob == coroutineContext[Job]) aiBusy = false
            r.onSuccess { if (text.trim() == q) { aiScores = it; aiScoresFor = q; aiShowMore = false; memo = null } }
                .onFailure { e ->
                    if (e is CancellationException) return@onFailure // superata da una ricerca più nuova: non è un errore
                    android.util.Log.w("VcsAi", "ricerca non riuscita", e)
                    say("error", L.t("Ricerca IA non riuscita: ", "AI search failed: ") + (e.message ?: e.javaClass.simpleName))
                }
        }
    }

    /**
     * La frase data al modello: questi modelli rendono meglio con "una foto di …" che con la parola nuda (provato sulle
     * foto vere: «insetti» da 6 a 8 insetti veri nei primi 10). Se la frase parla già di foto si lascia com'è.
     */
    private fun aiPhrase(q: String): String {
        val l = q.lowercase()
        val already = listOf("foto", "una foto", "fotografia", "immagine", "photo", "a photo", "picture").any { l.startsWith(it) }
        // dopo un nome tolto la frase può cominciare con una preposizione («al mare»): «una foto al mare»
        val first = l.substringBefore(' ')
        return when {
            already -> q
            first in PREPOSITIONS -> "una foto $q"
            else -> "una foto di $q"
        }
    }

    /**
     * Il nome di una persona della libreria contenuto nella frase (parola intera, maiuscole indifferenti) e il resto
     * della frase senza il nome e senza congiunzioni all'inizio o alla fine («Marta e il cane» → «il cane»).
     */
    private fun splitPerson(idx: LibIndex, q: String): Pair<String?, String> {
        for (name in idx.people) {
            val re = Regex("(?iU)(^|\\W)" + Regex.escape(name) + "(\\W|$)")
            val m = re.find(q) ?: continue
            var rest = (q.substring(0, m.range.first) + " " + q.substring(m.range.last + 1)).trim().replace(Regex("\\s+"), " ")
            val joins = setOf("e", "ed", "con", "and", "with", "&", ",")
            var words = rest.split(' ').filter { it.isNotEmpty() }
            while (words.isNotEmpty() && words.first().lowercase() in joins) words = words.drop(1)
            while (words.isNotEmpty() && words.last().lowercase() in joins) words = words.dropLast(1)
            rest = words.joinToString(" ")
            return name to rest
        }
        return null to q
    }

    /** La descrizione della foto scritta dal PC (motore IA), o null. */
    fun aiDescription(id: Int): String? {
        val idx = index ?: return null
        return AiEngine.descriptions(AiStore.descriptionsFile(getApplication(), idx.library))?.text?.get(id)
            ?.substringBefore("Parole chiave:")?.trim()
    }

    /** Quante foto ha trovato la ricerca per soggetto in corso (dopo soglia e doppioni), o null se non attiva. */
    var aiFound by mutableStateOf<Int?>(null); private set
    /** Quante altre, meno somiglianti, si vedrebbero con "Mostra anche le meno somiglianti". */
    var aiMore by mutableStateOf(0); private set
    /** Mostrare anche le meno somiglianti (soglia più bassa) per la ricerca in corso. */
    var aiShowMore by mutableStateOf(false)

    /** Registro delle ricerche (frase, tempi, quanti risultati, i primi): per capire cosa migliorare. */
    private fun logSearch(line: String) {
        runCatching {
            val dir = getApplication<Application>().getExternalFilesDir(null) ?: return
            val f = java.io.File(dir, "ricerche-ia.log")
            if (f.length() > 2_000_000) f.delete()
            f.appendText("${java.time.LocalDateTime.now().withNano(0)} $line\n")
        }
    }

    /** Scarica il modello "testo" dal NAS (una volta; riprende se interrotto). */
    fun downloadAiModel() {
        val idx = index ?: return
        val m = aiManifest ?: return
        if (aiDownload != null || !configured) return
        val cfg = settings.nas
        viewModelScope.launch {
            aiDownload = 0L to AiStore.modelSize(m)
            val r = runCatching {
                AiStore.downloadModel(getApplication(), cfg, share, libRoot, idx.library, m) { d, t -> aiDownload = d to t }
            }
            aiDownload = null
            r.onSuccess {
                aiModelReady = AiStore.modelReady(getApplication(), m)
                warmUpAi()
                say("ok", L.t("Modello IA scaricato: ora puoi cercare per soggetto.", "AI model downloaded: you can now search by subject."))
                requestAi()
            }.onFailure { e ->
                say("warn", L.t("Download del modello interrotto: riprova, riparte da dove era arrivato. ", "Model download interrupted: try again, it resumes where it stopped. ") +
                    (e.message ?: ""))
            }
        }
    }

    fun deleteAiModel() {
        AiStore.deleteModels(getApplication())
        aiModelReady = false
        aiScores = null
        aiScoresFor = ""
        memo = null
    }

    /** Modifiche fatte sul telefono non ancora confermate dalla libreria di casa (id → modifica). */
    var edits by mutableStateOf<Map<Int, LibEdit>>(emptyMap()); private set

    /** La foto come la vede il telefono: con le modifiche in attesa sopra. */
    fun effective(p: LibPhoto) = edits[p.id]?.applyTo(p) ?: p

    private var sendJob: Job? = null

    /** Applica una modifica alle foto indicate: subito sul telefono, poi (dopo qualche secondo) sul NAS. */
    fun edit(ids: Collection<Int>, change: (LibEdit) -> LibEdit) {
        val idx = index ?: return
        val now = System.currentTimeMillis()
        val next = edits.toMutableMap()
        ids.forEach { id -> next[id] = change(next[id] ?: LibEdit()).copy(at = now, sent = 0) }
        // una modifica che riporta la foto com'è a casa non serve più
        ids.forEach { id -> val e = next[id]; val p = idx.byId[id]; if (e != null && p != null && isNoop(e, p)) next.remove(id) }
        edits = next
        memo = null
        LibraryEdits.save(getApplication(), idx.library, next)
        sendJob?.cancel()
        sendJob = viewModelScope.launch { delay(3000); sendPending() }
    }

    private fun isNoop(e: LibEdit, p: LibPhoto) = e.settledIn(p, index?.stackOf?.get(p.id))

    fun setRating(ids: Collection<Int>, r: Int) = edit(ids) { it.copy(rating = r) }
    fun setFlag(ids: Collection<Int>, f: String) = edit(ids) { it.copy(flag = f) }
    fun setLabel(ids: Collection<Int>, l: String) = edit(ids) { it.copy(label = l) }
    /** Nel cestino di LightCraft: la foto sparisce subito dal telefono, dalla libreria quando il PC la applica. */
    fun trash(ids: Collection<Int>) {
        edit(ids) { it.copy(trash = true) }
        clearSelection()
        say("info", L.t("${ids.size} foto nel cestino di LightCraft (Eliminate di recente) appena il PC di casa applica la richiesta. I file restano sul NAS: li cancelli con CleanCraftNas.",
            "${ids.size} photos go to LightCraft's trash (Recently Deleted) once the home PC applies the request. The files stay on the NAS: delete them with CleanCraftNas."))
    }

    /** Ripensamento: se la richiesta non è ancora partita si toglie, altrimenti si chiede di ripristinare. */
    fun untrash(id: Int) {
        val e = edits[id] ?: return
        if (e.sent == 0L) edit(listOf(id)) { it.copy(trash = null) } else edit(listOf(id)) { it.copy(trash = false) }
    }

    /** Una pila delle foto selezionate: copertina la prima selezionata; quelle già in una pila portano con sé la loro. */
    fun stack(ids: Collection<Int>) {
        if (ids.size < 2) return
        val top = ids.first()
        edit(ids) { it.copy(stack = top) }
        clearSelection()
        expanded = expanded + (-top)
        say("info", L.t("Pila di ${ids.size} foto: entra in LightCraft quando il PC di casa applica la richiesta.",
            "Stack of ${ids.size} photos: it enters LightCraft when the home PC applies the request."))
    }

    /** Le pile come le vede il telefono: quelle di casa più quelle create qui e non ancora in LightCraft. */
    private fun stackMap(idx: LibIndex): Map<Int, LibStack> {
        val groups = edits.entries.filter { it.value.stack != null && it.key in idx.byId }.groupBy({ it.value.stack!! }, { it.key })
        if (groups.isEmpty()) return idx.stackOf
        val out = idx.stackOf.toMutableMap()
        for ((top, members) in groups) {
            if (top !in idx.byId) continue
            val photos = LinkedHashSet<Int>()
            for (m in listOf(top) + members.filter { it != top }) { photos += m; out[m]?.photos?.let { photos += it } }
            if (photos.size < 2) continue
            val s = LibStack(-top, photos.toList(), collapsed = true)
            photos.forEach { out[it] = s }
        }
        return out
    }

    private var stacksNow: Map<Int, LibStack> = emptyMap()

    /** La pila della foto (comprese quelle create sul telefono). */
    fun stackOf(id: Int): LibStack? { cells(); return stacksNow[id] }

    /** Nome a un volto trovato da LightCraft ("" = toglie il nome): arriva in LightCraft come le altre modifiche. */
    fun setFaceName(id: Int, face: LibFace, name: String) =
        edit(listOf(id)) { it.copy(faces = (it.faces ?: emptyMap()) + (face.key to name.trim())) }

    fun setTexts(id: Int, title: String, caption: String, keywords: List<String>) =
        edit(listOf(id)) { it.copy(title = title, caption = caption, keywords = keywords) }

    /** Lascia sul NAS le modifiche non ancora inviate. */
    fun sendPending() {
        val idx = index ?: return
        val unsent = edits.filter { it.value.sent == 0L }
        if (unsent.isEmpty() || !configured) return
        val cfg = settings.nas
        viewModelScope.launch {
            val r = runCatching { LibraryEdits.send(cfg, share, libRoot, idx.library, unsent) }
            r.onSuccess {
                val now = System.currentTimeMillis()
                edits = edits.mapValues { (id, e) -> if (id in unsent && e.at == unsent.getValue(id).at) e.copy(sent = now) else e }
                LibraryEdits.save(getApplication(), idx.library, edits)
                say("ok", L.t("Modifiche inviate: entrano in LightCraft quando PresetCraftAppLicator le applica (PC acceso, LightCraft chiuso).",
                    "Changes sent: they enter LightCraft when PresetCraftAppLicator applies them (PC on, LightCraft closed)."))
            }.onFailure {
                say("warn", L.t("Modifiche salvate sul telefono: partono appena il NAS è raggiungibile.",
                    "Changes kept on the phone: they will be sent as soon as the NAS can be reached."))
            }
        }
    }

    /** Selezione multipla (id delle foto): si entra con la pressione lunga o con «Seleziona» nelle opzioni. */
    var selected by mutableStateOf(setOf<Int>())
    var selectMode by mutableStateOf(false)
    val selecting: Boolean get() = selectMode || selected.isNotEmpty()

    fun toggleSelect(id: Int) {
        selected = if (id in selected) selected - id else selected + id
    }

    fun selectAll() {
        selected = cells().map { it.photo.id }.toSet()
    }

    fun clearSelection() {
        selected = emptySet()
        selectMode = false
    }

    /** Scarica sul telefono le foto indicate (originale o JPEG), in background. */
    fun download(ids: Collection<Int>, jpeg: Boolean) {
        val lib = index?.library ?: return
        DownloadService.start(getApplication(), lib, ids, jpeg)
        say("info", L.t("Download di ${ids.size} foto avviato: segui la notifica. Finiscono in ${DownloadService.FOLDER.trimEnd('/')}.",
            "Downloading ${ids.size} photos: see the notification. They go to ${DownloadService.FOLDER.trimEnd('/')}."))
        clearSelection()
    }

    /** Pile aperte (id). All'apertura di una libreria: quelle aperte anche in LightCraft. */
    var expanded by mutableStateOf(setOf<Int>())

    val filtering: Boolean
        get() = stars != StarFilter.ANY || flag.isNotEmpty() || labels.isNotEmpty() || text.isNotBlank() ||
            camera.isNotEmpty() || dateFrom != null || dateTo != null || fileType.isNotEmpty()

    /** Quanti filtri sono attivi tra quelli che stanno solo nel pannello "Tutti i filtri". */
    val advancedCount: Int
        get() = listOf(dateFrom != null || dateTo != null, labels.isNotEmpty(), camera.isNotEmpty(), flag.isNotEmpty(),
            fileType.isNotEmpty()).count { it }

    fun clearFilters() {
        stars = StarFilter.ANY; flag = ""; labels = emptySet(); text = ""; camera = ""; dateFrom = null; dateTo = null
        fileType = ""
    }

    val cameras: List<String> get() = index?.photos?.map { it.camera }?.filter { it.isNotEmpty() }?.distinct()?.sorted() ?: emptyList()

    /** Il NAS e la cartella delle librerie sono impostati. */
    val configured: Boolean get() = settings.nas.complete && settings.libFolder.isNotEmpty()

    private val share: String get() = settings.libShare.ifEmpty { settings.nas.share }

    /** La cartella delle librerie sul NAS (trovata all'ultimo aggiornamento; prima, quella delle versioni recenti). */
    private var rootFound: String? = null
    val libRoot: String get() = rootFound ?: LibraryStore.defaultRoot(settings.libFolder)
    val libShare: String get() = share

    /** Foto con l'anteprima sviluppata da PresetCraftAppLicator (ritoccate in LightCraft): id → firma. */
    var developed by mutableStateOf<Map<Int, String>>(emptyMap()); private set

    /** Come si vede la galleria (ricordato nelle impostazioni del telefono). */
    var gridMode by mutableStateOf(settings.libGrid); private set
    var gridSize by mutableStateOf(settings.libSize); private set
    var segment by mutableStateOf(settings.libSegment); private set
    var segmentBy by mutableStateOf(settings.libSegmentBy); private set
    fun setGrid(mode: String) { gridMode = mode; settings.libGrid = mode }
    fun setSize(size: Int) { gridSize = size.coerceIn(1, 5); settings.libSize = gridSize }
    fun setSegmented(on: Boolean) { segment = on; settings.libSegment = on }
    fun setSegmentKind(kind: String) { segmentBy = kind; settings.libSegmentBy = kind }

    /** L'ultima foto vista nel visualizzatore (evidenziata nella griglia) e quando lo si è chiuso. */
    var lastViewed by mutableStateOf<Int?>(null)
    var lastViewedAt by mutableStateOf(0L)

    /** Nel visualizzatore: l'anteprima della fotocamera (com'era allo scatto) invece di quella sviluppata. */
    var showOriginal by mutableStateOf(false)

    init {
        if (current.isNotEmpty()) openCached(current)
    }

    private fun openCached(name: String) {
        viewModelScope.launch {
            val idx = withContext(Dispatchers.IO) { LibraryStore.load(getApplication(), name) }
            if (idx != null && current == name) show(idx)
        }
    }

    private fun show(idx: LibIndex) {
        val sameLibrary = index?.library == idx.library
        index = idx
        developed = LibraryStore.previews(getApplication(), idx.library)
        if (!sameLibrary) loadAiLocal(idx.library)
        if (!sameLibrary) expanded = idx.stacks.filter { !it.collapsed }.map { it.id }.toSet()
        // le modifiche che la libreria di casa ormai contiene sono arrivate: si tolgono
        val stored = LibraryEdits.load(getApplication(), idx.library)
        val kept = stored.filter { (id, e) -> idx.byId[id]?.let { !e.settledIn(it, idx.stackOf[id]) } ?: false }
        if (kept.size != stored.size) LibraryEdits.save(getApplication(), idx.library, kept)
        edits = kept
        memo = null
    }

    /**
     * Aggiorna l'elenco delle librerie sul NAS e (se cambiato) l'indice di quella scelta. `background`: controllo
     * automatico mentre la Libreria è aperta (si scarica solo ciò che è cambiato; nessun avviso se il NAS non risponde).
     */
    fun refresh(quiet: Boolean = true, background: Boolean = false) {
        if (!configured || loading) return
        val cfg = settings.nas
        viewModelScope.launch {
            loading = true
            val result = runCatching {
                val (root, names) = LibraryStore.list(cfg, share, settings.libFolder)
                rootFound = root
                libraries = names
                val name = current.takeIf { it in names } ?: names.firstOrNull() ?: ""
                if (name.isNotEmpty()) {
                    val fresh = LibraryStore.download(getApplication(), cfg, share, libRoot, name)
                    val idx = withContext(Dispatchers.IO) { LibraryStore.load(getApplication(), name) }
                    if (fresh && idx != null && index?.library == idx.library) developed = LibraryStore.previews(getApplication(), name)
                    if (current != name) { current = name; settings.library = name; index = null; clearFilters() }
                    if (idx != null && (index == null || index?.updated != idx.updated || index?.library != idx.library)) show(idx)
                    syncAi(name)
                }
                names
            }
            loading = false
            result.onSuccess { names ->
                if (names.isEmpty() && !background) say("warn", L.t("Nessuna libreria nella cartella del NAS: in PresetCraftAppLicator, Importazione automatica › Librerie da consultare sul telefono.",
                    "No library in the NAS folder: in PresetCraftAppLicator, Automatic import › Libraries to browse on the phone."))
                else if (!quiet) say("ok", L.t("Libreria aggiornata.", "Library updated."))
                sendPending()
            }.onFailure { e ->
                if (background) return@onFailure
                if (index != null) say("warn", L.t("NAS non raggiungibile: mostro l'ultima copia della libreria sul telefono.",
                    "NAS not reachable: showing the last copy of the library on the phone."))
                else say("error", L.t("NAS non raggiungibile: ", "NAS not reachable: ") + (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    fun choose(name: String) {
        if (name == current && index != null) return
        current = name
        settings.library = name
        index = null
        clearFilters()
        clearSelection()
        openCached(name)
        refresh()
    }

    private fun say(level: String, text: String) {
        messageLevel = level
        message = text
        Notices.post(level, text)
    }

    // ---------------------------------------------------------------- celle della griglia

    /** Una cella: la foto, la sua pila (se ne ha una), se è la copertina e se la pila è aperta. */
    data class Cell(val photo: LibPhoto, val stack: LibStack?, val cover: Boolean, val open: Boolean)

    private var memo: Pair<List<Any?>, List<Cell>>? = null

    private fun matches(p: LibPhoto, useText: Boolean = true): Boolean {
        if (stars != StarFilter.ANY) {
            if (stars.exact && p.rating != stars.min) return false
            if (!stars.exact && p.rating < stars.min) return false
        }
        if (flag.isNotEmpty() && p.flag != flag) return false
        if (labels.isNotEmpty() && p.label.lowercase() !in labels) return false
        if (camera.isNotEmpty() && p.camera != camera) return false
        if (fileType.isNotEmpty() && fileTypeOf(p.name) != fileType) return false
        if (dateFrom != null || dateTo != null) {
            val d = runCatching { LocalDate.parse(p.captured.take(10)) }.getOrNull() ?: return false
            if (dateFrom != null && d < dateFrom) return false
            if (dateTo != null && d > dateTo) return false
        }
        val q = if (useText) text.trim().lowercase() else ""
        if (q.isNotEmpty() && listOf(p.name, p.title, p.caption, p.copyName).none { it.lowercase().contains(q) } &&
            p.keywords.none { it.lowercase().contains(q) } && p.people.none { it.lowercase().contains(q) } &&
            aiDescription(p.id)?.lowercase()?.contains(q) != true) return false
        return true
    }

    /**
     * Le celle in ordine di scatto (dalla più recente). Senza filtri le pile si mostrano come in LightCraft: la copertina
     * con il numero di foto, e se la pila è aperta le altre subito dopo. Con un filtro si vedono le foto che corrispondono.
     */
    fun cells(): List<Cell> {
        val idx = index ?: return emptyList()
        val key = listOf(idx, stars, flag, labels, text, camera, dateFrom, dateTo, fileType, expanded, edits, aiActive, aiScores,
            aiScoresFor, aiShowMore)
        memo?.let { (k, v) -> if (k == key) return v }
        val stackOf = stackMap(idx)
        stacksNow = stackOf
        val ordered = idx.photos.map { effective(it) }
            .sortedWith(compareByDescending<LibPhoto> { it.captured }.thenByDescending { it.id })
        val out = ArrayList<Cell>()
        aiFound = null
        val scores = aiScores
        if (aiActive && aiScoresFor != text.trim()) {
            // la frase scritta non è ancora stata cercata: niente risultati di una ricerca precedente
        } else if (aiActive && scores != null) {
            // per soggetto: le più somiglianti prima; dello stesso scatto in due formati (DNG + JPG) solo una. Solo quelle
            // vicine alla migliore e sopra una soglia minima: se il soggetto non c'è, nessun risultato (non 150 a caso)
            val seen = HashSet<String>()
            val candidates = ordered.filter { edits[it.id]?.trash != true && it.id in scores && matches(it, useText = false) }
            // soglia che si adatta alla ricerca: fissa, ma mai troppo sotto le 5 foto migliori (le ricerche con persone
            // hanno punteggi più alti per tutte le foto con persone: senza, «donna con il cappello» darebbe 600 foto)
            val top5 = aiTop5
            val normal = maxOf(minAiScore(), top5 - AI_BAND)
            val more = maxOf(minAiScoreMore(), top5 - AI_BAND_MORE)
            val cutoff = if (aiShowMore) more else normal
            aiMore = if (aiShowMore) 0 else candidates.count { scores.getValue(it.id) in more..<normal }
            candidates.asSequence()
                .filter { scores.getValue(it.id) >= cutoff }
                .sortedByDescending { scores.getValue(it.id) }
                .filter { p ->
                    // tutte le foto che corrispondono, anche della stessa pila (una pila è spesso una serie dello stesso
                    // soggetto: la Giulia, la piscina); si toglie solo lo stesso scatto in due formati (DNG + JPG)
                    seen.add(p.name.substringBeforeLast('.').lowercase() + "|" + p.copyName)
                }
                .take(AI_RESULTS)
                .forEach { out += Cell(it, stackOf[it.id], cover = false, open = false) }
            aiFound = out.size
        } else if (filtering) {
            for (p in ordered) if (edits[p.id]?.trash != true && matches(p, useText = !aiActive)) out += Cell(p, stackOf[p.id], cover = false, open = false)
        } else {
            for (p in ordered) {
                if (edits[p.id]?.trash == true) continue // nel cestino (in attesa del PC)
                val s = stackOf[p.id]
                if (s == null) { out += Cell(p, null, cover = false, open = false); continue }
                if (s.photos.first() != p.id) continue // le altre foto della pila stanno dopo la copertina
                val open = s.id in expanded
                out += Cell(p, s, cover = true, open = open)
                if (open) s.photos.drop(1).mapNotNull { idx.byId[it] }.filter { edits[it.id]?.trash != true }
                    .forEach { out += Cell(effective(it), s, cover = false, open = true) }
            }
        }
        memo = key to out
        return out
    }

    /**
     * Punteggio minimo per essere tra i risultati, misurato sulle foto vere con MetaCLIP 2 giant e "una foto di …":
     * piscina, donna con il cappello, motorino rosso, auto d'epoca hanno le foto giuste fin verso 0,19-0,20; sotto
     * prevalgono quelle che non c'entrano. "Mostra anche le meno somiglianti" scende a 0,175.
     */
    private fun minAiScore(): Float = if (aiManifest?.optString("model") == "metaclip2-giant") AI_MIN else 0.15f
    private fun minAiScoreMore(): Float = if (aiManifest?.optString("model") == "metaclip2-giant") AI_MIN_MORE else 0.13f

    companion object {
        private val RAW = setOf("dng", "cr2", "cr3", "crw", "nef", "nrw", "arw", "srf", "orf", "rw2", "raf", "pef", "srw", "x3f", "3fr", "iiq")

        /** "raw", "jpeg", "tiff" o "altro" dall'estensione del file. */
        fun fileTypeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            in RAW -> "raw"
            "jpg", "jpeg" -> "jpeg"
            "tif", "tiff", "psd" -> "tiff"
            else -> "altro"
        }

        /** Al massimo quanti risultati mostra la ricerca per soggetto. */
        const val AI_RESULTS = 600
        /** Quanto sale una foto con tutte le parole cercate nella descrizione (provato sulle foto vere: 0,04). */
        const val AI_DESC_BONUS = 0.04f
        /** Pausa di scrittura prima di cercare (con il modello veloce una ricerca costa ~0,1 s). */
        const val AI_PAUSE_MS = 350L
        /** Preposizioni con cui può cominciare il resto della frase dopo un nome. */
        val PREPOSITIONS = setOf("a", "al", "allo", "alla", "ai", "agli", "alle", "in", "nel", "nella", "nei", "sul",
            "sulla", "sui", "da", "dal", "dalla", "su", "per", "tra", "fra", "at", "in", "on", "with")
        /** Ogni quanto, con la Libreria aperta, si guarda se sul NAS è cambiato qualcosa (pochi byte se niente). */
        const val AUTO_REFRESH_MS = 60_000L
        /** Lettere minime per cercare mentre si scrive ("ga", "t" danno risultati a caso). */
        const val AI_MIN_CHARS = 3
        const val AI_MIN = 0.19f
        const val AI_MIN_MORE = 0.175f
        /** Quanto sotto la media delle 5 migliori si può stare (e con "meno somiglianti"). */
        const val AI_BAND = 0.05f
        const val AI_BAND_MORE = 0.065f
    }

    fun toggle(stack: LibStack) {
        expanded = if (stack.id in expanded) expanded - stack.id else expanded + stack.id
        toggledStack = stack.id
        toggledAt = System.currentTimeMillis()
    }

    /** L'ultima pila aperta o chiusa, e quando: le sue foto entrano in scena con un'animazione. */
    var toggledStack by mutableStateOf<Int?>(null); private set
    var toggledAt by mutableStateOf(0L); private set
}
