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
import androidx.compose.material3.Button
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
    var start by remember { mutableIntStateOf(0) }
    var list by remember { mutableStateOf<List<PhotoItem>>(emptyList()) }
    var libList by remember { mutableStateOf<List<LibPhoto>>(emptyList()) }
    BackHandler(enabled = screen != "grid") { screen = "grid" }
    when (screen) {
        "viewer" -> ViewerScreen(vm, list, start) { screen = "grid" }
        "libviewer" -> LibraryViewer(lib, libList, start) { screen = "grid" }
        "settings" -> SettingsScreen(vm, lib) { screen = "grid"; vm.reload(); lib.refresh() }
        else -> GridScreen(vm, lib, onOpen = { i -> list = vm.visible; start = i; screen = "viewer" },
            onOpenLib = { photos, i -> libList = photos; start = i; screen = "libviewer" },
            onSettings = { screen = "settings" })
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
