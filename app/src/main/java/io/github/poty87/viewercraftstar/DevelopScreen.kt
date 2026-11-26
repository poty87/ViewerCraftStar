package io.github.poty87.viewercraftstar

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Menu
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Un cursore dello sviluppo: il campo del JSON di LightCraft e i limiti di LightCraft (`lightcraft-cli controls`). */
internal class DevControl(val path: String, val it: String, val en: String, val min: Float, val max: Float, val step: Float,
                      val decimals: Int, val default: Float)

internal class DevSection(val it: String, val en: String, val controls: List<DevControl>)

private val LIGHT = DevSection("Base", "Basic", listOf(
    DevControl("light.exposure", "Esposizione", "Exposure", -5f, 5f, 0.01f, 2, 0f),
    DevControl("light.contrast", "Contrasto", "Contrast", -100f, 100f, 1f, 0, 0f),
    DevControl("light.highlights", "Alte luci", "Highlights", -100f, 100f, 1f, 0, 0f),
    DevControl("light.shadows", "Ombre", "Shadows", -100f, 100f, 1f, 0, 0f),
    DevControl("light.whites", "Bianchi", "Whites", -100f, 100f, 1f, 0, 0f),
    DevControl("light.blacks", "Neri", "Blacks", -100f, 100f, 1f, 0, 0f),
))
private val COLOR = DevSection("Colore", "Color", listOf(
    DevControl("color.vibrance", "Vividezza", "Vibrance", -100f, 100f, 1f, 0, 0f),
    DevControl("color.saturation", "Saturazione", "Saturation", -100f, 100f, 1f, 0, 0f),
))
private val WHITE = DevSection("Bianco", "White balance", listOf(
    DevControl("wb.temp", "Temperatura", "Temperature", 2000f, 50000f, 50f, 0, 6500f),
    DevControl("wb.tint", "Tinta", "Tint", -150f, 150f, 1f, 0, 0f),
))
private val PROFILE = DevSection("Profilo", "Profile", listOf(
    DevControl("profile.amount", "Intensità", "Amount", 0f, 200f, 1f, 0, 100f),
))
private val CROP = DevSection("Ritaglio", "Crop", emptyList())
private val PRESETS = DevSection("Preset", "Presets", emptyList())

// seconda fase: i controlli di LightCraft (lightcraft-cli controls), con i nomi del JSON delle impostazioni
private val CURVE = DevSection("Curva", "Curve", listOf(
    DevControl("curve.highlights", "Alte luci", "Highlights", -100f, 100f, 1f, 0, 0f),
    DevControl("curve.lights", "Luci", "Lights", -100f, 100f, 1f, 0, 0f),
    DevControl("curve.darks", "Scuri", "Darks", -100f, 100f, 1f, 0, 0f),
    DevControl("curve.shadows", "Ombre", "Shadows", -100f, 100f, 1f, 0, 0f),
    DevControl("curve.split_shadows", "Divisione ombre", "Shadows split", 10f, 70f, 1f, 0, 25f),
    DevControl("curve.split_mid", "Divisione mezzitoni", "Midtones split", 20f, 80f, 1f, 0, 50f),
    DevControl("curve.split_highlights", "Divisione alte luci", "Highlights split", 30f, 90f, 1f, 0, 75f),
))
private val MIXER = DevSection("Tinte", "HSL", emptyList())
private val GRADING = DevSection("Grading", "Grading", emptyList())
private val SHARP = DevSection("Nitidezza", "Sharpening", listOf(
    DevControl("detail.sharpen_amount", "Fattore", "Amount", 0f, 150f, 1f, 0, 40f),
    DevControl("detail.sharpen_radius", "Raggio", "Radius", 0.5f, 3f, 0.1f, 1, 1f),
    DevControl("detail.sharpen_detail", "Dettagli", "Detail", 0f, 100f, 1f, 0, 25f),
    DevControl("detail.sharpen_masking", "Mascheratura", "Masking", 0f, 100f, 1f, 0, 0f),
))
private val NOISE = DevSection("Rumore", "Noise", listOf(
    DevControl("detail.nr_luminance", "Riduzione rumore", "Noise reduction", 0f, 100f, 1f, 0, 0f),
    DevControl("detail.nr_detail", "Dettagli", "Detail", 0f, 100f, 1f, 0, 50f),
    DevControl("detail.nr_contrast", "Contrasto", "Contrast", 0f, 100f, 1f, 0, 0f),
))
private val COLOR_NOISE = DevSection("Disturbo colore", "Color noise", listOf(
    DevControl("detail.nr_color", "Fattore", "Amount", 0f, 100f, 1f, 0, 25f),
    DevControl("detail.nr_color_detail", "Dettagli", "Detail", 0f, 100f, 1f, 0, 50f),
    DevControl("detail.nr_color_smoothness", "Uniformità", "Smoothness", 0f, 100f, 1f, 0, 50f),
))
private val EFFECTS = DevSection("Effetti", "Effects", listOf(
    DevControl("effects.texture", "Texture", "Texture", -100f, 100f, 1f, 0, 0f),
    DevControl("effects.clarity", "Chiarezza", "Clarity", -100f, 100f, 1f, 0, 0f),
    DevControl("effects.dehaze", "Foschia", "Dehaze", -100f, 100f, 1f, 0, 0f),
))
private val VIGNETTE = DevSection("Vignettatura", "Vignette", listOf(
    DevControl("vignette.amount", "Fattore", "Amount", -100f, 100f, 1f, 0, 0f),
    DevControl("vignette.midpoint", "Punto medio", "Midpoint", 0f, 100f, 1f, 0, 50f),
    DevControl("vignette.roundness", "Rotondità", "Roundness", -100f, 100f, 1f, 0, 0f),
    DevControl("vignette.feather", "Sfumatura", "Feather", 0f, 100f, 1f, 0, 50f),
    DevControl("vignette.highlights", "Alte luci", "Highlights", 0f, 100f, 1f, 0, 0f),
))
private val GRAIN = DevSection("Grana", "Grain", listOf(
    DevControl("grain.amount", "Fattore", "Amount", 0f, 100f, 1f, 0, 0f),
    DevControl("grain.size", "Dimensione", "Size", 0f, 100f, 1f, 0, 25f),
    DevControl("grain.roughness", "Ruvidità", "Roughness", 0f, 100f, 1f, 0, 50f),
))

/** Le categorie della barra in basso (come nei programmi di sviluppo per telefono), ognuna con le sue sotto-schede. */
internal class DevCategory(val it: String, val en: String, val icon: String, val groups: List<DevSection>)

private val CATEGORIES = listOf(
    DevCategory("Luce", "Light", "light", listOf(LIGHT, CURVE)),
    DevCategory("Colore", "Color", "color", listOf(WHITE, COLOR, MIXER, GRADING)),
    DevCategory("Effetti", "Effects", "effects", listOf(EFFECTS, VIGNETTE, GRAIN)),
    DevCategory("Dettaglio", "Detail", "detail", listOf(SHARP, NOISE, COLOR_NOISE)),
    DevCategory("Ritaglio", "Crop", "crop", listOf(CROP)),
    DevCategory("Profili", "Profiles", "profiles", listOf(PROFILE)),
    DevCategory("Preset", "Presets", "presets", listOf(PRESETS)),
)

/** I colori del mixer (Tinte), con il loro colore per il pallino accanto al nome. */
private val MIXER_COLORS = listOf(
    Triple("red", "Rossi" to "Reds", Color(0xFFEF4444)), Triple("orange", "Arancioni" to "Oranges", Color(0xFFF97316)),
    Triple("yellow", "Gialli" to "Yellows", Color(0xFFEAB308)), Triple("green", "Verdi" to "Greens", Color(0xFF22C55E)),
    Triple("aqua", "Acqua" to "Aquas", Color(0xFF06B6D4)), Triple("blue", "Blu" to "Blues", Color(0xFF3B82F6)),
    Triple("purple", "Viola" to "Purples", Color(0xFFA855F7)), Triple("magenta", "Magenta" to "Magentas", Color(0xFFEC4899)))

