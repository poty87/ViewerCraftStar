package io.github.poty87.viewercraftstar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object BuildConfigInfo {
    var version = ""
}

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_VPN = "connect_vpn" }
    private val vm: AppViewModel by viewModels()
    private val lib: LibraryViewModel by viewModels()
    private var granted by mutableStateOf(false)
    // al primo avvio, dopo i permessi normali, una volta sola: la «gestione dei media» (si attiva nelle impostazioni di Android)
    private var mediaStep by mutableStateOf(false)
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val media = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { mediaStep = false; Perms.tick++ }

    private val ask = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasPermission()
        Perms.tick++
        if (granted) {
            vm.reload()
            mediaStep = !Perms.canManageMedia(this) && !prefs.getBoolean("media_asked", false)
        }
    }

    private fun hasPermission() =
        checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        BuildConfigInfo.version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        granted = hasPermission()
        Notices.init(applicationContext)
        NasId.init(applicationContext)
        setContent {
            AppTheme {
                // tutti i permessi normali in una volta sola (foto, notifiche, dispositivi nelle vicinanze per la fotocamera)
                if (!granted) PermissionScreen { ask.launch(Perms.missing(this).ifEmpty { listOf(Manifest.permission.READ_MEDIA_IMAGES) }.toTypedArray()) }
                else if (mediaStep) MediaStepScreen(
                    onOpen = { prefs.edit().putBoolean("media_asked", true).apply(); media.launch(Perms.manageMediaIntent(this)) },
                    onLater = { prefs.edit().putBoolean("media_asked", true).apply(); mediaStep = false })
                else App(vm, lib)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_VPN, false)) Vpn.request++
    }

    // aprendo l'app (non al ritorno dalle finestre dei permessi) la VPN automatica riprova da capo
    override fun onStart() {
        super.onStart()
        Vpn.foreground = true
        vm.resetAutoVpn()
    }

    // uscendo dall'app la VPN si spegne (dopo qualche secondo), se nessuna spedizione o download la usa
    override fun onStop() {
        super.onStop()
        Vpn.foreground = false
        Vpn.scheduleStop(this)
    }

    override fun onResume() {
        super.onResume()
        Perms.tick++
        lifecycleScope.launch { Vpn.attach(this@MainActivity, vm.settings) }
        granted = hasPermission()
        if (granted) {
            vm.reload()
            vm.checkConnection()
            vm.sync(quiet = true) // quello che è rimasto in coda parte appena si torna nell'app
        }
    }
}

