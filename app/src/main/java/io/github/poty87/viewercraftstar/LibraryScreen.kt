package io.github.poty87.viewercraftstar

import androidx.compose.foundation.layout.wrapContentHeight

import androidx.compose.ui.semantics.contentDescription

import androidx.compose.ui.semantics.semantics

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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.MoreVert
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
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
 * L'ultima foto vista: un velo grigio chiaro che lampeggia lentamente una volta dopo il ritorno alla griglia e si ferma
 * al suo minimo (non sparisce di colpo); poi resta un bordo leggero, così in una pila di foto simili si ritrova quella
 * appena guardata.
 */
@Composable
private fun LastViewedMark(at: Long) {
    val pulse = remember(at) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(at) {
        repeat(1) {
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
internal fun RemoteImage(lib: LibraryViewModel, photo: LibPhoto, maxSide: Int, modifier: Modifier, crop: Boolean,
                        original: Boolean = false) {
    val context = LocalContext.current
    val idx = lib.index ?: return
    // ritoccata in LightCraft: l'anteprima sviluppata dal PC (se non si chiede quella della fotocamera)
    val sig = if (original) null else lib.developed[photo.id]
    val dev = sig?.let { RemotePreview.Developed(lib.libShare, listOf(lib.libRoot, lib.current, "previews", "${photo.id}.jpg").joinToString("\\"), it) }
    // mentre si scorre si prende solo ciò che è già in memoria: la lettura dal disco e la decodifica vanno fuori dal
    // thread che disegna (prima si facevano lì, ed era quello a far impuntare lo scorrimento)
    val bmp by produceState<Bitmap?>(initialValue = if (maxSide <= RemotePreview.THUMB)
        RemotePreview.memoryThumb(idx.library, photo, dev) else null, photo.id, maxSide, sig) {
        // si riparte anche quando cambia lo sviluppo (sig): l'immagine vecchia resta finché arriva quella nuova
        value = withContext(Dispatchers.IO) {
            (if (maxSide <= RemotePreview.THUMB) RemotePreview.cachedThumb(context, idx.library, photo, dev) else null)
                ?: runCatching { RemotePreview.load(context, lib.settings.nas, idx, photo, maxSide, dev) }.getOrNull()
        } ?: value
    }
    Box(modifier.background(C.card), contentAlignment = Alignment.Center) {
        val b = bmp
        val image = remember(b) { b?.asImageBitmap() }
        if (image != null) Image(image, null, Modifier.fillMaxSize(),
            contentScale = if (crop) ContentScale.Crop else ContentScale.Fit)
        else if (maxSide > RemotePreview.THUMB) CircularProgressIndicator(color = C.accent, strokeWidth = 2.dp)
    }
}

/** Un'opzione a pillola: accesa in blu (o nei colori della bandierina), un tocco la sceglie. */
@Composable
fun Pill(text: String, on: Boolean, colors: Pair<Color, Color>? = null, onClick: () -> Unit) {
    val (fg, bg) = if (on) colors ?: (Color.Black to C.accent) else (C.text to C.card)
    Text(text, color = fg, fontSize = 13.sp, maxLines = 1, softWrap = false,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(bg).clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp))
}

/**
 * Stelle, bandierina ed etichetta per una o più foto: ogni tocco si applica subito e si vede subito. Con più foto è
 * acceso il valore che hanno tutte in comune (nessuno se sono diverse).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun EditControls(lib: LibraryViewModel, ids: Collection<Int>, current: LibPhoto?, showNone: Boolean = true,
                 status: (@Composable () -> Unit)? = null, flagExtra: (@Composable () -> Unit)? = null) {
    val photos = ids.mapNotNull { lib.index?.byId?.get(it) }.map { lib.effective(it) }.ifEmpty { listOfNotNull(current) }
    fun <T> common(f: (LibPhoto) -> T): T? = photos.map(f).distinct().singleOrNull()
    val flag = common { it.flag.ifEmpty { "none" } }
    val rating = common { it.rating }
    val label = common { it.label.lowercase() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // intestazione con lo stato della modifica a destra (da inviare / inviata), sulla stessa riga
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(L.t("Bandierina", "Flag"), Modifier.weight(1f))
            status?.invoke()
        }
        // bandierine (un tocco su quella accesa la toglie) e, a destra, il pulsante dei testi
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("✓ " + L.t("Scelta", "Pick"), flag == "pick", C.pick) { lib.setFlag(ids, if (flag == "pick") "none" else "pick") }
            Pill("✕ " + L.t("Scartata", "Reject"), flag == "reject", C.reject) { lib.setFlag(ids, if (flag == "reject") "none" else "reject") }
            if (showNone) Pill(L.t("Nessuna", "None"), flag == "none") { lib.setFlag(ids, "none") }
            Spacer(Modifier.weight(1f))
            flagExtra?.invoke()
        }
        SectionLabel(L.t("Stelle", "Stars"), Modifier.padding(top = 6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StarsRow(rating ?: 0, 28.dp) { st -> lib.setRating(ids, if (rating == st) 0 else st) }
            if (showNone) Pill(L.t("Nessuna", "None"), rating == 0) { lib.setRating(ids, 0) }
        }
        SectionLabel(L.t("Etichetta", "Label"), Modifier.padding(top = 6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            labelColors.forEach { (key, color) ->
                val on = label == key
                Box(Modifier.size(28.dp).clip(CircleShape).background(color)
                    .then(if (on) Modifier.border(3.dp, Color.White, CircleShape) else Modifier)
                    .clickable { lib.setLabel(ids, if (on) "" else key) })
            }
            if (showNone) Pill(L.t("Nessuna", "None"), label == "") { lib.setLabel(ids, "") }
        }
        if (photos.size > 1 && (flag == null || rating == null || label == null)) Text(
            L.t("Dove nessuna opzione è accesa, le foto hanno valori diversi: un tocco li rende uguali per tutte.",
                "Where nothing is lit, the photos differ: one tap makes them the same for all."),
            color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * Titolo, didascalia e parole chiave per più foto: si vedono i valori comuni; titolo e didascalia si cambiano per tutte
 * solo se li tocchi; le parole chiave aggiunte o tolte valgono per tutte, le altre di ogni foto restano.
 */
@Composable
fun GroupTextsDialog(lib: LibraryViewModel, ids: Collection<Int>, onDismiss: () -> Unit) {
    val photos = ids.mapNotNull { lib.index?.byId?.get(it) }.map { lib.effective(it) }
    val commonTitle = photos.map { it.title }.distinct().singleOrNull()
    val commonCaption = photos.map { it.caption }.distinct().singleOrNull()
    val commonKw = photos.map { p -> p.keywords.map { it.lowercase() }.toSet() }.reduceOrNull { a, b -> a intersect b }.orEmpty()
    val startKw = photos.firstOrNull()?.keywords?.filter { it.lowercase() in commonKw }.orEmpty()
    var title by remember { mutableStateOf(commonTitle ?: "") }
    var caption by remember { mutableStateOf(commonCaption ?: "") }
    var keywords by remember { mutableStateOf(startKw.joinToString(", ")) }
    val differ = L.t("valori diversi: scrivi per cambiarli tutti", "different values: type to change them all")
    BlurDialog(onDismiss) {
        DialogTitle(L.t("Testi di ${photos.size} foto", "Texts of ${photos.size} photos"))
        OutlinedTextField(title, { title = it }, label = { Text(L.t("Titolo", "Title")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = if (commonTitle == null) ({ Text(differ, fontSize = 13.sp) }) else null)
        OutlinedTextField(caption, { caption = it }, label = { Text(L.t("Didascalia", "Caption")) }, modifier = Modifier.fillMaxWidth(),
            placeholder = if (commonCaption == null) ({ Text(differ, fontSize = 13.sp) }) else null)
        OutlinedTextField(keywords, { keywords = it }, label = { Text(L.t("Parole chiave in comune (separate da virgole)", "Shared keywords (comma-separated)")) },
            modifier = Modifier.fillMaxWidth())
        Text(L.t("Le parole chiave che aggiungi o togli valgono per tutte; le altre di ogni foto restano.",
            "Keywords you add or remove apply to all; each photo keeps its others."), color = C.muted, fontSize = 12.sp)
        DialogButtons(L.t("Annulla", "Cancel") to onDismiss, main = L.t("Salva", "Save") to {
            val now = keywords.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val add = now.filter { n -> startKw.none { it.equals(n, ignoreCase = true) } }
            val remove = startKw.filter { s -> now.none { it.equals(s, ignoreCase = true) } }
            lib.setTextsGroup(ids,
                title.trim().takeIf { it != (commonTitle ?: "") },
                caption.trim().takeIf { it != (commonCaption ?: "") }, add, remove)
            onDismiss()
        })
    }
}

/** Titolo, didascalia e parole chiave di una foto. */
@Composable
fun TextsDialog(p: LibPhoto, onDismiss: () -> Unit, onSave: (String, String, List<String>) -> Unit) {
    var title by remember { mutableStateOf(p.title) }
    var caption by remember { mutableStateOf(p.caption) }
    var keywords by remember { mutableStateOf(p.keywords.joinToString(", ")) }
    BlurDialog(onDismiss) {
        DialogTitle(p.name)
        OutlinedTextField(title, { title = it }, label = { Text(L.t("Titolo", "Title")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(caption, { caption = it }, label = { Text(L.t("Didascalia", "Caption")) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(keywords, { keywords = it }, label = { Text(L.t("Parole chiave (separate da virgole)", "Keywords (comma-separated)")) },
            modifier = Modifier.fillMaxWidth())
        DialogButtons(L.t("Annulla", "Cancel") to onDismiss, main = L.t("Salva", "Save") to {
            onSave(title.trim(), caption.trim(), keywords.split(',').map { it.trim() }.filter { it.isNotEmpty() })
        })
    }
}

/** Nome di un volto: scritto o scelto tra le persone già nella libreria. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun FaceNameDialog(face: LibFace, known: List<String>, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    // un volto senza nome con un suggerimento: la domanda è già la risposta («Forse è Marta?»), basta confermare
    val guess = face.suggest.takeIf { face.name.isEmpty() && it.isNotEmpty() } ?: face.name.takeIf { face.auto }
    var name by remember { mutableStateOf(face.name.ifEmpty { guess ?: "" }) }
    val hits = known.filter { name.isBlank() || it.contains(name.trim(), ignoreCase = true) }.sorted().take(12)
    BlurDialog(onDismiss) {
        DialogTitle(when {
            face.auto -> L.t("È davvero $guess?", "Is it really $guess?")
            guess != null -> L.t("Forse è $guess?", "Is it $guess?")
            else -> L.t("Chi è?", "Who is it?")
        })
        OutlinedTextField(name, { name = it }, label = { Text(L.t("Nome", "Name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (hits.isNotEmpty()) androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            hits.forEach { n -> Pill(n, n == name.trim()) { name = n } }
        }
        tip(L.t("Il nome va in LightCraft quando il PC di casa applica le modifiche: lì LightCraft lo usa per riconoscere la persona nelle altre foto (se hai attivato i volti).",
            "The name goes to LightCraft when the home PC applies the changes: there LightCraft uses it to recognise the person in other photos (if faces are turned on)."),
            L.t("Arriva in LightCraft dal PC di casa.", "Reaches LightCraft through the home PC."))?.let { Text(it, color = C.muted, fontSize = 12.sp) }
        val secondary = buildList {
            if (face.name.isNotEmpty()) add(L.t("Togli il nome", "Remove name") to { onSave("") })
            add(L.t("Annulla", "Cancel") to onDismiss)
        }
        DialogButtons(*secondary.toTypedArray(),
            main = (if (guess != null && name.trim() == guess) L.t("Sì, è $guess", "Yes, it's $guess") else L.t("Salva", "Save")) to {
                if (name.isNotBlank()) onSave(name.trim())
            }, enabled = name.isNotBlank())
    }
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
            val color = when { f.auto -> C.accent; named -> C.accent; guess -> Color(0xFFFBBF24); else -> Color.White.copy(alpha = 0.85f) }
            Box(Modifier.offset(x = (ox + f.x0 * dw).dp, y = (oy + f.y0 * dh).dp)
                .size(((f.x1 - f.x0) * dw).coerceAtLeast(18f).dp, ((f.y1 - f.y0) * dh).coerceAtLeast(18f).dp)
                // nome dato da solo: bordo tratteggiato, da confermare con un tocco
                .then(if (f.auto) Modifier.drawBehind {
                    drawRoundRect(color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
                } else Modifier.border(2.dp, color, RoundedCornerShape(4.dp)))
                .clickable { onTap(f) })
            Box(Modifier.offset(x = (ox + f.x0 * dw).dp, y = (oy + f.y1 * dh + 2).dp)
                .clip(RoundedCornerShape(4.dp)).background(Color(0xB3000000)).clickable { onTap(f) }
                .padding(horizontal = 5.dp, vertical = 1.dp)) {
                Text(when { f.auto -> "${f.name} ·"; named -> f.name; guess -> "${f.suggest}?"; else -> "?" },
                    color = when { named -> Color.White; guess -> Color(0xFFFBBF24); else -> C.muted }, fontSize = 11.sp,
                    maxLines = 1)
            }
        }
    }
}

/** Scelta di cosa scaricare: l'originale o il JPEG della fotocamera. */
@Composable
fun DownloadDialog(count: Int, onDismiss: () -> Unit, onPick: (Boolean) -> Unit) {
    BlurDialog(onDismiss) {
        DialogTitle(L.t("Scarica $count foto sul telefono", "Download $count photos to the phone"))
        DialogText(tip(L.t("Originale: il file così com'è sul NAS (DNG, CR2, JPG…). JPEG: l'anteprima della fotocamera, leggera e pronta da condividere (per i TIF è solo una miniatura). Finiscono in ${DownloadService.FOLDER.trimEnd('/')}.",
            "Original: the file as it is on the NAS (DNG, CR2, JPG…). JPEG: the camera's preview, light and ready to share (for TIFFs only a thumbnail). They go to ${DownloadService.FOLDER.trimEnd('/')}."),
            L.t("In ${DownloadService.FOLDER.trimEnd('/')}.", "To ${DownloadService.FOLDER.trimEnd('/')}.")) ?: "")
        DialogButtons(L.t("Annulla", "Cancel") to onDismiss, "JPEG" to { onPick(true) },
            main = L.t("Originale", "Original") to { onPick(false) })
    }
}

/** Nel cestino di LightCraft: conferma, con la spiegazione di cosa succede ai file. */
@Composable
fun TrashDialog(count: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    BlurDialog(onDismiss) {
        DangerIcon()
        DialogTitle(L.t("Spostare $count foto nel cestino di LightCraft?", "Move $count photos to LightCraft's trash?"))
        DialogText(tip(L.t("Finiscono in «Eliminate di recente» quando il PC di casa applica la richiesta. I file restano sul NAS: al PC puoi ricontrollarle in LightCraft o cancellarle definitivamente con CleanCraftNas.",
            "They go to “Recently Deleted” when the home PC applies the request. The files stay on the NAS: at the PC you can check them in LightCraft or delete them for good with CleanCraftNas."),
            L.t("I file restano sul NAS finché non usi CleanCraftNas.", "The files stay on the NAS until you use CleanCraftNas.")) ?: "")
        DialogButtons(L.t("Annulla", "Cancel") to onDismiss, main = L.t("Nel cestino", "Move to trash") to onConfirm, danger = true)
    }
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
    // senza cartella delle librerie la pagina vuota (in LibraryGallery) dice cosa fare
    if (!lib.configured) return
    Column {
        SearchRow(lib)
        ActiveFilters(lib)
        val idx = lib.index
        if (idx != null) Text(if (lib.filtering || lib.aiActive) L.t("${lib.cells().size} foto", "${lib.cells().size} photos")
            else L.t("${lib.cells().size} elementi", "${lib.cells().size} items"), color = C.muted, fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
    }
}

/** Il titolo della pagina Librerie: la libreria aperta (un tocco per cambiarla), aggiornamento e opzioni. */
@Composable
fun LibraryTitle(lib: LibraryViewModel, onOptions: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 10.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        var open by remember { mutableStateOf(false) }
        Box(Modifier.weight(1f)) {
            Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = lib.libraries.size > 1 || lib.current.isEmpty()) { open = true }
                .padding(vertical = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(lib.current.ifEmpty { L.t("Librerie", "Libraries") }, color = C.text, fontSize = 22.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    if (lib.libraries.size > 1) Icon(Icons.Filled.KeyboardArrowDown, L.t("Cambia libreria", "Change library"),
                        tint = C.text, modifier = Modifier.size(26.dp))
                }
                lib.index?.let { Text(L.t("${it.photos.size} foto · ${it.stacks.size} pile · aggiornata ", "${it.photos.size} photos · ${it.stacks.size} stacks · updated ") +
                    dateText(it.updated), color = C.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            DropdownMenu(open, { open = false }) {
                lib.libraries.forEach { name ->
                    DropdownMenuItem({ Text(name, color = if (name == lib.current) C.accent else C.text) },
                        onClick = { open = false; lib.choose(name) })
                }
            }
        }
        if (lib.configured) IconButton(onClick = { lib.refresh(quiet = false) }, enabled = !lib.loading) {
            if (lib.loading) CircularProgressIndicator(Modifier.size(18.dp), color = C.accent, strokeWidth = 2.dp)
            else Icon(Icons.Filled.Refresh, L.t("Aggiorna", "Refresh"), tint = C.text)
        }
        Box {
            IconButton(onClick = onOptions) { Icon(Icons.Filled.MoreVert, L.t("Opzioni di visualizzazione", "View options"), tint = C.text) }
            // ci sono foto nel cestino: si trovano nel menu ⋮
            if (lib.trashCount > 0) TrashDot(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp))
        }
    }
}

/**
 * Una cella della galleria della Libreria: miniatura con bandierina, etichetta, stelle, pila, ritocco e selezione.
 * `i` e `all`: la posizione della foto e l'elenco che il visualizzatore scorre.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibCell(lib: LibraryViewModel, c: LibraryViewModel.Cell, i: Int, all: List<LibPhoto>,
            onOpen: (List<LibPhoto>, Int) -> Unit, modifier: Modifier) {
    val cells = all
    val p = c.photo
    val chosen = p.id in lib.selected
    // pila appena aperta: le sue foto entrano una dopo l'altra (dissolvenza e leggero ingrandimento)
    val fresh = c.stack != null && !c.cover && c.stack.id == lib.toggledStack &&
        System.currentTimeMillis() - lib.toggledAt < 700
    val appear = remember(p.id, lib.toggledAt) { androidx.compose.animation.core.Animatable(if (fresh) 0f else 1f) }
    if (fresh) LaunchedEffect(p.id, lib.toggledAt) {
        val order = c.stack!!.photos.indexOf(p.id).coerceAtLeast(0)
        kotlinx.coroutines.delay(minOf(order * 30L, 360L))
        appear.animateTo(1f, androidx.compose.animation.core.tween(260))
    }
    Box(modifier.graphicsLayer {
        alpha = appear.value
        val k = 0.85f + 0.15f * appear.value
        scaleX = k; scaleY = k
    }.clip(RoundedCornerShape(2.dp))
        .then(if (chosen) Modifier.border(3.dp, Color.White, RoundedCornerShape(2.dp))
        else if (c.open) Modifier.border(2.dp, C.accent.copy(alpha = 0.7f), RoundedCornerShape(2.dp)) else Modifier)
        .dragSelectTarget(p.id)
        // la pressione lunga (seleziona, e trascinando seleziona anche le altre) la gestisce la galleria: DragSelect
        .combinedClickable(
            onClick = { if (lib.selecting) lib.toggleSelect(p.id) else onOpen(cells, i) })) {
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
        // se la copertina è ritoccata, la matita sta accanto al numero della pila, a sinistra (non sopra)
        // allineati in basso: la matita resta alla stessa altezza che ha sulle foto senza pila (4 dp dal bordo)
        if (c.cover && c.stack != null) Row(Modifier.align(Alignment.BottomEnd).padding(4.dp),
            verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (p.id in lib.developed) EditedMark()
            Box(Modifier.clip(RoundedCornerShape(4.dp)).background(if (c.open) C.accent else Color(0xCC000000))
                .clickable { lib.toggle(c.stack) }.padding(horizontal = 7.dp, vertical = 3.dp)) {
                Text((if (c.open) "▾ " else "▣ ") + c.stack.photos.size, color = if (c.open) Color.Black else Color.White,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (chosen) Box(Modifier.fillMaxSize().background(C.accent.copy(alpha = 0.33f)), contentAlignment = Alignment.Center) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(C.accent), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, null, tint = Color.Black, modifier = Modifier.size(18.dp))
            }
        }
        // ritoccata in LightCraft (l'anteprima è quella sviluppata dal PC)
        if (p.id in lib.developed && !(c.cover && c.stack != null)) EditedMark(Modifier.align(Alignment.BottomEnd).padding(4.dp))
        if (c.cover && c.stack != null && c.stack.restored && c.stack.id !in lib.seenRestored && !c.open)
            Text(L.t("Ripristinate", "Restored"), color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp).clip(RoundedCornerShape(8.dp))
                    .background(C.accent).padding(horizontal = 6.dp, vertical = 2.dp))
        // modifica fatta sul telefono, non ancora in LightCraft: da inviare (arancione) o inviata (azzurro)
        lib.edits[p.id]?.let { SyncMark(it, Modifier.align(Alignment.TopCenter).padding(5.dp)) }
        if (p.rating > 0) Row(Modifier.align(Alignment.BottomStart).padding(4.dp).clip(RoundedCornerShape(3.dp))
            .background(Color(0x99000000)).padding(horizontal = 3.dp, vertical = 1.dp)) {
            repeat(p.rating) { Icon(Icons.Filled.Star, null, tint = C.star, modifier = Modifier.size(10.dp)) }
        }
        // formato del file: una scritta piccola in nero trasparente, come le stelle (sopra le stelle se ci sono). Non sulla
        // copertina di una pila chiusa (lì la pila conta come un tutto); a pila aperta sì, anche sulla prima
        val format = formatBadge(p.name)
        if (format != null && !(c.cover && c.stack != null && !c.open))
            Text(format, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 4.dp, bottom = if (p.rating > 0) 20.dp else 4.dp)
                    .clip(RoundedCornerShape(3.dp)).background(Color(0x99000000)).padding(horizontal = 4.dp, vertical = 1.dp))
    }
}

/** Barra della selezione multipla, fissa in basso (resta raggiungibile anche scorrendo la griglia). */
@Composable
fun LibrarySelectionBar(lib: LibraryViewModel, modifier: Modifier = Modifier) {
    var asking by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var trashing by remember { mutableStateOf(false) }
    var groupTexts by remember { mutableStateOf(false) }
    if (groupTexts) GroupTextsDialog(lib, lib.selected) { groupTexts = false }
    if (asking) DownloadDialog(lib.selected.size, onDismiss = { asking = false }) { jpeg -> asking = false; lib.download(lib.selected, jpeg) }
    if (trashing) TrashDialog(lib.selected.size, onDismiss = { trashing = false }) { trashing = false; lib.trash(lib.selected) }
    if (editing) BlurDialog({ editing = false }) {
        DialogTitle(L.t("Modifica ${lib.selected.size} foto", "Edit ${lib.selected.size} photos"))
        EditControls(lib, lib.selected, null)
        DialogButtons(L.t("Titolo e parole chiave…", "Title and keywords…") to { editing = false; groupTexts = true },
            main = L.t("Fine", "Done") to { editing = false })
    }
    Column(modifier.fillMaxWidth().background(BarBg).navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { lib.clearSelection() }) { Icon(Icons.Filled.Close, L.t("Annulla", "Cancel"), tint = C.text) }
            Text(L.t("${lib.selected.size} selezionate", "${lib.selected.size} selected"), color = C.text, fontSize = 15.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val w = Modifier.weight(1f)
            PillButton(onClick = { editing = true }, modifier = w, pad = 6.dp) { Text(L.t("Modifica", "Edit"), maxLines = 1) }
            PillButton(onClick = { asking = true }, modifier = w, pad = 6.dp) { Text(L.t("Scarica", "Download"), maxLines = 1) }
            // pila: un menu a tendina con le azioni che valgono per la selezione (crea, unisci, copertina, togli)
            val sel = lib.selected
            val stackActions = buildList {
                if (lib.canMerge(sel)) add(L.t("Unisci le pile", "Merge stacks") to { lib.mergeStacks(sel) })
                else if (sel.size >= 2) add(L.t("Crea una pila", "Create a stack") to { lib.stack(sel) })
                if (lib.canMakeCover(sel)) add(L.t("Rendi copertina", "Make cover") to { lib.makeCover(sel.first()) })
                if (lib.canUnstack(sel)) add(L.t("Togli dalla pila", "Remove from stack") to { lib.unstack(sel) })
            }
            Box(w) {
                var open by remember { mutableStateOf(false) }
                PillButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth(), pad = 6.dp, enabled = stackActions.isNotEmpty()) {
                    Icon(Icons.Filled.Menu, null, modifier = Modifier.size(15.dp))
                    Text(" " + L.t("Pila", "Stack"), maxLines = 1)
                }
                androidx.compose.material3.DropdownMenu(open, { open = false }) {
                    stackActions.forEach { (label, act) ->
                        androidx.compose.material3.DropdownMenuItem({ Text(label, color = C.text) }, onClick = { open = false; act() })
                    }
                }
            }
            PillButton(onClick = { trashing = true }, modifier = w, pad = 6.dp, content = Color(0xFFF87171)) { Text(L.t("Cestino", "Trash"), maxLines = 1) }
        }
    }
}

/**
 * Il campo "Cerca", sempre visibile: per soggetto con l'IA (cerca dentro le foto rimaste dopo i filtri). Accanto, il
 * pulsante Filtri con il numero dei filtri attivi.
 */
@Composable
private fun SearchRow(lib: LibraryViewModel) {
    var sheet by remember { mutableStateOf(false) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val ai = lib.aiAvailable
    // ricerca per soggetto: i risultati si calcolano dopo una breve pausa di scrittura
    LaunchedEffect(lib.aiText, lib.aiModelReady, lib.aiPending) { lib.requestAi() }
    Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp)).background(C.card).padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, tint = if (ai) C.accent else C.muted, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            val value = lib.searchField
            androidx.compose.foundation.text.BasicTextField(value, { lib.searchField = it }, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = C.text, fontSize = 15.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(C.accent),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { lib.requestAi(now = true); keyboard?.hide() }),
                modifier = Modifier.weight(1f)) { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(if (ai) L.t("Cerca con l'IA…", "Search with AI…")
                        else L.t("Cerca per nome o parola chiave", "Search by name or keyword"),
                        color = C.muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            }
            if (lib.aiBusy) CircularProgressIndicator(Modifier.padding(horizontal = 6.dp).size(18.dp), strokeWidth = 2.dp, color = C.accent)
            if (value.isNotEmpty()) IconButton(onClick = { lib.searchField = ""; keyboard?.hide() }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Close, L.t("Cancella la ricerca", "Clear the search"), tint = C.text, modifier = Modifier.size(18.dp))
            }
        }
        val n = lib.filterCount
        Row(Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(if (n > 0) C.accent else C.card)
            .clickable { sheet = true }.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Menu, null, tint = if (n > 0) Color.Black else C.text, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(L.t("Filtri", "Filters") + if (n > 0) " $n" else "", color = if (n > 0) Color.Black else C.text, fontSize = 14.sp,
                fontWeight = if (n > 0) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
        }
    }
    if (ai && lib.aiText.isNotBlank() || ai && lib.aiDownload != null) AiSearchBar(lib)
    if (sheet) AdvancedSearchSheet(lib) { sheet = false }
}

/** I filtri attivi, sotto la ricerca: ognuno si toglie con la sua ×, "Azzera" li toglie tutti senza aprire il pannello. */
@Composable
private fun ActiveFilters(lib: LibraryViewModel) {
    if (!lib.filtering) return
    val f = DateTimeFormatter.ofPattern("dd/MM/yy")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(L.t("Azzera", "Clear all"), color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(C.accent).clickable { lib.clearFilters() }
                .padding(horizontal = 12.dp, vertical = 6.dp))
        if (lib.text.isNotBlank()) FilterTag("«${lib.aiText.trim()}»") { lib.text = "" }
        if (lib.stars != StarFilter.ANY) FilterTag(starsText(lib.stars)) { lib.stars = StarFilter.ANY }
        if (lib.dateFrom != null || lib.dateTo != null)
            FilterTag("${lib.dateFrom?.format(f) ?: "…"} – ${lib.dateTo?.format(f) ?: "…"}") { lib.dateFrom = null; lib.dateTo = null }
        lib.labels.forEach { key -> FilterTag(labelName(key), labelColors[key]) { lib.labels = lib.labels - key } }
        if (lib.camera.isNotEmpty()) FilterTag(cameraName(lib.camera)) { lib.camera = "" }
        if (lib.flag.isNotEmpty()) FilterTag(flagText(lib.flag)) { lib.flag = "" }
        if (lib.fileType.isNotEmpty()) FilterTag(fileTypeName(lib.fileType)) { lib.fileType = "" }
    }
}

/** Un filtro attivo: il suo valore e la × per toglierlo. */
@Composable
private fun FilterTag(text: String, dot: Color? = null, onRemove: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(16.dp)).background(C.card).clickable { onRemove() }
        .padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        if (dot != null) { Box(Modifier.size(10.dp).clip(CircleShape).background(dot)); Spacer(Modifier.width(6.dp)) }
        Text(text, color = C.text, fontSize = 13.sp, maxLines = 1)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Filled.Close, L.t("Togli", "Remove"), tint = C.muted, modifier = Modifier.size(15.dp))
    }
}

private fun fileTypeName(k: String) = when (k) { "raw" -> "RAW"; "jpeg" -> "JPEG"; "tiff" -> "TIFF / PSD"; else -> L.t("Tutti", "All") }

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
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp)) {
        val dl = lib.aiDownload
        if (lib.aiPending && lib.aiModelReady) Text(
            if (lib.aiTooShort) L.t("Scrivi almeno 3 lettere (o premi Cerca sulla tastiera)",
                "Type at least 3 letters (or press Search on the keyboard)")
            else L.t("Cerco «${lib.aiText.trim()}»… (premi Cerca sulla tastiera per partire subito)",
                "Searching for “${lib.aiText.trim()}”… (press Search on the keyboard to start right away)"),
            color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        val found = lib.aiFound
        if (lib.aiActive && !lib.aiPending && found != null) Text(
            if (found == 0) L.t("Nessuna foto per «${lib.aiText.trim()}» (prova a dirlo in un altro modo)", "No photos for “${lib.aiText.trim()}” (try saying it another way)")
            else L.t(if (lib.filtering) "$found foto per «${lib.aiText.trim()}» tra quelle filtrate, dalla più somigliante" else "$found foto per «${lib.aiText.trim()}», dalla più somigliante",
                if (lib.filtering) "$found photos for “${lib.aiText.trim()}” among the filtered ones, most similar first" else "$found photos for “${lib.aiText.trim()}”, most similar first"),
            color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        if (lib.aiActive && !lib.aiPending && found != null && lib.aiMore > 0)
            PillButton(onClick = { lib.aiShowMore = true }) {
                Text(L.t("Mostra anche le meno somiglianti (altre ${lib.aiMore})", "Also show less similar ones (${lib.aiMore} more)"))
            }
        if (!lib.aiModelReady) {
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
                PillButton(onClick = { lib.downloadAiModel() }) { Text(L.t("Scarica il modello ($mb MB)", "Download the model ($mb MB)")) }
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
        selectedContainerColor = C.accent, selectedLabelColor = Color.Black, selectedLeadingIconColor = Color.Black)

    @Composable
    fun Section(title: String) = Text(title, color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))

    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onClose, containerColor = C.bg,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        // pulsanti fissi in basso (sopra la tastiera), le scelte scorrono sopra
        Column(Modifier.fillMaxWidth().imePadding()) {
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 8.dp)) {
            Text(L.t("Filtri", "Filters"), color = C.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Section(L.t("Parole chiave, persone, titolo o nome del file", "Keywords, people, title or file name"))
            OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(L.t("es. Marta, compleanno, IMG_1234", "e.g. Marta, birthday, IMG_1234"), fontSize = 13.sp) },
                trailingIcon = { if (text.isNotEmpty()) IconButton(onClick = { text = "" }) { Icon(Icons.Filled.Close, null, tint = C.text) } },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { keyboard?.hide() }))
            if (lib.aiAvailable) Text(L.t("Per cercare cosa c'è nella foto usa il campo Cerca: cerca tra le foto filtrate qui.",
                "To search what's in the photo use the Search field: it searches among the photos filtered here."),
                color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))

            Section(L.t("Stelle", "Stars"))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StarFilter.entries.forEach { f ->
                    RoundChip(stars == f, { stars = f }) { Text(if (f == StarFilter.ANY) L.t("Tutte", "All") else starsText(f), fontSize = 13.sp) }
                }
            }

            Section(L.t("Data di scatto", "Capture date"))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val f = DateTimeFormatter.ofPattern("dd/MM/yyyy")
                RoundChip(from != null || to != null, { pickDates = true }) {
                    Text(if (from == null && to == null) L.t("Tutte le date", "Any date")
                    else "${from?.format(f) ?: "…"} – ${to?.format(f) ?: "…"}", fontSize = 13.sp)
                }
                if (from != null || to != null) PillButton(onClick = { from = null; to = null }) { Text(L.t("Togli", "Remove")) }
            }

            Section(L.t("Etichetta", "Label"))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                labelColors.forEach { (key, color) ->
                    RoundChip(key in labels, { labels = if (key in labels) labels - key else labels + key }, leading = { Box(Modifier.size(12.dp).clip(CircleShape).background(color)) }) { Text(labelName(key), fontSize = 13.sp) }
                }
            }

            if (lib.cameras.size > 1) {
                Section(L.t("Fotocamera", "Camera"))
                var open by remember { mutableStateOf(false) }
                Box {
                    PillButton(onClick = { open = true }) {
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
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("", "pick", "reject", "none").forEach { f ->
                    RoundChip(flag == f, { flag = f }) { Text(flagText(f), fontSize = 13.sp) }
                }
            }

            Section(L.t("Tipo di file", "File type"))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("", "raw", "jpeg", "tiff").map { it to fileTypeName(it) }.forEach { (k, name) ->
                    RoundChip(fileType == k, { fileType = k }) { Text(name, fontSize = 13.sp) }
                }
            }

        }
            Row(Modifier.fillMaxWidth().background(C.bg).padding(horizontal = 20.dp, vertical = 10.dp).navigationBarsPadding(),
                verticalAlignment = Alignment.CenterVertically) {
                PillButton(onClick = {
                    lib.clearFilters()
                    onClose()
                }) { Text(L.t("Azzera i filtri", "Clear filters")) }
                Spacer(Modifier.weight(1f))
                PillButton(onClick = {
                    lib.text = text.trim(); lib.stars = stars; lib.dateFrom = from; lib.dateTo = to
                    lib.labels = labels; lib.camera = camera; lib.flag = flag; lib.fileType = fileType
                    onClose()
                }, primary = true) { Text(L.t("Applica", "Apply")) }
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
    "none" -> L.t("Senza bandierina", "No flag"); else -> L.t("Tutte", "All")
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
        // zoom (fino al 125%: sono anteprime): con due dita o con un doppio tocco; ingrandita, un dito sposta la foto e
        // lo scorrimento tra le foto si ferma finché non si torna al 100%
        var zoom by remember { mutableStateOf(1f) }
        var pan by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        LaunchedEffect(pager.currentPage) { zoom = 1f; pan = androidx.compose.ui.geometry.Offset.Zero }
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { list[it].id }, beyondViewportPageCount = 1,
            userScrollEnabled = zoom <= 1.001f) { page ->
            val current = page == pager.currentPage
            Box(Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { controls = !controls }, onDoubleTap = { at ->
                        if (zoom > 1.001f) { zoom = 1f; pan = androidx.compose.ui.geometry.Offset.Zero }
                        else {
                            zoom = MAX_ZOOM
                            // il punto toccato resta sotto il dito
                            val c = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                            pan = clampPan((c - at) * (MAX_ZOOM - 1f), MAX_ZOOM, size)
                        }
                    })
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val ev = awaitPointerEvent()
                            val fingers = ev.changes.count { it.pressed }
                            if (fingers >= 2) {
                                zoom = (zoom * ev.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                                pan = clampPan(pan + ev.calculatePan(), zoom, size)
                                ev.changes.forEach { it.consume() }
                            } else if (fingers == 1 && zoom > 1.001f) {
                                pan = clampPan(pan + ev.calculatePan(), zoom, size)
                                ev.changes.forEach { it.consume() }
                            }
                        } while (ev.changes.any { it.pressed })
                    }
                }
                .graphicsLayer {
                    if (current) { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y }
                }) {
                RemoteImage(lib, list[page], RemotePreview.FULL, Modifier.fillMaxSize().background(Color.Black),
                    crop = false, original = lib.showOriginal || faceMode)
                if (faceMode) FaceOverlay(lib.effective(list[page])) { naming = it }
            }
        }
        // l'intestazione sopra, e sotto di lei il resto: le informazioni lunghe scorrono senza mai coprire i pulsanti
        if (controls) Column(Modifier.fillMaxSize()) {
            // intestazione: indietro, posizione e data; a destra i pulsanti, tutti tondi della stessa misura e allineati
            // (volti, modificata/originale, cestino in rosso come nel resto dell'app, scarica, informazioni)
            Row(Modifier.fillMaxWidth().background(Color(0xB3000000)).statusBarsPadding()
                .padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, L.t("Indietro", "Back"), tint = C.text)
                }
                Text("${pager.currentPage + 1}/${list.size}  ·  ${dateText(p.captured).substringBefore(' ')}", color = C.text,
                    fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                // ritoccata in LightCraft: com'è adesso (sviluppata dal PC) o com'era allo scatto
                if (p.id in lib.developed && !faceMode) Text(if (lib.showOriginal) L.t("Originale", "Original") else L.t("Modificata", "Edited"),
                    color = if (lib.showOriginal) C.text else C.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(Color(0x33FFFFFF))
                        .clickable { lib.showOriginal = !lib.showOriginal }.padding(horizontal = 12.dp).wrapContentHeight())
                // volti trovati da LightCraft: mostra i riquadri per dare un nome
                if (p.faces.isNotEmpty()) ViewerAction(L.t("Volti", "Faces"), on = faceMode, onClick = { faceMode = !faceMode }) {
                    Icon(Icons.Filled.Face, null, tint = if (faceMode) Color.Black else C.text, modifier = Modifier.size(20.dp))
                }
                ViewerAction(L.t("Nel cestino di LightCraft", "Move to LightCraft's trash"), enabled = !inTrash, onClick = { trashing = true }) {
                    Icon(Icons.Filled.Delete, null, tint = if (inTrash) C.muted else Color(0xFFF87171), modifier = Modifier.size(20.dp))
                }
                ViewerAction(L.t("Scarica sul telefono", "Download to the phone"), onClick = { asking = true }) {
                    DownloadIcon(Modifier.size(20.dp), C.text)
                }
                ViewerAction(L.t("Informazioni", "Info"), on = info, onClick = { info = !info }) {
                    Icon(Icons.Filled.Info, null, tint = if (info) Color.Black else C.text, modifier = Modifier.size(20.dp))
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Bottom) {
            // con le informazioni aperte le stelle e il resto della modifica si nascondono (resta solo l'avviso del cestino)
            if (info) Column(Modifier.weight(1f, fill = false).fillMaxWidth().background(Color(0xE6141414))
                .then(if (inTrash) Modifier else Modifier.navigationBarsPadding())
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
                    (L.t("Nome", "Name") to (p.name + (if (p.copyName.isNotEmpty()) " · ${p.copyName}" else ""))),
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
            if (!info || inTrash) Column(Modifier.fillMaxWidth().background(Color(0xD9141414)).navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (inTrash) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(L.t("Nel cestino di LightCraft (in attesa del PC di casa).", "In LightCraft's trash (waiting for the home PC)."),
                        color = Color(0xFFF87171), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    PillButton(onClick = { lib.untrash(p.id) }) { Text(L.t("Annulla", "Undo")) }
                }
                // foto singola: niente «Nessuna» (si toglie toccando di nuovo stella, etichetta o bandierina accese);
                // lo stato della modifica sta in alto a destra, i testi accanto alle bandierine
                if (!info) EditControls(lib, listOf(p.id), p, showNone = false,
                    status = {
                        val e = lib.edits[p.id]
                        if (e != null) Row(verticalAlignment = Alignment.CenterVertically) {
                            SyncMark(e, size = 14.dp)
                            Text(" " + if (e.sent > 0) L.t("Inviata, in attesa del PC", "Sent, waiting for the PC") else L.t("Da inviare", "To send"),
                                color = syncColor(e), fontSize = 12.sp)
                        }
                    },
                    flagExtra = {
                        PillButton(onClick = { texts = true }, pad = 10.dp) {
                            Icon(Icons.Filled.Edit, null, modifier = Modifier.size(15.dp))
                            Text(" " + L.t("Testi", "Texts"), fontSize = 13.sp)
                        }
                    })
            }
            }
        }
    }
}

