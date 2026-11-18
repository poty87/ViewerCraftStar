package io.github.poty87.viewercraftstar

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val labelColors = ColorLabel.entries.associate { it.xmp.lowercase() to Color(it.argb) }

private fun labelName(key: String) = when (key) {
    "red" -> L.t("Rossa", "Red"); "yellow" -> L.t("Gialla", "Yellow"); "green" -> L.t("Verde", "Green")
    "blue" -> L.t("Blu", "Blue"); "purple" -> L.t("Viola", "Purple"); else -> key
}

private fun dateText(iso: String): String = runCatching {
    java.time.LocalDateTime.parse(iso.take(19)).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
}.getOrDefault(iso)

/** Miniatura dal NAS (o dal telefono se già vista), con il segnaposto finché non arriva. */
@Composable
private fun RemoteImage(lib: LibraryViewModel, photo: LibPhoto, maxSide: Int, modifier: Modifier, crop: Boolean) {
    val context = LocalContext.current
    val idx = lib.index ?: return
    val bmp by produceState<Bitmap?>(initialValue = if (maxSide <= RemotePreview.THUMB)
        RemotePreview.cachedThumb(context, idx.library, photo) else null, photo.id, maxSide) {
        if (value == null) value = runCatching { RemotePreview.load(context, lib.settings.nas, idx, photo, maxSide) }.getOrNull()
    }
    Box(modifier.background(C.card), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(),
            contentScale = if (crop) ContentScale.Crop else ContentScale.Fit)
        else if (maxSide > RemotePreview.THUMB) CircularProgressIndicator(color = C.accent, strokeWidth = 2.dp)
    }
}

/** Stelle, bandierina ed etichetta per una o più foto: ogni tocco si applica subito. */
@Composable
fun EditControls(lib: LibraryViewModel, ids: Collection<Int>, current: LibPhoto?) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            for ((f, label) in listOf("pick" to L.t("Scelta", "Pick"), "reject" to L.t("Scartata", "Reject"))) {
                val on = current?.flag == f
                val (fg, bg) = if (f == "pick") C.pick else C.reject
                Box(Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) bg else C.card)
                    .clickable { lib.setFlag(ids, if (on) "none" else f) }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                    Text((if (f == "pick") "✓ " else "✕ ") + label, color = if (on) fg else C.text, fontSize = 13.sp)
                }
            }
            if (current == null) TextButton(onClick = { lib.setFlag(ids, "none") }) { Text(L.t("Nessuna", "None")) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StarsRow(current?.rating ?: 0, 28.dp) { s -> lib.setRating(ids, if (current?.rating == s) 0 else s) }
            if (current == null) TextButton(onClick = { lib.setRating(ids, 0) }) { Text(L.t("Nessuna", "None")) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            labelColors.forEach { (key, color) ->
                val on = current?.label.equals(key, ignoreCase = true)
                Box(Modifier.size(28.dp).clip(CircleShape).background(color)
                    .then(if (on) Modifier.border(3.dp, Color.White, CircleShape) else Modifier)
                    .clickable { lib.setLabel(ids, if (on) "" else key) })
            }
            TextButton(onClick = { lib.setLabel(ids, "") }) { Text(L.t("Nessuna", "None")) }
        }
    }
}

/** Titolo, didascalia e parole chiave di una foto. */
@Composable
fun TextsDialog(p: LibPhoto, onDismiss: () -> Unit, onSave: (String, String, List<String>) -> Unit) {
    var title by remember { mutableStateOf(p.title) }
    var caption by remember { mutableStateOf(p.caption) }
    var keywords by remember { mutableStateOf(p.keywords.joinToString(", ")) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(p.name) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text(L.t("Titolo", "Title")) }, singleLine = true)
            OutlinedTextField(caption, { caption = it }, label = { Text(L.t("Didascalia", "Caption")) })
            OutlinedTextField(keywords, { keywords = it }, label = { Text(L.t("Parole chiave (separate da virgole)", "Keywords (comma-separated)")) })
        }
    }, confirmButton = {
        TextButton(onClick = { onSave(title.trim(), caption.trim(), keywords.split(',').map { it.trim() }.filter { it.isNotEmpty() }) }) {
            Text(L.t("Salva", "Save"))
        }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(L.t("Annulla", "Cancel")) } })
}

