package io.github.poty87.viewercraftstar

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Le miniature si scaricano tre alla volta: il server della fotocamera è piccolo. */
private val thumbGate = Semaphore(3)

/**
 * Importa dalla fotocamera RICOH GR: si collega (Bluetooth → Wi-Fi), mostra le foto, scarica quelle scelte nella
 * cartella delle foto nuove. Chiudendo, il telefono torna alla sua rete.
 */
@Composable
fun CameraScreen(vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(L.t("Preparo il collegamento…", "Getting ready…")) }
    var error by remember { mutableStateOf<String?>(null) }
    var camera by remember { mutableStateOf("") }
    var photos by remember { mutableStateOf<List<GrCamera.Photo>?>(null) }
    var network by remember { mutableStateOf<Network?>(null) }
    var wifiCb by remember { mutableStateOf<ConnectivityManager.NetworkCallback?>(null) }
    var gatt by remember { mutableStateOf<GrCamera.Gatt?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var progress by remember { mutableStateOf<Triple<Int, Int, Float>?>(null) }
    var bytesNow by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(setOf<String>()) } // foto scaricate in questo collegamento: spunta verde
    var granted by remember { mutableStateOf(false) }
    // la fotocamera a cui collegarsi; se non ce n'è una associata, si cerca e si associa (come GR World)
    var device by remember { mutableStateOf<android.bluetooth.BluetoothDevice?>(null) }
    var pairing by remember { mutableStateOf(false) }
    val found = remember { androidx.compose.runtime.mutableStateListOf<android.bluetooth.BluetoothDevice>() }
    var bonding by remember { mutableStateOf(false) }
    var linked by remember { mutableStateOf(false) } // durante l'associazione: collegati, si aspetta la fotocamera
    var chosen by remember { mutableStateOf<String?>(null) } // indirizzo della fotocamera scelta nell'elenco

    // già sul telefono: lo stesso nome, anche con il prefisso che aggiunge GR World ("<millisecondi>_" davanti al nome
    // della fotocamera: _R000111.DNG → 1795197313000__R000111.DNG)
    val known = remember(vm.photos, vm.nas) {
        // anche senza il « (1)» che Android aggiunge ai doppioni, così una copia rinominata conta come già presa
        (vm.photos.map { it.name.lowercase() } + vm.nas.names.map { it.lowercase() })
            .flatMap { listOf(it, it.replace(Regex(""" \([0-9]+\)(?=\.[^.]+$)"""), "")) }.toHashSet()
    }
    val knownShort = remember(known) { known.map { it.replace(Regex("^[0-9]{10,}_"), "") }.toHashSet() }
    // una foto è «già presa» se sul telefono o sul NAS c'è un file con il suo nome completo (ora di scatto + nome);
    // dopo un reset della fotocamera i numeri ricominciano, quindi il solo nome non basta
    fun have(p: GrCamera.Photo) = if (p.taken > 0) p.phoneName.lowercase() in known else p.name.lowercase() in knownShort
    var groupBy by remember { mutableStateOf("day") }
    var format by remember { mutableStateOf(vm.settings.cameraFormat) } // "dng", "jpg", "both"

    fun close() {
        GrCamera.release(context, wifiCb); wifiCb = null; network = null
        gatt?.close(); gatt = null
        onBack()
    }
    BackHandler { close() }
    DisposableEffect(Unit) { onDispose { GrCamera.release(context, wifiCb); gatt?.close() } }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        granted = r.values.all { it }
        if (!granted) error = L.t("Senza i permessi Bluetooth e Wi-Fi l'app non può collegarsi alla fotocamera.",
            "Without the Bluetooth and Wi-Fi permissions the app can't connect to the camera.")
    }
    LaunchedEffect(Unit) {
        ask.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.NEARBY_WIFI_DEVICES))
    }
    LaunchedEffect(granted) {
        if (!granted) return@LaunchedEffect
        val d = GrCamera.bonded(context)
        if (d != null) device = d else pairing = true
    }
    // associazione: si cercano le fotocamere che la stanno chiedendo, finché non se ne sceglie una
    LaunchedEffect(pairing, bonding) {
        while (pairing && !bonding) {
            runCatching { GrCamera.scan(context, 30) { d -> if (found.none { it.address == d.address }) found += d } }
                .onFailure { error = it.message; pairing = false }
        }
    }
    LaunchedEffect(device) {
        val dev = device ?: return@LaunchedEffect
        runCatching {
            step = L.t("Mi collego via Bluetooth… (accendi la fotocamera)", "Connecting over Bluetooth… (turn the camera on)")
            val g = GrCamera.Gatt(context, dev).also { gatt = it }
            g.connect()
            camera = runCatching { GrCamera.text(g.read(GrCamera.MODEL)) + " · firmware " + GrCamera.text(g.read(GrCamera.FIRMWARE)) }
                .getOrDefault(dev.name ?: "GR")
            runCatching { g.write(GrCamera.DATETIME, GrCamera.now()) } // come GR World: la fotocamera prende l'ora del telefono
            val ssid = GrCamera.text(g.read(GrCamera.SSID))
            val pass = GrCamera.text(g.read(GrCamera.PASSWORD))
            step = L.t("Accendo il Wi-Fi della fotocamera…", "Turning on the camera's Wi-Fi…")
            val t0 = System.currentTimeMillis()
            g.write(GrCamera.WIFI_ON, byteArrayOf(1))
            // la rete della fotocamera impiega qualche secondo ad accendersi: se Android la cerca prima, la trova solo alla
            // ricerca successiva (circa 10 s dopo). Si aspetta che la fotocamera dica di averla accesa (massimo 10 s)
            val ready = GrCamera.waitWifiOn(g)
            android.util.Log.i("VcsGr", "Wi-Fi della fotocamera acceso: $ssid (pronto: $ready, ${System.currentTimeMillis() - t0} ms)")
            // la fotocamera impiega circa 25 s ad accendere davvero la sua rete (misurato; lo stesso con GR World)
            step = L.t("La fotocamera sta accendendo il suo Wi-Fi: ci vogliono circa 30 secondi…",
                "The camera is turning on its Wi-Fi: it takes about 30 seconds…")
            val (net, cb) = GrCamera.joinWifi(context, ssid, pass)
            network = net; wifiCb = cb
            android.util.Log.i("VcsGr", "collegato alla rete della fotocamera dopo ${System.currentTimeMillis() - t0} ms")
            runCatching { g.write(GrCamera.TRANSFER, byteArrayOf(1)) }
            step = L.t("Leggo le foto della fotocamera…", "Reading the camera's photos…")
            val raw = withContext(Dispatchers.IO) { GrCamera.photos(net) }
            // le date di scatto: per dividere per giorno/mese e riconoscere le foto già prese
            photos = GrCamera.withDates(net, raw) { n -> step = L.t("Leggo le date delle foto ($n di ${raw.size})…", "Reading photo dates ($n of ${raw.size})…") }
                .sortedWith(compareByDescending<GrCamera.Photo> { it.taken }.thenByDescending { it.name })
            step = ""
        }.onFailure { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("VcsGr", "collegamento non riuscito", e)
            error = e.message ?: e.javaClass.simpleName
        }
    }

    // si vedono i DNG (con «Solo JPEG», i JPEG)
    val list = photos?.filter { if (format == "jpg") !it.name.endsWith(".dng", true) else it.name.endsWith(".dng", true) }.orEmpty()
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 10.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { close() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L.t("Indietro", "Back"), tint = C.text) }
            Column(Modifier.weight(1f)) {
                TitleText(L.t("Fotocamera", "Camera"))
                if (camera.isNotEmpty()) Text(camera, color = C.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val err = error
        when {
            pairing && err == null -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(L.t("Associa la fotocamera", "Pair the camera"), color = C.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                // la procedura della GR IV, con il passaggio che il manuale non dice chiaro: il QR non basta, servono due OK
                Text(L.t("1. Sulla fotocamera: menu E6 › Wireless Communication › Action Mode: On › Pairing › Execute Pairing.\n" +
                        "2. Compare un codice QR: ignoralo e premi OK sulla fotocamera DUE volte, finché è davvero in attesa di associazione.\n" +
                        "3. Qui sotto scegli la fotocamera e premi «Associa».\n" +
                        "4. Controlla che il codice sia uguale sul telefono e sulla fotocamera, conferma sul telefono e premi OK sulla fotocamera.",
                    "1. On the camera: E6 menu › Wireless Communication › Action Mode: On › Pairing › Execute Pairing.\n" +
                        "2. A QR code appears: ignore it and press OK on the camera TWICE, until it is really waiting for pairing.\n" +
                        "3. Below, choose the camera and tap “Pair”.\n" +
                        "4. Check the code matches on the phone and on the camera, confirm on the phone and press OK on the camera."),
                    color = C.muted, fontSize = 13.sp)
                if (bonding) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = C.accent, strokeWidth = 2.dp)
                    Spacer(Modifier.size(10.dp))
                    Text(if (!linked) L.t("Mi collego alla fotocamera…", "Connecting to the camera…")
                        else L.t("Collegata. Controlla che il codice sia uguale sul telefono e sulla fotocamera e conferma su tutti e due.",
                            "Connected. Check the code matches on the phone and on the camera and confirm on both."),
                        color = C.text, fontSize = 13.sp)
                }
                else if (found.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = C.accent, strokeWidth = 2.dp)
                    Spacer(Modifier.size(10.dp))
                    Text(L.t("Cerco le fotocamere vicine…", "Looking for nearby cameras…"), color = C.text, fontSize = 13.sp)
                }
                // le fotocamere trovate: un elenco da cui sceglierne una, poi «Associa»
                if (found.isNotEmpty()) {
                    Text(L.t("Fotocamere trovate", "Cameras found"), color = C.muted, fontSize = 12.sp)
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.card)) {
                        found.forEach { d ->
                            @Suppress("MissingPermission") val name = runCatching { d.name }.getOrNull() ?: d.address
                            val on = chosen == d.address
                            Row(Modifier.fillMaxWidth().clickable(enabled = !bonding) { chosen = d.address }
                                .background(if (on) C.accent.copy(alpha = 0.18f) else Color.Transparent)
                                .padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.RadioButton(on, { chosen = d.address }, enabled = !bonding,
                                    colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = C.accent))
                                Spacer(Modifier.size(8.dp))
                                Text(name, color = C.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    if (found.size == 1 && chosen == null) chosen = found.first().address
                    PillButton(onClick = {
                        val d = found.firstOrNull { it.address == chosen } ?: return@PillButton
                        bonding = true; linked = false
                        scope.launch {
                            runCatching { GrCamera.bond(context, d) { linked = true } }
                                .onSuccess { pairing = false; bonding = false; device = d }
                                .onFailure {
                    bonding = false
                    Notices.post("error", L.t("La fotocamera ha rifiutato l'associazione: di solito non era davvero in attesa. Con il QR a schermo premi OK due volte sulla fotocamera, poi riprova.",
                        "The camera refused pairing: usually it wasn't really waiting. With the QR code on screen press OK twice on the camera, then retry."))
                }
                        }
                    }, primary = true, enabled = !bonding && chosen != null, modifier = Modifier.fillMaxWidth()) {
                        Text(L.t("Associa", "Pair"))
                    }
                }
            }
            err != null -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(err, color = Color(0xFFF87171), fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(onClick = { close() }) { Text(L.t("Chiudi", "Close")) }
                    // per una fotocamera nuova o un'associazione persa: si cerca e si associa di nuovo
                    PillButton(onClick = {
                        GrCamera.release(context, wifiCb); wifiCb = null; network = null
                        gatt?.close(); gatt = null
                        error = null; device = null; found.clear(); pairing = true
                    }) { Text(L.t("Associa una fotocamera", "Pair a camera")) }
                }
            }
            photos == null -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), color = C.accent, strokeWidth = 2.dp)
                Spacer(Modifier.size(12.dp))
                Text(step, color = C.text, fontSize = 14.sp)
            }
            photos.orEmpty().isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(EmptyGlyph.CARD, L.t("Scheda vuota", "Empty card"),
                    L.t("Sulla fotocamera non ci sono foto da scaricare.", "There are no photos on the camera to download."))
            }
            else -> {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // cosa scaricare, a tendina come «Ordina»: i JPEG vanno sempre nella galleria del telefono
                    val formatLabels = listOf("dng" to L.t("Solo DNG", "DNG only"), "jpg" to L.t("Solo JPEG", "JPEG only"),
                        "both" to "DNG+JPEG")
                    Box {
                        var open by remember { mutableStateOf(false) }
                        RoundChip(false, { open = true }, leading = { Icon(Icons.Filled.Menu, null, modifier = Modifier.size(16.dp)) }) {
                            Text(L.t("Formato", "Format"))
                        }
                        androidx.compose.material3.DropdownMenu(open, { open = false }) {
                            for ((k, label) in formatLabels) {
                                val on = format == k
                                androidx.compose.material3.DropdownMenuItem({ Text(label, color = if (on) C.accent else C.text) },
                                    onClick = { format = k; vm.settings.cameraFormat = k; selected = emptySet(); open = false },
                                    leadingIcon = { androidx.compose.material3.RadioButton(on, null,
                                        colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = C.accent)) })
                            }
                        }
                    }
                    // raggruppamento per periodo, a tendina come i Filtri di Importa
                    val groupLabels = listOf("" to L.t("Tutte insieme", "All together"), "day" to L.t("Per giorno", "By day"),
                        "month" to L.t("Per mese", "By month"), "year" to L.t("Per anno", "By year"))
                    Box {
                        var open by remember { mutableStateOf(false) }
                        RoundChip(groupBy.isNotEmpty(), { open = true }, leading = { Icon(Icons.Filled.Menu, null, modifier = Modifier.size(16.dp)) }) {
                            Text(L.t("Ordina", "Sort"))
                        }
                        androidx.compose.material3.DropdownMenu(open, { open = false }) {
                            for ((k, label) in groupLabels) {
                                val on = groupBy == k
                                androidx.compose.material3.DropdownMenuItem({ Text(label, color = if (on) C.accent else C.text) },
                                    onClick = { groupBy = k; open = false },
                                    leadingIcon = { androidx.compose.material3.RadioButton(on, null,
                                        colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = C.accent)) })
                            }
                        }
                    }
                    if (selected.isNotEmpty()) RoundChip(false, { selected = emptySet() }) { Text(L.t("Nessuna", "None")) }
                }
                Text(L.t("${list.size} foto sulla fotocamera · scure = già sul telefono o sul NAS", "${list.size} photos on the camera · dark = already on the phone or the NAS"),
                    color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                val names = remember(photos) { photos.orEmpty().map { it.name.lowercase() }.toHashSet() }
                // i gruppi per periodo (dalla data di scatto); senza data finiscono in «Senza data»
                val groups = remember(list, groupBy) {
                    if (groupBy.isEmpty()) listOf("" to list)
                    else list.groupBy { p ->
                        if (p.taken <= 0) "" else {
                            val dt = java.time.Instant.ofEpochMilli(p.taken).atZone(java.time.ZoneId.systemDefault())
                            val pattern = when (groupBy) { "day" -> "EEEE d MMMM yyyy"; "month" -> "MMMM yyyy"; else -> "yyyy" }
                            dt.format(java.time.format.DateTimeFormatter.ofPattern(pattern, java.util.Locale.getDefault()))
                                .replaceFirstChar { it.uppercase() }
                        }
                    }.toList()
                }
                // le foto scorrono sotto la barra in basso (traslucida); spazio in fondo per l'ultima riga
                Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.fillMaxSize().padding(horizontal = 6.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 180.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((title, items) in groups) {
                        if (groupBy.isNotEmpty()) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }, key = "h-$title") {
                            val all = items.map { it.path }
                            val allOn = all.isNotEmpty() && selected.containsAll(all)
                            Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(title.ifEmpty { L.t("Senza data", "No date") }, color = C.text, fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Text("${items.size}  ", color = C.muted, fontSize = 13.sp)
                                // tutte le foto di quel giorno (o mese, anno) con un tocco
                                androidx.compose.material3.Checkbox(allOn, { selected = if (allOn) selected - all.toSet() else selected + all },
                                    colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = C.accent,
                                        checkmarkColor = Color.Black, uncheckedColor = C.muted))
                            }
                        }
                        items(items, key = { it.path }) { p ->
                            val on = p.path in selected
                            val had = have(p)
                            Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(C.card)
                                // selezione come nella galleria della Libreria: bordo bianco, velo blu, spunta blu al centro
                                .then(if (on) Modifier.border(3.dp, Color.White, RoundedCornerShape(6.dp)) else Modifier)
                                .clickable { selected = if (on) selected - p.path else selected + p.path }) {
                                network?.let { CameraThumb(it, p, names) }
                                if (had && !on && p.path !in done) Box(Modifier.fillMaxSize().background(Color(0x99000000)))
                                Text(p.name + if (p.taken > 0) "  " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.ITALY)
                                    .format(java.util.Date(p.taken)) else "", color = Color.White, fontSize = 10.sp, maxLines = 1,
                                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0x80000000))
                                        .padding(horizontal = 4.dp, vertical = 2.dp))
                                val ok = p.path in done
                                if (on || ok) Box(Modifier.fillMaxSize().background((if (ok) GREEN else C.accent).copy(alpha = 0.33f)), contentAlignment = Alignment.Center) {
                                    Box(Modifier.size(26.dp).clip(CircleShape).background(if (ok) GREEN else C.accent), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Filled.Check, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                                    }
                                }
                                // scatto RAW+JPEG: informazione sulla foto, in alto a sinistra come la bandierina in Libreria
                                if (format == "both" && p.name.endsWith(".dng", true) && p.name.substringBeforeLast('.').lowercase() + ".jpg" in names) {
                                    // verde quando quel JPEG è nella galleria (scaricato ora o già presente)
                                    val stem = p.name.substringBeforeLast('.')
                                    val jpgDone = photos.orEmpty().any { it.dir == p.dir && it.path in done &&
                                        it.name.substringBeforeLast('.').equals(stem, true) && !it.name.endsWith(".dng", true) }
                                    Text("+JPEG", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.align(Alignment.TopStart).padding(5.dp).clip(RoundedCornerShape(50))
                                            .background(if (jpgDone) GREEN else C.accent).padding(horizontal = 6.dp, vertical = 1.dp))
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
                        // la foto in corso e il totale, nello stesso stile; verdi quando arrivano al 100%
                        val total = ((pr.first - 1 + pr.third) / pr.second).coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { pr.third }, color = if (pr.third >= 1f) GREEN else C.accent,
                            trackColor = C.card, modifier = Modifier.fillMaxWidth())
                        Text(L.t("Totale ${(total * 100).toInt()}%", "Total ${(total * 100).toInt()}%"), color = C.muted, fontSize = 12.sp)
                        LinearProgressIndicator(progress = { total }, color = if (total >= 1f) GREEN else C.accent,
                            trackColor = C.card, modifier = Modifier.fillMaxWidth())
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        PillButton(onClick = {
                            scope.launch {
                                runCatching { gatt?.write(GrCamera.POWER, byteArrayOf(0)) }
                                Notices.post("info", L.t("Fotocamera spenta.", "Camera turned off."))
                                close()
                            }
                        }, enabled = !downloading, modifier = Modifier.weight(1f)) { Text(L.t("Spegni e chiudi", "Turn off and close"), maxLines = 1) }
                        PillButton(onClick = {
                            val net = network ?: return@PillButton
                            val chosenNow = list.filter { it.path in selected }
                            val all = photos.orEmpty()
                            fun twinOf(dngP: GrCamera.Photo): GrCamera.Photo? {
                                val stem = dngP.name.substringBeforeLast('.')
                                return all.firstOrNull { it.dir == dngP.dir && it.name.substringBeforeLast('.').equals(stem, true) &&
                                    (it.name.endsWith(".jpg", true) || it.name.endsWith(".jpeg", true)) }
                            }
                            // prima tutti i DNG (le spunte diventano verdi una dopo l'altra), poi i loro JPEG (le etichette +JPEG
                            // diventano verdi una dopo l'altra, appena arriva ciascun JPEG)
                            val twins = if (format != "both") emptyList()
                                else chosenNow.filter { it.name.endsWith(".dng", true) }.mapNotNull { twinOf(it) }
                            val todo = (chosenNow + twins).distinctBy { it.path }
                            fun isJpeg(p: GrCamera.Photo) = p.name.endsWith(".jpg", true) || p.name.endsWith(".jpeg", true)
                            scope.launch {
                                var ok = 0
                                var skipped = 0 // JPEG già nella galleria: non riscaricati
                                var skippedDng = 0 // DNG già sul telefono o sul NAS: non riscaricati (eviterebbe i doppioni « (1)»)
                                downloading = true
                                todo.forEachIndexed { i, p ->
                                    progress = Triple(i + 1, todo.size, 0f)
                                    val ok1 = withContext(Dispatchers.IO) {
                                        var shown = -1
                                        // i JPEG nella galleria (se c'è già, non si riscarica); i DNG nella cartella delle foto nuove
                                        val folder = if (isJpeg(p)) GrCamera.GALLERY else vm.settings.workFolder
                                        if (folder == GrCamera.GALLERY && GrCamera.existsOnPhone(context, folder, p.phoneName)) {
                                            skipped++
                                            return@withContext true
                                        }
                                        if (folder != GrCamera.GALLERY && p.taken > 0 && have(p)) {
                                            skippedDng++
                                            return@withContext true
                                        }
                                        GrCamera.download(context, net, p, folder) { got, total ->
                                            // un aggiornamento per ogni punto percentuale (o ogni MB se la dimensione non è nota)
                                            val step = if (total > 0) (got * 100 / total).toInt() else (got / 1_000_000).toInt()
                                            if (step != shown) {
                                                shown = step
                                                bytesNow = got to total
                                                progress = Triple(i + 1, todo.size, if (total > 0) got.toFloat() / total else 0f)
                                            }
                                        }
                                    }
                                    if (ok1) { ok++; progress = Triple(i + 1, todo.size, 1f); done = done + p.path }
                                }
                                // a fine scarico: barre verdi, le foto completate escono dalla selezione (restano con la spunta verde)
                                downloading = false
                                selected = selected - done
                                vm.reload()
                                // il riepilogo conta solo i file scaricati davvero; i JPEG saltati hanno la loro notifica
                                val got = ok - skipped - skippedDng
                                val wanted = todo.size - skipped - skippedDng
                                if (wanted > 0) Notices.post(if (got == wanted) "ok" else "warn",
                                    L.t("$got di $wanted file scaricati: i DNG in Da rivedere" + (if (todo.any { isJpeg(it) }) ", i JPEG nella galleria del telefono." else "."),
                                        "$got of $wanted files downloaded: the DNGs are in To review" + (if (todo.any { isJpeg(it) }) ", the JPEGs in the phone's gallery." else ".")))
                                if (skipped > 0) Notices.post("info",
                                    L.t(if (skipped == 1) "1 JPEG era già nella galleria del telefono e non è stato importato di nuovo."
                                        else "$skipped JPEG erano già nella galleria del telefono e non sono stati importati di nuovo.",
                                        if (skipped == 1) "1 JPEG was already in the phone's gallery and wasn't imported again."
                                        else "$skipped JPEGs were already in the phone's gallery and weren't imported again."))
                                if (skippedDng > 0) Notices.post("info",
                                    L.t(if (skippedDng == 1) "1 DNG era già sul telefono o sul NAS e non è stato scaricato di nuovo."
                                        else "$skippedDng DNG erano già sul telefono o sul NAS e non sono stati scaricati di nuovo.",
                                        if (skippedDng == 1) "1 DNG was already on the phone or the NAS and wasn't downloaded again."
                                        else "$skippedDng DNGs were already on the phone or the NAS and weren't downloaded again."))
                            }
                        }, primary = true, enabled = selected.isNotEmpty() && !downloading, modifier = Modifier.weight(1f)) {
                            Text(L.t("Scarica ${selected.size}", "Download ${selected.size}"))
                        }
                    }
                }
                }
            }
        }
    }
}

