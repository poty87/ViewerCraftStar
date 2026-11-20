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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
        while (i < cells.size) {
            row += cells[i]
            sum += ratioOf(cells[i].second.photo)
            i++
            val w = target.value * sum + GAP.value * (row.size - 1)
            if (w >= width.value) break
        }
        val gaps = GAP.value * (row.size - 1)
        val full = target.value * sum + gaps >= width.value
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
    val loc = Locale.getDefault()
    when (by) {
        "year" -> key
        "day" -> LocalDate.parse(key).format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", loc))
            .replaceFirstChar { it.titlecase(loc) }
        else -> LocalDate.parse("$key-01").format(DateTimeFormatter.ofPattern("yyyy MMMM", loc))
    }
}.getOrDefault(key.ifEmpty { L.t("Senza data", "No date") })

/**
 * La galleria della Libreria: intestazione (che scorre via con le foto), poi le foto in righe giustificate o quadrate,
 * divise per anno, mese o giorno con il titolo del periodo che resta in alto, in trasparenza, mentre si scorre.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryGallery(lib: LibraryViewModel, state: LazyListState, header: @Composable () -> Unit,
                   onOpen: (List<LibPhoto>, Int) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)) {
        val width = maxWidth - 6.dp
        // una foto compare una volta sola (le righe hanno per chiave la loro prima foto)
        val cells = if (lib.configured && lib.index != null) lib.cells().distinctBy { it.photo.id } else emptyList()
        val photos = remember(cells) { cells.map { it.photo } }
        // la ricerca per soggetto ordina per somiglianza: lì niente periodi, conta l'ordine
        val bySegment = lib.segment && !lib.aiActive
        val segments = remember(cells, width, lib.gridMode, lib.gridSize, bySegment, lib.segmentBy) {
            val indexed = cells.mapIndexed { i, c -> i to c }
            val groups = if (bySegment) indexed.groupBy { segmentKey(it.second.photo.captured, lib.segmentBy) }.toList()
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
        LazyColumn(Modifier.fillMaxSize(), state = state,
            contentPadding = PaddingValues(start = 3.dp, end = 3.dp, bottom = if (lib.selecting) 120.dp else 16.dp + LocalBarSpace.current),
            verticalArrangement = Arrangement.spacedBy(GAP)) {
            item(key = "header", contentType = "header") { header() }
            if (lib.configured && lib.index == null) item(key = "empty") {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    if (lib.loading) CircularProgressIndicator(color = C.accent)
                    else Text(L.t("Nessuna libreria scaricata.", "No library downloaded yet."), color = C.muted)
                }
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

/** Opzioni della galleria: selezione, griglia giustificata o quadrata, dimensione, divisione per periodo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryOptionsSheet(lib: LibraryViewModel, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF1C1C1C)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            SheetTitle(L.t("Selezione", "Selection"))
            SheetRow(L.t("Seleziona foto", "Select photos"), checked = lib.selectMode) {
                lib.selectMode = !lib.selectMode; if (!lib.selectMode) lib.clearSelection(); onClose()
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