/** Scelta di cosa scaricare: l'originale o il JPEG della fotocamera. */
@Composable
fun DownloadDialog(count: Int, onDismiss: () -> Unit, onPick: (Boolean) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(L.t("Scarica $count foto sul telefono", "Download $count photos to the phone")) },
        text = { Text(tip(L.t("Originale: il file così com'è sul NAS (DNG, CR2, JPG…). JPEG: l'anteprima della fotocamera, leggera e pronta da condividere (per i TIF è solo una miniatura). Finiscono in ${DownloadService.FOLDER.trimEnd('/')}.",
            "Original: the file as it is on the NAS (DNG, CR2, JPG…). JPEG: the camera's preview, light and ready to share (for TIFFs only a thumbnail). They go to ${DownloadService.FOLDER.trimEnd('/')}."),
            L.t("In ${DownloadService.FOLDER.trimEnd('/')}.", "To ${DownloadService.FOLDER.trimEnd('/')}.")) ?: "") },
        confirmButton = { TextButton(onClick = { onPick(false) }) { Text(L.t("Originale", "Original")) } },
        dismissButton = { TextButton(onClick = { onPick(true) }) { Text("JPEG") } })
}

/** Nel cestino di LightCraft: conferma, con la spiegazione di cosa succede ai file. */
@Composable
fun TrashDialog(count: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(L.t("Spostare $count foto nel cestino di LightCraft?", "Move $count photos to LightCraft's trash?")) },
        text = { Text(tip(L.t("Finiscono in «Eliminate di recente» quando il PC di casa applica la richiesta. I file restano sul NAS: al PC puoi ricontrollarle in LightCraft o cancellarle definitivamente con CleanCraftNas.",
            "They go to “Recently Deleted” when the home PC applies the request. The files stay on the NAS: at the PC you can check them in LightCraft or delete them for good with CleanCraftNas."),
            L.t("I file restano sul NAS finché non usi CleanCraftNas.", "The files stay on the NAS until you use CleanCraftNas.")) ?: "") },
        confirmButton = { TextButton(onClick = onConfirm) { Text(L.t("Nel cestino", "Move to trash")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.t("Annulla", "Cancel")) } })
}

