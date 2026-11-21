package io.github.poty87.viewercraftstar

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** L'icona della VPN: uno scudo con il buco della serratura (del colore delle altre icone della barra). */
@Composable
fun VpnIcon(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width
        val t = w * 0.09f
        val p = Path().apply {
            moveTo(w * 0.50f, w * 0.06f)
            lineTo(w * 0.86f, w * 0.20f)
            lineTo(w * 0.86f, w * 0.46f)
            cubicTo(w * 0.86f, w * 0.70f, w * 0.70f, w * 0.86f, w * 0.50f, w * 0.95f)
            cubicTo(w * 0.30f, w * 0.86f, w * 0.14f, w * 0.70f, w * 0.14f, w * 0.46f)
            lineTo(w * 0.14f, w * 0.20f)
            close()
        }
        drawPath(p, color, style = Stroke(t, join = StrokeJoin.Round))
        drawCircle(color, w * 0.09f, Offset(w * 0.50f, w * 0.44f))
        drawLine(color, Offset(w * 0.50f, w * 0.48f), Offset(w * 0.50f, w * 0.66f), strokeWidth = t, cap = StrokeCap.Round)
    }
}

/** Il colore del pallino della VPN: verde accesa, giallo mentre si collega, rosso spenta (o errore). */
fun vpnDot(state: Vpn.State): Color = when (state) {
    Vpn.State.ON -> Color(0xFF4ADE80)
    Vpn.State.CONNECTING -> Color(0xFFFBBF24)
    else -> Color(0xFFF87171)
}