/** Le zone del color grading. */
private val GRADING_RANGES = listOf("shadows" to ("Ombre" to "Shadows"), "midtones" to ("Mezzitoni" to "Midtones"),
    "highlights" to ("Alte luci" to "Highlights"), "global" to ("Globale" to "Global"))
private val STRAIGHTEN = DevControl("crop.geometry.angle", "Raddrizza", "Straighten", -45f, 45f, 0.1f, 1, 0f)

/** I rapporti del ritaglio (come in LightCraft): nome e larghezza:altezza. */
private val ASPECTS = listOf("1:1" to (1 to 1), "4:5" to (4 to 5), "5:4" to (5 to 4), "2:3" to (2 to 3), "3:2" to (3 to 2),
    "16:9" to (16 to 9), "9:16" to (9 to 16))

/** Le rotazioni di 90 gradi in senso orario (orientamento di LightCraft). */
private val TURNS = listOf("Normal", "Rotate90", "Rotate180", "Rotate270")
private val SWAPS = setOf("Rotate90", "Rotate270", "Transpose", "Transverse")

/** Ciò che resta uguale scorrendo da una foto all'altra nell'editor: categoria e sotto-scheda aperte. */
class DevelopUi {
    internal var section by mutableStateOf(LIGHT)
    internal var category by mutableStateOf(CATEGORIES[0])
    internal val lastGroup = HashMap<DevCategory, DevSection>()
}