/** Intestazione della scheda Libreria: libreria, aggiornamento, filtri, conteggio (scorre via con la griglia). */
@Composable
fun LibraryHeader(lib: LibraryViewModel) {
    LaunchedEffect(Unit) { lib.refresh() }
    if (!lib.configured) {
        Text(tip(L.t("Imposta nelle Impostazioni la cartella del NAS con le librerie (la stessa scelta in PresetCraftAppLicator › Importazione automatica › Librerie da consultare sul telefono).",
            "In Settings, set the NAS folder with the libraries (the one chosen in PresetCraftAppLicator › Automatic import › Libraries to browse on the phone)."),
            L.t("Imposta la cartella delle librerie nelle Impostazioni.", "Set the libraries folder in Settings.")) ?: "",
            color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
        return
    }
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 0.dp), verticalAlignment = Alignment.CenterVertically) {
            var open by remember { mutableStateOf(false) }
            Box(Modifier.weight(1f)) {
                Column(Modifier.clickable { open = true }.padding(vertical = 6.dp)) {
                    Text((lib.current.ifEmpty { L.t("Scegli la libreria", "Choose the library") }) + "  ▾", color = C.text,
                        fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    lib.index?.let { Text(L.t("${it.photos.size} foto · ${it.stacks.size} pile · aggiornata ", "${it.photos.size} photos · ${it.stacks.size} stacks · updated ") +
                        dateText(it.updated), color = C.muted, fontSize = 11.sp) }
                }
                DropdownMenu(open, { open = false }) {
                    lib.libraries.forEach { name -> DropdownMenuItem({ Text(name) }, onClick = { open = false; lib.choose(name) }) }
                }
            }
            IconButton(onClick = { lib.refresh(quiet = false) }, enabled = !lib.loading) {
                if (lib.loading) CircularProgressIndicator(Modifier.size(18.dp), color = C.accent, strokeWidth = 2.dp)
                else Icon(Icons.Filled.Refresh, L.t("Aggiorna", "Refresh"), tint = C.text)
            }
        }
        if (lib.message.isNotEmpty()) Banner(lib.messageLevel, lib.message, Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
        Filters(lib)
        val idx = lib.index
        if (idx != null) Text(if (lib.filtering) L.t("${lib.cells().size} foto con questi filtri", "${lib.cells().size} photos match")
            else L.t("${lib.cells().size} elementi", "${lib.cells().size} items"), color = C.muted, fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

/** Le celle della Libreria nella griglia comune (sotto l'intestazione). */
@OptIn(ExperimentalFoundationApi::class)
fun LazyGridScope.libraryItems(lib: LibraryViewModel, onOpen: (List<LibPhoto>, Int) -> Unit) {
    if (!lib.configured) return
    val idx = lib.index
    if (idx == null) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                if (lib.loading) CircularProgressIndicator(color = C.accent)
                else Text(L.t("Nessuna libreria scaricata.", "No library downloaded yet."), color = C.muted)
            }
        }
        return
    }
    val cells = lib.cells()
    itemsIndexed(cells, key = { _, c -> "lib" + c.photo.id }) { i, c ->
        val p = c.photo
        val chosen = p.id in lib.selected
        Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(5.dp))
            .then(if (chosen) Modifier.border(3.dp, Color.White, RoundedCornerShape(5.dp))
            else if (c.open) Modifier.border(2.dp, C.accent.copy(alpha = 0.7f), RoundedCornerShape(5.dp)) else Modifier)
            .combinedClickable(
                onClick = { if (lib.selecting) lib.toggleSelect(p.id) else onOpen(cells.map { it.photo }, i) },
                onLongClick = { lib.toggleSelect(p.id) })) {
            RemoteImage(lib, p, RemotePreview.THUMB, Modifier.fillMaxSize(), crop = true)
            if (p.flag == "reject") Box(Modifier.fillMaxSize().background(Color(0x99000000)))
            if (p.flag != "none") Box(Modifier.align(Alignment.TopStart).padding(5.dp).size(18.dp).clip(CircleShape)
                .background(if (p.flag == "pick") C.pick.second else C.reject.second), contentAlignment = Alignment.Center) {
                Icon(if (p.flag == "pick") Icons.Filled.Check else Icons.Filled.Close, null,
                    tint = if (p.flag == "pick") C.pick.first else C.reject.first, modifier = Modifier.size(12.dp))
            }
            labelColors[p.label.lowercase()]?.let {
                Box(Modifier.align(Alignment.TopEnd).padding(7.dp).size(11.dp).clip(CircleShape).background(it))
            }
            // pila: copertina con il numero di foto; tocco sul numero = apri / chiudi (come in LightCraft)
            if (c.cover && c.stack != null) Box(Modifier.align(Alignment.BottomEnd).padding(4.dp)
                .clip(RoundedCornerShape(4.dp)).background(if (c.open) C.accent else Color(0xCC000000))
                .clickable { lib.toggle(c.stack) }.padding(horizontal = 7.dp, vertical = 3.dp)) {
                Text((if (c.open) "▾ " else "▣ ") + c.stack.photos.size, color = if (c.open) Color.Black else Color.White,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            if (chosen) Box(Modifier.fillMaxSize().background(Color(0x553B82F6)), contentAlignment = Alignment.Center) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(C.accent), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Check, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                }
            }
            // modifica fatta sul telefono, non ancora in LightCraft
            if (p.id in lib.edits) Box(Modifier.align(Alignment.TopCenter).padding(6.dp).size(10.dp).clip(CircleShape)
                .background(Color(0xFFFB923C)))
            if (p.rating > 0) Row(Modifier.align(Alignment.BottomStart).padding(4.dp).clip(RoundedCornerShape(3.dp))
                .background(Color(0x99000000)).padding(horizontal = 3.dp, vertical = 1.dp)) {
                repeat(p.rating) { Icon(Icons.Filled.Star, null, tint = C.star, modifier = Modifier.size(10.dp)) }
            }
        }
    }
}

