package io.github.poty87.viewercraftstar

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Miniature lette dal disco tre alla volta (USB: più insieme non va più veloce). */
private val diskThumbGate = Semaphore(3)

/**
 * Importa da un disco esterno collegato al telefono (chiavetta, disco USB, lettore di schede): come la pagina della
 * fotocamera (stessi filtri, raggruppamento, selezione, scarico in background), cambia solo da dove arrivano le foto.
 * Il disco si apre da solo se Android l'ha già concesso una volta; altrimenti si apre il selettore di Android, già sul
 * disco collegato se l'app lo riconosce.
 */
@Composable
fun DiskScreen(vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var tree by remember { mutableStateOf<Uri?>(null) }
    var label by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<ExternalDisk.Item>?>(null) }
    var step by remember { mutableStateOf(L.t("Scegli il disco nella finestra di Android…", "Choose the disk in Android's window…")) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val progress = CameraImport.progress
    val bytesNow = CameraImport.bytesNow
    val downloading = CameraImport.running
    val done = CameraImport.done
    LaunchedEffect(Unit) { CameraImport.reset() }
    BackHandler { onBack() }

    // il selettore di cartelle di Android; annullato la prima volta (nessun disco aperto) si torna indietro
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data
        if (r.resultCode == Activity.RESULT_OK && uri != null) { ExternalDisk.keep(context, uri); tree = uri }
        else if (tree == null) onBack()
    }
    fun choose() {
        val vol = ExternalDisk.volumes(context).firstOrNull()
        runCatching { pick.launch(ExternalDisk.pickIntent(vol)) }
            .onFailure { error = L.t("Il selettore di cartelle di Android non si apre.", "Android's folder picker doesn't open.") }
    }
    LaunchedEffect(Unit) {
        val vols = ExternalDisk.volumes(context)
        // già concesso una volta per un disco collegato adesso: si apre da solo
        val granted = vols.firstNotNullOfOrNull { ExternalDisk.grantedTree(context, it) }
        if (granted != null) tree = granted
        else {
            if (vols.isEmpty()) Notices.post("info", L.t("Non vedo dischi esterni collegati: scegli tu la cartella.",
                "I can't see an external disk: choose the folder yourself."))
            choose()
        }
    }
    LaunchedEffect(tree) {
        val t = tree ?: return@LaunchedEffect
        items = null; selected = emptySet(); error = null
        label = ExternalDisk.label(context, t)
        step = L.t("Cerco le foto…", "Looking for photos…")
        runCatching {
            withContext(Dispatchers.IO) {
                ExternalDisk.list(context, t) { n -> step = L.t("Cerco le foto… ($n trovate)", "Looking for photos… ($n found)") }
            }
        }.onSuccess { list -> items = list.sortedWith(compareByDescending<ExternalDisk.Item> { it.modified }.thenByDescending { it.name }) }
            .onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = L.t("Il disco non si legge: è stato staccato? ", "The disk can't be read: was it unplugged? ") + (e.message ?: "")
            }
    }

    // già sul telefono o sul NAS: come per la fotocamera (con o senza i millisecondi di GR World davanti al nome)
    val known = remember(vm.photos, vm.nas) {
        (vm.photos.map { it.name.lowercase() } + vm.nas.names.map { it.lowercase() })
            .flatMap { listOf(it, it.replace(Regex(""" \([0-9]+\)(?=\.[^.]+$)"""), "")) }.toHashSet()
    }
    val knownShort = remember(known) { known.map { it.replace(Regex("^[0-9]{10,}_"), "") }.toHashSet() }
    fun have(p: ExternalDisk.Item) = p.name.lowercase() in knownShort
    var groupBy by remember { mutableStateOf("day") }
    var format by remember { mutableStateOf(vm.settings.cameraFormat) }
    val all = items.orEmpty()
    // i formati che Android non sa mettere tra le foto (CR3 su Android 17): non si importano dal telefono, solo dal PC
    var refused by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(items) {
        val exts = items.orEmpty().map { it.name }.distinctBy { RawFormats.ext(it) }
        refused = withContext(Dispatchers.IO) { exts.filterNot { RawFormats.phoneAccepts(context, it) }.map { RawFormats.ext(it) }.toSet() }
    }
    val importable = all.filter { RawFormats.ext(it.name) !in refused }
    val notHere = (all - importable.toSet()).groupBy { RawFormats.ext(it.name).uppercase() }
    val list = importable.filter { if (format == "jpg") it.isJpeg else it.isRaw }
    fun twinOf(d: ExternalDisk.Item) = all.firstOrNull { it.isJpeg && it.dir == d.dir &&
        it.name.substringBeforeLast('.').equals(d.name.substringBeforeLast('.'), true) }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 10.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L.t("Indietro", "Back"), tint = C.text) }
            Column(Modifier.weight(1f)) {
                TitleText(L.t("Disco esterno", "External disk"))
                if (label.isNotEmpty()) Text(label, color = C.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (tree != null && !downloading) RoundChip(false, { choose() }) { Text(L.t("Cambia", "Change")) }
        }
        val err = error
        when {
            err != null -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(err, color = Color(0xFFF87171), fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(onClick = onBack) { Text(L.t("Chiudi", "Close")) }
                    PillButton(onClick = { error = null; choose() }) { Text(L.t("Scegli la cartella", "Choose the folder")) }
                }
            }
            items == null -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = C.accent, strokeWidth = 2.dp)
                Spacer(Modifier.size(14.dp))
                Text(step, color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 24.dp))
            }
            all.isEmpty() -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                EmptyState(EmptyGlyph.CARD, L.t("Nessuna foto", "No photos"),
                    L.t("In questa cartella non ci sono raw o JPEG.", "There are no raw or JPEG files in this folder."))
                Spacer(Modifier.size(12.dp))
                PillButton(onClick = { choose() }) { Text(L.t("Scegli un'altra cartella", "Choose another folder")) }
            }
            else -> {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val formatLabels = listOf("dng" to L.t("Solo RAW", "RAW only"), "jpg" to L.t("Solo JPEG", "JPEG only"), "both" to "RAW+JPEG")
                    Box {
                        var open by remember { mutableStateOf(false) }
                        RoundChip(false, { open = true }, leading = { Icon(Icons.Filled.Menu, null, modifier = Modifier.size(16.dp)) }) {
                            Text(L.t("Formato", "Format"))
                        }
                        androidx.compose.material3.DropdownMenu(open, { open = false }) {
                            for ((k, l) in formatLabels) {
                                val on = format == k
                                androidx.compose.material3.DropdownMenuItem({ Text(l, color = if (on) C.accent else C.text) },
                                    onClick = { format = k; vm.settings.cameraFormat = k; selected = emptySet(); open = false },
                                    leadingIcon = { androidx.compose.material3.RadioButton(on, null,
                                        colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = C.accent)) })
                            }
                        }
                    }
                    val groupLabels = listOf("" to L.t("Tutte insieme", "All together"), "day" to L.t("Per giorno", "By day"),
                        "month" to L.t("Per mese", "By month"), "year" to L.t("Per anno", "By year"))
                    Box {
                        var open by remember { mutableStateOf(false) }
                        RoundChip(groupBy.isNotEmpty(), { open = true }, leading = { Icon(Icons.Filled.Menu, null, modifier = Modifier.size(16.dp)) }) {
                            Text(L.t("Ordina", "Sort"))
                        }
                        androidx.compose.material3.DropdownMenu(open, { open = false }) {
                            for ((k, l) in groupLabels) {
                                val on = groupBy == k
                                androidx.compose.material3.DropdownMenuItem({ Text(l, color = if (on) C.accent else C.text) },
                                    onClick = { groupBy = k; open = false },
                                    leadingIcon = { androidx.compose.material3.RadioButton(on, null,
                                        colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = C.accent)) })
                            }
                        }
                    }
                    if (selected.isNotEmpty()) RoundChip(false, { selected = emptySet() }) { Text(L.t("Nessuna", "None")) }
                }
                Text(L.t("${list.size} foto sul disco", "${list.size} photos on the disk") +
                    L.t(" · scure = già sul telefono o sul NAS", " · dark = already on the phone or the NAS"),
                    color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                if (notHere.isNotEmpty()) {
                    val what = notHere.entries.joinToString(", ") { (ext, l) -> "${l.size} $ext" }
                    Text(L.t("$what: questo telefono non li riconosce come foto, quindi non si importano da qui. Copiali sul NAS dal PC: PresetCraftAppLicator li importa in LightCraft e li vedi nella Libreria.",
                        "$what: this phone doesn't recognise them as photos, so they can't be imported from here. Copy them to the NAS from the PC: PresetCraftAppLicator imports them into LightCraft and you see them in the Library."),
                        color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
                }
                // i gruppi per periodo: dalla data del file (sul disco è quella dello scatto, se copiato così com'era)
                val groups = remember(list, groupBy) {
                    if (groupBy.isEmpty()) listOf("" to list)
                    else list.groupBy { p ->
                        if (p.modified <= 0) "" else {
                            val dt = java.time.Instant.ofEpochMilli(p.modified).atZone(java.time.ZoneId.systemDefault())
                            val pattern = when (groupBy) { "day" -> L.t("EEEE d MMMM yyyy", "EEEE, MMMM d, yyyy"); "month" -> "MMMM yyyy"; else -> "yyyy" }
                            dt.format(java.time.format.DateTimeFormatter.ofPattern(pattern, L.locale)).replaceFirstChar { it.uppercase() }
                        }
                    }.toList()
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.fillMaxSize().padding(horizontal = 6.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 180.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((title, group) in groups) {
                            if (groupBy.isNotEmpty()) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }, key = "h-$title") {
                                val keys = group.map { it.key }
                                val allOn = keys.isNotEmpty() && selected.containsAll(keys)
                                Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(title.ifEmpty { L.t("Senza data", "No date") }, color = C.text, fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    Text("${group.size}  ", color = C.muted, fontSize = 13.sp)
                                    androidx.compose.material3.Checkbox(allOn, { selected = if (allOn) selected - keys.toSet() else selected + keys },
                                        colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = C.accent,
                                            checkmarkColor = Color.Black, uncheckedColor = C.muted))
                                }
                            }
                            items(group, key = { it.key }) { p ->
                                val on = p.key in selected
                                val had = have(p)
                                Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(C.card)
                                    .then(if (on) Modifier.border(3.dp, Color.White, RoundedCornerShape(6.dp)) else Modifier)
                                    .clickable { selected = if (on) selected - p.key else selected + p.key }) {
                                    DiskThumb(p)
                                    if (had && !on && p.key !in done) Box(Modifier.fillMaxSize().background(Color(0x99000000)))
                                    Text(p.name + if (p.modified > 0) "  " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.ITALY)
                                        .format(java.util.Date(p.modified)) else "", color = Color.White, fontSize = 10.sp, maxLines = 1,
                                        modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0x80000000))
                                            .padding(horizontal = 4.dp, vertical = 2.dp))
                                    val ok = p.key in done
                                    if (on || ok) Box(Modifier.fillMaxSize().background((if (ok) GREEN else C.accent).copy(alpha = 0.33f)), contentAlignment = Alignment.Center) {
                                        Box(Modifier.size(26.dp).clip(CircleShape).background(if (ok) GREEN else C.accent), contentAlignment = Alignment.Center) {
                                            Icon(Icons.Filled.Check, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                    // scatto RAW+JPEG: «+JPEG» in alto a sinistra, verde quando il JPEG è nella galleria
                                    if (format == "both" && p.isRaw) twinOf(p)?.let { twin ->
                                        Text("+JPEG", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                            modifier = Modifier.align(Alignment.TopStart).padding(5.dp).clip(RoundedCornerShape(50))
                                                .background(if (twin.key in done) GREEN else C.accent).padding(horizontal = 6.dp, vertical = 1.dp))
                                    }
                                }
                            }
                        }
                    }
                    val pr = progress
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(BarBg).navigationBarsPadding().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (pr != null) {
                            val pct = (pr.third * 100).toInt()
                            Text(L.t("Foto ${pr.first} di ${pr.second} · $pct%", "Photo ${pr.first} of ${pr.second} · $pct%") +
                                (bytesNow?.let { (got, tot) -> "  (${got / 1_000_000} / ${tot / 1_000_000} MB)" } ?: ""),
                                color = C.text, fontSize = 13.sp)
                            val total = ((pr.first - 1 + pr.third) / pr.second).coerceIn(0f, 1f)
                            LinearProgressIndicator(progress = { pr.third }, color = if (pr.third >= 1f) GREEN else C.accent,
                                trackColor = C.card, modifier = Modifier.fillMaxWidth())
                            Text(L.t("Totale ${(total * 100).toInt()}%", "Total ${(total * 100).toInt()}%"), color = C.muted, fontSize = 12.sp)
                            LinearProgressIndicator(progress = { total }, color = if (total >= 1f) GREEN else C.accent,
                                trackColor = C.card, modifier = Modifier.fillMaxWidth())
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            PillButton(onClick = onBack, enabled = !downloading, modifier = Modifier.weight(1f)) {
                                Text(L.t("Chiudi", "Close"), maxLines = 1)
                            }
                            PillButton(onClick = {
                                val chosenNow = list.filter { it.key in selected }
                                val twins = if (format != "both") emptyList() else chosenNow.filter { it.isRaw }.mapNotNull { twinOf(it) }
                                val todo = (chosenNow + twins).distinctBy { it.key }
                                CameraImport.start(context, disk = true) {
                                    var ok = 0
                                    var skipped = 0 // JPEG già nella galleria
                                    var skippedDng = 0 // DNG già sul telefono o sul NAS (con la stessa ora di scatto)
                                    todo.forEachIndexed { i, p ->
                                        CameraImport.progress = Triple(i + 1, todo.size, 0f)
                                        val ok1 = withContext(Dispatchers.IO) {
                                            val taken = ExternalDisk.taken(context, p.uri)
                                            val phoneName = if (taken > 0) "${taken}_${p.name}" else p.name
                                            val folder = if (p.isJpeg) GrCamera.GALLERY else vm.settings.workFolder
                                            if (folder == GrCamera.GALLERY && GrCamera.existsOnPhone(context, folder, phoneName)) {
                                                skipped++
                                                return@withContext true
                                            }
                                            if (folder != GrCamera.GALLERY && taken > 0 && phoneName.lowercase() in known) {
                                                skippedDng++
                                                return@withContext true
                                            }
                                            var shown = -1
                                            ExternalDisk.copy(context, p, folder, taken) { got, total ->
                                                val s = if (total > 0) (got * 100 / total).toInt() else (got / 1_000_000).toInt()
                                                if (s != shown) {
                                                    shown = s
                                                    CameraImport.bytesNow = got to total
                                                    CameraImport.progress = Triple(i + 1, todo.size, if (total > 0) got.toFloat() / total else 0f)
                                                }
                                            }
                                        }
                                        if (ok1) { ok++; CameraImport.progress = Triple(i + 1, todo.size, 1f); CameraImport.done = CameraImport.done + p.key }
                                    }
                                    selected = selected - CameraImport.done
                                    vm.reload()
                                    val got = ok - skipped - skippedDng
                                    val wanted = todo.size - skipped - skippedDng
                                    if (wanted > 0) Notices.post(if (got == wanted) "ok" else "warn",
                                        L.t("$got di $wanted file copiati dal disco: i raw in Da rivedere" + (if (todo.any { it.isJpeg }) ", i JPEG nella galleria del telefono." else "."),
                                            "$got of $wanted files copied from the disk: the raws are in To review" + (if (todo.any { it.isJpeg }) ", the JPEGs in the phone's gallery." else ".")))
                                    if (skipped > 0) Notices.post("info",
                                        L.t(if (skipped == 1) "1 JPEG era già nella galleria del telefono e non è stato importato di nuovo."
                                            else "$skipped JPEG erano già nella galleria del telefono e non sono stati importati di nuovo.",
                                            if (skipped == 1) "1 JPEG was already in the phone's gallery and wasn't imported again."
                                            else "$skipped JPEGs were already in the phone's gallery and weren't imported again."))
                                    if (skippedDng > 0) Notices.post("info",
                                        L.t(if (skippedDng == 1) "1 raw era già sul telefono o sul NAS e non è stato copiato di nuovo."
                                            else "$skippedDng raw erano già sul telefono o sul NAS e non sono stati copiati di nuovo.",
                                            if (skippedDng == 1) "1 raw was already on the phone or the NAS and wasn't copied again."
                                            else "$skippedDng raws were already on the phone or the NAS and weren't copied again."))
                                }
                            }, primary = true, enabled = selected.isNotEmpty() && !downloading, modifier = Modifier.weight(1f)) {
                                Text(L.t("Importa ${selected.size}", "Import ${selected.size}"))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** La miniatura di una foto del disco. */
@Composable
private fun DiskThumb(p: ExternalDisk.Item) {
    val context = LocalContext.current
    val bmp by produceState<Bitmap?>(null, p.key) {
        value = withContext(Dispatchers.IO) { diskThumbGate.withPermit { ExternalDisk.thumbnail(context, p) } }
    }
    bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
}

/** Il disco esterno per il menu del tondo di Importa: una chiavetta USB (corpo, connettore con i due contatti). */
@Composable
fun UsbDiskIcon(modifier: Modifier, color: Color) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.08f)
        drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(w * 0.26f, h * 0.36f),
            size = androidx.compose.ui.geometry.Size(w * 0.48f, h * 0.56f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f), style = stroke)
        drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(w * 0.34f, h * 0.10f),
            size = androidx.compose.ui.geometry.Size(w * 0.32f, h * 0.26f), style = stroke)
        drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(w * 0.41f, h * 0.17f), size = androidx.compose.ui.geometry.Size(w * 0.06f, h * 0.06f))
        drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(w * 0.53f, h * 0.17f), size = androidx.compose.ui.geometry.Size(w * 0.06f, h * 0.06f))
    }
}

