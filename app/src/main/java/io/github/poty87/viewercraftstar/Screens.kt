package io.github.poty87.viewercraftstar

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------- pezzi comuni

/** L'anteprima della fotocamera, caricata in background; con un preset, la sua resa approssimata (Look). */
@Composable
fun PhotoImage(item: PhotoItem, maxSide: Int, modifier: Modifier, crop: Boolean, presetId: String = "",
               onNote: ((String?) -> Unit)? = null) {
    val context = LocalContext.current
    val bmp by produceState<Bitmap?>(null, item.id, item.modified, maxSide) {
        value = withContext(Dispatchers.IO) { runCatching { DngPreview.load(context, item, maxSide) }.getOrNull() }
    }
    val spec by produceState<Look.Spec?>(null, item.id, presetId) {
        value = if (presetId.isEmpty()) null else withContext(Dispatchers.Default) {
            runCatching { Look.spec(context, DngPreview.style(context, item), presetId) }.getOrNull()
        }
    }
    LaunchedEffect(spec, presetId) { onNote?.invoke(spec?.note) }
    Box(modifier, contentAlignment = Alignment.Center) {
        val b = bmp
        val look = spec
        if (b == null) CircularProgressIndicator(Modifier.size(22.dp), color = C.muted, strokeWidth = 2.dp)
        else Image(b.asImageBitmap(), item.name,
            Modifier.fillMaxSize().then(if (look == null) Modifier else Modifier.graphicsLayer {
                renderEffect = Look.effect(look, size.width, size.height).asComposeRenderEffect()
            }),
            contentScale = if (crop) ContentScale.Crop else ContentScale.Fit)
    }
}

@Composable
fun StarsRow(rating: Int, size: Dp, onClick: ((Int) -> Unit)?) {
    Row(horizontalArrangement = Arrangement.spacedBy(if (onClick != null) 6.dp else 1.dp)) {
        for (i in 1..5) {
            Icon(Icons.Filled.Star, null, tint = if (i <= rating) C.star else C.muted.copy(alpha = 0.35f),
                modifier = Modifier.size(size).let { m -> if (onClick != null) m.clickable { onClick(i) } else m })
        }
    }
}

@Composable
private fun FlagBadge(flag: Flag, size: Dp) {
    if (flag == Flag.NONE) return
    val (fg, bg) = if (flag == Flag.PICK) C.pick else C.reject
    Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Icon(if (flag == Flag.PICK) Icons.Filled.Check else Icons.Filled.Close, null, tint = fg,
            modifier = Modifier.size(size * 0.7f))
    }
}

// ---------------------------------------------------------------- griglia