/**
 * «Modifica»: lo sviluppo della foto sul telefono, con il motore di LightCraft sulla smart preview. «Fatto» manda le
 * impostazioni al PC (come le altre modifiche): finché non le applica, la foto mostra il risultato del telefono.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DevelopEditor(lib: LibraryViewModel, photo: LibPhoto, ui: DevelopUi, position: String, neighbors: List<LibPhoto>,
                  onNavigate: (Int, LibEdit?) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val idx = lib.index ?: return
    val libDir = listOf(lib.libRoot, lib.current).joinToString("\\")
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var base by remember { mutableStateOf("") }
    var original by remember { mutableStateOf<JSONObject?>(null) }
    var settings by remember { mutableStateOf<JSONObject?>(null) }
    var resets by remember { mutableIntStateOf(0) } // i cursori riprendono il valore delle impostazioni
    var image by remember { mutableStateOf<Bitmap?>(null) }
    // la foto intera non raddrizzata per il ritaglio: tenuta a parte, così passando da una scheda all'altra si mostra
    // sempre quella giusta (prima per un attimo si girava la foto già raddrizzata: doppia rotazione)
    var cropImage by remember { mutableStateOf<Bitmap?>(null) }
    var straightening by remember { mutableStateOf(false) } // dito sul cursore Raddrizza: griglia fitta
    var before by remember { mutableStateOf<Bitmap?>(null) }
    var showBefore by remember { mutableStateOf(false) }
    var section by ui::section
    var category by ui::category
    val lastGroup = ui.lastGroup // la sotto-scheda ricordata per ogni categoria (anche passando ad altre foto)
    var swipe by remember { mutableStateOf(0f) } // lo scorrimento in corso verso un'altra foto
    var confirmClose by remember { mutableStateOf(false) }
    val thumbs = remember { androidx.compose.runtime.mutableStateMapOf<String, Bitmap>() } // miniature dei profili
    var presets by remember { mutableStateOf<List<DevelopPresets.Preset>>(emptyList()) }
    var presetGroup by remember { mutableStateOf("") }
    val presetThumbs = remember { androidx.compose.runtime.mutableStateMapOf<String, Bitmap>() }
    var copying by remember { mutableStateOf(false) } // la finestra «Copia sviluppo»
    var profiles by remember { mutableStateOf<List<DevelopProfiles.Profile>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }
    val handle = remember { longArrayOf(0L) }
    // un solo filo per il motore (la foto aperta non va usata da due sviluppi insieme); conta solo l'ultima richiesta
    val engine = remember { Executors.newSingleThreadExecutor().asCoroutineDispatcher() }
    // (impostazioni da mostrare, per il ritaglio, impostazioni dell'altra vista da preparare in sottofondo)
    val requests = remember { MutableStateFlow<Triple<String, Boolean, String>?>(null) }
    // zoom sulla foto (due dita, doppio tocco): ingrandita, si sviluppa alla risoluzione piena della smart preview
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val sharp = remember { MutableStateFlow(false) }
    var srcSize by remember { mutableStateOf(3.0 to 2.0) } // la foto aperta, in pixel
    var cropTick by remember { mutableIntStateOf(0) } // ridisegna il riquadro del ritaglio
    var editTick by remember { mutableIntStateOf(0) } // cambia a ogni modifica: aggiorna i segni di «modificato»
    var mixerMode by remember { mutableStateOf("hue") } // Tinte: tinta, saturazione o luminanza
    var gradingRange by remember { mutableStateOf("midtones") } // Grading: la zona
    val density = LocalDensity.current
    val screen = LocalConfiguration.current
    val edge = with(density) { maxOf(screen.screenWidthDp, screen.screenHeightDp).dp.toPx() }.roundToInt().coerceIn(800, 1600)

    DisposableEffect(Unit) {
        onDispose {
            val h = handle[0]
            handle[0] = 0
            // si chiude sul filo del motore, dopo l'eventuale sviluppo in corso
            engine.executor.execute { if (h != 0L) DevelopEngine.close(h) }
            (engine.executor as java.util.concurrent.ExecutorService).shutdown()
        }
    }

    LaunchedEffect(photo.id) {
        if (!DevelopEngine.available) { error = L.t("Motore di sviluppo non disponibile su questo telefono.", "Develop engine not available on this phone."); return@LaunchedEffect }
        val cfg = lib.settings.nas
        val entry = withContext(Dispatchers.IO) { DevelopStore.entry(context, idx.library, photo.id) }
        if (entry == null || entry.smart == null) {
            error = L.t("Impostazioni di sviluppo non ancora pubblicate dal PC (serve PresetCraftAppLicator 1.29.1).",
                "Develop settings not published by the PC yet (PresetCraftAppLicator 1.29.1 needed).")
            return@LaunchedEffect
        }
        val bytes = runCatching { withContext(Dispatchers.IO) { SmartCache.get(context, cfg, lib.libShare, libDir, entry.smart) } }.getOrNull()
        if (bytes == null) {
            error = L.t("Smart preview non raggiungibile: il NAS non risponde o il PC non l'ha ancora preparata.",
                "Smart preview not reachable: the NAS doesn't answer or the PC hasn't prepared it yet.")
            return@LaunchedEffect
        }
        profiles = withContext(Dispatchers.IO) { DevelopProfiles.list(context, cfg, lib.libShare, libDir, idx.library) }
        presets = withContext(Dispatchers.IO) { DevelopPresets.list(context, cfg, lib.libShare, libDir, idx.library) }
        val current = entry.settings.optJSONObject("profile")?.optString("id").orEmpty()
        profiles.firstOrNull { it.id == current }?.let { withContext(Dispatchers.IO) { DevelopProfiles.ensure(context, cfg, lib.libShare, libDir, idx.library, it) } }
        if (current.startsWith("dcp:")) note = L.t("Profilo della fotocamera (DCP): sul telefono il cambio di profilo è approssimato.",
            "Camera profile (DCP): changing profile on the phone is approximate.")
        // versione del motore e di LightCraft sul PC
        withContext(Dispatchers.IO) {
            runCatching {
                val folder = lib.libRoot.substringBeforeLast("\\libraries")
                Remote.use(cfg, lib.libShare) { s -> Remote.open(s, "$folder\\lightcraft.json").use { r -> String(r.read(0, r.size.toInt())) } }
            }.getOrNull()?.let { JSONObject(it).optString("version") }
        }?.let { pc ->
            val mine = DevelopEngine.version()
            if (pc.isNotEmpty() && pc != mine) note = L.t("Motore del telefono $mine, LightCraft sul PC $pc: il risultato potrebbe differire di poco. Aggiorna ViewerCraftStar.",
                "Phone engine $mine, LightCraft on the PC $pc: the result may differ slightly. Update ViewerCraftStar.")
        }
        val h = withContext(engine) { DevelopEngine.open(bytes) }
        if (h == 0L) { error = L.t("Smart preview non leggibile.", "Smart preview can't be read."); return@LaunchedEffect }
        handle[0] = h
        DevelopEngine.size(h)?.let { srcSize = it[0].toDouble() to it[1].toDouble() }
        // se sul telefono c'è già una modifica di questa foto non ancora applicata dal PC, si riparte da quella (prima si
        // ripartiva dalle impostazioni del PC: si vedeva, e si copiava, lo sviluppo di prima)
        val pending = lib.edits[photo.id]?.develop
        val start = pending?.let { runCatching { JSONObject(it.settings) }.getOrNull() } ?: entry.settings
        base = pending?.base ?: entry.sig
        start.optJSONObject("profile")?.optString("id")?.let { id ->
            profiles.firstOrNull { it.id == id }?.let { withContext(Dispatchers.IO) { DevelopProfiles.ensure(context, cfg, lib.libShare, libDir, idx.library, it) } }
        }
        original = JSONObject(start.toString())
        settings = start
        requests.value = Triple(start.toString(), false, frameJson(start))
        // le foto accanto: smart preview scaricate in anticipo, così scorrendo non si aspetta il NAS
        withContext(Dispatchers.IO) {
            for (n in neighbors) runCatching {
                DevelopStore.entry(context, idx.library, n.id)?.smart?.let { SmartCache.get(context, cfg, lib.libShare, libDir, it) }
            }
        }
    }

    // sviluppo: ogni nuova richiesta sostituisce quella in attesa
    LaunchedEffect(Unit) {
        kotlinx.coroutines.flow.combine(requests, sharp) { j, s -> j to s }.collectLatest { (req, big) ->
            if (req == null || handle[0] == 0L) return@collectLatest
            val (json, forCrop, other) = req
            // ingrandita: il lato lungo della smart preview (circa 2560 pixel), altrimenti quanto lo schermo
            val full = maxOf(srcSize.first, srcSize.second).toInt()
            val size = if (big && full > edge) full else edge
            val a = withContext(engine) { DevelopEngine.render(handle[0], json, size, true) } ?: return@collectLatest
            val bmp = DevelopEngine.bitmap(a)
            if (forCrop) cropImage = bmp
            else {
                if (before == null) before = bmp
                image = bmp
            }
            // poi, se per un attimo non si tocca niente, l'altra vista (ritaglio o foto finita), così passando da una
            // scheda all'altra è già pronta e aggiornata (una nuova modifica annulla questa attesa)
            kotlinx.coroutines.delay(400)
            val b = withContext(engine) { DevelopEngine.render(handle[0], other, edge, true) } ?: return@collectLatest
            val ob = DevelopEngine.bitmap(b)
            if (forCrop) {
                if (before == null) before = ob
                image = ob
            } else cropImage = ob
        }
    }

    /** Larghezza e altezza della foto girata come nelle impostazioni (quelle su cui si misura il ritaglio). */
    fun dims(): Pair<Double, Double> {
        val o = settings?.optString("orientation").orEmpty()
        return if (o in SWAPS) srcSize.second to srcSize.first else srcSize
    }

    fun geometry(): JSONObject = settings?.optJSONObject("crop")?.optJSONObject("geometry") ?: unitGeometry(0.0)

    fun setGeometry(json: String?) {
        val s = settings ?: return
        json ?: return
        DevelopStore.setPath(s, "crop.geometry", JSONObject(json))
        cropTick++
        editTick++
    }

    /** Il rapporto bloccato (larghezza/altezza in pixel), 0 = libero. */
    fun aspect(): Double = settings?.optJSONObject("crop")?.optJSONArray("aspect")?.let { a ->
        if (a.length() == 2 && a.getDouble(1) > 0) a.getDouble(0) / a.getDouble(1) else 0.0
    } ?: 0.0

    /**
     * Quello che si sviluppa: le impostazioni; mentre si ritaglia, la foto intera NON raddrizzata (sempre la stessa,
     * qualunque angolo): la raddrizza la scheda grafica girandola sullo schermo, all'istante. È la stessa cosa che fa
     * LightCraft (la vista raddrizzata è la foto girata attorno al centro dentro il riquadro), senza risvilupparla a
     * ogni movimento del cursore (circa un secondo ogni volta).
     */
    fun renderJson(): String? {
        val s = settings ?: return null
        return if (section != CROP) s.toString() else frameJson(s)
    }

    fun changed() {
        editTick++
        presetThumbs.clear()
        val s = settings
        requests.value = if (s == null) null
        else if (section == CROP) Triple(frameJson(s), true, s.toString()) else Triple(s.toString(), false, frameJson(s))
    }

    fun value(c: DevControl): Float = (settings?.let { DevelopStore.getPath(it, c.path) } as? Number)?.toFloat() ?: c.default

    /** Il valore che il controllo aveva quando si è aperto l'editor (un tocco sul numero lo rimette). */
    fun start(c: DevControl): Float = (original?.let { DevelopStore.getPath(it, c.path) } as? Number)?.toFloat() ?: c.default

    /** I campi di una sotto-scheda (per sapere se lì è cambiato qualcosa). */
    fun pathsOf(sec: DevSection): List<String> = when (sec) {
        MIXER -> listOf("mixer")
        GRADING -> listOf("grading")
        CROP -> listOf("crop", "orientation")
        PROFILE -> listOf("profile")
        PRESETS -> emptyList()
        WHITE -> listOf("wb")
        else -> sec.controls.map { it.path }
    }

    /** True se nella sotto-scheda qualcosa è diverso da com'era all'apertura dell'editor. */
    fun sectionChanged(sec: DevSection): Boolean {
        if (editTick < 0) return false // (letto: i segni si aggiornano a ogni modifica)
        val s = settings ?: return false
        val o = original ?: return false
        return pathsOf(sec).any { DevelopStore.getPath(s, it)?.toString() != DevelopStore.getPath(o, it)?.toString() }
    }

    fun set(c: DevControl, v: Float) {
        val s = settings ?: return
        val rounded = if (c.decimals == 0) v.roundToInt().toDouble() else (v / c.step).roundToInt() * c.step.toDouble()
        DevelopStore.setPath(s, c.path, rounded)
        if (c.path.startsWith("wb.")) DevelopStore.setPath(s, "wb.mode", "custom")
        changed()
    }

    /** True se la foto aperta ha modifiche rispetto a quando l'hai aperta. */
    fun dirty(): Boolean = settings != null && settings.toString() != original.toString()

    /** Salva la foto aperta come «Fatto» (solo se è cambiata): la foto sviluppata dal telefono si mostra finché il PC
     * non applica. Torna la modifica che c'era prima (per «Annulla» dopo uno scorrimento). */
    suspend fun save(): LibEdit? {
        val s = settings ?: return null
        val before = lib.edits[photo.id]
        val big = withContext(engine) { DevelopEngine.render(handle[0], s.toString(), 2048, true) }?.let { DevelopEngine.bitmap(it) }
        lib.setDevelop(photo.id, s.toString(), base, big)
        return before
    }

    fun navigate(delta: Int) {
        if (saving) return
        if (!dirty()) { onNavigate(delta, null); return }
        saving = true
        scope.launch {
            val before = save()
            onNavigate(delta, before ?: LibEdit())
        }
    }

    fun requestClose() { if (dirty()) confirmClose = true else onClose() }
    androidx.activity.compose.BackHandler { requestClose() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            // intestazione: chiudi, posizione, «Prima», incolla, ☰, «Fatto»
            Row(Modifier.fillMaxWidth().background(Color(0xB3000000)).statusBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(onClick = { requestClose() }, pad = 12.dp) { Text(L.t("Chiudi", "Close")) }
                Text(position, color = C.muted, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
                // «Prima»: tenendo premuto si vede com'era; «Ripristina» torna alle impostazioni di partenza
                if (settings != null) {
                    Text(L.t("Prima", "Before"), color = if (showBefore) Color.Black else C.text, fontSize = 13.sp,
                        modifier = Modifier.background(if (showBefore) C.accent else C.card, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                            .pointerInput(Unit) { detectTapGestures(onPress = { showBefore = true; tryAwaitRelease(); showBefore = false }) }
                            .padding(horizontal = 12.dp, vertical = 7.dp))
                    // incolla rapido dello sviluppo copiato (per sviluppare una serie: incolla, scorri, incolla…)
                    if (lib.clipboardLabel.isNotEmpty()) Pill(L.t("Incolla", "Paste"), false) {
                        val copied = DevelopClipboard.settings
                        val s = settings
                        if (copied != null && s != null) scope.launch {
                            profiles.firstOrNull { it.id == copied.optJSONObject("profile")?.optString("id") }?.let { prof ->
                                withContext(Dispatchers.IO) { DevelopProfiles.ensure(context, lib.settings.nas, lib.libShare, libDir, idx.library, prof) }
                            }
                            DevelopStore.merge(s, copied)
                            resets++
                            cropTick++
                            changed()
                        }
                    }
                    // ☰: copia lo sviluppo (per incollarlo su altre foto), incolla quello copiato, ripristina
                    Box {
                        var open by remember { mutableStateOf(false) }
                        Row(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).background(C.card)
                            .clickable { open = true }.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Menu, null, tint = C.text, modifier = Modifier.size(15.dp))
                        }
                        androidx.compose.material3.DropdownMenu(open, { open = false }) {
                            androidx.compose.material3.DropdownMenuItem({ Text(L.t("Copia sviluppo…", "Copy develop settings…"), color = C.text) },
                                onClick = { open = false; copying = true })
                            if (lib.clipboardLabel.isNotEmpty()) androidx.compose.material3.DropdownMenuItem(
                                { Text(L.t("Incolla lo sviluppo di ${lib.clipboardLabel}", "Paste develop settings from ${lib.clipboardLabel}"), color = C.text) },
                                onClick = {
                                    open = false
                                    val copied = DevelopClipboard.settings
                                    val s = settings
                                    if (copied != null && s != null) scope.launch {
                                        profiles.firstOrNull { it.id == copied.optJSONObject("profile")?.optString("id") }?.let { prof ->
                                            withContext(Dispatchers.IO) { DevelopProfiles.ensure(context, lib.settings.nas, lib.libShare, libDir, idx.library, prof) }
                                        }
                                        DevelopStore.merge(s, copied)
                                        resets++
                                        cropTick++
                                        changed()
                                    }
                                })
                            androidx.compose.material3.DropdownMenuItem({ Text(L.t("Ripristina com'era", "Reset to how it was"), color = C.text) }, onClick = {
                                open = false
                                settings = original?.let { JSONObject(it.toString()) }
                                resets++
                                cropTick++
                                changed()
                            })
                        }
                    }
                }
                PillButton(onClick = {
                    if (!dirty()) { onClose(); return@PillButton }
                    saving = true
                    scope.launch { save(); onClose() }
                }, primary = true, enabled = settings != null && !saving, pad = 12.dp) { Text(L.t("Fatto", "Done")) }
            }
            // la foto (tenendo premuto «Prima» si vede com'era)
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds(), contentAlignment = Alignment.Center) {
                val shown = if (showBefore) before else if (section == CROP) cropImage else image
                val bitmap = remember(shown) { shown?.asImageBitmap() }
                val cropping = section == CROP && !showBefore
                if (bitmap != null && cropping) {
                    // la foto intera girata dell'angolo di Raddrizza, tutta visibile (rimpicciolita quanto basta), come in
                    // Lightroom e LightCraft: quello che resta fuori dal ritaglio lo scurisce il riquadro sopra
                    val angle = geometry().optDouble("angle", 0.0).toFloat() + 0f * cropTick
                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                        val f = frameRect(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), bitmap.width, bitmap.height, angle)
                        Image(bitmap, null, Modifier
                            .offset { androidx.compose.ui.unit.IntOffset(f.left.roundToInt(), f.top.roundToInt()) }
                            .width(with(density) { f.width.toDp() }).height(with(density) { f.height.toDp() })
                            .graphicsLayer { rotationZ = angle }, contentScale = ContentScale.FillBounds)
                    }
                } else if (bitmap != null) {
                    // due dita per ingrandire e spostare, doppio tocco per 2× e ritorno
                    LaunchedEffect(zoom > 1.2f) { sharp.value = zoom > 1.2f }
                    Image(bitmap, null, Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = { at ->
                                if (zoom > 1.001f) { zoom = 1f; pan = androidx.compose.ui.geometry.Offset.Zero }
                                else {
                                    zoom = DOUBLE_TAP_ZOOM
                                    val c = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                                    pan = clampPan((c - at) * (DOUBLE_TAP_ZOOM - 1f), DOUBLE_TAP_ZOOM, size)
                                }
                            })
                        }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var multi = false
                                do {
                                    val ev = awaitPointerEvent()
                                    val fingers = ev.changes.count { it.pressed }
                                    if (fingers >= 2) {
                                        multi = true
                                        swipe = 0f
                                        zoom = (zoom * ev.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                                        pan = clampPan(pan + ev.calculatePan(), zoom, size)
                                        ev.changes.forEach { it.consume() }
                                    } else if (fingers == 1 && zoom > 1.001f) {
                                        pan = clampPan(pan + ev.calculatePan(), zoom, size)
                                        ev.changes.forEach { it.consume() }
                                    } else if (fingers == 1 && !multi) {
                                        // a 1×: la foto segue il dito, verso la successiva o la precedente
                                        swipe += ev.calculatePan().x
                                    }
                                } while (ev.changes.any { it.pressed })
                                if (zoom < 1.02f) { zoom = 1f; pan = androidx.compose.ui.geometry.Offset.Zero }
                                val far = kotlin.math.abs(swipe) > size.width * 0.22f
                                if (far && !multi) navigate(if (swipe < 0) 1 else -1)
                                swipe = 0f
                            }
                        }
                        .graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x + swipe; translationY = pan.y },
                        contentScale = ContentScale.Fit)
                }
                else if (error == null) CircularProgressIndicator(color = C.accent, strokeWidth = 2.dp)
                // ritaglio: il riquadro sopra la foto intera raddrizzata, con angoli e lati da trascinare
                if (section == CROP && !showBefore && shown != null) CropOverlay(shown.width, shown.height,
                    geometry().optDouble("angle", 0.0).toFloat(), cropTick, dense = straightening,
                    rect = { geometry().optJSONObject("rect") },
                    current = { geometry().toString() },
                    onDrag = { start, handleIndex, gx, gy, px, py ->
                        val (w, h) = dims()
                        setGeometry(DevelopEngine.cropDrag(start, handleIndex, gx, gy, px, py, w, h, aspect()))
                    })
                error?.let { Text(it, color = Color(0xFFF87171), fontSize = 14.sp, modifier = Modifier.padding(24.dp)) }
            }
            if (settings != null) Column(Modifier.fillMaxWidth().background(Color(0xE6141414)).navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                note?.let { Text(it, color = C.muted, fontSize = 12.sp) }
                fun open(sec: DevSection) {
                    if (sec == CROP) { zoom = 1f; pan = androidx.compose.ui.geometry.Offset.Zero } // il ritaglio si fa sulla foto intera
                    val wasCrop = section == CROP
                    section = sec
                    lastGroup[category] = sec
                    if (wasCrop != (sec == CROP)) changed() // dentro e fuori dal ritaglio la foto si mostra diversa
                }
                // le sotto-schede della categoria (Nitidezza | Rumore | Disturbo colore…), sottolineata quella aperta
                // (sempre, anche con una scheda sola: il pannello resta della stessa altezza e la foto non si sposta)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    category.groups.forEach { g -> SubTab(L.t(g.it, g.en), section == g, sectionChanged(g)) { open(g) } }
                }
                // i cursori: sempre la stessa altezza (le schede non saltano), il resto scorre; la foto resta grande
                Column(Modifier.fillMaxWidth().height((screen.screenHeightDp * 0.27f).dp).verticalScroll(rememberScrollState())) {
                    if (section == PRESETS) {
                        val groups = presets.map { it.group }.distinct()
                        val group = presetGroup.takeIf { it in groups } ?: groups.firstOrNull().orEmpty()
                        val shownPresets = presets.filter { it.group == group }
                        if (presets.isEmpty()) Text(L.t("Nessun preset nella libreria.", "No presets in the library."), color = C.muted, fontSize = 12.sp)
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            groups.forEach { g -> Pill(g, g == group) { presetGroup = g } }
                        }
                        // miniature: la foto com'è adesso con ogni preset sopra (sviluppate dal motore, una alla volta)
                        LaunchedEffect(group, presets, editTick) {
                            val base = settings?.toString() ?: return@LaunchedEffect
                            for (p in shownPresets) {
                                if (presetThumbs.containsKey(p.id) || handle[0] == 0L) continue
                                profiles.firstOrNull { it.id == p.profileId }?.let { prof ->
                                    withContext(Dispatchers.IO) { DevelopProfiles.ensure(context, lib.settings.nas, lib.libShare, libDir, idx.library, prof) }
                                }
                                val s = JSONObject(base)
                                DevelopStore.merge(s, p.settings)
                                withContext(engine) { DevelopEngine.render(handle[0], s.toString(), 320, true) }
                                    ?.let { presetThumbs[p.id] = DevelopEngine.bitmap(it) }
                            }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            shownPresets.forEach { p ->
                                Column(Modifier.width(118.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                    .background(C.card).clickable {
                                        scope.launch {
                                            profiles.firstOrNull { it.id == p.profileId }?.let { prof ->
                                                withContext(Dispatchers.IO) { DevelopProfiles.ensure(context, lib.settings.nas, lib.libShare, libDir, idx.library, prof) }
                                            }
                                            settings?.let { DevelopStore.merge(it, p.settings) }
                                            resets++
                                            changed()
                                        }
                                    }.padding(3.dp)) {
                                    Box(Modifier.fillMaxWidth().height(80.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                        .background(Color(0xFF202020)), contentAlignment = Alignment.Center) {
                                        val b = presetThumbs[p.id]
                                        val img = remember(b) { b?.asImageBitmap() }
                                        if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                        else CircularProgressIndicator(color = C.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                                    }
                                    Text(p.name, color = C.text, fontSize = 12.sp, maxLines = 2,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp))
                                    if (p.profileId.startsWith("dcp:")) Text(L.t("profilo DCP: approssimato", "DCP profile: approximate"),
                                        color = C.muted, fontSize = 10.sp, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
                                }
                            }
                        }
                        Text(L.t("Un tocco applica il preset sopra lo sviluppo attuale (come in LightCraft); «Prima» e il ↺ dei cursori restano.",
                            "A tap applies the preset on top of the current settings (as in LightCraft); «Before» and the sliders' ↺ still work."),
                            color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                    if (section == PROFILE) {
                        val current = settings?.optJSONObject("profile")?.optString("id").orEmpty()
                        val shown = profilesFor(profiles, photo.camera)
                        // le miniature: la foto com'è adesso, con ogni profilo (sviluppate dal motore, una alla volta)
                        LaunchedEffect(shown, current) {
                            val base = settings?.toString() ?: return@LaunchedEffect
                            for (p in shown) {
                                if (thumbs.containsKey(p.id) || handle[0] == 0L) continue
                                val ok = withContext(Dispatchers.IO) { DevelopProfiles.ensure(context, lib.settings.nas, lib.libShare, libDir, idx.library, p) }
                                if (!ok) continue
                                val s = JSONObject(base)
                                DevelopStore.setPath(s, "profile.id", p.id)
                                withContext(engine) { DevelopEngine.render(handle[0], s.toString(), 320, true) }
                                    ?.let { thumbs[p.id] = DevelopEngine.bitmap(it) }
                            }
                        }
                        ProfileStrip(shown, profiles, current, thumbs) { p ->
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { DevelopProfiles.ensure(context, lib.settings.nas, lib.libShare, libDir, idx.library, p) }
                                if (ok) { settings?.let { DevelopStore.setPath(it, "profile.id", p.id) }; changed() }
                            }
                        }
                    }
                    if (section == WHITE) {
                        val kelvin = settings?.optJSONObject("wb")?.optString("scale") == "kelvin"
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Pill(L.t("Come scattata", "As shot"), settings?.optJSONObject("wb")?.optString("mode") == "asShot") {
                                val s = settings ?: return@Pill
                                val o = original?.optJSONObject("wb")
                                DevelopStore.setPath(s, "wb.mode", "asShot")
                                o?.let { DevelopStore.setPath(s, "wb.temp", it.opt("temp")); DevelopStore.setPath(s, "wb.tint", it.opt("tint")) }
                                resets++
                                changed()
                            }
                        }
                        if (!kelvin) Text(L.t("Foto non raw: temperatura e tinta sono relative allo scatto.", "Not a raw photo: temperature and tint are relative to the shot."),
                            color = C.muted, fontSize = 12.sp)
                    }
                    section.controls.forEach { c -> ControlSlider(c, value(c), resets, start(c)) { set(c, it) } }
                    if (section == MIXER) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)) {
                            listOf("hue" to ("Tinta" to "Hue"), "sat" to ("Saturazione" to "Saturation"), "lum" to ("Luminanza" to "Luminance"))
                                .forEach { (k, n) -> Pill(L.t(n.first, n.second), mixerMode == k) { mixerMode = k } }
                        }
                        MIXER_COLORS.forEach { (key, names, dot) ->
                            val c = DevControl("mixer.$key.$mixerMode", names.first, names.second, -100f, 100f, 1f, 0, 0f)
                            androidx.compose.runtime.key(c.path) { ControlSlider(c, value(c), resets, start(c), dot) { set(c, it) } }
                        }
                    }
                    if (section == GRADING) {
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GRADING_RANGES.forEach { (k, n) -> Pill(L.t(n.first, n.second), gradingRange == k) { gradingRange = k } }
                        }
                        val hue = DevControl("grading.$gradingRange.hue", "Tinta", "Hue", 0f, 360f, 1f, 0, 0f)
                        val sat = DevControl("grading.$gradingRange.sat", "Saturazione", "Saturation", 0f, 100f, 1f, 0, 0f)
                        val lum = DevControl("grading.$gradingRange.lum", "Luminanza", "Luminance", -100f, 100f, 1f, 0, 0f)
                        // il pallino mostra il colore scelto (tinta a piena saturazione)
                        val swatch = Color(android.graphics.Color.HSVToColor(floatArrayOf(value(hue), 0.8f, 0.95f)))
                        androidx.compose.runtime.key(gradingRange) {
                            ControlSlider(hue, value(hue), resets, start(hue), swatch) { set(hue, it) }
                            ControlSlider(sat, value(sat), resets, start(sat)) { set(sat, it) }
                            ControlSlider(lum, value(lum), resets, start(lum)) { set(lum, it) }
                        }
                        val blending = DevControl("grading.blending", "Fusione", "Blending", 0f, 100f, 1f, 0, 50f)
                        val balance = DevControl("grading.balance", "Bilanciamento", "Balance", -100f, 100f, 1f, 0, 0f)
                        ControlSlider(blending, value(blending), resets, start(blending)) { set(blending, it) }
                        ControlSlider(balance, value(balance), resets, start(balance)) { set(balance, it) }
                    }
                    if (section == CROP) Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val current = settings?.optJSONObject("crop")?.optJSONArray("aspect")
                        val key = current?.let { if (it.length() == 2) "${it.getInt(0)}:${it.getInt(1)}" else null }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill(L.t("Libero", "Free"), key == null) {
                                settings?.let { DevelopStore.setPath(it, "crop.aspect", null) }
                                cropTick++
                            }
                            Pill(L.t("Originale", "Original"), false) {
                                val (w, h) = dims()
                                settings?.let { DevelopStore.setPath(it, "crop.aspect", null) }
                                setGeometry(DevelopEngine.cropFit(w, h, geometry().optDouble("angle", 0.0), 0.0))
                            }
                            // gli altri rapporti, che si usano meno: in un menu (acceso quando ne è scelto uno)
                            Box {
                                var open by remember { mutableStateOf(false) }
                                val on = key != null
                                Row(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                                    .background(if (on) C.accent else C.card).clickable { open = true }
                                    .padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Menu, null,
                                        tint = if (on) Color.Black else C.text, modifier = Modifier.size(15.dp))
                                    Text(" " + (key ?: L.t("Rapporto", "Ratio")), color = if (on) Color.Black else C.text, fontSize = 13.sp)
                                }
                                androidx.compose.material3.DropdownMenu(open, { open = false }) {
                                    ASPECTS.forEach { (name, ratio) ->
                                        androidx.compose.material3.DropdownMenuItem({ Text(name, color = C.text) }, onClick = {
                                            open = false
                                            val (w, h) = dims()
                                            settings?.let { DevelopStore.setPath(it, "crop.aspect", org.json.JSONArray(listOf(ratio.first, ratio.second))) }
                                            setGeometry(DevelopEngine.cropFit(w, h, geometry().optDouble("angle", 0.0), ratio.first.toDouble() / ratio.second))
                                        })
                                    }
                                }
                            }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // ruota di 90 gradi in senso orario: il ritaglio si rifà (cambiano larghezza e altezza)
                            Pill(L.t("Ruota", "Rotate"), false) {
                                val s = settings ?: return@Pill
                                val o = s.optString("orientation", "Normal")
                                s.put("orientation", TURNS[(TURNS.indexOf(o).coerceAtLeast(0) + 1) % TURNS.size])
                                val (w, h) = dims()
                                setGeometry(DevelopEngine.cropFit(w, h, geometry().optDouble("angle", 0.0), aspect()))
                                changed()
                            }
                            Pill(L.t("Rifletti", "Flip"), settings?.optJSONObject("crop")?.optBoolean("flip_h") == true) {
                                val s = settings ?: return@Pill
                                DevelopStore.setPath(s, "crop.flip_h", !(s.optJSONObject("crop")?.optBoolean("flip_h") ?: false))
                                changed()
                            }
                            Pill(L.t("Azzera ritaglio", "Clear crop"), false) {
                                val s = settings ?: return@Pill
                                DevelopStore.setPath(s, "crop.aspect", null)
                                DevelopStore.setPath(s, "crop.geometry", unitGeometry(0.0))
                                cropTick++
                                resets++
                                changed()
                            }
                        }
                        ControlSlider(STRAIGHTEN, geometry().optDouble("angle", 0.0).toFloat(), resets, start(STRAIGHTEN),
                            onActive = { straightening = it }) { v ->
                            val (w, h) = dims()
                            val angle = (v * 10).roundToInt() / 10.0
                            setGeometry(DevelopEngine.cropWithAngle(geometry().toString(), angle, w, h))
                            changed()
                        }
                        Text(L.t("Trascina angoli e lati del riquadro sulla foto; dentro il riquadro lo sposti.",
                            "Drag the frame's corners and edges on the photo; drag inside it to move it."), color = C.muted, fontSize = 12.sp)
                    }
                }
                // la barra delle categorie, in basso (icona e nome; accesa quella aperta)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    CATEGORIES.forEach { c ->
                        CategoryButton(L.t(c.it, c.en), c.icon, c == category, c.groups.any { sectionChanged(it) }) {
                            category = c
                            open(lastGroup[c] ?: c.groups.first())
                        }
                    }
                }
            }
        }
        if (copying) CopyDevelopDialog({ copying = false }) { sections ->
            copying = false
            settings?.let { lib.copyDevelop(it, sections, photo.name) }
        }
        if (confirmClose) BlurDialog({ confirmClose = false }) {
            DialogTitle(L.t("Scartare le modifiche?", "Discard the changes?"))
            DialogText(L.t("Le modifiche a questa foto non sono salvate. Quelle salvate scorrendo tra le foto restano.",
                "The changes to this photo aren't saved. The ones saved while swiping between photos stay."))
            DialogButtons(L.t("Continua", "Keep editing") to { confirmClose = false }, main = L.t("Scarta", "Discard") to {
                confirmClose = false
                onClose()
            }, danger = true)
        }
        if (saving) Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = C.accent, strokeWidth = 2.dp)
        }
    }
}