@androidx.compose.runtime.Composable
private fun App(vm: AppViewModel, lib: LibraryViewModel) {
    // appena la VPN risulta accesa si ricontrolla dove si è: a casa (il NAS risponde dal Wi-Fi) si rispegne
    androidx.compose.runtime.LaunchedEffect(Vpn.state) { if (Vpn.state == Vpn.State.ON) vm.checkConnection() }
    var screen by remember { mutableStateOf("grid") }
    // le tre parti dell'app, nella barra in basso: Librerie, Importa (le foto del telefono), Impostazioni
    var tab by remember { mutableStateOf(if (vm.inLibrary) "library" else "import") }
    var start by remember { mutableIntStateOf(0) }
    var list by remember { mutableStateOf<List<PhotoItem>>(emptyList()) }
    var libList by remember { mutableStateOf<List<LibPhoto>>(emptyList()) }
    // le posizioni delle gallerie vivono qui: chiudendo una foto si torna esattamente dov'eri
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val libState = androidx.compose.foundation.lazy.rememberLazyListState()
    // la barra in basso si nasconde scorrendo verso il basso e ricompare scorrendo verso l'alto (come YouTube)
    var barShown by remember { mutableStateOf(true) }
    var barH by remember { mutableStateOf(80.dp) } // altezza misurata della barra (con lo spazio dei tasti di sistema)
    val barScroll = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            private var acc = 0f
            // conta solo lo scorrimento avvenuto davvero: se non c'è niente da scorrere (zero foto, pagina corta) la barra resta
            override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset,
                                      source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                val dy = consumed.y
                if (dy == 0f) return androidx.compose.ui.geometry.Offset.Zero
                // si accumula nella stessa direzione: la barra si muove solo dopo un tratto deciso, non a ogni tremolio
                acc = if ((dy < 0) == (acc < 0)) acc + dy else dy
                if (acc < -100f) barShown = false // per nasconderla serve un tratto un po' più lungo che per farla tornare
                if (acc > 40f) barShown = true
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    fun go(t: String) {
        barShown = true
        if (tab == "settings" && t != "settings") { vm.reload(); lib.refresh() }
        tab = t
        if (t == "library") vm.inLibrary = true
        if (t == "import") vm.inLibrary = false
    }
    BackHandler(enabled = screen != "grid" || tab == "settings" || tab == "notices") { if (screen != "grid") screen = "grid" else go("library") }
    val blur = Confirm.ask != null || Blur.depth > 0
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().then(if (blur) Modifier.blur(14.dp) else Modifier)) {
    when (screen) {
        "viewer" -> ViewerScreen(vm, list, start) { screen = "grid" }
        "libviewer" -> LibraryViewer(lib, libList, start) { screen = "grid" }
        "camera" -> CameraScreen(vm) { screen = "grid"; vm.reload() }
        "disk" -> DiskScreen(vm) { screen = "grid"; vm.reload() }
        // la barra in basso sta sopra le pagine (traslucida): le liste le scorrono sotto, con lo spazio in fondo per l'ultima riga
        else -> androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val selectingNow = lib.selecting && tab == "library"
            val shown by androidx.compose.animation.core.animateDpAsState(if (barShown && !selectingNow) barH else 0.dp, label = "bar")
            androidx.compose.runtime.CompositionLocalProvider(LocalBarSpace provides barH, LocalBarShown provides shown) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().nestedScroll(barScroll)) {
                if (tab == "settings") SettingsScreen(vm, lib) { go("library") }
                else if (tab == "notices") NoticesScreen()
                else GridScreen(vm, lib, gridState, libState, onOpen = { i -> list = vm.visible; start = i; screen = "viewer" },
                    onOpenLib = { photos, i -> libList = photos; start = i; screen = "libviewer" },
                    onCamera = { screen = "camera" }, onDisk = { screen = "disk" })
            }
            }
            androidx.compose.animation.AnimatedVisibility(barShown, Modifier.align(Alignment.BottomCenter),
                enter = androidx.compose.animation.slideInVertically { it },
                exit = androidx.compose.animation.slideOutVertically { it }) {
                androidx.compose.foundation.layout.Box(Modifier.onSizeChanged { if (it.height > 0) barH = with(density) { it.height.toDp() } }) {
                    BottomBar(tab, selectingNow, vm.settings) { go(it) }
                }
            }
        }
    }
    }
    // notifiche in sovrimpressione (sopra la barra in basso) e conferme a tutto schermo, valide in ogni pagina
    NoticeOverlay(Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
        .padding(bottom = if (screen == "grid" && barShown && !(lib.selecting && tab == "library")) 72.dp else 8.dp))
    ConfirmOverlay()
    }
}

