package io.github.poty87.viewercraftstar

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
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
        index = idx
        expanded = idx.stacks.filter { !it.collapsed }.map { it.id }.toSet()
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
        val key = listOf(idx, stars, flag, labels, text, camera, dateFrom, dateTo, expanded)
        memo?.let { (k, v) -> if (k == key) return v }
        val ordered = idx.photos.sortedWith(compareByDescending<LibPhoto> { it.captured }.thenByDescending { it.id })
        val out = ArrayList<Cell>()
        if (filtering) {
            for (p in ordered) if (matches(p)) out += Cell(p, idx.stackOf[p.id], cover = false, open = false)
        } else {
            for (p in ordered) {
                val s = idx.stackOf[p.id]
                if (s == null) { out += Cell(p, null, cover = false, open = false); continue }
                if (s.photos.first() != p.id) continue // le altre foto della pila stanno dopo la copertina
                val open = s.id in expanded
                out += Cell(p, s, cover = true, open = open)
                if (open) s.photos.drop(1).mapNotNull { idx.byId[it] }.forEach { out += Cell(it, s, cover = false, open = true) }
            }
        }
        memo = key to out
        return out
    }

    fun toggle(stack: LibStack) {
        expanded = if (stack.id in expanded) expanded - stack.id else expanded + stack.id
    }
}
