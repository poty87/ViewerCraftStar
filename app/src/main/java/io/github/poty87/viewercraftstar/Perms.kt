package io.github.poty87.viewercraftstar

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * I permessi dell'app, tutti in un posto. Android non permette di darli all'installazione: quelli normali si chiedono
 * con le sue finestre (qui tutti insieme), la «gestione dei media» si attiva una volta dalle impostazioni di Android e
 * da lì in poi spostare nel cestino o sistemare le foto non chiede più conferma ogni volta.
 */
object Perms {
    /** Cambia a ogni ritorno nell'app (onResume): fa rileggere lo stato dei permessi. */
    var tick by mutableIntStateOf(0)

    data class Group(val title: String, val why: String, val perms: List<String>)

    fun groups(): List<Group> = listOf(
        Group(L.t("Foto", "Photos"), L.t("Per vedere e spedire i raw del telefono (con la posizione dove sono state scattate).",
            "To see and send the phone's raw files (with the place where they were taken)."),
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.ACCESS_MEDIA_LOCATION)),
        Group(L.t("Notifiche", "Notifications"), L.t("Per seguire spedizioni e scarichi anche ad app chiusa.",
            "To follow sending and downloads even with the app closed."), listOf(Manifest.permission.POST_NOTIFICATIONS)),
        Group(L.t("Dispositivi nelle vicinanze", "Nearby devices"), L.t("Per collegarsi alla fotocamera (Bluetooth e Wi-Fi).",
            "To connect to the camera (Bluetooth and Wi-Fi)."),
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES)),
    )

    fun has(context: Context, p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    fun missing(context: Context): List<String> = groups().flatMap { it.perms }.filterNot { has(context, it) }

    /** «Gestione dei media»: spostare nel cestino e modificare le foto senza la conferma di Android ogni volta. */
    fun canManageMedia(context: Context) = MediaStore.canManageMedia(context)

    fun manageMediaIntent(context: Context) =
        Intent(android.provider.Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:" + context.packageName))

    fun allGranted(context: Context) = missing(context).isEmpty() && canManageMedia(context)
}

/** Chiede tutti i permessi che mancano: prima quelli normali (una finestra di Android), poi la gestione dei media. */
@Composable
fun rememberAskAll(onDone: () -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val media = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { Perms.tick++; onDone() }
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        Perms.tick++
        if (!Perms.canManageMedia(context)) media.launch(Perms.manageMediaIntent(context)) else onDone()
    }
    return {
        val miss = Perms.missing(context)
        if (miss.isNotEmpty()) runtime.launch(miss.toTypedArray())
        else if (!Perms.canManageMedia(context)) media.launch(Perms.manageMediaIntent(context))
        else onDone()
    }
}

/** Le righe dei permessi con il loro stato (spunta verde o «da concedere») e un solo pulsante per i mancanti. */
@Composable
fun PermissionsCard() {
    val context = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val t = Perms.tick // rilegge lo stato quando si torna nell'app
    val askAll = rememberAskAll()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!Tips.compact) Text(L.t("Android non li dà all'installazione: qui li concedi tutti in una volta. Con la gestione dei media spostare le foto nel cestino non chiede più conferma ogni volta.",
            "Android doesn't grant them at install time: here you grant them all at once. With media management, moving photos to the bin no longer asks every time."),
            color = C.muted, fontSize = 12.sp)
        val rows = Perms.groups().map { Triple(it.title, it.why, it.perms.all { p -> Perms.has(context, p) }) } +
            Triple(L.t("Gestione dei media", "Media management"),
                L.t("Per spedire e spostare nel cestino le foto senza la conferma di Android ogni volta.",
                    "To send and bin photos without Android's confirmation every time."), Perms.canManageMedia(context))
        for ((title, why, ok) in rows) Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = C.text, fontSize = 15.sp)
                Text(why, color = C.muted, fontSize = 12.sp)
            }
            if (ok) Icon(Icons.Filled.Check, L.t("Concesso", "Granted"), tint = Color(0xFF4ADE80), modifier = Modifier.size(22.dp))
            else Text(L.t("da concedere", "to grant"), color = Color(0xFFFBBF24), fontSize = 12.sp)
        }
        val all = Perms.allGranted(context)
        PillButton(onClick = askAll, primary = !all, enabled = !all) {
            Text(if (all) L.t("Tutti concessi", "All granted") else L.t("Concedi quelli che mancano", "Grant the missing ones"))
        }
    }
}
