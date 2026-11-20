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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Home
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
    private val vm: AppViewModel by viewModels()
    private val lib: LibraryViewModel by viewModels()
    private var granted by mutableStateOf(false)

    private val ask = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasPermission()
        if (granted) vm.reload()
    }

    private fun hasPermission() =
        checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        BuildConfigInfo.version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        granted = hasPermission()
        Tips.compact = vm.settings.compactTips
        setContent {
            AppTheme {
                if (!granted) PermissionScreen { ask.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES)) }
                else App(vm, lib)
            }
        }
    }

    override fun onResume() {
        super.onResume()
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
    var screen by remember { mutableStateOf("grid") }
    // le tre parti dell'app, nella barra in basso: Librerie, Importa (le foto del telefono), Impostazioni
    var tab by remember { mutableStateOf(if (vm.inLibrary) "library" else "import") }
    var start by remember { mutableIntStateOf(0) }
    var list by remember { mutableStateOf<List<PhotoItem>>(emptyList()) }
    var libList by remember { mutableStateOf<List<LibPhoto>>(emptyList()) }
    // le posizioni delle gallerie vivono qui: chiudendo una foto si torna esattamente dov'eri
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val libState = androidx.compose.foundation.lazy.rememberLazyListState()
    fun go(t: String) {
        if (tab == "settings" && t != "settings") { vm.reload(); lib.refresh() }
        tab = t
        if (t == "library") vm.inLibrary = true
        if (t == "import") vm.inLibrary = false
    }
    BackHandler(enabled = screen != "grid" || tab == "settings") { if (screen != "grid") screen = "grid" else go("library") }
    when (screen) {
        "viewer" -> ViewerScreen(vm, list, start) { screen = "grid" }
        "libviewer" -> LibraryViewer(lib, libList, start) { screen = "grid" }
        else -> Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                if (tab == "settings") SettingsScreen(vm, lib) { go("library") }
                else GridScreen(vm, lib, gridState, libState, onOpen = { i -> list = vm.visible; start = i; screen = "viewer" },
                    onOpenLib = { photos, i -> libList = photos; start = i; screen = "libviewer" })
            }
            BottomBar(tab, lib.selecting && tab == "library") { go(it) }
        }
    }
}

/** La barra fissa in basso: Librerie, Importa, Impostazioni. */
@androidx.compose.runtime.Composable
private fun BottomBar(tab: String, hidden: Boolean, onPick: (String) -> Unit) {
    if (hidden) return // durante la selezione in basso c'è la barra delle azioni
    val items: List<Triple<String, androidx.compose.ui.graphics.vector.ImageVector, String>> = listOf(
        Triple("library", Icons.Filled.Home, L.t("Librerie", "Libraries")),
        Triple("import", Icons.AutoMirrored.Filled.Send, L.t("Importa", "Import")),
        Triple("settings", Icons.Filled.Settings, L.t("Impostazioni", "Settings")),
    )
    NavigationBar(containerColor = androidx.compose.ui.graphics.Color(0xFF111111),
        tonalElevation = 0.dp) {
        for ((key, icon, label) in items) {
            NavigationBarItem(selected = tab == key, onClick = { onPick(key) },
                icon = { Icon(icon, null) }, label = { Text(label, fontSize = 12.sp) },
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
        Text(L.t("Per mostrare i DNG della cartella serve il permesso di accedere a foto e video. Scegli «Consenti tutto»: con l'accesso solo ad alcune foto le altre non compaiono.",
            "To show the DNG files of the folder the app needs access to photos and videos. Choose “Allow all”: with access to only some photos, the others won't appear."),
            color = C.muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 16.dp))
        Button(onClick = onAsk) { Text(L.t("Consenti l'accesso", "Allow access")) }
    }
}