/**
 * Il riquadro del ritaglio sopra la foto (intera e raddrizzata): fuori scurito, griglia dei terzi, maniglie. Si
 * trascinano angoli (0..3), lati (4..7) o l'interno (8) con il modello di LightCraft (onDrag riceve la geometria di
 * partenza, la maniglia, il punto preso e quello attuale in coordinate 0..1 del riquadro).
 */
@Composable
private fun CropOverlay(imgW: Int, imgH: Int, angle: Float, tick: Int, dense: Boolean, rect: () -> JSONObject?, current: () -> String,
                        onDrag: (String, Int, Double, Double, Double, Double) -> Unit) {
    val density = LocalDensity.current
    val grabRadius = with(density) { 36.dp.toPx() }
    var box by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    // la cornice del riquadro raddrizzato sullo schermo (la stessa della foto girata)
    val angleNow by androidx.compose.runtime.rememberUpdatedState(angle)
    fun shownRect(): androidx.compose.ui.geometry.Rect =
        frameRect(box.width.toFloat(), box.height.toFloat(), imgW, imgH, angleNow)
    Box(Modifier.fillMaxSize().onSizeChanged { box = it }.pointerInput(imgW, imgH) {
        var start = ""
        var handleIndex = 8
        var gx = 0.0
        var gy = 0.0
        var px = 0.0
        var py = 0.0
        detectDragGestures(onDragStart = { at ->
            val img = shownRect()
            val r = rect()
            if (r != null && img.width > 0f) {
                fun sx(x: Double) = img.left + (x * img.width).toFloat()
                fun sy(y: Double) = img.top + (y * img.height).toFloat()
                val x0 = sx(r.optDouble("x0")); val x1 = sx(r.optDouble("x1"))
                val y0 = sy(r.optDouble("y0")); val y1 = sy(r.optDouble("y1"))
                val points = listOf(x0 to y0, x1 to y0, x1 to y1, x0 to y1, (x0 + x1) / 2 to y0, x1 to (y0 + y1) / 2,
                    (x0 + x1) / 2 to y1, x0 to (y0 + y1) / 2)
                val near = points.withIndex().minByOrNull { (_, p) -> kotlin.math.hypot(p.first - at.x, p.second - at.y) }
                handleIndex = if (near != null && kotlin.math.hypot(near.value.first - at.x, near.value.second - at.y) <= grabRadius)
                    near.index else 8
                start = current()
                gx = ((at.x - img.left) / img.width).toDouble()
                gy = ((at.y - img.top) / img.height).toDouble()
                px = gx
                py = gy
            }
        }, onDrag = { change, delta ->
            val img = shownRect()
            if (img.width > 0f && start.isNotEmpty()) {
                change.consume()
                px += delta.x / img.width
                py += delta.y / img.height
                onDrag(start, handleIndex, gx, gy, px, py)
            }
        })
    }) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            if (tick < 0) return@Canvas // si ridisegna quando cambia il ritaglio (tick)
            val img = shownRect()
            val r = rect() ?: return@Canvas
            if (img.width <= 0f) return@Canvas
            val l = img.left + (r.optDouble("x0") * img.width).toFloat()
            val t = img.top + (r.optDouble("y0") * img.height).toFloat()
            val ri = img.left + (r.optDouble("x1") * img.width).toFloat()
            val b = img.top + (r.optDouble("y1") * img.height).toFloat()
            val shade = Color(0xA6000000)
            // tutto quello che è fuori dal ritaglio, scuro (anche gli angoli della foto girata)
            val fw = size.width
            val fh = size.height
            drawRect(shade, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Size(fw, t))
            drawRect(shade, androidx.compose.ui.geometry.Offset(0f, b), androidx.compose.ui.geometry.Size(fw, fh - b))
            drawRect(shade, androidx.compose.ui.geometry.Offset(0f, t), androidx.compose.ui.geometry.Size(l, b - t))
            drawRect(shade, androidx.compose.ui.geometry.Offset(ri, t), androidx.compose.ui.geometry.Size(fw - ri, b - t))
            // griglia: dei terzi; mentre si raddrizza, fitta (righe ogni ~1/12 del lato lungo) per allinearsi alle linee
            // della foto
            val n = if (dense) 12 else 3
            val cell = maxOf(ri - l, b - t) / n
            val cols = if (dense) maxOf(2, ((ri - l) / cell).roundToInt()) else 3
            val rows = if (dense) maxOf(2, ((b - t) / cell).roundToInt()) else 3
            val grid = if (dense) Color(0x55FFFFFF) else Color(0x66FFFFFF)
            for (i in 1 until cols) {
                val x = l + (ri - l) * i / cols
                drawLine(grid, androidx.compose.ui.geometry.Offset(x, t), androidx.compose.ui.geometry.Offset(x, b), if (dense) 1f else 1.5f)
            }
            for (i in 1 until rows) {
                val y = t + (b - t) * i / rows
                drawLine(grid, androidx.compose.ui.geometry.Offset(l, y), androidx.compose.ui.geometry.Offset(ri, y), if (dense) 1f else 1.5f)
            }
            drawRect(Color(0xCCFFFFFF), androidx.compose.ui.geometry.Offset(l, t), androidx.compose.ui.geometry.Size(ri - l, b - t),
                style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
            // maniglie agli angoli
            val k = 28f
            val thick = 9f
            listOf(Triple(l, t, 1f to 1f), Triple(ri, t, -1f to 1f), Triple(ri, b, -1f to -1f), Triple(l, b, 1f to -1f)).forEach { (x, y, d) ->
                drawLine(C.accent, androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Offset(x + k * d.first, y), thick)
                drawLine(C.accent, androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Offset(x, y + k * d.second), thick)
            }
        }
    }
}

