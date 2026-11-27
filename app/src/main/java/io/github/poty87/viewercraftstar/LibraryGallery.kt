package io.github.poty87.viewercraftstar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Spazio tra le foto della galleria. */
private val GAP = 3.dp

/** Una riga della galleria: le celle e la larghezza di ciascuna (l'altezza è quella della riga). */
private class GalleryRow(val cells: List<Pair<Int, LibraryViewModel.Cell>>, val widths: List<Dp>, val height: Dp)

/** Un periodo della galleria (anno, mese o giorno) con le sue righe; senza segmenti uno solo, senza titolo. */
private class Segment(val key: String, val title: String, val count: Int, val rows: List<GalleryRow>)

private fun ratioOf(p: LibPhoto) = if (p.width > 0 && p.height > 0) p.width.toFloat() / p.height else 1.5f

/** Righe giustificate: tante foto quante ne stanno all'altezza voluta, poi la riga si allarga a tutta la larghezza. */
private fun justifiedRows(cells: List<Pair<Int, LibraryViewModel.Cell>>, width: Dp, target: Dp): List<GalleryRow> {
    val rows = ArrayList<GalleryRow>()
    var i = 0
    while (i < cells.size) {
        val row = ArrayList<Pair<Int, LibraryViewModel.Cell>>()
        var sum = 0f
        var broke = false // la riga è finita perché piena (non perché le foto sono finite): va stirata alla larghezza
        while (i < cells.size) {
            row += cells[i]
            sum += ratioOf(cells[i].second.photo)
            i++
            val w = target.value * sum + GAP.value * (row.size - 1)
            if (w >= width.value) {
                // la foto che fa sforare: si tiene solo se la riga resta più vicina all'altezza scelta (Dimensione, menu
                // ⋮) che lasciandola alla riga dopo; senza questo ogni riga piena si rimpiccioliva sotto la misura scelta
                // (molto visibile nelle pile aperte, tante foto verticali, accanto alla copertina chiusa a misura piena)
                if (row.size > 1) {
                    val r = ratioOf(cells[i - 1].second.photo)
                    val hWith = (width.value - GAP.value * (row.size - 1)) / sum
                    val hWithout = (width.value - GAP.value * (row.size - 2)) / (sum - r)
                    if (kotlin.math.abs(hWithout - target.value) < kotlin.math.abs(hWith - target.value)) {
                        row.removeAt(row.size - 1); sum -= r; i--
                    }
                }
                broke = true
                break
            }
        }
        val gaps = GAP.value * (row.size - 1)
        val full = broke || target.value * sum + gaps >= width.value
        // l'ultima riga, se corta, resta all'altezza voluta (non si stira)
        val h = if (full) (width.value - gaps) / sum else target.value
        rows += GalleryRow(row, row.map { (h * ratioOf(it.second.photo)).dp }, h.dp)
    }
    return rows
}

/** Righe di celle quadrate, `columns` per riga. */
private fun squareRows(cells: List<Pair<Int, LibraryViewModel.Cell>>, width: Dp, columns: Int): List<GalleryRow> {
    val side = ((width.value - GAP.value * (columns - 1)) / columns).dp
    return cells.chunked(columns).map { row -> GalleryRow(row, row.map { side }, side) }
}

private fun segmentKey(iso: String, by: String): String = when (by) {
    "year" -> iso.take(4)
    "day" -> iso.take(10)
    else -> iso.take(7)
}

private fun segmentTitle(key: String, by: String): String = runCatching {
    val loc = L.locale
    when (by) {
        "year" -> key
        "day" -> LocalDate.parse(key).format(DateTimeFormatter.ofPattern(L.t("EEEE d MMMM yyyy", "EEEE, MMMM d, yyyy"), loc))
            .replaceFirstChar { it.titlecase(loc) }
        else -> LocalDate.parse("$key-01").format(DateTimeFormatter.ofPattern(L.t("yyyy MMMM", "MMMM yyyy"), loc))
    }
}.getOrDefault(key.ifEmpty { L.t("Senza data", "No date") })