@Composable
fun GridScreen(vm: AppViewModel, lib: LibraryViewModel, onOpen: (Int) -> Unit, onOpenLib: (List<LibPhoto>, Int) -> Unit,
               onSettings: () -> Unit) {
    vm.vpnAsk?.let { names ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.answerVpn(false) },
            title = { Text(L.t("NAS non raggiungibile", "NAS not reachable")) },
            text = { Text(tip(L.t("${names.size} foto spedite: Synology Drive le caricherà appena può. Per inviare i metadati e sapere quando sono al sicuro a casa serve il NAS: se sei fuori casa, accendi la VPN. L'invio continua in background, anche con l'app chiusa.",
                "${names.size} photos sent: Synology Drive will upload them when it can. Sending the metadata and confirming they're safe at home needs the NAS: if you're away, turn on the VPN. Sending continues in the background, even with the app closed."),
                L.t("Per inviare i metadati serve il NAS: fuori casa accendi la VPN.", "Sending the metadata needs the NAS: away from home, turn on the VPN.")) ?: "") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { vm.answerVpn(true) }) { Text(L.t("Apri VPN", "Open VPN")) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { vm.answerVpn(false) }) { Text(L.t("Più tardi", "Later")) } },
        )
    }
    val list = if (vm.inLibrary) emptyList() else vm.visible
    Box(Modifier.fillMaxSize().background(C.bg)) {
        // una griglia sola: tutto quello che sta in alto è il suo inizio, quindi scorre via con le foto e torna in cima
        LazyVerticalGrid(GridCells.Adaptive(if (vm.inLibrary) 110.dp else 118.dp),
            Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 6.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = if (lib.selecting) 120.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (vm.inLibrary) 5.dp else 6.dp),
            horizontalArrangement = Arrangement.spacedBy(if (vm.inLibrary) 5.dp else 6.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(start = 10.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            TitleText("ViewerCraftStar")
                            if (!vm.inLibrary) Text("${sectionFolder(vm)} · ${vm.countIn(vm.section)} DNG", color = C.muted,
                                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box {
                            IconButton(onClick = { vm.sync() }, enabled = !vm.syncing) {
                                if (vm.syncing) CircularProgressIndicator(Modifier.size(20.dp), color = C.accent, strokeWidth = 2.dp)
                                else Icon(Icons.Filled.Refresh, L.t("Invia al NAS", "Send to NAS"), tint = C.text)
                            }
                            if (vm.pending > 0) Text("${vm.pending}", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 2.dp).clip(CircleShape)
                                    .background(C.star).padding(horizontal = 5.dp))
                        }
                        IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, L.t("Impostazioni", "Settings"), tint = C.text) }
                    }
                    ConnRow(vm)
                    Banner(vm.statusLevel, vm.status, Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
                    SectionTabs(vm)
                    if (vm.inLibrary) LibraryHeader(lib)
                    else {
                        if (vm.section == Section.REVIEW) ReviewHeader(vm) else SentHeader(vm)
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val chips = listOf(
                                Filter.ALL to L.t("Tutte", "All"), Filter.UNRATED to L.t("Da valutare", "Unrated"),
                                Filter.PICKED to L.t("Scelte", "Picked"), Filter.REJECTED to L.t("Scartate", "Rejected"),
                                Filter.STARRED to L.t("Con stelle", "Starred"), Filter.PENDING to L.t("Da inviare", "To send"),
                            )
                            for ((f, label) in chips) {
                                FilterChip(selected = vm.filter == f, onClick = { vm.filter = f },
                                    label = { Text("$label  ${vm.count(f)}", fontSize = 13.sp) },
                                    colors = FilterChipDefaults.filterChipColors(containerColor = C.card, labelColor = C.text,
                                        selectedContainerColor = Color(0xFF253650), selectedLabelColor = Color(0xFFDCE8FB)))
                            }
                        }
                    }
                }
            }
            if (vm.inLibrary) libraryItems(lib, onOpenLib)
            else if (vm.loading) item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.accent) }
            }
            else if (list.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    Text(if (vm.countIn(vm.section) == 0) L.t("Nessun DNG in ${sectionFolder(vm)}", "No DNG files in ${sectionFolder(vm)}")
                    else L.t("Nessuna foto con questo filtro", "No photos match this filter"), color = C.muted)
                }
            }
            else itemsIndexed(list, key = { _, p -> p.id }) { i, p ->
                val m = vm.meta(p.name)
                Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(C.card).clickable { onOpen(i) }) {
                    PhotoImage(p, DngPreview.THUMB, Modifier.fillMaxSize(), crop = true, presetId = m.presetId)
                    if (m.flag == Flag.REJECT) Box(Modifier.fillMaxSize().background(Color(0x99000000)))
                    Box(Modifier.align(Alignment.TopStart).padding(5.dp)) { FlagBadge(m.flag, 20.dp) }
                    m.label?.let { Box(Modifier.align(Alignment.TopEnd).padding(7.dp).size(12.dp).clip(CircleShape).background(Color(it.argb))) }
                    val level = vm.level(p)
                    if (m.rating > 0 || level != Level.NONE) Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0x80000000)).padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (m.rating > 0) StarsRow(m.rating, 11.dp, null)
                        Spacer(Modifier.weight(1f))
                        // foto spedite: casetta verde (in LightCraft), blu (al sicuro sul NAS) o rossa (da completare)
                        if (level != Level.NONE) Icon(Icons.Filled.Home, levelLabel(level), tint = levelColors.getValue(level),
                            modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
        if (vm.inLibrary && lib.selecting) LibrarySelectionBar(lib, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun SectionTabs(vm: AppViewModel) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // la libreria LightCraft di casa
        Box(Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(if (vm.inLibrary) Color(0xFF253650) else C.card)
            .clickable { vm.inLibrary = true }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(L.t("Libreria", "Library"), color = if (vm.inLibrary) Color(0xFFDCE8FB) else C.text, fontSize = 14.sp,
                fontWeight = if (vm.inLibrary) FontWeight.Bold else FontWeight.Normal)
        }
        for ((s, label) in listOf(Section.REVIEW to L.t("Da rivedere", "To review"), Section.SENT to L.t("Spedite", "Sent"))) {
            val on = vm.section == s && !vm.inLibrary
            Box(Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(if (on) Color(0xFF253650) else C.card)
                .clickable { vm.section = s; vm.inLibrary = false }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                Text("$label  ${vm.countIn(s)}", color = if (on) Color(0xFFDCE8FB) else C.text, fontSize = 14.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

/** Dove si trova il NAS: a casa, via VPN, o non raggiungibile (con il bottone per aprire la VPN). Tocco = ricontrolla. */
@Composable
private fun ConnRow(vm: AppViewModel) {
    val (dot, text) = when (vm.conn) {
        Conn.UNKNOWN, Conn.CHECKING -> C.muted to L.t("Controllo il NAS…", "Checking the NAS…")
        Conn.NO_CONFIG -> C.muted to L.t("NAS non impostato (Impostazioni)", "NAS not set up (Settings)")
        Conn.HOME -> Color(0xFF4ADE80) to L.t("NAS raggiungibile · rete di casa", "NAS reachable · home network")
        Conn.VPN -> Color(0xFF4ADE80) to L.t("NAS raggiungibile · via VPN", "NAS reachable · via VPN")
        Conn.VPN_NO_NAS -> Color(0xFFFB923C) to L.t("VPN accesa ma NAS non raggiungibile", "VPN on but NAS not reachable")
        Conn.AWAY -> Color(0xFFFB923C) to L.t("NAS non raggiungibile: fuori casa serve la VPN", "NAS not reachable: away from home you need the VPN")
    }
    Row(Modifier.fillMaxWidth().clickable { vm.checkConnection() }.padding(start = 16.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
        Text(text, color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp).weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (vm.conn == Conn.AWAY || vm.conn == Conn.VPN_NO_NAS)
            androidx.compose.material3.TextButton(onClick = { vm.openVpn() }) { Text(L.t("Apri VPN", "Open VPN"), fontSize = 13.sp) }
    }
}

private fun sectionFolder(vm: AppViewModel) =
    if (vm.section == Section.REVIEW) vm.settings.workFolder else vm.settings.phoneFolder

/** Sezione "Da rivedere": la cartella di lavoro, e il bottone che spedisce tutto nella cartella sincronizzata. */
@Composable
private fun ReviewHeader(vm: AppViewModel) {
    val context = LocalContext.current
    var plan by remember { mutableStateOf<AppViewModel.Shipment?>(null) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val p = plan
        if (r.resultCode == android.app.Activity.RESULT_OK && p != null) vm.ship(p)
        plan = null
    }
    fun proceed() {
        val s = vm.shippable()
        if (s.all.isNotEmpty()) {
            plan = s
            // una sola conferma di Android per spostare e cestinare tutto (non sono file creati da questa app)
            val pi = android.provider.MediaStore.createWriteRequest(context.contentResolver, s.all.map { it.uri })
            launcher.launch(androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender).build())
        }
    }
    val notifAsk = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { proceed() }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        vm.workProblem?.let { Banner("error", it) }
        if (!Tips.compact) Text(L.t("Qui arrivano le foto nuove (cartella non sincronizzata). Quando hai finito, spediscile: passano in ${vm.settings.phoneFolder} e Synology Drive le carica sul NAS.",
            "New photos land here (not synced). When you're done, send them: they move to ${vm.settings.phoneFolder} and Synology Drive uploads them to the NAS."),
            color = C.muted, fontSize = 12.sp)
        val n = vm.countIn(Section.REVIEW)
        val rejected = vm.photos.count { it.inWork && vm.meta(it.name).flag == Flag.REJECT }
        if (n > 0) Button(enabled = !vm.moving, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth(), onClick = {
            if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) notifAsk.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            else proceed()
        }) {
            if (vm.moving) CircularProgressIndicator(Modifier.size(18.dp), color = C.text, strokeWidth = 2.dp)
            else Text(L.t("Spedisci al NAS (${n - rejected})", "Send to the NAS (${n - rejected})"))
        }
        if (rejected > 0) Text(if (Tips.compact) L.t("$rejected scartate → cestino del telefono", "$rejected rejected → phone's bin") else L.t("Le $rejected scartate non vengono spedite: finiscono nel cestino del telefono (recuperabili per 30 giorni).",
            "The $rejected rejected photos aren't sent: they go to the phone's bin (recoverable for 30 days)."),
            color = C.muted, fontSize = 12.sp)
    }
}

private val levelColors = mapOf(Level.GREEN to Color(0xFF4ADE80), Level.BLUE to Color(0xFF60A5FA), Level.RED to Color(0xFFF87171))

private fun levelLabel(l: Level) = when (l) {
    Level.GREEN -> L.t("In LightCraft", "In LightCraft")
    Level.BLUE -> L.t("Al sicuro sul NAS", "Safe on the NAS")
    Level.RED -> L.t("Non ancora sul NAS o da aggiornare", "Not on the NAS yet or to be updated")
    Level.NONE -> ""
}

@Composable
private fun HouseCount(l: Level, n: Int, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Home, null, tint = levelColors.getValue(l), modifier = Modifier.size(16.dp))
        Text(" $n $label", color = C.text, fontSize = 13.sp)
    }
}

/** Sezione "Spedite": casette rossa/blu/verde, stato del PC di casa, ripresa dell'invio e cestino. */
@Composable
private fun SentHeader(vm: AppViewModel) {
    val trash = rememberTrash(vm)
    val safe = vm.visible.filter { vm.isSafe(it) }
    val red = vm.countLevel(Level.RED)
    val blue = vm.countLevel(Level.BLUE)
    val nas = vm.nas
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            HouseCount(Level.GREEN, vm.countLevel(Level.GREEN), L.t("in LightCraft", "in LightCraft"))
            HouseCount(Level.BLUE, blue, L.t("sul NAS", "on the NAS"))
            HouseCount(Level.RED, red, L.t("da completare", "to complete"))
        }
        if (!Tips.compact) Text(L.t("Verde: già in LightCraft. Blu: foto e metadati al sicuro sul NAS, entrano in LightCraft quando il PC di casa è acceso. Rossa: foto o metadati non ancora sul NAS, o da aggiornare. Verdi e blu si possono togliere dal telefono.",
            "Green: already in LightCraft. Blue: photo and metadata safe on the NAS, they enter LightCraft when the home PC is on. Red: photo or metadata not on the NAS yet, or to be updated. Green and blue can be removed from the phone."),
            color = C.muted, fontSize = 12.sp)
        val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.ITALY)
        Text(when {
            nas.pcTs == 0L -> L.t("PC di casa: nessun segnale ancora (serve PresetCraftAppLicator 1.7.0 o successivo).",
                "Home PC: no signal yet (needs PresetCraftAppLicator 1.7.0 or later).")
            nas.pcOn() && nas.pcPhase == "waiting" -> L.t("PC di casa acceso, ma LightCraft è aperto: le foto entrano in libreria quando lo chiudi.",
                "Home PC on, but LightCraft is open: photos enter the library when you close it.")
            nas.pcOn() -> L.t("PC di casa acceso (ultimo segnale ", "Home PC on (last signal ") + fmt.format(java.util.Date(nas.pcTs)) + ")."
            else -> L.t("PC di casa spento o non attivo (ultimo segnale ", "Home PC off or idle (last signal ") + fmt.format(java.util.Date(nas.pcTs)) + ")."
        }, color = C.muted, fontSize = 11.sp)
        if (vm.shipping) Text(L.t("Invio in background in corso (vedi la notifica): puoi chiudere l'app.",
            "Sending in the background (see the notification): you can close the app."), color = C.accent, fontSize = 12.sp)
        else if (red > 0 || (blue > 0 && nas.pcOn())) OutlinedButton(onClick = { vm.resume() }, shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth()) {
            Text(L.t("Riprendi l'invio in background", "Resume sending in the background"))
        }
        if (safe.isNotEmpty()) Button(onClick = { trash(safe.map { it.name }) }, shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth()) {
            Text(L.t("Sposta nel cestino del telefono le foto al sicuro (${safe.size})",
                "Move the safe photos to the phone's bin (${safe.size})"))
        }
    }
}