/** La barra fissa in basso: Librerie, Importa, Impostazioni. */
@androidx.compose.runtime.Composable
private fun BottomBar(tab: String, hidden: Boolean, settings: Settings, onPick: (String) -> Unit) {
    // la VPN: il tocco accende o spegne; le richieste da dialoghi e notifica passano da qui (servono i consensi)
    val (vpnToggle, vpnStart) = rememberVpnControls(settings) { onPick("settings") }
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    androidx.compose.runtime.LaunchedEffect(Vpn.request) {
        // la richiesta arriva solo quando il NAS non si raggiunge (nessuna VPN, o un'altra come AdGuard): si accende
        if (Vpn.request > 0) vpnStart()
    }
    // un'altra VPN (AdGuard…) occupa il posto: si invita a spegnerla, con il pulsante che apre l'app
    if (Vpn.otherAsk) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val adguard = Adguard.installed(ctx)
        BlurDialog({ Vpn.otherAsk = false }) {
            TitleText(L.t("Un'altra VPN è accesa", "Another VPN is on"))
            Text(if (adguard) L.t("AdGuard è acceso e occupa il posto della VPN: Android ne tiene una sola alla volta. Spegnilo, poi torna qui e tocca di nuovo lo scudo per collegarti al NAS.",
                "AdGuard is on and takes the VPN slot: Android keeps one at a time. Turn it off, then come back and tap the shield again to connect to the NAS.")
                else L.t("Un'altra app VPN è accesa e occupa il posto: Android ne tiene una sola alla volta. Spegnila, poi tocca di nuovo lo scudo.",
                "Another VPN app is on and takes the slot: Android keeps one at a time. Turn it off, then tap the shield again."),
                color = C.muted, fontSize = 14.sp)
            DialogButtons(L.t("Annulla", "Cancel") to { Vpn.otherAsk = false },
                main = (if (adguard) L.t("Apri AdGuard", "Open AdGuard") else L.t("Impostazioni VPN", "VPN settings")) to {
                    Vpn.otherAsk = false; Adguard.open(ctx) })
        }
    }
    if (hidden) return // durante la selezione in basso c'è la barra delle azioni
    val items: List<Triple<String, androidx.compose.ui.graphics.vector.ImageVector, String>> = listOf(
        Triple("library", Icons.Filled.Home, L.t("Librerie", "Libraries")),
        Triple("import", Icons.AutoMirrored.Filled.Send, L.t("Importa", "Import")),
        Triple("notices", Icons.Filled.Notifications, L.t("Notifiche", "Notifications")),
        Triple("settings", Icons.Filled.Settings, L.t("Impostazioni", "Settings")),
    )
    NavigationBar(containerColor = BarBg,
        tonalElevation = 0.dp) {
        for ((key, icon, label) in items) {
            // lo scudo della VPN, prima delle Impostazioni: stesso colore delle altre icone, pallino rosso/giallo/verde
            if (key == "settings") NavigationBarItem(selected = false, onClick = vpnToggle,
                icon = {
                    androidx.compose.material3.BadgedBox(badge = {
                        androidx.compose.foundation.layout.Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            .background(vpnDot(Vpn.state)))
                    }) { VpnIcon(Modifier.size(24.dp), C.muted) }
                },
                label = { BarLabel("VPN") },
                colors = NavigationBarItemDefaults.colors(indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unselectedIconColor = C.muted, unselectedTextColor = C.muted))
            NavigationBarItem(selected = tab == key, onClick = { onPick(key) },
                icon = {
                    androidx.compose.material3.BadgedBox(badge = {
                        if (key == "notices" && Notices.unread > 0 && tab != "notices")
                            androidx.compose.material3.Badge(containerColor = C.accent, contentColor = androidx.compose.ui.graphics.Color.Black) {
                                Text(if (Notices.unread > 99) "99+" else "${Notices.unread}") }
                    }) { Icon(icon, null) }
                }, label = { BarLabel(label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = C.accent, selectedTextColor = C.accent, indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unselectedIconColor = C.muted, unselectedTextColor = C.muted))
        }
    }
}

@androidx.compose.runtime.Composable
private fun PermissionScreen(onAsk: () -> Unit) {
    Column(Modifier.fillMaxSize().background(C.bg).padding(32.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        TitleText("ViewerCraftStar")
        Text(L.t("Per mostrare i raw della cartella serve il permesso di accedere a foto e video. Scegli «Consenti tutto»: con l'accesso solo ad alcune foto le altre non compaiono.",
            "To show the raw files of the folder the app needs access to photos and videos. Choose “Allow all”: with access to only some photos, the others won't appear."),
            color = C.muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 16.dp))
        Text(L.t("Insieme chiedo anche le notifiche (per seguire le spedizioni) e i dispositivi nelle vicinanze (per la fotocamera): così non te li chiedo più dopo.",
            "Together I also ask for notifications (to follow sending) and nearby devices (for the camera), so I won't ask again later."),
            color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 16.dp))
        PillButton(onClick = onAsk, primary = true) { Text(L.t("Consenti l'accesso", "Allow access")) }
    }
}

/** Secondo passo del primo avvio: la gestione dei media, che Android concede solo dalle sue impostazioni. */
@androidx.compose.runtime.Composable
private fun MediaStepScreen(onOpen: () -> Unit, onLater: () -> Unit) {
    Column(Modifier.fillMaxSize().background(C.bg).padding(32.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        TitleText(L.t("Un ultimo permesso", "One last permission"))
        Text(L.t("Con la «gestione dei media» l'app sposta nel cestino le foto già al sicuro a casa senza che Android chieda conferma ogni volta. Si attiva una volta sola, nella pagina di Android che si apre ora: accendi l'interruttore e torna indietro.",
            "With “media management” the app moves the photos already safe at home to the bin without Android asking every time. You turn it on once, in the Android page that opens now: flip the switch and come back."),
            color = C.muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 16.dp))
        PillButton(onClick = onOpen, primary = true) { Text(L.t("Apri le impostazioni", "Open settings")) }
        androidx.compose.foundation.layout.Spacer(Modifier.padding(6.dp))
        PillButton(onClick = onLater) { Text(L.t("Più tardi (da Impostazioni › Permessi)", "Later (from Settings › Permissions)")) }
    }
}

/** Le scritte della barra in basso: una riga sola anche con cinque voci (niente «Impostazi-oni»). */
@androidx.compose.runtime.Composable
private fun BarLabel(text: String) =
    Text(text, fontSize = 11.sp, letterSpacing = 0.sp, maxLines = 1, softWrap = false)
