package io.github.poty87.viewercraftstar

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
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

/** Contenuto della scheda Libreria (sotto le schede in alto). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryContent(lib: LibraryViewModel, onOpen: (List<LibPhoto>, Int) -> Unit) {
    LaunchedEffect(Unit) { lib.refresh() }
    if (!lib.configured) {
        Text(L.t("Imposta nelle Impostazioni la cartella del NAS con le librerie (la stessa scelta in PresetCraftAppLicator › Importazione automatica › Librerie da consultare sul telefono).",
            "In Settings, set the NAS folder with the libraries (the one chosen in PresetCraftAppLicator › Automatic import › Libraries to browse on the phone)."),
            color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
        return
    }
    Column(Modifier.fillMaxSize()) {
        // libreria scelta e aggiornamento
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
        if (lib.message.isNotEmpty()) Banner(lib.messageLevel, lib.message, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        Filters(lib)
        val idx = lib.index
        val cells = lib.cells()
        if (idx == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (lib.loading) CircularProgressIndicator(color = C.accent)
                else Text(L.t("Nessuna libreria scaricata.", "No library downloaded yet."), color = C.muted)
            }
            return@Column
        }
        Text(if (lib.filtering) L.t("${cells.size} foto con questi filtri", "${cells.size} photos match")
            else L.t("${cells.size} elementi", "${cells.size} items"), color = C.muted, fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.fillMaxSize().padding(horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            itemsIndexed(cells, key = { _, c -> c.photo.id }) { i, c ->
                val p = c.photo
                val inOpenStack = c.open
                Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(5.dp))
                    .then(if (inOpenStack) Modifier.border(2.dp, C.accent.copy(alpha = 0.7f), RoundedCornerShape(5.dp)) else Modifier)
                    .clickable { onOpen(cells.map { it.photo }, i) }) {
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
                    if (p.rating > 0) Row(Modifier.align(Alignment.BottomStart).padding(4.dp).clip(RoundedCornerShape(3.dp))
                        .background(Color(0x99000000)).padding(horizontal = 3.dp, vertical = 1.dp)) {
                        repeat(p.rating) { Icon(Icons.Filled.Star, null, tint = C.star, modifier = Modifier.size(10.dp)) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Filters(lib: LibraryViewModel) {
    var menu by remember { mutableStateOf("") }
    var dates by remember { mutableStateOf(false) }
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
            if (lib.filtering) TextButton(onClick = { lib.clearFilters() }) { Text(L.t("Azzera", "Clear")) }
        }
        OutlinedTextField(lib.text, { lib.text = it }, singleLine = true,
            placeholder = { Text(L.t("Cerca nome, titolo, parole chiave…", "Search name, title, keywords…"), fontSize = 13.sp) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp))
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
            DateRangePicker(state, Modifier.weight(1f), title = {
                Text(L.t("Data di scatto", "Capture date"), Modifier.padding(start = 24.dp, top = 16.dp))
            })
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
    val p = list[pager.currentPage.coerceIn(0, list.size - 1)]
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
                IconButton(onClick = { info = !info }) { Icon(Icons.Filled.Info, L.t("Informazioni", "Info"), tint = C.text) }
            }
            if (info) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xE6141414))
                .navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
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
                    lib.index?.stackOf?.get(p.id)?.let { L.t("Pila", "Stack") to L.t("${it.photos.size} foto", "${it.photos.size} photos") },
                )
                rows.forEach { (k, v) ->
                    Row { Text(k, color = C.muted, fontSize = 12.sp, modifier = Modifier.width(96.dp)); Text(v, color = C.text, fontSize = 13.sp) }
                }
            }
        }
    }
}