/**
 * Sposta nel cestino di Android le foto indicate: prima rilegge il resoconto dal NAS e tiene solo quelle ancora
 * confermate a casa, poi Android chiede la conferma. Il cestino le tiene 30 giorni.
 */
@Composable
fun rememberTrash(vm: AppViewModel): (List<String>) -> Unit {
    val context = LocalContext.current
    var count by remember { mutableStateOf(0) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) vm.trashed(count)
    }
    return { names ->
        vm.refreshHome(quiet = false) {
            val items = vm.trashable(names)
            if (items.isNotEmpty()) {
                count = items.size
                val pi = android.provider.MediaStore.createTrashRequest(context.contentResolver, items.map { it.uri }, true)
                launcher.launch(androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender).build())
            }
        }
    }
}

// ---------------------------------------------------------------- visualizzatore

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(vm: AppViewModel, list: List<PhotoItem>, start: Int, onBack: () -> Unit) {
    val pager = rememberPagerState(initialPage = start.coerceIn(0, maxOf(0, list.size - 1))) { list.size }
    var controls by remember { mutableStateOf(true) }
    var info by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val trash = rememberTrash(vm)
    if (list.isEmpty()) { onBack(); return }
    val item = list[pager.currentPage.coerceIn(0, list.size - 1)]
    val m = vm.meta(item.name)
    fun after() {
        if (vm.settings.advance && pager.currentPage < list.size - 1) {
            // passa alla foto successiva dopo una bandierina o una stella
            controlsAdvance = pager.currentPage + 1
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { list[it].id }, beyondViewportPageCount = 1) { page ->
            val photo = list[page]
            ZoomableImage(photo, vm.meta(photo.name).presetId,
                onNote = { n -> if (page == pager.currentPage) note = n }) { controls = !controls }
        }
        LaunchedEffect(controlsAdvance) {
            controlsAdvance?.let { pager.animateScrollToPage(it); controlsAdvance = null }
        }
        if (controls) {
            Row(Modifier.fillMaxWidth().background(Color(0xB3141414)).statusBarsPadding().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L.t("Indietro", "Back"), tint = C.text) }
                Column(Modifier.weight(1f)) {
                    Text(item.name, color = C.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${pager.currentPage + 1} / ${list.size}" + when {
                        !item.inWork && vm.level(item) == Level.GREEN -> L.t(" · in LightCraft", " · in LightCraft")
                        !item.inWork && vm.level(item) == Level.BLUE -> L.t(" · al sicuro sul NAS", " · safe on the NAS")
                        m.error.isNotEmpty() -> L.t(" · errore di invio", " · send error")
                        m.pending && m.notOnNas -> L.t(" · non trovata sul NAS", " · not found on the NAS")
                        m.pending -> L.t(" · da inviare", " · to send")
                        m.sent > 0 -> L.t(" · inviata al NAS", " · sent to NAS")
                        else -> ""
                    }, color = C.muted, fontSize = 12.sp)
                }
                if (vm.isSafe(item)) IconButton(onClick = { trash(listOf(item.name)) }) {
                    Icon(Icons.Filled.Delete, L.t("Sposta nel cestino", "Move to bin"), tint = C.text)
                }
                IconButton(onClick = { info = true }) { Icon(Icons.Filled.Edit, L.t("Informazioni", "Info"), tint = C.text) }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xD9141414))
                .navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    FlagButton(L.t("Scelta", "Pick"), Icons.Filled.Check, m.flag == Flag.PICK, C.pick) {
                        vm.setFlag(item.name, Flag.PICK); after()
                    }
                    FlagButton(L.t("Scartata", "Reject"), Icons.Filled.Close, m.flag == Flag.REJECT, C.reject) {
                        vm.setFlag(item.name, Flag.REJECT); after()
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StarsRow(m.rating, 30.dp) { vm.setRating(item.name, it); after() }
                    Spacer(Modifier.weight(1f))
                    for (label in ColorLabel.entries) {
                        Box(Modifier.padding(start = 8.dp).size(26.dp).clip(CircleShape).background(Color(label.argb))
                            .border(if (m.label == label) 3.dp else 0.dp, C.text, CircleShape)
                            .clickable { vm.setLabel(item.name, label) })
                    }
                }
                OutlinedButton(onClick = { choosing = true }, shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    Text(L.t("Preset: ", "Preset: ") + m.presetName.ifEmpty { L.t("profilo della fotocamera", "camera profile") },
                        maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (m.presetId.isEmpty()) C.text else C.accent)
                }
                if (m.pending && m.notOnNas) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(L.t("Il DNG non è sul NAS: forse non è ancora stato caricato, o è stato cancellato dal NAS.",
                        "The DNG isn't on the NAS: maybe not uploaded yet, or deleted from the NAS."),
                        color = Color(0xFFFB923C), fontSize = 12.sp, modifier = Modifier.weight(1f))
                    androidx.compose.material3.TextButton(onClick = { vm.dropPending(item.name) }) {
                        Text(L.t("Togli dalla coda", "Remove from queue"))
                    }
                }
                if (m.presetId.isNotEmpty()) Text(note ?: L.t("Anteprima approssimata: il risultato vero lo dà LightCraft",
                    "Approximate preview: LightCraft gives the real result"), color = C.muted, fontSize = 11.sp)
                val details = listOf(m.title, m.keywords.joinToString(", ")).filter { it.isNotEmpty() }
                if (details.isNotEmpty()) Text(details.joinToString("  ·  "), color = C.muted, fontSize = 12.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (info) InfoSheet(vm, item) { info = false }
    if (choosing) PresetSheet(vm, item) { choosing = false }
}

