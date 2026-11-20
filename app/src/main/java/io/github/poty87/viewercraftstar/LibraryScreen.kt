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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
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
import androidx.lifecycle.repeatOnLifecycle
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

/**
 * L'ultima foto vista: un velo grigio chiaro che lampeggia lentamente tre volte dopo il ritorno alla griglia e si ferma
 * al suo minimo (non sparisce di colpo); poi resta un bordo leggero, così in una pila di foto simili si ritrova quella
 * appena guardata.
 */
@Composable
private fun LastViewedMark(at: Long) {
    val pulse = remember(at) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(at) {
        repeat(3) {
            pulse.animateTo(1f, androidx.compose.animation.core.tween(1100))
            pulse.animateTo(0f, androidx.compose.animation.core.tween(1100))
        }
    }
    val v = pulse.value
    Box(Modifier.fillMaxSize().background(Color(0xFFE5E7EB).copy(alpha = 0.4f * v))
        .border(2.dp, Color(0xFFE5E7EB).copy(alpha = 0.6f + 0.3f * v), RoundedCornerShape(5.dp)))
}

/** Miniatura dal NAS (o dal telefono se già vista), con il segnaposto finché non arriva. */
@Composable
private fun RemoteImage(lib: LibraryViewModel, photo: LibPhoto, maxSide: Int, modifier: Modifier, crop: Boolean,
                        original: Boolean = false) {
    val context = LocalContext.current
    val idx = lib.index ?: return
    // ritoccata in LightCraft: l'anteprima sviluppata dal PC (se non si chiede quella della fotocamera)
    val sig = if (original) null else lib.developed[photo.id]
    val dev = sig?.let { RemotePreview.Developed(lib.libShare, listOf(lib.libRoot, lib.current, "previews", "${photo.id}.jpg").joinToString("\\"), it) }
    val bmp by produceState<Bitmap?>(initialValue = if (maxSide <= RemotePreview.THUMB)
        RemotePreview.cachedThumb(context, idx.library, photo, dev) else null, photo.id, maxSide, sig) {
        // si riparte anche quando cambia lo sviluppo (sig): l'immagine vecchia resta finché arriva quella nuova
        val cached = if (maxSide <= RemotePreview.THUMB) RemotePreview.cachedThumb(context, idx.library, photo, dev) else null
        value = cached ?: runCatching { RemotePreview.load(context, lib.settings.nas, idx, photo, maxSide, dev) }.getOrNull()
            ?: value
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

/** Nome di un volto: scritto o scelto tra le persone già nella libreria. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun FaceNameDialog(face: LibFace, known: List<String>, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    // un volto senza nome con un suggerimento: la domanda è già la risposta («Forse è Marta?»), basta confermare
    val guess = face.suggest.takeIf { face.name.isEmpty() && it.isNotEmpty() }
    var name by remember { mutableStateOf(face.name.ifEmpty { guess ?: "" }) }
    val hits = known.filter { name.isBlank() || it.contains(name.trim(), ignoreCase = true) }.sorted().take(12)
    AlertDialog(onDismissRequest = onDismiss, title = {
        Text(if (guess != null) L.t("Forse è $guess?", "Is it $guess?") else L.t("Chi è?", "Who is it?"))
    }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text(L.t("Nome", "Name")) }, singleLine = true)
            if (hits.isNotEmpty()) androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                hits.forEach { n ->
                    Box(Modifier.clip(RoundedCornerShape(14.dp)).background(C.card).clickable { name = n }
                        .padding(horizontal = 10.dp, vertical = 5.dp)) { Text(n, color = C.text, fontSize = 13.sp) }
                }
            }
            Text(tip(L.t("Il nome va in LightCraft quando il PC di casa applica le modifiche: lì LightCraft lo usa per riconoscere la persona nelle altre foto (se hai attivato i volti).",
                "The name goes to LightCraft when the home PC applies the changes: there LightCraft uses it to recognise the person in other photos (if faces are turned on)."),
                L.t("Arriva in LightCraft dal PC di casa.", "Reaches LightCraft through the home PC.")) ?: "",
                color = C.muted, fontSize = 12.sp)
        }
    }, confirmButton = {
        TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) {
            Text(if (guess != null && name.trim() == guess) L.t("Sì, è $guess", "Yes, it's $guess") else L.t("Salva", "Save"))
        }
    }, dismissButton = {
        Row {
            if (face.name.isNotEmpty()) TextButton(onClick = { onSave("") }) { Text(L.t("Togli il nome", "Remove name")) }
            TextButton(onClick = onDismiss) { Text(L.t("Annulla", "Cancel")) }
        }
    })
}

/** I riquadri dei volti sopra la foto (mostrata intera, al centro): un tocco per dare o cambiare il nome. */
@Composable
private fun FaceOverlay(photo: LibPhoto, onTap: (LibFace) -> Unit) {
    if (photo.width <= 0 || photo.height <= 0) return
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = photo.width.toFloat()
        val h = photo.height.toFloat()
        val scale = minOf(maxWidth.value / w, maxHeight.value / h)
        val dw = w * scale
        val dh = h * scale
        val ox = (maxWidth.value - dw) / 2
        val oy = (maxHeight.value - dh) / 2
        photo.faces.forEach { f ->
            val named = f.name.isNotEmpty()
            val guess = !named && f.suggest.isNotEmpty()  // suggerimento di LightCraft, da confermare
            Box(Modifier.offset(x = (ox + f.x0 * dw).dp, y = (oy + f.y0 * dh).dp)
                .size(((f.x1 - f.x0) * dw).coerceAtLeast(18f).dp, ((f.y1 - f.y0) * dh).coerceAtLeast(18f).dp)
                .border(2.dp, when { named -> C.accent; guess -> Color(0xFFFBBF24); else -> Color.White.copy(alpha = 0.85f) },
                    RoundedCornerShape(4.dp))
                .clickable { onTap(f) })
            Box(Modifier.offset(x = (ox + f.x0 * dw).dp, y = (oy + f.y1 * dh + 2).dp)
                .clip(RoundedCornerShape(4.dp)).background(Color(0xB3000000)).clickable { onTap(f) }
                .padding(horizontal = 5.dp, vertical = 1.dp)) {
                Text(when { named -> f.name; guess -> "${f.suggest}?"; else -> "?" },
                    color = when { named -> Color.White; guess -> Color(0xFFFBBF24); else -> C.muted }, fontSize = 11.sp,
                    maxLines = 1)
            }
        }
    }
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
    // all'apertura, a ogni ritorno nell'app e poi ogni minuto finché la Libreria resta in primo piano: le novità del
    // PC (indice, anteprime sviluppate, ricerca IA) arrivano senza premere il tasto di aggiornamento
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
            if (p.id == lib.lastViewed && lib.lastViewedAt > 0) LastViewedMark(lib.lastViewedAt)
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
            // ritoccata in LightCraft (l'anteprima è quella sviluppata dal PC)
            if (p.id in lib.developed) Box(Modifier.align(Alignment.BottomEnd)
                .padding(end = 4.dp, bottom = if (c.cover && c.stack != null) 28.dp else 4.dp).size(18.dp).clip(CircleShape)
                .background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Edit, L.t("Ritoccata in LightCraft", "Edited in LightCraft"), tint = Color.White,
                    modifier = Modifier.size(11.dp))
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
    // il campo di ricerca è aperto finché c'è una ricerca (anche tornando dal visualizzatore) o finché l'ha aperto l'utente
    val search = lib.searchOpen || lib.text.isNotBlank()
    var focusNow by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    val chipColors = FilterChipDefaults.filterChipColors(containerColor = C.card, labelColor = C.text,
        selectedContainerColor = Color(0xFF253650), selectedLabelColor = Color(0xFFDCE8FB))
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(search, {
                if (search && lib.text.isBlank()) lib.searchOpen = false else { lib.searchOpen = true; focusNow = true }
            }, colors = chipColors,
                leadingIcon = { Icon(Icons.Filled.Search, null, modifier = Modifier.size(16.dp)) },
                label = { Text(lib.text.ifBlank { L.t("Cerca", "Search") }, fontSize = 13.sp, maxLines = 1) })
            // tutti i parametri insieme, con "Applica": acceso e con il numero dei filtri attivi lì dentro (date, etichette,
            // fotocamera, bandierina, tipo di file), così un filtro impostato non passa inosservato
            val inside = lib.advancedCount
            FilterChip(inside > 0, { advanced = true }, colors = chipColors,
                leadingIcon = { Icon(Icons.Filled.Menu, null, modifier = Modifier.size(16.dp)) },
                label = { Text(L.t("Tutti i filtri", "All filters") + if (inside > 0) " · $inside" else "", fontSize = 13.sp) })
            Box {
                FilterChip(lib.stars != StarFilter.ANY, { menu = "stars" }, label = { Text(starsText(lib.stars), fontSize = 13.sp) }, colors = chipColors)
                DropdownMenu(menu == "stars", { menu = "" }) {
                    StarFilter.entries.forEach { f -> DropdownMenuItem({ Text(starsText(f)) }, onClick = { lib.stars = f; menu = "" }) }
                }
            }
            if (lib.filtering) TextButton(onClick = { lib.clearFilters(); lib.searchOpen = false }) { Text(L.t("Azzera", "Clear")) }
        }
        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
        if (search && focusNow) LaunchedEffect(Unit) { runCatching { focus.requestFocus() }; focusNow = false }
        val ai = lib.aiMode && lib.aiAvailable
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        // ricerca per soggetto: i risultati si calcolano dopo una breve pausa di scrittura
        LaunchedEffect(lib.text, lib.aiMode, lib.aiModelReady, lib.aiPending) { lib.requestAi() }
        if (search) OutlinedTextField(lib.text, { lib.text = it }, singleLine = true,
            placeholder = {
                Text(if (ai) L.t("Cosa cerchi? es. gatti, auto d'epoca, tramonto al mare", "What are you looking for? e.g. cats, vintage car, sunset at the beach")
                else L.t("Nome, titolo, parole chiave…", "Name, title, keywords…"), fontSize = 13.sp, maxLines = 1)
            },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (lib.aiBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = C.accent)
                    IconButton(onClick = { lib.text = ""; lib.searchOpen = false }) { Icon(Icons.Filled.Close, L.t("Cancella", "Clear"), tint = C.text) }
                }
            },
            // "Cerca" sulla tastiera: la ricerca per soggetto parte subito
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
                lib.requestAi(now = true)
                keyboard?.hide()
            }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp)
                .focusRequester(focus))
        if (search && lib.aiAvailable) AiSearchBar(lib)
    }
    if (dates) DateRangeDialog(lib.dateFrom, lib.dateTo, { a, b -> lib.dateFrom = a; lib.dateTo = b; dates = false }) { dates = false }
    if (advanced) AdvancedSearchSheet(lib) { advanced = false }
}