/**
 * Dove sta sullo schermo il riquadro raddrizzato (la foto non girata, larga imgW × imgH) quando la foto girata di
 * `angle` gradi deve stare tutta nello spazio cw × ch: centrato, rimpicciolito quanto basta.
 */
private fun frameRect(cw: Float, ch: Float, imgW: Int, imgH: Int, angle: Float): androidx.compose.ui.geometry.Rect {
    if (cw <= 0f || ch <= 0f || imgW <= 0 || imgH <= 0) return androidx.compose.ui.geometry.Rect.Zero
    val a = Math.toRadians(angle.toDouble())
    val c = kotlin.math.abs(kotlin.math.cos(a)).toFloat()
    val sn = kotlin.math.abs(kotlin.math.sin(a)).toFloat()
    val bw = imgW * c + imgH * sn
    val bh = imgW * sn + imgH * c
    val s = minOf(cw / bw, ch / bh)
    val w = imgW * s
    val h = imgH * s
    return androidx.compose.ui.geometry.Rect((cw - w) / 2f, (ch - h) / 2f, (cw + w) / 2f, (ch + h) / 2f)
}

/** La vista del ritaglio: le impostazioni con la foto intera, non raddrizzata (la gira lo schermo). */
private fun frameJson(s: JSONObject): String {
    val view = JSONObject(s.toString())
    DevelopStore.setPath(view, "crop.geometry", unitGeometry(0.0))
    return view.toString()
}