/** Scelta del preset: "profilo della fotocamera" (quello che PresetCraftAppLicator dà da solo) o un preset della libreria. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetSheet(vm: AppViewModel, item: PhotoItem, close: () -> Unit) {
    val m = vm.meta(item.name)
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = C.bg) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            TitleText(L.t("Preset", "Preset"))
            Text(L.t("Viene applicato in LightCraft al posto del profilo automatico.",
                "It is applied in LightCraft instead of the automatic profile."), color = C.muted, fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp))
            val picker = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                vm.uploadPresets(uris); close()
            }
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Text(L.t("Importa preset da file…", "Import presets from file…"))
            }
            PresetRow(L.t("Profilo della fotocamera (predefinito)", "Camera profile (default)"), m.presetId.isEmpty()) {
                vm.setPreset(item.name, null); close()
            }
            if (vm.presets.isEmpty()) Text(L.t(
                "L'elenco dei preset arriva dal NAS: imposta il NAS e premi «Prova connessione», con l'importazione automatica di PresetCraftAppLicator attiva.",
                "The preset list comes from the NAS: set up the NAS and tap “Test connection”, with PresetCraftAppLicator's automatic import on."),
                color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
            var group: String? = null
            for (preset in vm.presets) {
                val g = preset.group.ifEmpty { L.t("Altri", "Other") }
                if (g != group) {
                    group = g
                    SectionLabel(g, Modifier.padding(top = 16.dp, bottom = 4.dp))
                }
                PresetRow(preset.name, m.presetId == preset.id) { vm.setPreset(item.name, preset); close() }
            }
        }
    }
}

@Composable
private fun PresetRow(name: String, selected: Boolean, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
        .background(if (selected) Color(0xFF253650) else Color.Transparent).clickable(onClick = click)
        .padding(horizontal = 10.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, color = if (selected) Color(0xFFDCE8FB) else C.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Filled.Check, null, tint = C.accent, modifier = Modifier.size(18.dp))
    }
}

private var controlsAdvance by mutableStateOf<Int?>(null)

@Composable
private fun FlagButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean,
                       colors: Pair<Color, Color>, click: () -> Unit) {
    val (fg, bg) = colors
    OutlinedButton(onClick = click, shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (on) bg else Color.Transparent,
            contentColor = if (on) fg else C.text)) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text)
    }
}

/** Anteprima a tutto schermo: pizzico per ingrandire, doppio tocco per tornare, tocco singolo per i comandi. */
@Composable
private fun ZoomableImage(item: PhotoItem, presetId: String, onNote: (String?) -> Unit, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize()
        .pointerInput(item.id) {
            detectTapGestures(onTap = { onTap() }, onDoubleTap = {
                if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
            })
        }
        .pointerInput(item.id) {
            // un dito su una foto non ingrandita: il gesto resta allo scorrimento tra le foto;
            // due dita: zoom; foto ingrandita: un dito la sposta
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val event = awaitPointerEvent()
                    val fingers = event.changes.count { it.pressed }
                    if (fingers > 1 || scale > 1f) {
                        scale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                        offset = if (scale == 1f) Offset.Zero else offset + event.calculatePan()
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        }
        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)) {
        PhotoImage(item, 2400, Modifier.fillMaxSize(), crop = false, presetId = presetId, onNote = onNote)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfoSheet(vm: AppViewModel, item: PhotoItem, close: () -> Unit) {
    val m = vm.meta(item.name)
    var title by remember(item.name) { mutableStateOf(m.title) }
    var caption by remember(item.name) { mutableStateOf(m.caption) }
    var keywords by remember(item.name) { mutableStateOf(m.keywords.joinToString(", ")) }
    var creator by remember(item.name) { mutableStateOf(m.creator.ifEmpty { vm.settings.defaultCreator }) }
    var copyright by remember(item.name) { mutableStateOf(m.copyright.ifEmpty { vm.settings.defaultCopyright }) }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = C.bg) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TitleText(L.t("Informazioni", "Info"))
            Text(item.name, color = C.muted, fontSize = 12.sp)
            Field(L.t("Titolo", "Title"), title) { title = it }
            Field(L.t("Didascalia", "Caption"), caption, lines = 3) { caption = it }
            Field(L.t("Parole chiave (separate da virgole; a|b = gerarchia)", "Keywords (comma separated; a|b = hierarchy)"),
                keywords) { keywords = it }
            Field(L.t("Autore", "Creator"), creator) { creator = it }
            Field("Copyright", copyright) { copyright = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = close) { Text(L.t("Annulla", "Cancel")) }
                Button(onClick = {
                    vm.update(item.name) {
                        it.copy(title = title.trim(), caption = caption.trim(), creator = creator.trim(), copyright = copyright.trim(),
                            keywords = keywords.split(',').map(String::trim).filter(String::isNotEmpty).distinct())
                    }
                    close()
                }) { Text(L.t("Salva", "Save")) }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, lines: Int = 1, password: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
        singleLine = lines == 1, minLines = lines,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = if (password) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default)
}