/**
 * La galleria della Libreria: intestazione (che scorre via con le foto), poi le foto in righe giustificate o quadrate,
 * divise per anno, mese o giorno con il titolo del periodo che resta in alto, in trasparenza, mentre si scorre.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LibraryGallery(lib: LibraryViewModel, state: LazyListState, header: @Composable () -> Unit,
                   onOpen: (List<LibPhoto>, Int) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)) {
        val width = maxWidth - 6.dp
        // una foto compare una volta sola (le righe hanno per chiave la loro prima foto)
        // all'apertura, a ogni ritorno nell'app e poi ogni minuto finché la Libreria resta in primo piano: le novità del
        // PC (indice, anteprime sviluppate, ricerca IA) arrivano senza premere il tasto di aggiornamento. Sta qui e non
        // nell'intestazione: quella è un elemento della lista, esce e rientra scorrendo, e l'aggiornamento ripartiva
        // (dal NAS, a metà dello scorrimento) a ogni ritorno in cima
        val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
        LaunchedEffect(owner) {
            owner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                lib.refresh()
                while (true) {
                    kotlinx.coroutines.delay(LibraryViewModel.AUTO_REFRESH_MS)
                    lib.refresh(background = true)
                }
            }
        }
        // cells() rende sempre la stessa lista finché niente cambia: così anche le righe si ricalcolano solo allora
        val all = if (lib.configured && lib.index != null) lib.cells() else emptyList()
        val cells = remember(all) { all.distinctBy { it.photo.id } }
        val photos = remember(cells) { cells.map { it.photo } }
        // la ricerca per soggetto ordina per somiglianza: lì niente periodi, conta l'ordine
        val bySegment = lib.segment && !lib.aiActive
        val segments = remember(cells, width, lib.gridMode, lib.gridSize, bySegment, lib.segmentBy) {
            val indexed = cells.mapIndexed { i, c -> i to c }
            // una pila aperta resta tutta nel periodo della sua copertina, anche le foto scattate in un altro mese
            // (la serata di Capodanno è a cavallo tra dicembre e gennaio: senza, metà pila finiva in un'altra sezione)
            val coverDate = cells.filter { it.cover && it.stack != null }.associate { it.stack!!.id to it.photo.captured }
            fun dateOf(c: LibraryViewModel.Cell) =
                if (c.open && !c.cover && c.stack != null) coverDate[c.stack.id] ?: c.photo.captured else c.photo.captured
            val groups = if (bySegment) indexed.groupBy { segmentKey(dateOf(it.second), lib.segmentBy) }.toList()
            else listOf("" to indexed)
            groups.map { (key, list) ->
                val rows = if (lib.gridMode == "square") squareRows(list, width, 8 - lib.gridSize)
                else justifiedRows(list, width, listOf(64, 84, 110, 145, 200)[lib.gridSize - 1].dp)
                Segment(key, if (bySegment) segmentTitle(key, lib.segmentBy) else "", list.size, rows)
            }
        }
        // tornando dal visualizzatore: se l'ultima foto vista non è sullo schermo, la si porta lì
        LaunchedEffect(lib.lastViewedAt) {
            val id = lib.lastViewed ?: return@LaunchedEffect
            if (lib.lastViewedAt == 0L) return@LaunchedEffect
            var item = 1 // dopo l'intestazione
            for (s in segments) {
                if (s.title.isNotEmpty()) item++
                for (r in s.rows) {
                    if (r.cells.any { it.second.photo.id == id }) {
                        val info = state.layoutInfo
                        if (info.visibleItemsInfo.none { it.index == item }) state.scrollToItem(item, -info.viewportSize.height / 3)
                        return@LaunchedEffect
                    }
                    item++
                }
            }
        }
        // trascinando verso il basso dalla cima: aggiorna la libreria (il tondo blu resta finché non ha finito)
        val pull = rememberPullToRefreshState()
        PullToRefreshBox(isRefreshing = lib.pulling, onRefresh = { lib.refresh(quiet = false) },
            modifier = Modifier.fillMaxSize(), state = pull,
            indicator = {
                if (lib.configured) PullToRefreshDefaults.Indicator(state = pull, isRefreshing = lib.pulling,
                    modifier = Modifier.align(Alignment.TopCenter), containerColor = Color(0xFF1C1C1C), color = C.accent)
            }) {
        // pressione lunga e trascinamento: seleziona le foto toccate (vedi DragSelect)
        LazyColumn(Modifier.fillMaxSize().dragSelect(lib, state), state = state,
            contentPadding = PaddingValues(start = 3.dp, end = 3.dp, bottom = if (lib.selecting) 120.dp else 16.dp + LocalBarSpace.current),
            verticalArrangement = Arrangement.spacedBy(GAP)) {
            item(key = "header", contentType = "header") { header() }
            // pagine vuote: disegno grande in grigio tenue con una frase che dice perché e cosa fare
            if (!lib.configured) item(key = "empty") {
                EmptyStateFill(EmptyGlyph.LIBRARY, L.t("Nessuna libreria collegata", "No library connected"),
                    L.t("Scegli in Impostazioni la cartella di servizio sul NAS: lì PresetCraftAppLicator mette le librerie.",
                        "In Settings, choose the service folder on the NAS: PresetCraftAppLicator puts the libraries there."))
            }
            else if (lib.index == null) item(key = "empty") {
                if (lib.loading) Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = C.accent)
                }
                else EmptyStateFill(EmptyGlyph.LIBRARY, L.t("Nessuna libreria scaricata", "No library downloaded yet"),
                    L.t("Arriva dal NAS appena è raggiungibile: da casa o con la VPN.",
                        "It comes from the NAS as soon as it can be reached: at home or over the VPN."))
            }
            else if (cells.isEmpty()) item(key = "empty") {
                if (lib.filtering || lib.aiActive) EmptyStateFill(EmptyGlyph.SEARCH, L.t("Nessuna foto trovata", "No photos found"),
                    L.t("Prova a togliere qualche filtro o a dirlo in un altro modo.", "Try removing a filter or saying it another way."))
                else EmptyStateFill(EmptyGlyph.PHOTOS, L.t("Non hai ancora foto", "No photos yet"),
                    L.t("Le foto importate in questa libreria compaiono qui.", "Photos imported into this library show up here."))
            }
            for (s in segments) {
                if (s.title.isNotEmpty()) stickyHeader(key = "seg-" + s.key, contentType = "segment") {
                    Row(Modifier.fillMaxWidth().background(Color(0xB3000000)).padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(s.title, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
                        Text("${s.count}", color = C.muted, fontSize = 15.sp)
                    }
                }
                s.rows.forEach { row ->
                    // chiave = la prima foto della riga: aprendo o chiudendo una pila le righe nuove sfumano e le altre
                    // scorrono al loro posto invece di saltare
                    item(key = "row-" + row.cells.first().second.photo.id, contentType = "row") {
                        Row(Modifier.animateItem(fadeInSpec = androidx.compose.animation.core.tween(220),
                            placementSpec = androidx.compose.animation.core.spring(
                                stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow),
                            fadeOutSpec = androidx.compose.animation.core.tween(180)),
                            horizontalArrangement = Arrangement.spacedBy(GAP)) {
                            row.cells.forEachIndexed { k, (i, c) ->
                                LibCell(lib, c, i, photos, onOpen, Modifier.width(row.widths[k]).height(row.height))
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

/** Opzioni della galleria: selezione, griglia giustificata o quadrata, dimensione, divisione per periodo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryOptionsSheet(lib: LibraryViewModel, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF1C1C1C)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            // si seleziona con la pressione lunga (e trascinando): niente voce apposta nel menu
            // il cestino di LightCraft: con il pallino rosso se ci sono foto
            val trashN = lib.trashCount
            Row(Modifier.fillMaxWidth().clickable { lib.openTrash(); onClose() }.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(L.t("Cestino", "Trash"), color = C.text, fontSize = 16.sp)
                if (trashN > 0) { Spacer(Modifier.width(8.dp)); TrashDot() }
                Spacer(Modifier.weight(1f))
                Text(if (trashN > 0) L.t("$trashN foto", "$trashN photos") else L.t("vuoto", "empty"),
                    color = if (trashN > 0) TrashRed else C.muted, fontSize = 14.sp)
            }
            SheetTitle(L.t("Visualizzazione", "View"))
            SheetRow(L.t("Griglia con celle quadrate", "Square grid"), checked = lib.gridMode == "square") { lib.setGrid("square") }
            SheetRow(L.t("Griglia giustificata", "Justified grid"), checked = lib.gridMode == "justified") { lib.setGrid("justified") }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(L.t("Dimensione delle foto", "Photo size"), color = C.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                SizeButton("−", lib.gridSize > 1) { lib.setSize(lib.gridSize - 1) }
                Text("${lib.gridSize}", color = C.text, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 14.dp))
                SizeButton("+", lib.gridSize < 5) { lib.setSize(lib.gridSize + 1) }
            }
            Row(Modifier.fillMaxWidth().clickable { lib.setSegmented(!lib.segment) }.padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(L.t("Dividi per periodo", "Group by period"), color = C.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Switch(lib.segment, { lib.setSegmented(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = C.accent, checkedThumbColor = Color.White))
            }
            if (lib.segment) for ((kind, label) in listOf("year" to L.t("Per anno", "By year"),
                    "month" to L.t("Per mese", "By month"), "day" to L.t("Per giorno", "By day"))) {
                SheetRow(label, checked = lib.segmentBy == kind, indent = true) { lib.setSegmentKind(kind) }
            }
            if (lib.aiActive) Text(L.t("Durante la ricerca per soggetto le foto sono in ordine di somiglianza, senza periodi.",
                "While searching by subject photos are in order of similarity, without periods."),
                color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(text.uppercase(), color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp))
}

@Composable
private fun SheetRow(text: String, checked: Boolean, indent: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick)
        .padding(start = if (indent) 40.dp else 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = if (checked) C.accent else C.text, fontSize = 16.sp, modifier = Modifier.weight(1f),
            fontWeight = if (checked) FontWeight.Medium else FontWeight.Normal)
        if (checked) Icon(Icons.Filled.Check, null, tint = C.accent, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun SizeButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).background(if (enabled) Color(0xFF2B2B2B) else Color(0xFF222222), androidx.compose.foundation.shape.CircleShape)
        .clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = if (enabled) C.text else C.muted, fontSize = 18.sp)
    }
}