/**
 * «Copia sviluppo»: scegli i gruppi da copiare (pillole; il ritaglio di base spento). Usata dall'editor e dalla galleria.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun CopyDevelopDialog(onDismiss: () -> Unit, onCopy: (List<String>) -> Unit) {
    val chosen = remember { androidx.compose.runtime.mutableStateMapOf<Int, Boolean>().apply {
        DevelopClipboard.GROUPS.forEachIndexed { i, g -> put(i, g.third) } } }
    BlurDialog(onDismiss) {
        DialogTitle(L.t("Copia sviluppo", "Copy develop settings"))
        DialogText(L.t("Scegli cosa copiare. Poi lo incolli su altre foto: in «Sviluppa» (Incolla) o selezionandole nella galleria (☰ Sviluppo).",
            "Choose what to copy. Then paste it on other photos: in «Develop» (Paste) or by selecting them in the gallery (☰ Develop)."))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DevelopClipboard.GROUPS.forEachIndexed { i, g -> Pill(L.t(g.first.first, g.first.second), chosen[i] == true) { chosen[i] = chosen[i] != true } }
        }
        DialogButtons(L.t("Annulla", "Cancel") to onDismiss, main = L.t("Copia", "Copy") to {
            onCopy(DevelopClipboard.GROUPS.filterIndexed { i, _ -> chosen[i] == true }.flatMap { it.second })
        })
    }
}

/** La geometria di un ritaglio che prende tutta la foto, girata di `angle` gradi. */
private fun unitGeometry(angle: Double): JSONObject = JSONObject()
    .put("rect", JSONObject().put("x0", 0.0).put("y0", 0.0).put("x1", 1.0).put("y1", 1.0)).put("angle", angle)