/** La miniatura di una foto della fotocamera (o del suo JPEG gemello). */
@Composable
private fun CameraThumb(network: Network, p: GrCamera.Photo, names: Set<String>) {
    val bmp by produceState<Bitmap?>(null, p.path) {
        value = withContext(Dispatchers.IO) { thumbGate.withPermit { GrCamera.thumbnail(network, p, names) } }
    }
    bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
}

/** L'icona «associa una fotocamera»: una fotocamera con un + nel tondo in alto a destra. */
@Composable
fun CameraPlusIcon(modifier: Modifier, color: Color) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.08f)
        // corpo e mirino, centrati nel tondo
        drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(w * 0.14f, h * 0.30f),
            size = androidx.compose.ui.geometry.Size(w * 0.72f, h * 0.52f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.10f), style = stroke)
        drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(w * 0.32f, h * 0.20f),
            size = androidx.compose.ui.geometry.Size(w * 0.20f, h * 0.10f))
        // obiettivo, al centro del corpo
        drawCircle(color, radius = w * 0.14f, center = androidx.compose.ui.geometry.Offset(w * 0.50f, h * 0.56f), style = stroke)
        // il + in un tondo, in alto a destra
        val c = androidx.compose.ui.geometry.Offset(w * 0.86f, h * 0.22f)
        drawCircle(C.accent, radius = w * 0.20f, center = c)
        val a = w * 0.11f
        val t = w * 0.06f
        drawLine(Color.Black, c.copy(x = c.x - a), c.copy(x = c.x + a), strokeWidth = t)
        drawLine(Color.Black, c.copy(y = c.y - a), c.copy(y = c.y + a), strokeWidth = t)
    }
}

/** Il verde di «fatto» (lo stesso delle casette verdi di Importa). */
private val GREEN = Color(0xFF4ADE80)
