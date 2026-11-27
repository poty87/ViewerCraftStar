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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.lifecycle.repeatOnLifecycle
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
import kotlinx.coroutines.launch
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
fun GridScreen(vm: AppViewModel, lib: LibraryViewModel, gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
               libState: androidx.compose.foundation.lazy.LazyListState,
               onOpen: (Int) -> Unit, onOpenLib: (List<LibPhoto>, Int) -> Unit, onCamera: () -> Unit = {},
               onDisk: () -> Unit = {}) {
    var options by remember { mutableStateOf(false) }
    if (options) GalleryOptionsSheet(lib) { options = false }
    vm.vpnAsk?.let { names ->
        BlurDialog({ vm.answerVpn(false) }) {
            DialogTitle(L.t("NAS non raggiungibile", "NAS not reachable"))
            DialogText(tip(L.t("${names.size} foto spedite: l'app di sincronizzazione le caricherà appena può. Per inviare i metadati e sapere quando sono al sicuro a casa serve il NAS: se sei fuori casa, accendi la VPN. L'invio continua in background, anche con l'app chiusa.",
                "${names.size} photos sent: the sync app will upload them when it can. Sending the metadata and confirming they're safe at home needs the NAS: if you're away, turn on the VPN. Sending continues in the background, even with the app closed."),
                L.t("Per inviare i metadati serve il NAS: fuori casa accendi la VPN.", "Sending the metadata needs the NAS: away from home, turn on the VPN.")) ?: "")
            DialogButtons(L.t("Più tardi", "Later") to { vm.answerVpn(false) }, main = L.t("Accendi VPN", "Turn on VPN") to { vm.answerVpn(true) })
        }
    }
    val list = if (vm.inLibrary) emptyList() else vm.visible
    val header: @Composable () -> Unit = {
        if (vm.inLibrary) Column {
            // Librerie: il titolo è la libreria aperta (l'icona in basso dice già in che pagina sei)
            LibraryTitle(lib) { options = true }
            ConnRow(vm)
            LibraryHeader(lib)
        }
        else Column {
            // Importa: in testa solo il collegamento al NAS e l'invio (cartelle e conteggi stanno in Impostazioni e nelle schede)
            // l'aggiornamento è il tondo verde in basso a sinistra in «Inviate» (e parte da solo)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { ConnRow(vm) }
            }
            SectionTabs(vm)
            run {
                if (vm.section == Section.REVIEW) ReviewHeader(vm) else SentHeader(vm)
                ImportFilters(vm)
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (vm.inLibrary && lib.showTrash) LibraryTrash(lib, Modifier.statusBarsPadding())
        else if (vm.inLibrary) LibraryGallery(lib, libState, header, onOpenLib, Modifier.statusBarsPadding())
        else LazyVerticalGrid(GridCells.Adaptive(118.dp),
            Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 6.dp), state = gridState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp + LocalBarSpace.current),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) { header() }
            if (vm.loading) item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.accent) }
            }
            // pagina vuota al centro, al posto delle foto
            else if (list.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                when {
                    vm.countIn(vm.section) > 0 -> EmptyStateFill(EmptyGlyph.SEARCH, L.t("Nessuna foto con questi filtri", "No photos match these filters"),
                        L.t("Prova a toglierne qualcuno.", "Try removing some."))
                    vm.section == Section.REVIEW -> EmptyStateFill(EmptyGlyph.DONE, L.t("Tutto fatto", "All done"),
                        L.t("Nessuna foto da rivedere. Importa le nuove dalla fotocamera o da un disco esterno con il tondo in basso a sinistra.",
                            "No photos to review. Import new ones from the camera or an external disk with the round button at the bottom left."))
                    else -> EmptyStateFill(EmptyGlyph.SENT, L.t("Niente in viaggio", "Nothing on its way"),
                        L.t("Le foto spedite al NAS restano qui finché non le togli dal telefono.",
                            "Photos sent to the NAS stay here until you remove them from the phone."))
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
        if (vm.inLibrary && lib.showTrash && lib.selected.isNotEmpty()) TrashSelectionBar(lib, Modifier.align(Alignment.BottomCenter))
        else if (vm.inLibrary && !lib.showTrash && lib.selecting) LibrarySelectionBar(lib, Modifier.align(Alignment.BottomCenter))
        // Importa: tondo in basso a sinistra, solo in «Da rivedere» (in «Spedite» non serve). Un tocco apre il menu da dove
        // importare: la fotocamera (RICOH GR, al posto di GR World) o un disco esterno collegato al telefono
        if (!vm.inLibrary && vm.section == Section.REVIEW) Box(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = fabBottom())) {
            var open by remember { mutableStateOf(false) }
            Box(Modifier.size(58.dp).clip(CircleShape).background(C.cardHigh).clickable { open = true }, contentAlignment = Alignment.Center) {
                ImportPlusIcon(Modifier.size(32.dp), C.text)
            }
            androidx.compose.material3.DropdownMenu(open, { open = false }) {
                androidx.compose.material3.DropdownMenuItem({ Text(L.t("Fotocamera", "Camera"), color = C.text) },
                    onClick = { open = false; onCamera() },
                    leadingIcon = { CameraPlusIcon(Modifier.size(22.dp), C.text, plus = false) })
                androidx.compose.material3.DropdownMenuItem({ Text(L.t("Disco esterno", "External disk"), color = C.text) },
                    onClick = { open = false; onDisk() },
                    leadingIcon = { UsbDiskIcon(Modifier.size(22.dp), C.text) })
            }
        }
        // «Spedisci al NAS»: tondo blu in basso a destra, allo stesso livello della fotocamera, con il numero di foto
        if (!vm.inLibrary && vm.section == Section.REVIEW) {
            val ship = rememberShip(vm)
            val toSend = vm.countIn(Section.REVIEW) - vm.photos.count { it.inWork && vm.meta(it.name).flag == Flag.REJECT }
            val on = vm.countIn(Section.REVIEW) > 0 && !vm.moving
            Box(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = fabBottom())) {
                Box(Modifier.size(58.dp).clip(CircleShape).background(if (on) C.accent else Color(0xFF8A8A8A))
                    .clickable(enabled = on) { ship() }, contentAlignment = Alignment.Center) {
                    if (vm.moving) CircularProgressIndicator(Modifier.size(24.dp), color = Color.Black, strokeWidth = 2.dp)
                    else UploadNasIcon(Modifier.size(30.dp), if (on) Color.Black else Color(0xFF2A2A2A))
                }
                // tondino bianco sempre della stessa misura (oltre 99 «99+», come il contatore delle Notifiche),
                // sul bordo in alto a destra del pulsante
                if (on && toSend > 0) Box(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp).size(24.dp)
                    .clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Text(if (toSend > 99) "99+" else "$toSend", color = Color.Black, fontWeight = FontWeight.Bold, maxLines = 1,
                        fontSize = if (toSend > 99) 8.sp else 10.sp, letterSpacing = if (toSend > 99) (-0.3).sp else 0.sp)
                }
            }
        }
        // «Inviate»: aggiorna (rilegge dal NAS lo stato di ogni foto e invia i metadati in coda) con il tondo verde in basso
        // a sinistra, di fronte al cestino; con il numerino bianco dei metadati ancora da inviare
        if (!vm.inLibrary && vm.section == Section.SENT) {
            Box(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = fabBottom())) {
                Box(Modifier.size(58.dp).clip(CircleShape).background(Color(0xFF4ADE80))
                    .clickable(enabled = !vm.syncing) { vm.sync() }, contentAlignment = Alignment.Center) {
                    if (vm.syncing) CircularProgressIndicator(Modifier.size(26.dp), color = Color.Black, strokeWidth = 2.5.dp)
                    else Icon(Icons.Filled.Refresh, L.t("Aggiorna lo stato dal NAS", "Refresh the status from the NAS"),
                        tint = Color.Black, modifier = Modifier.size(32.dp))
                }
                if (vm.pending > 0) Box(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp).size(24.dp)
                    .clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Text(if (vm.pending > 99) "99+" else "${vm.pending}", color = Color.Black, fontWeight = FontWeight.Bold, maxLines = 1,
                        fontSize = if (vm.pending > 99) 8.sp else 10.sp, letterSpacing = if (vm.pending > 99) (-0.3).sp else 0.sp)
                }
            }
            // aggiornamento automatico: con «Inviate» aperta e l'app in primo piano, ogni 10 s finché c'è una foto non
            // ancora verde (in LightCraft le porta il PC di casa: appena lo fa, la casetta cambia senza toccare niente)
            val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
            LaunchedEffect(owner) {
                owner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                    while (true) {
                        val notGreen = vm.countIn(Section.SENT) - vm.countLevel(Level.GREEN)
                        // solo con il NAS raggiungibile (a casa o con la VPN): fuori casa non si insiste
                        val reach = vm.conn == Conn.HOME || vm.conn == Conn.VPN
                        if (notGreen > 0 && reach && !vm.syncing && !vm.shipping) vm.sync(quiet = true)
                        kotlinx.coroutines.delay(10_000)
                    }
                }
            }
        }
        // «Spedite»: le foto già al sicuro a casa si tolgono dal telefono con il tondo rosso in basso a destra, come
        // «Spedisci al NAS» in «Da rivedere» (stesso posto, stessa misura, stesso tondino bianco con il numero)
        if (!vm.inLibrary && vm.section == Section.SENT) {
            val trash = rememberTrash(vm)
            val safe = vm.visible.filter { vm.isSafe(it) }
            val on = safe.isNotEmpty()
            Box(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = fabBottom())) {
                Box(Modifier.size(58.dp).clip(CircleShape).background(if (on) Color(0xFFF87171) else Color(0xFF8A8A8A))
                    .clickable(enabled = on) {
                        Confirm.show(L.t("Spostare ${safe.size} foto nel cestino del telefono?", "Move ${safe.size} photos to the phone's bin?"),
                            L.t("Sono foto già al sicuro a casa (sul NAS o già in LightCraft), quindi sul telefono non servono più. Prima ricontrollo sul NAS che lo siano davvero, poi Android ti chiede la conferma. Il cestino del telefono le tiene 30 giorni: fino ad allora le puoi recuperare dalla Galleria. Sul NAS e in LightCraft non cambia niente.",
                                "These photos are already safe at home (on the NAS or already in LightCraft), so the phone no longer needs them. I first check again on the NAS that they really are, then Android asks you to confirm. The phone's bin keeps them for 30 days: until then you can recover them from the Gallery. Nothing changes on the NAS or in LightCraft."),
                            L.t("Sposta nel cestino", "Move to bin")) { trash(safe.map { it.name }) }
                    }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Delete, L.t("Sposta nel cestino del telefono le foto al sicuro", "Move the safe photos to the phone's bin"),
                        tint = if (on) Color.Black else Color(0xFF2A2A2A), modifier = Modifier.size(30.dp))
                }
                if (on) Box(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp).size(24.dp)
                    .clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Text(if (safe.size > 99) "99+" else "${safe.size}", color = Color.Black, fontWeight = FontWeight.Bold, maxLines = 1,
                        fontSize = if (safe.size > 99) 8.sp else 10.sp, letterSpacing = if (safe.size > 99) (-0.3).sp else 0.sp)
                }
            }
        }
    }
}

