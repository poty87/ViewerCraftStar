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
import kotlinx.coroutines.withContext
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
    var camera by mutableStateOf("")
    var dateFrom by mutableStateOf<LocalDate?>(null)
    var dateTo by mutableStateOf<LocalDate?>(null)

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

    fun setTexts(id: Int, title: String, caption: String, keywords: List<String>) =
        edit(listOf(id)) { it.copy(title = title, caption = caption, keywords = keywords) }

    /** Lascia sul NAS le modifiche non ancora inviate. */
    fun sendPending() {
        val idx = index ?: return
        val unsent = edits.filter { it.value.sent == 0L }
        if (unsent.isEmpty() || !configured) return
        val cfg = settings.nas
        viewModelScope.launch {
            val r = runCatching { LibraryEdits.send(cfg, share, settings.libFolder, idx.library, unsent) }
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

    /** Selezione multipla (id delle foto): si entra con la pressione lunga. */
    var selected by mutableStateOf(setOf<Int>())
    val selecting: Boolean get() = selected.isNotEmpty()

    fun toggleSelect(id: Int) {
        selected = if (id in selected) selected - id else selected + id
    }

    fun selectAll() {
        selected = cells().map { it.photo.id }.toSet()
    }

    fun clearSelection() {
        selected = emptySet()
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
            camera.isNotEmpty() || dateFrom != null || dateTo != null

    fun clearFilters() {
        stars = StarFilter.ANY; flag = ""; labels = emptySet(); text = ""; camera = ""; dateFrom = null; dateTo = null
    }

    val cameras: List<String> get() = index?.photos?.map { it.camera }?.filter { it.isNotEmpty() }?.distinct()?.sorted() ?: emptyList()

    /** Il NAS e la cartella delle librerie sono impostati. */
    val configured: Boolean get() = settings.nas.complete && settings.libFolder.isNotEmpty()

    private val share: String get() = settings.libShare.ifEmpty { settings.nas.share }

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
        if (!sameLibrary) expanded = idx.stacks.filter { !it.collapsed }.map { it.id }.toSet()
        // le modifiche che la libreria di casa ormai contiene sono arrivate: si tolgono
        val stored = LibraryEdits.load(getApplication(), idx.library)
        val kept = stored.filter { (id, e) -> idx.byId[id]?.let { !e.settledIn(it, idx.stackOf[id]) } ?: false }
        if (kept.size != stored.size) LibraryEdits.save(getApplication(), idx.library, kept)
        edits = kept
        memo = null
    }

    /** Aggiorna l'elenco delle librerie sul NAS e (se cambiato) l'indice di quella scelta. */
    fun refresh(quiet: Boolean = true) {
        if (!configured || loading) return
        val cfg = settings.nas
        viewModelScope.launch {
            loading = true
            val result = runCatching {
                val names = LibraryStore.list(cfg, share, settings.libFolder)
                libraries = names
                val name = current.takeIf { it in names } ?: names.firstOrNull() ?: ""
                if (name.isNotEmpty()) {
                    LibraryStore.download(getApplication(), cfg, share, settings.libFolder, name)
                    val idx = withContext(Dispatchers.IO) { LibraryStore.load(getApplication(), name) }
                    if (current != name) { current = name; settings.library = name; index = null; clearFilters() }
                    if (idx != null && (index == null || index?.updated != idx.updated || index?.library != idx.library)) show(idx)
                }
                names
            }
            loading = false
            result.onSuccess { names ->
                if (names.isEmpty()) say("warn", L.t("Nessuna libreria nella cartella del NAS: in PresetCraftAppLicator, Importazione automatica › Librerie da consultare sul telefono.",
                    "No library in the NAS folder: in PresetCraftAppLicator, Automatic import › Libraries to browse on the phone."))
                else if (!quiet) say("ok", L.t("Libreria aggiornata.", "Library updated."))
                sendPending()
            }.onFailure { e ->
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
    }

    // ---------------------------------------------------------------- celle della griglia

    /** Una cella: la foto, la sua pila (se ne ha una), se è la copertina e se la pila è aperta. */
    data class Cell(val photo: LibPhoto, val stack: LibStack?, val cover: Boolean, val open: Boolean)

    private var memo: Pair<List<Any?>, List<Cell>>? = null

    private fun matches(p: LibPhoto): Boolean {
        if (stars != StarFilter.ANY) {
            if (stars.exact && p.rating != stars.min) return false
            if (!stars.exact && p.rating < stars.min) return false
        }
        if (flag.isNotEmpty() && p.flag != flag) return false
        if (labels.isNotEmpty() && p.label.lowercase() !in labels) return false
        if (camera.isNotEmpty() && p.camera != camera) return false
        if (dateFrom != null || dateTo != null) {
            val d = runCatching { LocalDate.parse(p.captured.take(10)) }.getOrNull() ?: return false
            if (dateFrom != null && d < dateFrom) return false
            if (dateTo != null && d > dateTo) return false
        }
        val q = text.trim().lowercase()
        if (q.isNotEmpty() && listOf(p.name, p.title, p.caption, p.copyName).none { it.lowercase().contains(q) } &&
            p.keywords.none { it.lowercase().contains(q) }) return false
        return true
    }

    /**
     * Le celle in ordine di scatto (dalla più recente). Senza filtri le pile si mostrano come in LightCraft: la copertina
     * con il numero di foto, e se la pila è aperta le altre subito dopo. Con un filtro si vedono le foto che corrispondono.
     */
    fun cells(): List<Cell> {
        val idx = index ?: return emptyList()
        val key = listOf(idx, stars, flag, labels, text, camera, dateFrom, dateTo, expanded, edits)
        memo?.let { (k, v) -> if (k == key) return v }
        val stackOf = stackMap(idx)
        stacksNow = stackOf
        val ordered = idx.photos.map { effective(it) }
            .sortedWith(compareByDescending<LibPhoto> { it.captured }.thenByDescending { it.id })
        val out = ArrayList<Cell>()
        if (filtering) {
            for (p in ordered) if (edits[p.id]?.trash != true && matches(p)) out += Cell(p, stackOf[p.id], cover = false, open = false)
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

    fun toggle(stack: LibStack) {
        expanded = if (stack.id in expanded) expanded - stack.id else expanded + stack.id
    }
}