/** Intervallo di date di scatto: OK con l'intervallo scelto, "Tutte le date" per toglierlo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangeDialog(from: java.time.LocalDate?, to: java.time.LocalDate?,
                            onPick: (java.time.LocalDate?, java.time.LocalDate?) -> Unit, onDismiss: () -> Unit) {
    run {
        val zone = ZoneOffset.UTC
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = from?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
            initialSelectedEndDateMillis = to?.atStartOfDay(zone)?.toInstant()?.toEpochMilli())
        DatePickerDialog(onDismissRequest = onDismiss, confirmButton = {
            TextButton(onClick = {
                val a = state.selectedStartDateMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                val b = state.selectedEndDateMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: a
                onPick(a, b)
            }) { Text("OK") }
        }, dismissButton = {
            TextButton(onClick = { onPick(null, null) }) { Text(L.t("Tutte le date", "Any date")) }
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

/** Sotto il campo di ricerca: cercare per soggetto (IA) o nel testo; la prima volta, il download del modello. */
@Composable
private fun AiSearchBar(lib: LibraryViewModel) {
    val chipColors = FilterChipDefaults.filterChipColors(containerColor = C.card, labelColor = C.text,
        selectedContainerColor = C.accent.copy(alpha = 0.25f), selectedLabelColor = C.text)
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(lib.aiMode, { lib.aiMode = true }, colors = chipColors,
                label = { Text(L.t("Per soggetto (IA)", "By subject (AI)"), fontSize = 13.sp) })
            FilterChip(!lib.aiMode, { lib.aiMode = false }, colors = chipColors,
                label = { Text(L.t("Nomi e parole chiave", "Names and keywords"), fontSize = 13.sp) })
        }
        val dl = lib.aiDownload
        if (lib.aiPending) Text(
            if (lib.aiTooShort) L.t("Scrivi almeno 3 lettere (o premi Cerca sulla tastiera)",
                "Type at least 3 letters (or press Search on the keyboard)")
            else L.t("Cerco «${lib.text.trim()}»… (premi Cerca sulla tastiera per partire subito)",
                "Searching for “${lib.text.trim()}”… (press Search on the keyboard to start right away)"),
            color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        val found = lib.aiFound
        if (lib.aiActive && !lib.aiPending && found != null) Text(
            if (found == 0) L.t("Nessuna foto per «${lib.text.trim()}» (prova a dirlo in un altro modo)", "No photos for “${lib.text.trim()}” (try saying it another way)")
            else L.t("$found foto per «${lib.text.trim()}», dalla più somigliante", "$found photos for “${lib.text.trim()}”, most similar first"),
            color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        if (lib.aiActive && !lib.aiPending && found != null && lib.aiMore > 0)
            TextButton(onClick = { lib.aiShowMore = true }) {
                Text(L.t("Mostra anche le meno somiglianti (altre ${lib.aiMore})", "Also show less similar ones (${lib.aiMore} more)"))
            }
        if (lib.aiMode && !lib.aiModelReady) {
            val mb = lib.aiModelSize / 1_000_000
            if (dl != null) {
                Text(L.t("Scarico il modello IA… ${dl.first / 1_000_000} di ${dl.second / 1_000_000} MB",
                    "Downloading the AI model… ${dl.first / 1_000_000} of ${dl.second / 1_000_000} MB"),
                    color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { if (dl.second > 0) dl.first.toFloat() / dl.second else 0f }, color = C.accent,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
            } else {
                Text(tip(L.t("Per cercare per soggetto serve il modello IA ($mb MB), da scaricare una volta dal NAS: meglio in Wi-Fi a casa. Poi la ricerca funziona anche senza NAS.",
                    "Searching by subject needs the AI model ($mb MB), downloaded once from the NAS: better on home Wi-Fi. Then search works even without the NAS."),
                    L.t("Serve il modello IA ($mb MB), una volta.", "Needs the AI model ($mb MB), once.")) ?: "",
                    color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                TextButton(onClick = { lib.downloadAiModel() }) { Text(L.t("Scarica il modello ($mb MB)", "Download the model ($mb MB)")) }
            }
        }
    }
}

/**
 * Ricerca con tutti i parametri insieme (testo o soggetto, stelle, date, etichette, fotocamera, bandierina, tipo di file):
 * si sceglie tutto e poi "Applica". Le scelte restano una bozza finché non si applica.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AdvancedSearchSheet(lib: LibraryViewModel, onClose: () -> Unit) {
    var text by remember { mutableStateOf(lib.text) }
    var aiMode by remember { mutableStateOf(lib.aiMode) }
    var stars by remember { mutableStateOf(lib.stars) }
    var from by remember { mutableStateOf(lib.dateFrom) }
    var to by remember { mutableStateOf(lib.dateTo) }
    var labels by remember { mutableStateOf(lib.labels) }
    var camera by remember { mutableStateOf(lib.camera) }
    var flag by remember { mutableStateOf(lib.flag) }
    var fileType by remember { mutableStateOf(lib.fileType) }
    var pickDates by remember { mutableStateOf(false) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val chipColors = FilterChipDefaults.filterChipColors(containerColor = C.card, labelColor = C.text,
        selectedContainerColor = Color(0xFF253650), selectedLabelColor = Color(0xFFDCE8FB))

    @Composable
    fun Section(title: String) = Text(title, color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))

    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onClose, containerColor = C.bg,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        // pulsanti fissi in basso (sopra la tastiera), le scelte scorrono sopra
        Column(Modifier.fillMaxWidth().imePadding()) {
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 8.dp)) {
            Text(L.t("Cerca le foto", "Find photos"), color = C.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Section(if (lib.aiAvailable) L.t("Cosa c'è nella foto, o nome e parole chiave", "What's in the photo, or name and keywords")
                else L.t("Nome, titolo, parole chiave", "Name, title, keywords"))
            OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(if (lib.aiAvailable && aiMode) L.t("es. gatti, auto d'epoca, tramonto al mare", "e.g. cats, vintage car, sunset at the beach")
                    else L.t("es. IMG_1234, compleanno…", "e.g. IMG_1234, birthday…"), fontSize = 13.sp)
                },
                trailingIcon = { if (text.isNotEmpty()) IconButton(onClick = { text = "" }) { Icon(Icons.Filled.Close, null, tint = C.text) } },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { keyboard?.hide() }))
            if (lib.aiAvailable) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 6.dp)) {
                FilterChip(aiMode, { aiMode = true }, colors = chipColors, label = { Text(L.t("Per soggetto (IA)", "By subject (AI)"), fontSize = 13.sp) })
                FilterChip(!aiMode, { aiMode = false }, colors = chipColors, label = { Text(L.t("Nomi e parole chiave", "Names and keywords"), fontSize = 13.sp) })
            }

            Section(L.t("Stelle", "Stars"))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StarFilter.entries.forEach { f ->
                    FilterChip(stars == f, { stars = f }, colors = chipColors,
                        label = { Text(if (f == StarFilter.ANY) L.t("Tutte", "All") else starsText(f), fontSize = 13.sp) })
                }
            }

            Section(L.t("Data di scatto", "Capture date"))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val f = DateTimeFormatter.ofPattern("dd/MM/yyyy")
                FilterChip(from != null || to != null, { pickDates = true }, colors = chipColors, label = {
                    Text(if (from == null && to == null) L.t("Tutte le date", "Any date")
                    else "${from?.format(f) ?: "…"} – ${to?.format(f) ?: "…"}", fontSize = 13.sp)
                })
                if (from != null || to != null) TextButton(onClick = { from = null; to = null }) { Text(L.t("Togli", "Remove")) }
            }

            Section(L.t("Etichetta", "Label"))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                labelColors.forEach { (key, color) ->
                    FilterChip(key in labels, { labels = if (key in labels) labels - key else labels + key }, colors = chipColors,
                        leadingIcon = { Box(Modifier.size(12.dp).clip(CircleShape).background(color)) },
                        label = { Text(labelName(key), fontSize = 13.sp) })
                }
            }

            if (lib.cameras.size > 1) {
                Section(L.t("Fotocamera", "Camera"))
                var open by remember { mutableStateOf(false) }
                Box {
                    androidx.compose.material3.OutlinedButton(onClick = { open = true }) {
                        Text(if (camera.isEmpty()) L.t("Tutte le fotocamere", "All cameras") else cameraName(camera), color = C.text)
                        Icon(Icons.Filled.KeyboardArrowDown, null, tint = C.text)
                    }
                    DropdownMenu(open, { open = false }) {
                        DropdownMenuItem({ Text(L.t("Tutte le fotocamere", "All cameras")) }, onClick = { camera = ""; open = false })
                        lib.cameras.forEach { c -> DropdownMenuItem({ Text(cameraName(c)) }, onClick = { camera = c; open = false }) }
                    }
                }
            }

            Section(L.t("Bandierina", "Flag"))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("", "pick", "reject", "none").forEach { f ->
                    FilterChip(flag == f, { flag = f }, colors = chipColors, label = { Text(flagText(f), fontSize = 13.sp) })
                }
            }

            Section(L.t("Tipo di file", "File type"))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("" to L.t("Tutti", "All"), "raw" to "RAW", "jpeg" to "JPEG", "tiff" to "TIFF / PSD").forEach { (k, name) ->
                    FilterChip(fileType == k, { fileType = k }, colors = chipColors, label = { Text(name, fontSize = 13.sp) })
                }
            }

        }
            Row(Modifier.fillMaxWidth().background(C.bg).padding(horizontal = 20.dp, vertical = 10.dp).navigationBarsPadding(),
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    lib.clearFilters()
                    lib.searchOpen = false
                    onClose()
                }) { Text(L.t("Azzera tutto", "Clear all")) }
                Spacer(Modifier.weight(1f))
                androidx.compose.material3.Button(onClick = {
                    lib.text = text.trim(); lib.aiMode = aiMode; lib.stars = stars; lib.dateFrom = from; lib.dateTo = to
                    lib.labels = labels; lib.camera = camera; lib.flag = flag; lib.fileType = fileType
                    lib.searchOpen = text.isNotBlank()
                    lib.requestAi(now = true)
                    onClose()
                }) { Text(L.t("Applica", "Apply")) }
            }
        }
    }
    if (pickDates) DateRangeDialog(from, to, { a, b -> from = a; to = b; pickDates = false }) { pickDates = false }
}

/** Il nome della fotocamera senza la marca ripetuta (i dati EXIF spesso hanno "Nikon" + "Nikon D90"). */
private fun cameraName(c: String): String {
    val first = c.substringBefore(' ')
    return if (first.isNotEmpty() && c.startsWith("$first $first", ignoreCase = true)) c.substring(first.length + 1) else c
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
    // volti: i riquadri trovati da LightCraft sulla foto com'era allo scatto (l'anteprima sviluppata può essere ritagliata)
    var faceMode by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf<LibFace?>(null) }
    val p = lib.effective(list[pager.currentPage.coerceIn(0, list.size - 1)])
    // l'ultima foto vista: la griglia la ritrova e la evidenzia quando si torna indietro
    LaunchedEffect(p.id) { lib.lastViewed = p.id }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { lib.lastViewedAt = System.currentTimeMillis() } }
    naming?.let { f ->
        FaceNameDialog(f, lib.index?.people.orEmpty(), onDismiss = { naming = null }) { n -> naming = null; lib.setFaceName(p.id, f, n) }
    }
    if (texts) TextsDialog(p, onDismiss = { texts = false }) { t, c, k -> texts = false; lib.setTexts(p.id, t, c, k) }
    if (trashing) TrashDialog(1, onDismiss = { trashing = false }) { trashing = false; lib.trash(listOf(p.id)) }
    val inTrash = lib.edits[p.id]?.trash == true
    if (asking) DownloadDialog(1, onDismiss = { asking = false }) { jpeg -> asking = false; lib.download(listOf(p.id), jpeg) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { list[it].id }, beyondViewportPageCount = 1) { page ->
            Box(Modifier.fillMaxSize()) {
                RemoteImage(lib, list[page], RemotePreview.FULL, Modifier.fillMaxSize().background(Color.Black)
                    .clickable { controls = !controls }, crop = false, original = lib.showOriginal || faceMode)
                if (faceMode) FaceOverlay(lib.effective(list[page])) { naming = it }
            }
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
                // volti trovati da LightCraft: mostra i riquadri per dare un nome
                if (p.faces.isNotEmpty()) IconButton(onClick = { faceMode = !faceMode }) {
                    Icon(Icons.Filled.Face, L.t("Volti", "Faces"), tint = if (faceMode) C.accent else C.text)
                }
                // ritoccata in LightCraft: com'è adesso (sviluppata dal PC) o com'era allo scatto
                if (p.id in lib.developed && !faceMode) TextButton(onClick = { lib.showOriginal = !lib.showOriginal }) {
                    Text(if (lib.showOriginal) L.t("Originale", "Original") else L.t("Modificata", "Edited"),
                        color = if (lib.showOriginal) C.muted else C.accent, fontSize = 13.sp)
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
                    p.people.takeIf { it.isNotEmpty() }?.let { L.t("Persone", "People") to it.joinToString(", ") },
                    lib.aiDescription(p.id)?.takeIf { it.isNotEmpty() }?.let { L.t("Descrizione (IA)", "Description (AI)") to it },
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