/**
 * La forma di una scheda di Chrome: angoli in alto arrotondati e, in basso, due raccordi curvi verso l'esterno che la
 * fondono con la linea sotto. `flare` è la larghezza dei raccordi (compresa nella larghezza della scheda).
 */
private class ChromeTabShape(private val radius: androidx.compose.ui.unit.Dp, private val flare: androidx.compose.ui.unit.Dp) :
    androidx.compose.ui.graphics.Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                               density: androidx.compose.ui.unit.Density): androidx.compose.ui.graphics.Outline {
        val r = with(density) { radius.toPx() }
        val f = with(density) { flare.toPx() }
        val w = size.width
        val h = size.height
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, h)
            quadraticTo(f, h, f, h - f)            // raccordo in basso a sinistra (verso l'esterno)
            lineTo(f, r)
            quadraticTo(f, 0f, f + r, 0f)          // angolo in alto a sinistra
            lineTo(w - f - r, 0f)
            quadraticTo(w - f, 0f, w - f, r)       // angolo in alto a destra
            lineTo(w - f, h - f)
            quadraticTo(w - f, h, w, h)            // raccordo in basso a destra
            close()
        }
        return androidx.compose.ui.graphics.Outline.Generic(path)
    }
}

@Composable
private fun SectionTabs(vm: AppViewModel) {
    // come le schede di Chrome: stessa forma e grandezza, accostate; quella aperta in blu davanti e fusa con la linea
    // sotto, l'altra grigia dietro. Ognuna si allarga di metà raccordo verso l'altra, così i raccordi si sovrappongono.
    val flare = 10.dp
    // di quanto ogni scheda si allarga verso l'altra: la davanti copre l'angolo di quella dietro
    val overlap = 16.dp
    val tabs = listOf(Section.REVIEW to L.t("Da rivedere", "To review"), Section.SENT to L.t("Inviate", "Sent"))
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.Bottom) {
            tabs.forEachIndexed { i, (sec, label) ->
                val on = vm.section == sec
                Box(Modifier.weight(1f).zIndex(if (on) 1f else 0f)
                    .layout { m, c ->
                        val extra = overlap.roundToPx()
                        val w = c.maxWidth + extra
                        val placeable = m.measure(c.copy(minWidth = w, maxWidth = w))
                        layout(c.maxWidth, placeable.height) { placeable.place(if (i == 0) 0 else -extra, 0) }
                    }
                    .height(42.dp)
                    // quella davanti proietta un'ombra sul bordo di quella dietro: sembra appoggiata sopra
                    .then(if (on) Modifier.shadow(10.dp, ChromeTabShape(10.dp, flare), clip = false,
                        ambientColor = Color.Black, spotColor = Color.Black) else Modifier)
                    .clip(ChromeTabShape(10.dp, flare))
                    .background(if (on) C.accent else Color(0xFF2B2B2B))
                    .clickable { vm.section = sec; vm.inLibrary = false }
                    // il lato di quella dietro coperto da quella davanti: il testo si sposta per non finirci sotto
                    .padding(start = flare + 12.dp + if (!on && i == 1) overlap else 0.dp,
                        end = flare + 10.dp + if (!on && i == 0) overlap else 0.dp),
                    contentAlignment = Alignment.CenterStart) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = if (on) Color.Black else C.muted, fontSize = 14.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.weight(1f))
                        Text("${vm.countIn(sec)}", color = if (on) Color.Black else C.muted, fontSize = 12.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(50))
                                .background(if (on) Color(0x33000000) else Color(0xFF3A3A3A)).padding(horizontal = 8.dp, vertical = 1.dp))
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(C.accent))
    }
}