/** Lo zoom massimo nel visualizzatore: le foto sono anteprime, oltre si vedrebbero i pixel. */
private const val MAX_ZOOM = 1.25f

/** Lo spostamento di una foto ingrandita, tenuto dentro i bordi (la foto copre sempre lo schermo che copriva). */
private fun clampPan(p: androidx.compose.ui.geometry.Offset, zoom: Float, size: androidx.compose.ui.unit.IntSize): androidx.compose.ui.geometry.Offset {
    val mx = size.width * (zoom - 1f) / 2f
    val my = size.height * (zoom - 1f) / 2f
    return androidx.compose.ui.geometry.Offset(p.x.coerceIn(-mx, mx), p.y.coerceIn(-my, my))
}

/** Pulsante tondo della barra della foto: stessa misura per tutti, acceso (blu) quando la sua funzione è attiva. */
@Composable
private fun ViewerAction(description: String, on: Boolean = false, enabled: Boolean = true, onClick: () -> Unit,
                         icon: @Composable () -> Unit) {
    Box(Modifier.size(36.dp).clip(CircleShape).background(if (on) C.accent else Color(0x33FFFFFF))
        .clickable(enabled = enabled, onClickLabel = description) { onClick() }
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) { icon() }
}

/** Icona «scarica»: una freccia in giù che entra in un vassoio. */
@Composable
fun DownloadIcon(modifier: Modifier, color: Color) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width; val h = size.height; val t = w * 0.11f
        val cap = androidx.compose.ui.graphics.StrokeCap.Round
        drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.08f), androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.62f), t, cap)
        drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.26f, h * 0.40f), androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.64f), t, cap)
        drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.74f, h * 0.40f), androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.64f), t, cap)
        val tray = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.12f, h * 0.66f); lineTo(w * 0.12f, h * 0.90f); lineTo(w * 0.88f, h * 0.90f); lineTo(w * 0.88f, h * 0.66f)
        }
        drawPath(tray, color, style = androidx.compose.ui.graphics.drawscope.Stroke(t, cap = cap,
            join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

/** La scritta del formato: JPEG, DNG, TIFF, PNG, HEIC e i raw delle marche più comuni (CR2, NEF, ARW, ORF, RAF…). */
private val FORMAT_NAMES = mapOf("jpg" to "JPEG", "jpeg" to "JPEG", "tif" to "TIFF", "tiff" to "TIFF") +
    listOf("dng", "png", "heic", "heif", "webp", "psd", "cr2", "cr3", "crw", "nef", "nrw", "arw", "srf", "sr2", "orf",
        "rw2", "raf", "pef", "srw", "x3f", "3fr", "iiq", "rwl", "mos", "erf", "kdc", "mrw").associateWith { it.uppercase() }

fun formatBadge(name: String): String? = FORMAT_NAMES[name.substringAfterLast('.', "").lowercase()]

/** La matita «ritoccata in LightCraft»: tondino scuro trasparente in basso a destra della cella. */
@Composable
private fun EditedMark(modifier: Modifier = Modifier) {
    Box(modifier.size(18.dp).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Edit, L.t("Ritoccata in LightCraft", "Edited in LightCraft"), tint = Color.White,
            modifier = Modifier.size(11.dp))
    }
}
