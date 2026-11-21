package io.github.poty87.viewercraftstar

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import de.blinkt.openvpn.api.IOpenVPNAPIService
import de.blinkt.openvpn.api.IOpenVPNStatusCallback
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * La VPN verso casa, comandata da ViewerCraftStar: il motore è l'app «OpenVPN for Android» (de.blinkt.openvpn), che
 * resta installata ma non si apre mai. A ogni accensione le si passa la configurazione completa (dal file zip esportato
 * da Synology VPN Server, con server, porta, MTU, utente e password inseriti qui) con la sua API pubblica: nell'altra
 * app non resta nessun profilo e la password vive solo qui, cifrata. La prima volta «OpenVPN for Android» chiede di
 * consentire a ViewerCraftStar di comandarla (e, se non l'ha mai fatto, Android chiede il permesso per la VPN).
 */
object Vpn {
    const val PKG = "de.blinkt.openvpn"
    enum class State { NO_APP, NO_CONFIG, OFF, CONNECTING, ON, ERROR }

    var state by mutableStateOf(State.OFF); private set
    var detail by mutableStateOf(""); private set

    /** Un'altra VPN (AdGuard…) occupa il posto: si mostra il riquadro che invita a spegnerla. */
    var otherAsk by mutableStateOf(false)

    /** Accensione automatica già tentata da quando l'app è stata aperta. */
    @Volatile var autoTried = false

    /** Accensione in corso (dai consensi fino al collegamento): AdGuard non va riacceso. */
    @Volatile private var startingAt = 0L
    /** Vale al massimo un minuto: se lo stato non arriva, AdGuard non resta fermo per sempre. */
    var starting: Boolean
        get() = startingAt > 0 && System.currentTimeMillis() - startingAt < 60_000
        set(v) { startingAt = if (v) System.currentTimeMillis() else 0L }

    /** Richiesta di accendere la VPN da un punto senza Activity (dialoghi, notifica): la esegue la barra in basso. */
    var request by androidx.compose.runtime.mutableIntStateOf(0)

    private var api: IOpenVPNAPIService? = null
    private var ready: CompletableDeferred<IOpenVPNAPIService?>? = null
    private var registered = false
    private val main = Handler(Looper.getMainLooper())

    private val callback = object : IOpenVPNStatusCallback.Stub() {
        override fun newStatus(uuid: String?, state: String?, message: String?, level: String?) {
            main.post { onStatus(state ?: "", message ?: "", level ?: "") }
        }
    }

    private var appContext: Context? = null

    private fun onStatus(s: String, message: String, level: String) {
        val before = state
        if (level == "LEVEL_CONNECTED" || level == "LEVEL_NOTCONNECTED" || level == "LEVEL_AUTH_FAILED") starting = false
        state = when (level) {
            "LEVEL_CONNECTED" -> State.ON
            "LEVEL_NOTCONNECTED", "LEVEL_VPNPAUSED", "LEVEL_NONETWORK" -> State.OFF
            "LEVEL_AUTH_FAILED" -> State.ERROR
            "UNKNOWN_LEVEL", "" -> state
            else -> State.CONNECTING
        }
        detail = when (state) {
            State.ERROR -> L.t("Utente o password della VPN non accettati.", "VPN user or password not accepted.")
            State.CONNECTING -> L.t("Collegamento in corso…", "Connecting…")
            else -> ""
        }
        if (s == "NOPROCESS" || s == "EXITING" || s == "DISCONNECTED") state = State.OFF
        // la VPN di casa si è spenta (o non è partita): AdGuard torna acceso, se l'avevamo fermato noi
        if ((before == State.ON || before == State.CONNECTING) && (state == State.OFF || state == State.ERROR))
            appContext?.let { Adguard.resumeIfPaused(it, Settings(it)) }
        // la notifica di Android «VPN di ViewerCraftStar accesa» (con «Spegni») segue lo stato
        if (state != before) appContext?.let { VpnNotice.update(it, state == State.ON) }
    }

    // ---------------------------------------------------------------- app, profilo e impostazioni

    fun installed(context: Context) = runCatching { context.packageManager.getPackageInfo(PKG, 0); true }.getOrDefault(false)

    fun playStore(context: Context) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PKG")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(market) }.onFailure {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$PKG"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Lo zip del profilo, sul telefono (serve anche fuori casa, quando il NAS non si raggiunge senza la VPN). */
    fun zipFile(context: Context) = File(context.filesDir, "vpn/openvpn.zip")
    fun hasProfile(context: Context) = zipFile(context).isFile

    fun configured(context: Context, s: Settings) = hasProfile(context) && s.vpnUser.isNotBlank() && s.vpnPassword.isNotEmpty()

    /** Stato «a riposo» quando non c'è un collegamento in corso: senza app, senza profilo, spenta. */
    fun refresh(context: Context, s: Settings) {
        if (state == State.ON || state == State.CONNECTING) return
        state = when {
            !installed(context) -> State.NO_APP
            !configured(context, s) -> State.NO_CONFIG
            state == State.ERROR -> State.ERROR
            else -> State.OFF
        }
    }

    data class Profile(val name: String, val text: String, val files: Map<String, String>)

    /** Il primo .ovpn dello zip (o il file stesso, se è un .ovpn) con i file accanto (ca.crt, ta.key…). */
    fun read(bytes: ByteArray): Profile {
        val head = String(bytes, 0, minOf(bytes.size, 4), Charsets.ISO_8859_1)
        if (!head.startsWith("PK")) return Profile("profilo.ovpn", String(bytes, Charsets.UTF_8), emptyMap())
        val files = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory || e.size > 1_000_000) continue
                files[e.name.substringAfterLast('/')] = String(zip.readBytes(), Charsets.UTF_8)
            }
        }
        val ovpn = files.keys.firstOrNull { it.endsWith(".ovpn", true) }
            ?: error(L.t("Nello zip non c'è un file .ovpn.", "There's no .ovpn file in the zip."))
        return Profile(ovpn, files.getValue(ovpn), files - ovpn)
    }

    /** Il server e la porta scritti nel profilo («remote host porta»). */
    fun remote(p: Profile): Pair<String, Int?>? = p.text.lines().map { it.trim() }
        .firstOrNull { it.startsWith("remote ") }?.split(Regex("\\s+"))?.let { it.getOrNull(1).orEmpty() to it.getOrNull(2)?.toIntOrNull() }

    /**
     * La configurazione completa da passare a OpenVPN for Android: file accanto inseriti nel testo (<ca>…),
     * server e porta impostati qui, MTU ridotto per la rete mobile, utente e password in <auth-user-pass>.
     */
    fun build(context: Context, s: Settings): String {
        val p = read(zipFile(context).readBytes())
        val inlineTags = mapOf("ca" to "ca", "cert" to "cert", "key" to "key", "tls-auth" to "tls-auth", "tls-crypt" to "tls-crypt")
        val out = StringBuilder()
        var keyDirection: String? = null
        for (raw in p.text.lines()) {
            val line = raw.trim()
            val word = line.substringBefore(' ')
            when {
                word == "auth-user-pass" -> continue
                (word == "tun-mtu" || word == "mssfix") && s.vpnSmallMtu -> continue
                word == "remote" && s.vpnHost.isNotBlank() -> continue
                word in inlineTags && line.split(Regex("\\s+")).size >= 2 && p.files.containsKey(line.split(Regex("\\s+"))[1]) -> {
                    val parts = line.split(Regex("\\s+"))
                    if (word == "tls-auth") parts.getOrNull(2)?.let { keyDirection = it }
                    out.append("<$word>\n").append(p.files.getValue(parts[1]).trim()).append("\n</$word>\n")
                }
                else -> out.append(raw).append('\n')
            }
        }
        keyDirection?.let { out.append("key-direction $it\n") }
        if (s.vpnHost.isNotBlank()) {
            val port = s.vpnPort.takeIf { it > 0 } ?: remote(p)?.second ?: 1194
            out.append("remote ${s.vpnHost.trim()} $port\n")
        }
        if (s.vpnSmallMtu) out.append("tun-mtu 1340\nmssfix 1300\n")
        out.append("<auth-user-pass>\n${s.vpnUser.trim()}\n${s.vpnPassword}\n</auth-user-pass>\n")
        return out.toString()
    }

    // ---------------------------------------------------------------- collegamento con OpenVPN for Android

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val a = IOpenVPNAPIService.Stub.asInterface(service)
            api = a
            ready?.complete(a)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            api = null; registered = false; ready = null
        }
    }

    /** Si aggancia all'API di OpenVPN for Android (null se non è installata o non risponde). */
    suspend fun bind(context: Context): IOpenVPNAPIService? {
        appContext = context.applicationContext
        api?.let { return it }
        if (!installed(context)) return null
        val d = ready ?: CompletableDeferred<IOpenVPNAPIService?>().also { ready = it }
        val intent = Intent(IOpenVPNAPIService::class.java.name).setPackage(PKG)
        val ok = runCatching { context.applicationContext.bindService(intent, conn, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (!ok) { ready = null; return null }
        return withTimeoutOrNull(5000) { d.await() }
    }

    /** Lo stato arriva da OpenVPN for Android solo dopo il suo consenso: lo si chiede una volta, poi si ascolta. */
    fun listen(a: IOpenVPNAPIService, context: Context) {
        if (registered) return
        if (runCatching { a.prepare(context.packageName) }.getOrNull() != null) return
        runCatching { a.registerStatusCallback(callback); registered = true }
    }

    // ---------------------------------------------------------------- spegnimento quando l'app si chiude

    /** L'app è in primo piano (lo segna MainActivity in onStart/onStop). */
    @Volatile var foreground = false

    /** Lavori in background che hanno bisogno del NAS: spedizione e download dalla libreria. */
    private fun busy() = Shipper.running.value || DownloadService.progress.value != null

    /**
     * Uscendo dall'app (o alla fine di un lavoro in background con l'app chiusa): dopo qualche secondo, se l'app è
     * ancora chiusa e nessun lavoro ha bisogno del NAS, la VPN si spegne. Solo con la VPN automatica.
     */
    fun scheduleStop(context: Context) {
        val app = context.applicationContext
        main.removeCallbacksAndMessages(STOP)
        main.postAtTime({
            if (foreground || busy()) return@postAtTime
            if (Settings(app).vpnMode != "auto") return@postAtTime // solo nella modalità sperimentale
            if (state != State.ON && state != State.CONNECTING) return@postAtTime
            kotlinx.coroutines.MainScope().launch { disconnect(app) }
        }, STOP, android.os.SystemClock.uptimeMillis() + 90_000) // un minuto e mezzo: schermo spento o cambio d'app non la fanno cadere
    }

    private val STOP = Any()

    /** Spegne la VPN (senza bisogno di schermate: l'app è già autorizzata se l'ha accesa). */
    suspend fun disconnect(context: Context) {
        bind(context)?.let { a -> runCatching { a.disconnect() } }
    }

    /** All'avvio dell'app: se OpenVPN for Android è già stata autorizzata, si legge lo stato della VPN. */
    suspend fun attach(context: Context, s: Settings) {
        refresh(context, s)
        if (!installed(context)) return
        bind(context)?.let { listen(it, context) }
    }
}

/**
 * Il tocco sul pulsante della VPN: accende o spegne. La prima volta chiede i consensi (di OpenVPN for Android e,
 * se serve, quello di Android per la VPN); senza app o senza profilo porta alle Impostazioni.
 */
@Composable
fun rememberVpnToggle(settings: Settings, onSetup: () -> Unit): () -> Unit = rememberVpnControls(settings, onSetup).first

/** (tocco sul pulsante, sola accensione): la seconda serve alle richieste automatiche, che non devono mai spegnere. */
@Composable
fun rememberVpnControls(settings: Settings, onSetup: () -> Unit): Pair<() -> Unit, () -> Unit> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingStart by remember { mutableStateOf(false) }
    lateinit var go: () -> Unit
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && pendingStart) go()
        else { pendingStart = false; Vpn.starting = false; Vpn.refresh(context, settings) }
    }
    go = {
        scope.launch {
            // un'accensione alla volta: una seconda, ravvicinata, farebbe ricollegare la VPN
            if (Vpn.starting && !pendingStart) return@launch
            // un'altra VPN accesa (AdGuard…): fuori dalla modalità sperimentale non la si tocca, si chiede di spegnerla.
            // Prima si chiede lo stato a OpenVPN for Android (appena aperta l'app potrebbe non essere ancora arrivato)
            if (settings.vpnMode != "auto" && Shipper.vpnOn(context) && Vpn.state != Vpn.State.ON) {
                Vpn.bind(context)?.let { Vpn.listen(it, context) }
                kotlinx.coroutines.delay(400)
                if (Vpn.state == Vpn.State.ON || Vpn.state == Vpn.State.CONNECTING) return@launch // è già la nostra
                Vpn.otherAsk = true; return@launch
            }
            Vpn.starting = true
            val a = Vpn.bind(context)
            if (a == null) { pendingStart = false; Vpn.starting = false; Notices.post("error", L.t("OpenVPN for Android non risponde.", "OpenVPN for Android isn't answering.")); return@launch }
            // 1) consenso di OpenVPN for Android a essere comandata da questa app (una volta sola)
            runCatching { a.prepare(context.packageName) }.getOrNull()?.let { pendingStart = true; launcher.launch(it); return@launch }
            Vpn.listen(a, context)
            // 2) permesso di Android per le VPN (se OpenVPN for Android non l'ha mai avuto)
            runCatching { a.prepareVPNService() }.getOrNull()?.let { pendingStart = true; launcher.launch(it); return@launch }
            pendingStart = false
            val cfg = runCatching { withContext(Dispatchers.IO) { Vpn.build(context, settings) } }
            cfg.onFailure { Vpn.starting = false; Notices.post("error", L.t("Profilo VPN non leggibile: ", "VPN profile can't be read: ") + (it.message ?: "")); return@launch }
            // con il permesso di essere scavalcata: l'app può provare il NAS sul Wi-Fi e spegnere la VPN se è a casa
            val extras = android.os.Bundle().apply { putBoolean("de.blinkt.openvpn.api.ALLOW_VPN_BYPASS", true) }
            // Android tiene una sola VPN: se AdGuard è acceso lo si mette in pausa (e si riaccende quando questa si spegne)
            Adguard.cancelResume()
            Adguard.pauseIfNeeded(context, settings)
            runCatching { a.startVPNwithExtras(cfg.getOrThrow(), extras) }
                .onFailure { Vpn.starting = false; Adguard.resumeIfPaused(context, settings); Notices.post("error", L.t("La VPN non è partita: ", "The VPN didn't start: ") + (it.message ?: it.javaClass.simpleName)) }
        }
    }
    val start: () -> Unit = { if (Vpn.installed(context) && Vpn.configured(context, settings)) go() }
    return Pair({
        when {
            !Vpn.installed(context) -> { Notices.post("warn", L.t("Serve l'app «OpenVPN for Android»: la trovi in Impostazioni › VPN.",
                "The “OpenVPN for Android” app is needed: find it in Settings › VPN.")); onSetup() }
            !Vpn.configured(context, settings) -> { Notices.post("warn", L.t("Imposta la VPN in Impostazioni › VPN: profilo, utente e password.",
                "Set up the VPN in Settings › VPN: profile, user and password.")); onSetup() }
            // accesa davvero (Android vede una VPN) o in collegamento da pochi istanti: si spegne; altrimenti si accende
            (Vpn.state == Vpn.State.ON || Vpn.state == Vpn.State.CONNECTING) && Shipper.vpnOn(context) -> scope.launch {
                Vpn.bind(context)?.let { a -> runCatching { a.disconnect() } }
            }
            else -> go()
        }
    }, start)
}