/** Il tondo di Importa: una foto con il + blu (da dove si importa lo dice il menu: fotocamera o disco esterno). */
@Composable
fun ImportPlusIcon(modifier: Modifier, color: Color) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.08f)
        drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(w * 0.12f, h * 0.26f),
            size = androidx.compose.ui.geometry.Size(w * 0.70f, h * 0.58f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.10f), style = stroke)
        // montagnola e sole dentro la foto
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.18f, h * 0.78f); lineTo(w * 0.38f, h * 0.54f); lineTo(w * 0.52f, h * 0.68f)
            lineTo(w * 0.62f, h * 0.60f); lineTo(w * 0.76f, h * 0.78f); close()
        }
        drawPath(p, color)
        drawCircle(color, radius = w * 0.06f, center = androidx.compose.ui.geometry.Offset(w * 0.30f, h * 0.42f))
        // il + in un tondo, in alto a destra (come prima sulla fotocamera)
        val c = androidx.compose.ui.geometry.Offset(w * 0.84f, h * 0.24f)
        drawCircle(C.accent, radius = w * 0.20f, center = c)
        val a = w * 0.11f
        val t = w * 0.06f
        drawLine(Color.Black, c.copy(x = c.x - a), c.copy(x = c.x + a), strokeWidth = t)
        drawLine(Color.Black, c.copy(y = c.y - a), c.copy(y = c.y + a), strokeWidth = t)
    }
}