/**
 * Un cursore con nome e valore; un tocco sul valore lo riporta a `reset` (quello che la foto aveva all'apertura).
 * `dot`: un pallino colorato accanto al nome (i colori delle Tinte, la tinta del grading).
 */
@Composable
private fun ControlSlider(c: DevControl, value: Float, resets: Int, reset: Float, dot: Color? = null,
                          onActive: (Boolean) -> Unit = {}, onChange: (Float) -> Unit) {
    var v by remember(c.path, resets) { mutableStateOf(value.coerceIn(c.min, c.max)) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (dot != null) {
                Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(dot))
                Spacer(Modifier.width(8.dp))
            }
            Text(L.t(c.it, c.en), color = C.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            // toccato (diverso da com'era all'apertura): numero blu e ↺ per rimetterlo com'era
            val moved = kotlin.math.abs(v - reset.coerceIn(c.min, c.max)) >= c.step / 2
            Row(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                .clickable { v = reset.coerceIn(c.min, c.max); onChange(v) }.padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (moved) {
                    UndoIcon(Modifier.size(14.dp), C.accent)
                    Spacer(Modifier.width(5.dp))
                }
                Text(if (c.decimals == 0) v.roundToInt().toString() else "%.${c.decimals}f".format(v),
                    color = if (moved) C.accent else C.muted, fontSize = 13.sp)
            }
        }
        Slider(value = v, onValueChange = { onActive(true); v = it; onChange(it) }, onValueChangeFinished = { onActive(false) },
            valueRange = c.min..c.max, modifier = Modifier.height(30.dp),
            colors = SliderDefaults.colors(thumbColor = C.accent, activeTrackColor = C.accent, inactiveTrackColor = C.card))
    }
}