// ---------------------------------------------------------------- impostazioni

@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val s = vm.settings
    val nas = remember { s.nas }
    var host by remember { mutableStateOf(nas.host) }
    var share by remember { mutableStateOf(nas.share) }
    var folder by remember { mutableStateOf(nas.folder) }
    var sub by remember { mutableStateOf(nas.subfolders) }
    var user by remember { mutableStateOf(nas.user) }
    var password by remember { mutableStateOf(nas.password) }
    var phone by remember { mutableStateOf(s.phoneFolder) }
    var work by remember { mutableStateOf(s.workFolder) }
    var creator by remember { mutableStateOf(s.defaultCreator) }
    var copyright by remember { mutableStateOf(s.defaultCopyright) }
    var advance by remember { mutableStateOf(s.advance) }
    var compact by remember { mutableStateOf(s.compactTips) }
    var notifyLc by remember { mutableStateOf(s.notifyLightCraft) }
    var libShare by remember { mutableStateOf(s.libShare) }
    var libFolder by remember { mutableStateOf(s.libFolder) }
    var notifyEnd by remember { mutableStateOf(s.notifyEnd) }
    var test by remember { mutableStateOf("" to "info") }

    fun config() = NasConfig(host, share, folder, sub, user, password)
    fun save() {
        s.nas = config(); s.phoneFolder = phone; s.workFolder = work; s.defaultCreator = creator; s.defaultCopyright = copyright; s.advance = advance
        s.notifyLightCraft = notifyLc; s.notifyEnd = notifyEnd
        s.compactTips = compact; Tips.compact = compact
        s.libShare = libShare; s.libFolder = libFolder
    }

    Column(Modifier.fillMaxSize().background(C.bg).statusBarsPadding().navigationBarsPadding()
        .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { save(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = C.text) }
            TitleText(L.t("Impostazioni", "Settings"))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(compact, { compact = it })
            Text(L.t("Riduci consigli interfaccia (solo testi brevi)", "Fewer interface tips (short texts only)"), color = C.text)
        }
        SectionLabel(L.t("Cartelle dei DNG sul telefono", "DNG folders on the phone"), Modifier.padding(top = 8.dp))
        Field(L.t("Cartella di lavoro (dove arrivano le foto nuove, NON sincronizzata)", "Work folder (where new photos land, NOT synced)"), work) { work = it }
        Field(L.t("Cartella sincronizzata con il NAS da Synology Drive", "Folder synced to the NAS by Synology Drive"), phone) { phone = it }
        if (!Tips.compact) Text(L.t("Percorsi nella memoria interna. Se la cartella di lavoro manca, l'app la crea; se c'è già non la tocca.",
            "Paths in internal storage. If the work folder is missing the app creates it; if it exists it's left as is."),
            color = C.muted, fontSize = 12.sp)

        SectionLabel("NAS", Modifier.padding(top = 12.dp))
        if (!Tips.compact) Text(L.t("Gli .xmp vengono scritti accanto ai DNG già caricati da Synology Drive. Sul NAS l'app scrive solo file .xmp: non cancella e non modifica mai le foto.",
            "The .xmp files are written next to the DNG files Synology Drive has already uploaded. On the NAS the app only writes .xmp files: it never deletes or changes photos."),
            color = C.muted, fontSize = 12.sp)
        Field(L.t("Indirizzo IP del NAS (es. 192.168.1.10)", "NAS IP address (e.g. 192.168.1.10)"), host) { host = it }
        Field(L.t("Cartella condivisa (es. Foto)", "Shared folder (e.g. Photos)"), share) { share = it }
        Field(L.t("Cartella dentro la condivisione (es. Catalogo)", "Folder inside the share (e.g. Catalog)"), folder) { folder = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(sub, { sub = it })
            Text(L.t("Cerca i DNG anche nelle sottocartelle", "Also look for DNG files in subfolders"), color = C.text)
        }
        Field(L.t("Utente del NAS", "NAS user"), user) { user = it }
        Field(L.t("Password", "Password"), password, password = true) { password = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                save(); test = L.t("Provo la connessione…", "Testing the connection…") to "info"
                vm.testNas(config()) { msg, ok -> test = msg to if (ok) "ok" else "error" }
            }) { Text(L.t("Prova connessione", "Test connection")) }
        }
        Banner(test.second, test.first)

        SectionLabel(L.t("Libreria di casa", "Home library"), Modifier.padding(top = 12.dp))
        if (!Tips.compact) Text(L.t("La cartella del NAS in cui PresetCraftAppLicator scrive le librerie da consultare sul telefono (Importazione automatica › Librerie da consultare sul telefono). Usa lo stesso NAS, utente e password di sopra.",
            "The NAS folder where PresetCraftAppLicator writes the libraries to browse on the phone (Automatic import › Libraries to browse on the phone). Same NAS, user and password as above."),
            color = C.muted, fontSize = 12.sp)
        Field(L.t("Cartella condivisa (vuota = la stessa di sopra)", "Shared folder (empty = the same as above)"), libShare) { libShare = it }
        Field(L.t("Cartella delle librerie dentro la condivisione (es. ViewerCraftStar)", "Libraries folder inside the share (e.g. ViewerCraftStar)"), libFolder) { libFolder = it }

        SectionLabel(L.t("Notifiche della spedizione", "Sending notifications"), Modifier.padding(top = 12.dp))
        if (!Tips.compact) Text(L.t("Dopo «Spedisci al NAS» una notifica mostra quante foto, con i loro metadati, sono già al sicuro sul NAS.",
            "After “Send to the NAS” a notification shows how many photos, with their metadata, are already safe on the NAS."),
            color = C.muted, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(notifyLc, { notifyLc = it })
            Text(L.t("Segui anche l'ingresso in LightCraft (solo con il PC di casa acceso: la notifica resta finché non sono entrate)",
                "Also follow their arrival in LightCraft (only with the home PC on: the notification stays until they are in)"),
                color = C.text)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(notifyEnd, { notifyEnd = it })
            Text(L.t("Avvisami quando la spedizione è finita o sospesa", "Tell me when sending is finished or paused"), color = C.text)
        }

        SectionLabel(L.t("Valori predefiniti", "Defaults"), Modifier.padding(top = 12.dp))
        Field(L.t("Autore", "Creator"), creator) { creator = it }
        Field("Copyright", copyright) { copyright = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(advance, { advance = it })
            Text(L.t("Passa alla foto successiva dopo una bandierina o una stella", "Go to the next photo after a flag or a star"),
                color = C.text)
        }
        Text("ViewerCraftStar ${BuildConfigInfo.version}", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp))
        Spacer(Modifier.height(24.dp))
    }
}