/**
 * Impostazioni › VPN: app «OpenVPN for Android» (link al Play Store se manca), profilo dallo zip di Synology VPN Server
 * (salvato sul telefono e copiato nella cartella di servizio), server e porta esterni, MTU ridotto, utente e password.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun VpnCard(vm: AppViewModel, nasReachable: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = vm.settings
    @Suppress("UNUSED_VARIABLE") val t = Perms.tick // rilegge «installata?» al ritorno dal Play Store
    var hasProfile by remember { mutableStateOf(Vpn.hasProfile(context)) }
    var profileInfo by remember { mutableStateOf("") }
    var host by remember { mutableStateOf(s.vpnHost) }
    var port by remember { mutableStateOf(if (s.vpnPort > 0) s.vpnPort.toString() else "") }
    var small by remember { mutableStateOf(s.vpnSmallMtu) }
    var mode by remember { mutableStateOf(s.vpnMode) }
    var adPassword by remember { mutableStateOf(s.adguardPassword) }
    var user by remember { mutableStateOf(s.vpnUser) }
    var password by remember { mutableStateOf(s.vpnPassword) }
    var busy by remember { mutableStateOf(false) }
    fun save() {
        s.vpnHost = host; s.vpnPort = port.toIntOrNull() ?: 0; s.vpnSmallMtu = small; s.vpnMode = mode; s.adguardPassword = adPassword; s.vpnUser = user; s.vpnPassword = password
        Vpn.refresh(context, s)
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { save() } }
    LaunchedEffect(hasProfile) {
        profileInfo = if (!hasProfile) "" else runCatching {
            val p = withContext(Dispatchers.IO) { Vpn.read(Vpn.zipFile(context).readBytes()) }
            val r = Vpn.remote(p)
            p.name + (r?.let { "  ·  " + L.t("nel file: ", "in the file: ") + it.first + (it.second?.let { n -> ":$n" } ?: "") } ?: "")
        }.getOrDefault(L.t("profilo non leggibile", "profile can't be read"))
    }

    /** Un profilo nuovo (zip o .ovpn): controllato, salvato sul telefono, copiato nella cartella di servizio. */
    fun take(bytes: ByteArray) {
        scope.launch {
            val p = runCatching { Vpn.read(bytes) }
            p.onFailure { Notices.post("error", L.t("File non valido: ", "Invalid file: ") + (it.message ?: "")); return@launch }
            withContext(Dispatchers.IO) { Vpn.zipFile(context).let { it.parentFile?.mkdirs(); it.writeBytes(bytes) } }
            // server e porta: si propongono quelli del file, se non sono il segnaposto di Synology
            Vpn.remote(p.getOrThrow())?.let { (h, n) ->
                if (host.isBlank() && !h.contains("YOUR_SERVER", true)) host = h
                if (port.isBlank() && n != null) port = n.toString()
            }
            hasProfile = true
            save()
            if (nasReachable && ConfigBackup.ready(s)) runCatching { ConfigBackup.putVpn(s, bytes) }
                .onSuccess { Notices.post("ok", L.t("Profilo VPN salvato, anche nella cartella di servizio.", "VPN profile saved, also in the service folder.")) }
                .onFailure { Notices.post("warn", L.t("Profilo salvato sul telefono; nella cartella di servizio non sono riuscito a copiarlo.",
                    "Profile saved on the phone; I couldn't copy it to the service folder.")) }
            else Notices.post("ok", L.t("Profilo VPN salvato sul telefono.", "VPN profile saved on the phone."))
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()?.let { take(it) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(L.t("Per raggiungere il NAS fuori casa. La VPN si accende e si spegne con lo scudo nella barra in basso: il pallino è verde quando è accesa, rosso quando è spenta. Il motore è l'app «OpenVPN for Android», che non serve aprire.",
            "To reach the NAS away from home. Turn the VPN on and off with the shield in the bottom bar: the dot is green when it's on, red when it's off. The engine is the “OpenVPN for Android” app, which you never need to open."),
            color = C.muted, fontSize = 12.sp)
        // 1) l'app che fa da motore
        if (!Vpn.installed(context)) {
            Text(L.t("«OpenVPN for Android» non è installata.", "“OpenVPN for Android” isn't installed."), color = Color(0xFFFBBF24), fontSize = 14.sp)
            PillButton(onClick = { Vpn.playStore(context) }, primary = true) { Text(L.t("Scaricala dal Play Store", "Get it from the Play Store")) }
        } else Text(L.t("«OpenVPN for Android» è installata.", "“OpenVPN for Android” is installed."), color = C.text, fontSize = 14.sp)
        // 2) il profilo
        Text(if (hasProfile) L.t("Profilo: ", "Profile: ") + profileInfo
            else L.t("Nessun profilo: importa lo zip esportato da Synology VPN Server (OpenVPN › Esporta configurazione).",
                "No profile: import the zip exported from Synology VPN Server (OpenVPN › Export configuration)."),
            color = if (hasProfile) C.text else C.muted, fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(onClick = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/x-openvpn-profile", "application/octet-stream", "*/*")) },
                primary = !hasProfile) { Text(L.t("Importa zip…", "Import zip…")) }
            PillButton(onClick = {
                busy = true
                scope.launch {
                    runCatching { ConfigBackup.getVpn(s) }
                        .onSuccess { b -> if (b == null) Notices.post("info", L.t("Nella cartella di servizio non c'è un profilo VPN.", "There's no VPN profile in the service folder."))
                            else take(b) }
                        .onFailure { Notices.post("error", L.t("Non riesco a leggere la cartella di servizio.", "Can't read the service folder.")) }
                    busy = false
                }
            }, enabled = nasReachable && ConfigBackup.ready(s) && !busy) { Text(L.t("Dalla cartella di servizio", "From the service folder")) }
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = C.accent, strokeWidth = 2.dp)
        }
        // 3) server e porta visti da fuori casa (il router può inoltrare una porta esterna diversa)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(host, { host = it }, Modifier.weight(1f), singleLine = true,
                label = { Text(L.t("Server (es. nome.synology.me)", "Server (e.g. name.synology.me)")) })
            OutlinedTextField(port, { v -> port = v.filter { it.isDigit() }.take(5) }, Modifier.size(width = 110.dp, height = 64.dp), singleLine = true,
                label = { Text(L.t("Porta", "Port")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(small, { small = it })
            Text(L.t("Pacchetti più piccoli (consigliato: sulla rete mobile quelli grandi si perdono)",
                "Smaller packets (recommended: on mobile data large ones get lost)"), color = C.text, fontSize = 14.sp)
        }
        // 4) credenziali: restano sul telefono (la password cifrata), mai nel backup
        OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(L.t("Utente della VPN", "VPN user")) })
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(L.t("Password della VPN", "VPN password")) },
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        // come si comporta: manuale (di partenza), all'avvio, automatica (sperimentale); una sola alla volta
        Text(L.t("Come si accende", "How it turns on"), color = C.text, fontSize = 15.sp)
        val modes = listOf(
            Triple("manual", L.t("Manuale", "Manual"), L.t("La accendi e la spegni tu con lo scudo. Resta accesa finché non la spegni (anche dalla notifica).",
                "You turn it on and off with the shield. It stays on until you turn it off (also from the notification).")),
            Triple("start", L.t("All'avvio dell'app", "When the app opens"), L.t("Aprendo l'app fuori casa si accende da sola; la spegni tu.",
                "Opening the app away from home turns it on; you turn it off.")),
            Triple("auto", L.t("Completamente automatica (sperimentale)", "Fully automatic (experimental)"),
                L.t("Si accende aprendo l'app fuori casa, si spegne sul Wi-Fi di casa e un minuto e mezzo dopo l'uscita dall'app (se nessun invio la usa), mette in pausa AdGuard e lo riaccende. Può essere meno stabile.",
                    "Turns on when the app opens away from home, off on the home Wi-Fi and a minute and a half after leaving the app (if no sending uses it), pauses AdGuard and turns it back on. May be less stable.")))
        for ((key, title, desc) in modes) Row(Modifier.fillMaxWidth().clickable { mode = key }, verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.RadioButton(mode == key, { mode = key },
                colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = C.accent))
            Column(Modifier.weight(1f)) {
                Text(title, color = C.text, fontSize = 14.sp)
                Text(desc, color = C.muted, fontSize = 12.sp)
            }
        }
        // AdGuard: solo la modalità sperimentale lo mette in pausa da sola (serve la sua password di automazione)
        if (mode == "auto" && Adguard.installed(context)) {
            OutlinedTextField(adPassword, { adPassword = it.trim() }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(L.t("Password di automazione di AdGuard", "AdGuard automation password")) },
                visualTransformation = PasswordVisualTransformation())
            Text(L.t("In AdGuard: Impostazioni › Generali › Avanzate › tocca la scritta «Automazione». Senza password AdGuard non accetta il comando.",
                "In AdGuard: Settings › General › Advanced › tap the “Automation” text. Without it AdGuard ignores the command."),
                color = C.muted, fontSize = 12.sp)
        }
        Text(L.t("Utente e password restano solo su questo telefono (la password cifrata) e non vanno nel backup: dopo un ripristino si riscrivono. Android tiene una sola VPN alla volta: altre app VPN si fermano mentre questa è accesa. A casa tienila spenta.",
            "User and password stay only on this phone (the password encrypted) and are not in the backup: after a restore, type them again. Android keeps one VPN at a time: other VPN apps stop while this one is on. Keep it off at home."),
            color = C.muted, fontSize = 12.sp)
    }
}