/** Filtri della sezione Importa: «Tutte» e un menu a tendina in cui se ne sceglie uno o più. */
@Composable
private fun ImportFilters(vm: AppViewModel) {
    var open by remember { mutableStateOf(false) }
    val chipColors = FilterChipDefaults.filterChipColors(containerColor = C.card, labelColor = C.text,
        selectedContainerColor = C.accent, selectedLabelColor = Color.Black, selectedLeadingIconColor = Color.Black)
    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RoundChip(vm.filters.isEmpty(), { vm.filters = emptySet() }) { Text(L.t("Tutte", "All") + "  ${vm.count(Filter.ALL)}", fontSize = 13.sp) }
        Box {
            RoundChip(vm.filters.isNotEmpty(), { open = true }, leading = { Icon(Icons.Filled.Menu, null, modifier = Modifier.size(16.dp)) }) { Text(L.t("Filtri", "Filters") + if (vm.filters.isNotEmpty()) " · ${vm.filters.size}" else "", fontSize = 13.sp) }
            DropdownMenu(open, { open = false }) {
                for ((f, label) in listOf(Filter.UNRATED to L.t("Da valutare", "Unrated"), Filter.PICKED to L.t("Scelte", "Picked"),
                        Filter.REJECTED to L.t("Scartate", "Rejected"), Filter.STARRED to L.t("Con stelle", "Starred"),
                        Filter.PENDING to L.t("Da inviare", "To send"))) {
                    val on = f in vm.filters
                    DropdownMenuItem({ Text("$label  ${vm.count(f)}", color = if (on) C.accent else C.text) },
                        onClick = { vm.filters = if (on) vm.filters - f else vm.filters + f },
                        leadingIcon = { Checkbox(on, null) })
                }
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
        Conn.VPN_NO_NAS -> Color(0xFFFB923C) to L.t("VPN di casa accesa ma NAS non raggiungibile", "Home VPN on but NAS not reachable")
        // un'altra VPN (AdGuard…) è accesa: il NAS non si vede e quella di casa non può partire finché non la spegni
        Conn.OTHER_VPN -> Color(0xFFFB923C) to (if (Adguard.installed(LocalContext.current))
            L.t("NAS non raggiungibile: AdGuard occupa il posto della VPN di casa", "NAS not reachable: AdGuard takes the home VPN's slot")
            else L.t("NAS non raggiungibile: un'altra VPN occupa il posto di quella di casa", "NAS not reachable: another VPN takes the home VPN's slot"))
        Conn.AWAY -> Color(0xFFFB923C) to L.t("NAS non raggiungibile: fuori casa serve la VPN", "NAS not reachable: away from home you need the VPN")
    }
    Row(Modifier.fillMaxWidth().clickable { if (vm.conn == Conn.OTHER_VPN) Vpn.otherAsk = true else vm.checkConnection() }.padding(start = 16.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
        Text(text, color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp).weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun sectionFolder(vm: AppViewModel) =
    if (vm.section == Section.REVIEW) vm.settings.workFolder else vm.settings.phoneFolder

/** Sezione "Da rivedere": la cartella di lavoro, e il bottone che spedisce tutto nella cartella sincronizzata. */
@Composable
private fun ReviewHeader(vm: AppViewModel) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        vm.workProblem?.let { Banner("error", it) }
        if (!Tips.compact) Text(L.t("Qui arrivano le foto nuove (cartella non sincronizzata). Quando hai finito, spediscile con il tondo blu in basso a destra: passano in ${vm.settings.phoneFolder} e l'app di sincronizzazione del NAS le carica.",
            "New photos land here (not synced). When you're done, send them with the blue round button at the bottom right: they move to ${vm.settings.phoneFolder} and the NAS's sync app uploads them."),
            color = C.muted, fontSize = 12.sp)
        val rejected = vm.photos.count { it.inWork && vm.meta(it.name).flag == Flag.REJECT }
        if (rejected > 0) Text(if (Tips.compact) L.t("$rejected scartate → cestino del telefono", "$rejected rejected → phone's bin") else L.t("Le $rejected scartate non vengono spedite: finiscono nel cestino del telefono (recuperabili per 30 giorni).",
            "The $rejected rejected photos aren't sent: they go to the phone's bin (recoverable for 30 days)."),
            color = C.muted, fontSize = 12.sp)
    }
}

/** «Spedisci al NAS»: chiede il permesso delle notifiche (la prima volta) e la conferma di Android, poi spedisce. */
@Composable
private fun rememberShip(vm: AppViewModel): () -> Unit {
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
    return {
        if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) notifAsk.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        else proceed()
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
        Text(" $n $label", color = C.text, fontSize = 13.sp, maxLines = 1, softWrap = false)
    }
}

/** Sezione "Inviate": casette rossa/blu/verde, stato del PC di casa, ripresa dell'invio e cestino. */
@Composable
private fun SentHeader(vm: AppViewModel) {
    val red = vm.countLevel(Level.RED)
    val blue = vm.countLevel(Level.BLUE)
    val nas = vm.nas
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            HouseCount(Level.GREEN, vm.countLevel(Level.GREEN), L.t("in LightCraft", "in LightCraft"))
            HouseCount(Level.BLUE, blue, L.t("sul NAS", "on the NAS"))
            HouseCount(Level.RED, red, L.t("da inviare", "to send"))
        }
        if (!Tips.compact) Text(L.t("Verde: già in LightCraft. Blu: foto e metadati al sicuro sul NAS, entrano in LightCraft quando il PC di casa è acceso. Rossa: foto o metadati non ancora sul NAS, o da aggiornare. Verdi e blu si possono togliere dal telefono.",
            "Green: already in LightCraft. Blue: photo and metadata safe on the NAS, they enter LightCraft when the home PC is on. Red: photo or metadata not on the NAS yet, or to be updated. Green and blue can be removed from the phone."),
            color = C.muted, fontSize = 12.sp)
        val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.ITALY)
        Row(verticalAlignment = Alignment.CenterVertically) {
        Text(when {
            nas.pcTs == 0L -> L.t("PC di casa: nessun segnale ancora (serve PresetCraftAppLicator 1.7.0 o successivo).",
                "Home PC: no signal yet (needs PresetCraftAppLicator 1.7.0 or later).")
            nas.pcOn() && nas.pcPhase == "waiting" -> L.t("PC di casa acceso, ma LightCraft è aperto: le foto entrano in libreria quando lo chiudi.",
                "Home PC on, but LightCraft is open: photos enter the library when you close it.")
            nas.pcOn() -> L.t("PC di casa acceso (ultimo segnale ", "Home PC on (last signal ") + fmt.format(java.util.Date(nas.pcTs)) + ")."
            else -> L.t("PC di casa spento o non attivo (ultimo segnale ", "Home PC off or idle (last signal ") + fmt.format(java.util.Date(nas.pcTs)) + ")."
        }, color = C.muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
        // invio in background: in corso (rotellina) o da riprendere (icona); il risultato arriva come notifica
        if (vm.shipping) CircularProgressIndicator(Modifier.padding(horizontal = 12.dp).size(18.dp), color = C.accent, strokeWidth = 2.dp)
        else if (red > 0 || (blue > 0 && nas.pcOn())) IconButton(onClick = { vm.resume() }) {
            Icon(Icons.Filled.PlayArrow, L.t("Riprendi l'invio in background", "Resume sending in the background"), tint = C.accent)
        }
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
                PillButton(onClick = { choosing = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(L.t("Preset: ", "Preset: ") + m.presetName.ifEmpty { L.t("profilo della fotocamera", "camera profile") },
                        maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (m.presetId.isEmpty()) C.text else C.accent)
                }
                if (m.pending && m.notOnNas) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(L.t("Il DNG non è sul NAS: forse non è ancora stato caricato, o è stato cancellato dal NAS.",
                        "The DNG isn't on the NAS: maybe not uploaded yet, or deleted from the NAS."),
                        color = Color(0xFFFB923C), fontSize = 12.sp, modifier = Modifier.weight(1f))
                    PillButton(onClick = { vm.dropPending(item.name) }) {
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
            PillButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
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
        .background(if (selected) C.accent else Color.Transparent).clickable(onClick = click)
        .padding(horizontal = 10.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, color = if (selected) Color.Black else C.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Filled.Check, null, tint = C.accent, modifier = Modifier.size(18.dp))
    }
}

private var controlsAdvance by mutableStateOf<Int?>(null)

@Composable
private fun FlagButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean,
                       colors: Pair<Color, Color>, click: () -> Unit) {
    val (fg, bg) = colors
    PillButton(onClick = click, container = if (on) bg else null, content = if (on) fg else null) {
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
                PillButton(onClick = close) { Text(L.t("Annulla", "Cancel")) }
                PillButton(onClick = {
                    vm.update(item.name) {
                        it.copy(title = title.trim(), caption = caption.trim(), creator = creator.trim(), copyright = copyright.trim(),
                            keywords = keywords.split(',').map(String::trim).filter(String::isNotEmpty).distinct())
                    }
                    close()
                }, primary = true) { Text(L.t("Salva", "Save")) }
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

/** Il percorso completo di una cartella della memoria interna ("Pictures/X/" → "/storage/emulated/0/Pictures/X"). */
private fun fullPath(rel: String) = if (rel.isBlank()) "" else "/storage/emulated/0/" + rel.trim('/')

/** Dove aprire il selettore di Android: la cartella attuale (se non c'è, Android parte dalla sua posizione). */
private fun initialTree(rel: String): android.net.Uri? = runCatching {
    android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:" + rel.trim('/'))
}.getOrNull()

/** Una cartella del NAS da mostrare: "Condivisione › cartella › sottocartella". */
private fun nasPath(share: String, folder: String) =
    if (share.isBlank()) "" else (listOf(share) + folder.split('\\', '/').filter { it.isNotEmpty() }).joinToString(" › ")

@Composable
fun SettingsScreen(vm: AppViewModel, libVm: LibraryViewModel, onBack: () -> Unit) {
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
    var libShare by remember { mutableStateOf(s.libShare) }
    var libFolder by remember { mutableStateOf(s.libFolder) }
    var notifyEnd by remember { mutableStateOf(s.notifyEnd) }
    var test by remember { mutableStateOf("" to "info") }
    // cartelle: quale si sta scegliendo (telefono: "work"/"sync"; NAS: "photos"/"service") o scrivendo a mano
    var picking by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf("") }
    var nasPick by remember { mutableStateOf("") }

    fun config() = NasConfig(host, share, folder, sub, user, password, libShare, libFolder)
    fun save() {
        s.nas = config(); s.phoneFolder = phone; s.workFolder = work; s.defaultCreator = creator; s.defaultCopyright = copyright; s.advance = advance
        s.notifyEnd = notifyEnd
        s.libShare = libShare; s.libFolder = libFolder
    }
    // dalla barra in basso si esce senza la freccia: si salva comunque
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { save() } }

    fun setPhoneFolder(which: String, rel: String) {
        if (which == "work") { s.workFolder = rel; work = s.workFolder } else { s.phoneFolder = rel; phone = s.phoneFolder }
        vm.ensureFolders()
    }
    val treePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        val which = picking
        picking = ""
        if (uri == null) return@rememberLauncherForActivityResult
        val rel = treeToRelative(uri)
        if (rel == null) Notices.post("error", L.t("Scegli una cartella dentro Pictures o DCIM della memoria interna.",
            "Choose a folder inside Pictures or DCIM in internal storage."))
        else setPhoneFolder(which, rel)
    }
    if (editing.isNotEmpty()) PathEditDialog(
        if (editing == "work") L.t("Cartella delle foto nuove", "New photos folder") else L.t("Cartella sincronizzata", "Synced folder"),
        if (editing == "work") work else phone,
        if (editing == "work") Settings.DEFAULT_WORK else Settings.DEFAULT_SYNC,
        onDismiss = { editing = "" }) { v ->
        val top = v.trim('/').substringBefore('/')
        if (!top.equals("Pictures", true) && !top.equals("DCIM", true)) Notices.post("error",
            L.t("Il percorso deve cominciare con Pictures/ o DCIM/.", "The path must start with Pictures/ or DCIM/."))
        else { setPhoneFolder(editing, v); editing = "" }
    }
    if (nasPick.isNotEmpty()) NasFolderDialog(
        if (nasPick == "photos") L.t("Cartella delle foto sul NAS", "Photos folder on the NAS") else L.t("Cartella di servizio", "Service folder"),
        config(), if (nasPick == "photos") share to folder else libShare.ifEmpty { share } to libFolder,
        onDismiss = { nasPick = "" }) { sh, path ->
        if (nasPick == "photos") { share = sh; folder = path }
        else {
            if (path.isEmpty()) Notices.post("warn", L.t("Hai scelto la cartella condivisa intera: di solito la cartella di servizio è una sua sottocartella (es. ViewerCraftStar).",
                "You chose the whole shared folder: the service folder is usually a subfolder of it (e.g. ViewerCraftStar)."))
            libShare = if (sh.equals(share, ignoreCase = true)) "" else sh
            libFolder = path
            val cfg = config()
            kotlinx.coroutines.MainScope().launch {
                val ok = withContext(Dispatchers.IO) { NasBrowse.has(cfg.host, cfg.user, cfg.password, sh, path, "libraries") }
                if (!ok) Notices.post("warn",
                    L.t("In questa cartella non c'è «libraries»: è davvero la cartella di servizio di PresetCraftAppLicator?",
                        "There's no “libraries” in this folder: is it really PresetCraftAppLicator's service folder?"))
            }
        }
        save()
        nasPick = ""
    }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()
        .verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp).padding(bottom = LocalBarSpace.current),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { save(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = C.text) }
            TitleText(L.t("Impostazioni", "Settings"))
        }
        // ricerca per soggetti: il modello IA scaricato occupa spazio (centinaia di MB)
        val context = androidx.compose.ui.platform.LocalContext.current
        var aiBytes by remember { mutableStateOf(AiStore.modelsSize(context)) }
        SettingsCard(L.t("Lingua", "Language")) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((k, name) in listOf("auto" to L.t("Come il telefono", "Same as the phone"), "it" to "Italiano", "en" to "English"))
                    Pill(name, L.choice == k) {
                        if (L.choice != k) {
                            save()
                            context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit().putString("language", k).commit()
                            ConfigBackup.restart(context) // i testi si leggono all'avvio
                        }
                    }
            }
            Text(L.t("Cambiando lingua l'app si riavvia.", "Changing the language restarts the app."), color = C.muted, fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
        SettingsCard(L.t("Permessi", "Permissions")) { PermissionsCard() }
        SettingsCard(L.t("Cartelle dei DNG sul telefono", "DNG folders on the phone")) {
            PathRow(L.t("Cartella delle foto nuove (non sincronizzata)", "New photos folder (not synced)"), fullPath(work),
                onBrowse = { picking = "work"; treePicker.launch(initialTree(work)) }, onEdit = { editing = "work" })
            PathRow(L.t("Cartella sincronizzata con il NAS", "Folder synced with the NAS"), fullPath(phone),
                onBrowse = { picking = "sync"; treePicker.launch(initialTree(phone)) }, onEdit = { editing = "sync" })
            if (!Tips.compact) Text(L.t("Nella memoria interna, dentro Pictures o DCIM. Se una cartella manca, l'app la crea.",
                "In internal storage, inside Pictures or DCIM. If a folder is missing the app creates it."),
                color = C.muted, fontSize = 12.sp)
        }
        // le cartelle del NAS si scelgono sfogliando, dopo una connessione riuscita (o se il NAS è già raggiungibile)
        val unlocked = vm.nasShares != null || ((vm.conn == Conn.HOME || vm.conn == Conn.VPN) && host == nas.host &&
            user == nas.user && password == nas.password)
        val lockMsg = if (unlocked) null else L.t("Prova la connessione per scegliere la cartella.", "Test the connection to choose the folder.")
        SettingsCard("NAS") {
            if (!Tips.compact) Text(L.t("Gli .xmp vengono scritti accanto ai DNG già caricati dall'app di sincronizzazione (es. Synology Drive, QNAP Qfile, Nextcloud, Syncthing). Sul NAS l'app scrive solo file .xmp: non cancella e non modifica mai le foto.",
                "The .xmp files are written next to the DNG files the sync app has already uploaded (e.g. Synology Drive, QNAP Qfile, Nextcloud, Syncthing). On the NAS the app only writes .xmp files: it never deletes or changes photos."),
                color = C.muted, fontSize = 12.sp)
            Field(L.t("Indirizzo IP del NAS (es. 192.168.1.10)", "NAS IP address (e.g. 192.168.1.10)"), host) { host = it }
            Field(L.t("Utente del NAS", "NAS user"), user) { user = it }
            Field(L.t("Password", "Password"), password, password = true) { password = it }
            PillButton(onClick = {
                save(); test = L.t("Provo la connessione…", "Testing the connection…") to "info"
                vm.testNas(config()) { msg, ok -> test = msg to if (ok) "ok" else "error" }
            }, primary = !unlocked, enabled = host.isNotBlank() && user.isNotBlank() && password.isNotEmpty()) {
                Text(L.t("Prova connessione", "Test connection"))
            }
            androidx.compose.runtime.LaunchedEffect(test) { Notices.post(test.second, test.first) }
            PathRow(L.t("Cartella delle foto sul NAS", "Photos folder on the NAS"), nasPath(share, folder),
                onBrowse = { nasPick = "photos" }, onEdit = null, locked = lockMsg)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(sub, { sub = it })
                Text(L.t("Cerca i DNG anche nelle sottocartelle", "Also look for DNG files in subfolders"), color = C.text)
            }
        }
        SettingsCard(L.t("Cartella di servizio", "Service folder")) {
            // sempre visibile (anche con i consigli ridotti): senza, non si capisce cosa sia
            Text(L.t("Sul NAS, la cartella dove PresetCraftAppLicator tiene librerie, anteprime e impostazioni per il telefono.",
                "On the NAS, the folder where PresetCraftAppLicator keeps libraries, previews and settings for the phone."),
                color = C.muted, fontSize = 12.sp)
            PathRow(L.t("Cartella di servizio", "Service folder"), if (libFolder.isEmpty()) "" else nasPath(libShare.ifEmpty { share }, libFolder),
                onBrowse = { nasPick = "service" }, onEdit = null, locked = lockMsg)
        }
        SettingsCard("VPN") { VpnCard(vm, nasReachable = vm.conn == Conn.HOME || vm.conn == Conn.VPN) }
        SettingsCard(L.t("Velocità del NAS", "NAS speed")) { NasSpeedCard(vm, libVm) { save(); config() } }
        SettingsCard(L.t("Sviluppo sul telefono", "Developing on the phone")) { DevelopCard() }
        SettingsCard(L.t("Backup delle impostazioni", "Settings backup")) {
            BackupCard(vm, ready = unlocked && libFolder.isNotEmpty(), beforeSave = { save() })
        }
        SettingsCard(L.t("Ricerca per soggetti (IA)", "Search by subject (AI)")) {
            if (!Tips.compact) Text(L.t("Nella Libreria, «Cerca» trova le foto per quello che contengono («gatti», «auto d'epoca») se la libreria è stata analizzata con il motore IA di PresetCraftAppLicator. Il modello si scarica una volta dal NAS alla prima ricerca.",
                "In the Library, “Search” finds photos by what they contain (“cats”, “vintage car”) if the library has been analyzed with PresetCraftAppLicator's AI engine. The model is downloaded once from the NAS at the first search."),
                color = C.muted, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (aiBytes > 0) L.t("Modello sul telefono: ${aiBytes / 1_000_000} MB", "Model on the phone: ${aiBytes / 1_000_000} MB")
                else L.t("Modello non ancora scaricato", "Model not downloaded yet"), color = C.text, modifier = Modifier.weight(1f))
                if (aiBytes > 0) PillButton(onClick = { libVm.deleteAiModel(); aiBytes = AiStore.modelsSize(context) }) {
                    Text(L.t("Elimina", "Delete"))
                }
            }
        }
        SettingsCard(L.t("Notifiche della spedizione", "Sending notifications")) {
            if (!Tips.compact) Text(L.t("Dopo «Spedisci al NAS» una notifica mostra quante foto, con i loro metadati, sono già al sicuro sul NAS.",
                "After “Send to the NAS” a notification shows how many photos, with their metadata, are already safe on the NAS."),
                color = C.muted, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(notifyEnd, { notifyEnd = it })
                Text(L.t("Avvisami quando la spedizione è finita o sospesa", "Tell me when sending is finished or paused"), color = C.text)
            }
        }
        SettingsCard(L.t("Valori predefiniti", "Defaults")) {
            Field(L.t("Autore", "Creator"), creator) { creator = it }
            Field("Copyright", copyright) { copyright = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(advance, { advance = it })
                Text(L.t("Passa alla foto successiva dopo una bandierina o una stella", "Go to the next photo after a flag or a star"),
                    color = C.text)
            }
        }
        Text("ViewerCraftStar ${BuildConfigInfo.version}", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp))
        // donazioni: un menu con Buy Me a Coffee e PayPal; la scelta si apre nel browser
        val ctx = LocalContext.current
        Box {
            var donate by remember { mutableStateOf(false) }
            Text(L.t("☕ Offrimi un caffè", "☕ Buy me a coffee"), color = C.accent, fontSize = 14.sp,
                modifier = Modifier.padding(top = 10.dp).clickable { donate = true }.padding(vertical = 6.dp))
            DropdownMenu(donate, { donate = false }) {
                for ((name, url) in listOf("☕  Buy Me a Coffee" to "https://buymeacoffee.com/poty87", "💳  PayPal" to "https://paypal.me/poty87"))
                    DropdownMenuItem({ Text(name, color = C.text) }, onClick = {
                        donate = false
                        runCatching {
                            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    })
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** La prova di velocità verso il NAS: legge una foto della libreria in modi diversi e mostra i MB/s di ognuno. */
@Composable
private fun NasSpeedCard(vm: AppViewModel, libVm: LibraryViewModel, config: () -> NasConfig) {
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Text(L.t("Legge per qualche secondo una foto grande della libreria in modi diversi: una o più connessioni, una o più letture insieme, una o tutte e due le porte del NAS. Provala a casa e in VPN.",
        "Reads a large photo of the library for a few seconds in different ways: one or more connections, one or more reads at once, one or both NAS ports. Try it at home and over the VPN."),
        color = C.muted, fontSize = 12.sp)
    val reachable = vm.conn == Conn.HOME || vm.conn == Conn.VPN
    PillButton(onClick = {
        val idx = libVm.index ?: return@PillButton
        running = true
        val where = when (vm.conn) { Conn.HOME -> L.t("rete di casa", "home network"); Conn.VPN -> "VPN"; else -> vm.conn.name }
        lines = listOf(L.t("Collegamento: $where", "Connection: $where"))
        scope.launch {
            runCatching { NasSpeed.run(config(), idx) { l -> scope.launch { lines = lines + l } } }
                .onFailure { e -> lines = lines + L.t("Prova non riuscita: ", "Test failed: ") + (e.message ?: e.javaClass.simpleName) }
            running = false
        }
    }, primary = true, enabled = reachable && !running && libVm.index != null) {
        Text(if (running) L.t("Misuro…", "Measuring…") else L.t("Misura la velocità", "Measure the speed"))
    }
    if (!reachable) Text(L.t("Serve il NAS raggiungibile (a casa o in VPN).", "The NAS must be reachable (at home or over the VPN)."),
        color = C.muted, fontSize = 12.sp)
    lines.forEach { Text(it, color = C.text, fontSize = 13.sp) }
}

/** Sviluppo sul telefono: versione del motore e smart preview scaricate (spazio occupato, «Svuota»). */
@Composable
private fun DevelopCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var bytes by remember { mutableStateOf(-1L) }
    LaunchedEffect(Unit) { bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { SmartCache.size(context) + OriginalCache.size(context) } }
    val engine = if (DevelopEngine.available) "LightCraft " + DevelopEngine.version() else L.t("non disponibile", "not available")
    Text(L.t("Motore di sviluppo: $engine. Le smart preview si scaricano quando apri «Sviluppa» su una foto e restano sul telefono (al massimo circa 1 GB: oltre si tolgono le meno usate). Per i profili della fotocamera (DCP) serve il file originale: si scarica anche quello (al massimo circa 1 GB in tutto).",
        "Develop engine: $engine. Smart previews are downloaded when you open «Develop» on a photo and stay on the phone (about 1 GB at most: beyond that the least used go). Camera profiles (DCP) need the original file: that is downloaded too (about 1 GB at most)."),
        color = C.muted, fontSize = 12.sp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(L.t("Smart preview e originali scaricati: ", "Downloaded smart previews and originals: ") +
            if (bytes < 0) "…" else "%.0f MB".format(bytes / 1_048_576.0), color = C.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        PillButton(onClick = {
            scope.launch {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { SmartCache.clear(context); OriginalCache.clear(context) }
                bytes = 0
            }
        }, enabled = bytes > 0) { Text(L.t("Svuota", "Clear")) }
    }
}

/** Icona «spedisci al NAS»: una freccia in su che entra nel NAS (un box con due cassetti). */
@Composable
fun UploadNasIcon(modifier: Modifier, color: Color) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val h = size.height
        val t = w * 0.08f
        // il NAS: box con due cassetti e i loro led
        drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(w * 0.18f, h * 0.52f),
            size = androidx.compose.ui.geometry.Size(w * 0.64f, h * 0.40f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.07f), style = androidx.compose.ui.graphics.drawscope.Stroke(t))
        drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.18f, h * 0.72f), androidx.compose.ui.geometry.Offset(w * 0.82f, h * 0.72f), strokeWidth = t * 0.8f)
        drawCircle(color, radius = w * 0.04f, center = androidx.compose.ui.geometry.Offset(w * 0.70f, h * 0.62f))
        drawCircle(color, radius = w * 0.04f, center = androidx.compose.ui.geometry.Offset(w * 0.70f, h * 0.82f))
        // la freccia in su, sopra il NAS
        val x = w * 0.5f
        drawLine(color, androidx.compose.ui.geometry.Offset(x, h * 0.44f), androidx.compose.ui.geometry.Offset(x, h * 0.06f), strokeWidth = t)
        drawLine(color, androidx.compose.ui.geometry.Offset(x - w * 0.16f, h * 0.20f), androidx.compose.ui.geometry.Offset(x, h * 0.05f), strokeWidth = t)
        drawLine(color, androidx.compose.ui.geometry.Offset(x + w * 0.16f, h * 0.20f), androidx.compose.ui.geometry.Offset(x, h * 0.05f), strokeWidth = t)
    }
}

/** Dove stanno i pulsanti tondi: sopra la barra in basso quando si vede, sopra i tasti di sistema quando è nascosta. */
@Composable
private fun fabBottom(): androidx.compose.ui.unit.Dp {
    val nav = androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return maxOf(LocalBarShown.current, nav) + 16.dp
}