/** «Rimetti com'era»: una freccia circolare, disegnata come le altre icone dell'app. */
@Composable
private fun UndoIcon(modifier: Modifier, color: Color) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val st = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.13f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawArc(color, 200f, 280f, false, androidx.compose.ui.geometry.Offset(w * 0.15f, w * 0.15f),
            androidx.compose.ui.geometry.Size(w * 0.7f, w * 0.7f), style = st)
        // punta della freccia all'inizio dell'arco (in alto a sinistra)
        val tip = androidx.compose.ui.geometry.Offset(w * 0.17f, w * 0.36f)
        drawLine(color, tip, androidx.compose.ui.geometry.Offset(w * 0.05f, w * 0.12f), w * 0.13f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(color, tip, androidx.compose.ui.geometry.Offset(w * 0.42f, w * 0.30f), w * 0.13f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

/** I profili LUT della libreria da proporre: prima (o solo) quelli della fotocamera della foto. */
private fun profilesFor(profiles: List<DevelopProfiles.Profile>, camera: String): List<DevelopProfiles.Profile> {
    val luts = profiles.filter { it.kind == "lut" }
    val mine = luts.filter { camera.isNotEmpty() && (camera.contains(it.group, ignoreCase = true) || it.group.contains(camera, ignoreCase = true)) }
    return (mine.ifEmpty { luts }).sortedBy { it.name }
}

/** I profili come miniature della foto sviluppata con ciascuno (nome sotto); acceso quello in uso. */
@Composable
private fun ProfileStrip(shown: List<DevelopProfiles.Profile>, all: List<DevelopProfiles.Profile>, current: String,
                         thumbs: Map<String, Bitmap>, onPick: (DevelopProfiles.Profile) -> Unit) {
    if (current.isNotEmpty() && shown.none { it.id == current })
        Text(L.t("In uso: ", "In use: ") + (all.firstOrNull { it.id == current }?.name ?: current), color = C.muted, fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 6.dp))
    if (shown.isEmpty()) Text(L.t("Nessun profilo LUT nella libreria.", "No LUT profiles in the library."), color = C.muted, fontSize = 12.sp)
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEach { p ->
            val on = p.id == current
            Column(Modifier.width(118.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .background(if (on) C.accent else C.card).clickable { onPick(p) }.padding(3.dp)) {
                Box(Modifier.fillMaxWidth().height(80.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                    .background(Color(0xFF202020)), contentAlignment = Alignment.Center) {
                    val b = thumbs[p.id]
                    val img = remember(b) { b?.asImageBitmap() }
                    if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else CircularProgressIndicator(color = C.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                }
                Text(p.name.removePrefix("Measured ").removePrefix("Camera "), color = if (on) Color.Black else C.text, fontSize = 12.sp,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp))
                Text(if (p.name.startsWith("Measured")) L.t("Misurato", "Measured") else L.t("Fotocamera", "Camera"),
                    color = if (on) Color(0xCC000000) else C.muted, fontSize = 10.sp, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
            }
        }
    }
}

/** Una sotto-scheda: testo, sottolineato quando è aperta; un puntino blu se dentro è cambiato qualcosa. */
@Composable
private fun SubTab(text: String, on: Boolean, changed: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable { onClick() }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = if (on) C.text else C.muted, fontSize = 14.sp, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal)
            if (changed) {
                Spacer(Modifier.width(5.dp))
                Box(Modifier.size(6.dp).clip(androidx.compose.foundation.shape.CircleShape).background(C.accent))
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.width(if (on) 44.dp else 0.dp).height(2.dp).background(C.text))
    }
}

/** Un pulsante della barra delle categorie: icona sopra, nome sotto; acceso quando la categoria è aperta; un
 * puntino blu sotto il nome se dentro è cambiato qualcosa (come i programmi di sviluppo per telefono). */
@Composable
private fun CategoryButton(text: String, icon: String, on: Boolean, changed: Boolean, onClick: () -> Unit) {
    val color = if (on) C.text else C.muted
    Column(Modifier.width(64.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
        .background(if (on) C.card else Color.Transparent).clickable { onClick() }.padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        CategoryIcon(icon, color, Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        Text(text, color = color, fontSize = 11.sp, maxLines = 1)
        Spacer(Modifier.height(3.dp))
        Box(Modifier.size(5.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (changed) C.accent else Color.Transparent))
    }
}

/** Le icone delle categorie, disegnate come le altre icone dell'app. */
@Composable
private fun CategoryIcon(kind: String, color: Color, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val h = size.height
        val c = androidx.compose.ui.geometry.Offset(w / 2, h / 2)
        val st = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.08f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        fun line(x0: Float, y0: Float, x1: Float, y1: Float) = drawLine(color, androidx.compose.ui.geometry.Offset(x0 * w, y0 * h),
            androidx.compose.ui.geometry.Offset(x1 * w, y1 * h), w * 0.08f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        when (kind) {
            "light" -> { // sole
                drawCircle(color, w * 0.2f, c, style = st)
                for (i in 0 until 8) {
                    val a = Math.PI * i / 4
                    val (cx, sx) = kotlin.math.cos(a).toFloat() to kotlin.math.sin(a).toFloat()
                    line(0.5f + 0.32f * cx, 0.5f + 0.32f * sx, 0.5f + 0.45f * cx, 0.5f + 0.45f * sx)
                }
            }
            "color" -> { // termometro
                drawCircle(color, w * 0.14f, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.76f))
                line(0.5f, 0.12f, 0.5f, 0.66f)
            }
            "effects" -> { // riquadro con vignettatura
                drawRoundRect(color, androidx.compose.ui.geometry.Offset(w * 0.1f, h * 0.18f), androidx.compose.ui.geometry.Size(w * 0.8f, h * 0.64f),
                    androidx.compose.ui.geometry.CornerRadius(w * 0.08f), style = st)
                drawOval(color, androidx.compose.ui.geometry.Offset(w * 0.28f, h * 0.32f), androidx.compose.ui.geometry.Size(w * 0.44f, h * 0.36f), style = st)
            }
            "detail" -> { // triangolo pieno
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.5f, h * 0.1f); lineTo(w * 0.78f, h * 0.88f); lineTo(w * 0.22f, h * 0.88f); close()
                }
                drawPath(path, color)
            }
            "crop" -> { // ritaglio
                line(0.28f, 0.08f, 0.28f, 0.72f); line(0.28f, 0.72f, 0.92f, 0.72f)
                line(0.08f, 0.28f, 0.72f, 0.28f); line(0.72f, 0.28f, 0.72f, 0.92f)
            }
            "presets" -> { // preset: un elenco
                for (i in 0..2) {
                    val y = 0.25f + 0.25f * i
                    drawCircle(color, w * 0.07f, androidx.compose.ui.geometry.Offset(w * 0.18f, h * y))
                    line(0.36f, y, 0.9f, y)
                }
            }
            else -> { // profili: due quadrati sovrapposti
                drawRoundRect(color, androidx.compose.ui.geometry.Offset(w * 0.3f, h * 0.1f), androidx.compose.ui.geometry.Size(w * 0.6f, h * 0.6f),
                    androidx.compose.ui.geometry.CornerRadius(w * 0.08f), style = st)
                drawRoundRect(color, androidx.compose.ui.geometry.Offset(w * 0.1f, h * 0.3f), androidx.compose.ui.geometry.Size(w * 0.6f, h * 0.6f),
                    androidx.compose.ui.geometry.CornerRadius(w * 0.08f))
            }
        }
    }
}

/** L'icona di «Sviluppa»: tre cursori (come quelli di LightCraft), disegnata come le altre icone dell'app. */
@Composable
fun DevelopIcon(modifier: Modifier, color: Color) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        listOf(0.22f to 0.35f, 0.5f to 0.68f, 0.78f to 0.45f).forEach { (y, x) ->
            drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.08f, h * y), androidx.compose.ui.geometry.Offset(w * 0.92f, h * y), stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawCircle(color, w * 0.12f, androidx.compose.ui.geometry.Offset(w * x, h * y))
        }
    }
}