/** Barra della selezione multipla, fissa in basso (resta raggiungibile anche scorrendo la griglia). */
@Composable
fun LibrarySelectionBar(lib: LibraryViewModel, modifier: Modifier = Modifier) {
    var asking by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var trashing by remember { mutableStateOf(false) }
    if (asking) DownloadDialog(lib.selected.size, onDismiss = { asking = false }) { jpeg -> asking = false; lib.download(lib.selected, jpeg) }
    if (trashing) TrashDialog(lib.selected.size, onDismiss = { trashing = false }) { trashing = false; lib.trash(lib.selected) }
    if (editing) AlertDialog(onDismissRequest = { editing = false },
        title = { Text(L.t("Modifica ${lib.selected.size} foto", "Edit ${lib.selected.size} photos")) },
        text = { EditControls(lib, lib.selected, null) },
        confirmButton = { TextButton(onClick = { editing = false }) { Text(L.t("Fine", "Done")) } })
    Column(modifier.fillMaxWidth().background(Color(0xF2253650)).navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { lib.clearSelection() }) { Icon(Icons.Filled.Close, L.t("Annulla", "Cancel"), tint = C.text) }
            Text(L.t("${lib.selected.size} selezionate", "${lib.selected.size} selected"), color = C.text, fontSize = 15.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { lib.selectAll() }) { Text(L.t("Tutte", "All")) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = { editing = true }) { Text(L.t("Modifica", "Edit")) }
            TextButton(onClick = { asking = true }) { Text(L.t("Scarica", "Download")) }
            TextButton(onClick = { lib.stack(lib.selected) }, enabled = lib.selected.size >= 2) { Text(L.t("Pila", "Stack")) }
            TextButton(onClick = { trashing = true }) { Text(L.t("Cestino", "Trash"), color = Color(0xFFF87171)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Filters(lib: LibraryViewModel) {
    var menu by remember { mutableStateOf("") }
    var dates by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf(false) }
    val chipColors = FilterChipDefaults.filterChipColors(containerColor = C.card, labelColor = C.text,
        selectedContainerColor = Color(0xFF253650), selectedLabelColor = Color(0xFFDCE8FB))
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                FilterChip(lib.stars != StarFilter.ANY, { menu = "stars" }, label = { Text(starsText(lib.stars), fontSize = 13.sp) }, colors = chipColors)
                DropdownMenu(menu == "stars", { menu = "" }) {
                    StarFilter.entries.forEach { f -> DropdownMenuItem({ Text(starsText(f)) }, onClick = { lib.stars = f; menu = "" }) }
                }
            }
            Box {
                FilterChip(lib.flag.isNotEmpty(), { menu = "flag" }, label = { Text(flagText(lib.flag), fontSize = 13.sp) }, colors = chipColors)
                DropdownMenu(menu == "flag", { menu = "" }) {
                    listOf("", "pick", "reject", "none").forEach { f -> DropdownMenuItem({ Text(flagText(f)) }, onClick = { lib.flag = f; menu = "" }) }
                }
            }
            Box {
                FilterChip(lib.labels.isNotEmpty(), { menu = "label" }, colors = chipColors, label = {
                    Text(if (lib.labels.isEmpty()) L.t("Etichetta", "Label") else lib.labels.joinToString(", ") { labelName(it) }, fontSize = 13.sp)
                })
                DropdownMenu(menu == "label", { menu = "" }) {
                    labelColors.forEach { (key, color) ->
                        DropdownMenuItem({ Text((if (key in lib.labels) "✓ " else "    ") + labelName(key)) },
                            leadingIcon = { Box(Modifier.size(12.dp).clip(CircleShape).background(color)) },
                            onClick = { lib.labels = if (key in lib.labels) lib.labels - key else lib.labels + key })
                    }
                }
            }
            if (lib.cameras.size > 1) Box {
                FilterChip(lib.camera.isNotEmpty(), { menu = "camera" }, colors = chipColors,
                    label = { Text(lib.camera.ifEmpty { L.t("Fotocamera", "Camera") }, fontSize = 13.sp, maxLines = 1) })
                DropdownMenu(menu == "camera", { menu = "" }) {
                    DropdownMenuItem({ Text(L.t("Tutte", "All")) }, onClick = { lib.camera = ""; menu = "" })
                    lib.cameras.forEach { c -> DropdownMenuItem({ Text(c) }, onClick = { lib.camera = c; menu = "" }) }
                }
            }
            FilterChip(lib.dateFrom != null || lib.dateTo != null, { dates = true }, colors = chipColors, label = {
                val f = DateTimeFormatter.ofPattern("dd/MM/yy")
                Text(if (lib.dateFrom == null && lib.dateTo == null) L.t("Date", "Dates")
                else "${lib.dateFrom?.format(f) ?: "…"} – ${lib.dateTo?.format(f) ?: "…"}", fontSize = 13.sp)
            })
            FilterChip(lib.text.isNotBlank() || search, { search = !search }, colors = chipColors,
                leadingIcon = { Icon(Icons.Filled.Search, null, modifier = Modifier.size(16.dp)) },
                label = { Text(lib.text.ifBlank { L.t("Cerca", "Search") }, fontSize = 13.sp, maxLines = 1) })
            if (lib.filtering) TextButton(onClick = { lib.clearFilters(); search = false }) { Text(L.t("Azzera", "Clear")) }
        }
        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
        if (search) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        if (search) OutlinedTextField(lib.text, { lib.text = it }, singleLine = true,
            placeholder = { Text(L.t("Nome, titolo, parole chiave…", "Name, title, keywords…"), fontSize = 13.sp) },
            trailingIcon = {
                IconButton(onClick = { lib.text = ""; search = false }) { Icon(Icons.Filled.Close, L.t("Cancella", "Clear"), tint = C.text) }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp)
                .focusRequester(focus))
    }
    if (dates) {
        val zone = ZoneOffset.UTC
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = lib.dateFrom?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
            initialSelectedEndDateMillis = lib.dateTo?.atStartOfDay(zone)?.toInstant()?.toEpochMilli())
        DatePickerDialog(onDismissRequest = { dates = false }, confirmButton = {
            TextButton(onClick = {
                lib.dateFrom = state.selectedStartDateMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                lib.dateTo = state.selectedEndDateMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: lib.dateFrom
                dates = false
            }) { Text("OK") }
        }, dismissButton = {
            TextButton(onClick = { lib.dateFrom = null; lib.dateTo = null; dates = false }) { Text(L.t("Tutte le date", "Any date")) }
        }) {
            // intestazione compatta: inizio e fine su una riga sola
            val f = DateTimeFormatter.ofPattern("dd/MM/yyyy")
            fun day(ms: Long?) = ms?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().format(f) } ?: "…"
            DateRangePicker(state, Modifier.weight(1f), title = {
                Text(L.t("Data di scatto", "Capture date"), fontSize = 13.sp, color = C.muted,
                    modifier = Modifier.padding(start = 20.dp, top = 14.dp))
            }, headline = {
                Text("${day(state.selectedStartDateMillis)} – ${day(state.selectedEndDateMillis)}", fontSize = 18.sp,
                    maxLines = 1, modifier = Modifier.padding(start = 20.dp, end = 12.dp, bottom = 8.dp))
            }, showModeToggle = false)
        }
    }
}

private fun starsText(f: StarFilter) = when (f) {
    StarFilter.ANY -> L.t("Stelle", "Stars")
    StarFilter.FIVE -> "★★★★★"
    StarFilter.NONE -> L.t("Senza stelle", "No stars")
    else -> "★".repeat(f.min) + L.t(" o più", " or more")
}

private fun flagText(f: String) = when (f) {
    "pick" -> L.t("Scelte", "Picked"); "reject" -> L.t("Scartate", "Rejected")
    "none" -> L.t("Senza bandierina", "No flag"); else -> L.t("Bandierina", "Flag")
}

/** Visualizzatore della libreria: scorrimento tra le foto e informazioni (in sola lettura). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryViewer(lib: LibraryViewModel, list: List<LibPhoto>, start: Int, onBack: () -> Unit) {
    if (list.isEmpty()) { onBack(); return }
    val pager = rememberPagerState(initialPage = start.coerceIn(0, list.size - 1)) { list.size }
    var controls by remember { mutableStateOf(true) }
    var info by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    var texts by remember { mutableStateOf(false) }
    var trashing by remember { mutableStateOf(false) }
    val p = lib.effective(list[pager.currentPage.coerceIn(0, list.size - 1)])
    if (texts) TextsDialog(p, onDismiss = { texts = false }) { t, c, k -> texts = false; lib.setTexts(p.id, t, c, k) }
    if (trashing) TrashDialog(1, onDismiss = { trashing = false }) { trashing = false; lib.trash(listOf(p.id)) }
    val inTrash = lib.edits[p.id]?.trash == true
    if (asking) DownloadDialog(1, onDismiss = { asking = false }) { jpeg -> asking = false; lib.download(listOf(p.id), jpeg) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { list[it].id }, beyondViewportPageCount = 1) { page ->
            RemoteImage(lib, list[page], RemotePreview.FULL, Modifier.fillMaxSize().background(Color.Black)
                .clickable { controls = !controls }, crop = false)
        }
        if (controls) {
            Row(Modifier.fillMaxWidth().background(Color(0xB3141414)).statusBarsPadding().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L.t("Indietro", "Back"), tint = C.text) }
                Column(Modifier.weight(1f)) {
                    Text(p.name + (if (p.copyName.isNotEmpty()) " · ${p.copyName}" else ""), color = C.text, fontSize = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${pager.currentPage + 1} / ${list.size} · ${dateText(p.captured)}", color = C.muted, fontSize = 12.sp)
                }
                IconButton(onClick = { trashing = true }, enabled = !inTrash) {
                    Icon(Icons.Filled.Delete, L.t("Nel cestino di LightCraft", "Move to LightCraft's trash"),
                        tint = if (inTrash) C.muted else C.text)
                }
                IconButton(onClick = { asking = true }) {
                    Icon(Icons.Filled.KeyboardArrowDown, L.t("Scarica sul telefono", "Download to the phone"), tint = C.text)
                }
                IconButton(onClick = { info = !info }) { Icon(Icons.Filled.Info, L.t("Informazioni", "Info"), tint = C.text) }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            if (info) Column(Modifier.fillMaxWidth().background(Color(0xE6141414))
                .verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StarsRow(p.rating, 18.dp, null)
                    Spacer(Modifier.width(12.dp))
                    Text(flagText(if (p.flag == "none") "none" else p.flag), color = C.text, fontSize = 13.sp)
                    labelColors[p.label.lowercase()]?.let {
                        Spacer(Modifier.width(12.dp)); Box(Modifier.size(12.dp).clip(CircleShape).background(it))
                        Text(" " + labelName(p.label.lowercase()), color = C.text, fontSize = 13.sp)
                    }
                }
                val rows = listOfNotNull(
                    p.title.takeIf { it.isNotEmpty() }?.let { L.t("Titolo", "Title") to it },
                    p.caption.takeIf { it.isNotEmpty() }?.let { L.t("Didascalia", "Caption") to it },
                    p.keywords.takeIf { it.isNotEmpty() }?.let { L.t("Parole chiave", "Keywords") to it.joinToString(", ") },
                    (L.t("Scatto", "Taken") to dateText(p.captured)),
                    p.camera.takeIf { it.isNotEmpty() }?.let { L.t("Fotocamera", "Camera") to it },
                    p.lens.takeIf { it.isNotEmpty() }?.let { L.t("Obiettivo", "Lens") to it },
                    listOfNotNull(p.focal?.let { "${it.toInt()} mm" }, p.aperture?.let { "f/$it" },
                        p.shutter.takeIf { it.isNotEmpty() }?.let { "$it s" }, p.iso?.let { "ISO $it" })
                        .takeIf { it.isNotEmpty() }?.let { L.t("Esposizione", "Exposure") to it.joinToString("  ") },
                    p.creator.takeIf { it.isNotEmpty() }?.let { L.t("Autore", "Creator") to it },
                    p.copyright.takeIf { it.isNotEmpty() }?.let { "Copyright" to it },
                    (L.t("File", "File") to p.path),
                    lib.stackOf(p.id)?.let { L.t("Pila", "Stack") to L.t("${it.photos.size} foto", "${it.photos.size} photos") },
                )
                rows.forEach { (k, v) ->
                    Row { Text(k, color = C.muted, fontSize = 12.sp, modifier = Modifier.width(96.dp)); Text(v, color = C.text, fontSize = 13.sp) }
                }
            }
            // modifica: stelle, bandierina, etichetta, testi
            Column(Modifier.fillMaxWidth().background(Color(0xD9141414)).navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (inTrash) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(L.t("Nel cestino di LightCraft (in attesa del PC di casa).", "In LightCraft's trash (waiting for the home PC)."),
                        color = Color(0xFFF87171), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { lib.untrash(p.id) }) { Text(L.t("Annulla", "Undo")) }
                }
                EditControls(lib, listOf(p.id), p)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { texts = true }) { Text(L.t("Titolo e parole chiave…", "Title and keywords…")) }
                    if (p.id in lib.edits) Text(if (lib.edits.getValue(p.id).sent > 0)
                        (tip(L.t("Inviata: entra in LightCraft quando il PC di casa la applica.", "Sent: enters LightCraft when the home PC applies it."),
                            L.t("Inviata", "Sent")) ?: "")
                        else L.t("Da inviare", "To send"), color = Color(0xFFFB923C), fontSize = 12.sp)
                }
            }
            }
        }
    }
}
