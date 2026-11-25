package io.github.poty87.viewercraftstar

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
private class DevControl(val path: String, val it: String, val en: String, val min: Float, val max: Float, val step: Float,
                      val decimals: Int, val default: Float)

private class DevSection(val it: String, val en: String, val controls: List<DevControl>)

private val LIGHT = DevSection("Luce", "Light", listOf(
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
private val STRAIGHTEN = DevControl("crop.geometry.angle", "Raddrizza", "Straighten", -45f, 45f, 0.1f, 1, 0f)

/** I rapporti del ritaglio (come in LightCraft): nome e larghezza:altezza. */
private val ASPECTS = listOf("1:1" to (1 to 1), "4:5" to (4 to 5), "5:4" to (5 to 4), "2:3" to (2 to 3), "3:2" to (3 to 2),
    "16:9" to (16 to 9), "9:16" to (9 to 16))

/** Le rotazioni di 90 gradi in senso orario (orientamento di LightCraft). */
private val TURNS = listOf("Normal", "Rotate90", "Rotate180", "Rotate270")
private val SWAPS = setOf("Rotate90", "Rotate270", "Transpose", "Transverse")

/**
 * «Modifica»: lo sviluppo della foto sul telefono, con il motore di LightCraft sulla smart preview. «Fatto» manda le
 * impostazioni al PC (come le altre modifiche): finché non le applica, la foto mostra il risultato del telefono.
 */
@Composable
fun DevelopEditor(lib: LibraryViewModel, photo: LibPhoto, onClose: () -> Unit) {
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
    var before by remember { mutableStateOf<Bitmap?>(null) }
    var showBefore by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf(LIGHT) }
    var profiles by remember { mutableStateOf<List<DevelopProfiles.Profile>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }
    val handle = remember { longArrayOf(0L) }
    // un solo filo per il motore (la foto aperta non va usata da due sviluppi insieme); conta solo l'ultima richiesta
    val engine = remember { Executors.newSingleThreadExecutor().asCoroutineDispatcher() }
    val requests = remember { MutableStateFlow<String?>(null) }
    var srcSize by remember { mutableStateOf(3.0 to 2.0) } // la foto aperta, in pixel
    var cropTick by remember { mutableIntStateOf(0) } // ridisegna il riquadro del ritaglio
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
        base = entry.sig
        original = JSONObject(entry.settings.toString())
        settings = entry.settings
        requests.value = entry.settings.toString()
    }

    // sviluppo: ogni nuova richiesta sostituisce quella in attesa
    LaunchedEffect(Unit) {
        requests.collectLatest { json ->
            if (json == null || handle[0] == 0L) return@collectLatest
            val a = withContext(engine) { DevelopEngine.render(handle[0], json, edge, true) } ?: return@collectLatest
            val bmp = DevelopEngine.bitmap(a)
            if (before == null) before = bmp
            image = bmp
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
    }

    /** Il rapporto bloccato (larghezza/altezza in pixel), 0 = libero. */
    fun aspect(): Double = settings?.optJSONObject("crop")?.optJSONArray("aspect")?.let { a ->
        if (a.length() == 2 && a.getDouble(1) > 0) a.getDouble(0) / a.getDouble(1) else 0.0
    } ?: 0.0

    /**
     * Quello che si sviluppa: le impostazioni; mentre si ritaglia, la foto intera raddrizzata (il riquadro sopra la
     * mostra il ritaglio, come in LightCraft).
     */
    fun renderJson(): String? {
        val s = settings ?: return null
        if (section != CROP) return s.toString()
        val view = JSONObject(s.toString())
        DevelopStore.setPath(view, "crop.geometry", unitGeometry(geometry().optDouble("angle", 0.0)))
        return view.toString()
    }

    fun changed() {
        requests.value = renderJson()
    }

    fun value(c: DevControl): Float = (settings?.let { DevelopStore.getPath(it, c.path) } as? Number)?.toFloat() ?: c.default

    fun set(c: DevControl, v: Float) {
        val s = settings ?: return
        val rounded = if (c.decimals == 0) v.roundToInt().toDouble() else (v / c.step).roundToInt() * c.step.toDouble()
        DevelopStore.setPath(s, c.path, rounded)
        if (c.path.startsWith("wb.")) DevelopStore.setPath(s, "wb.mode", "custom")
        changed()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            // intestazione: chiudi senza salvare, titolo, «Fatto»
            Row(Modifier.fillMaxWidth().background(Color(0xB3000000)).statusBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(onClick = onClose, pad = 14.dp) { Text(L.t("Annulla", "Cancel")) }
                Spacer(Modifier.weight(1f))
                // «Prima»: tenendo premuto si vede com'era; «Ripristina» torna alle impostazioni di partenza
                if (settings != null) {
                    Text(L.t("Prima", "Before"), color = if (showBefore) Color.Black else C.text, fontSize = 13.sp,
                        modifier = Modifier.background(if (showBefore) C.accent else C.card, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                            .pointerInput(Unit) { detectTapGestures(onPress = { showBefore = true; tryAwaitRelease(); showBefore = false }) }
                            .padding(horizontal = 12.dp, vertical = 7.dp))
                    Pill(L.t("Ripristina", "Reset"), false) {
                        settings = original?.let { JSONObject(it.toString()) }
                        resets++
                        changed()
                    }
                }
                PillButton(onClick = {
                    val s = settings ?: return@PillButton
                    if (s.toString() == original.toString()) { onClose(); return@PillButton }
                    saving = true
                    scope.launch {
                        // la foto sviluppata dal telefono, da mostrare finché il PC non applica (2048 pixel)
                        val big = withContext(engine) { DevelopEngine.render(handle[0], s.toString(), 2048, true) }?.let { DevelopEngine.bitmap(it) }
                        lib.setDevelop(photo.id, s.toString(), base, big)
                        onClose()
                    }
                }, primary = true, enabled = settings != null && !saving, pad = 14.dp) { Text(L.t("Fatto", "Done")) }
            }
            // la foto (tenendo premuto «Prima» si vede com'era)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val shown = if (showBefore) before else image
                val bitmap = remember(shown) { shown?.asImageBitmap() }
                if (bitmap != null) Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else if (error == null) CircularProgressIndicator(color = C.accent, strokeWidth = 2.dp)
                // ritaglio: il riquadro sopra la foto intera raddrizzata, con angoli e lati da trascinare
                if (section == CROP && !showBefore && shown != null) CropOverlay(shown.width, shown.height, cropTick,
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
                // sezioni, a pillole come nel resto dell'app
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (sec in listOf(LIGHT, COLOR, WHITE, PROFILE, CROP)) Pill(L.t(sec.it, sec.en), section == sec) {
                        val wasCrop = section == CROP
                        section = sec
                        if (wasCrop != (sec == CROP)) changed() // dentro e fuori dal ritaglio la foto si mostra diversa
                    }
                }
                // i cursori: sempre la stessa altezza (le sezioni non saltano), il resto scorre; la foto resta grande
                Column(Modifier.height((screen.screenHeightDp * 0.34f).dp).verticalScroll(rememberScrollState())) {
                    if (section == PROFILE) ProfilePicker(profiles, settings?.optJSONObject("profile")?.optString("id").orEmpty(), photo.camera) { p ->
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { DevelopProfiles.ensure(context, lib.settings.nas, lib.libShare, libDir, idx.library, p) }
                            if (ok) { settings?.let { DevelopStore.setPath(it, "profile.id", p.id) }; changed() }
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
                    section.controls.forEach { c -> ControlSlider(c, value(c), resets) { set(c, it) } }
                    if (section == CROP) {
                        val current = settings?.optJSONObject("crop")?.optJSONArray("aspect")
                        val key = current?.let { if (it.length() == 2) "${it.getInt(0)}:${it.getInt(1)}" else null }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Pill(L.t("Libero", "Free"), key == null) {
                                settings?.let { DevelopStore.setPath(it, "crop.aspect", null) }
                                cropTick++
                            }
                            Pill(L.t("Originale", "Original"), false) {
                                val (w, h) = dims()
                                settings?.let { DevelopStore.setPath(it, "crop.aspect", null) }
                                setGeometry(DevelopEngine.cropFit(w, h, geometry().optDouble("angle", 0.0), 0.0))
                            }
                            ASPECTS.forEach { (name, ratio) ->
                                Pill(name, key == name) {
                                    val (w, h) = dims()
                                    settings?.let { DevelopStore.setPath(it, "crop.aspect", org.json.JSONArray(listOf(ratio.first, ratio.second))) }
                                    setGeometry(DevelopEngine.cropFit(w, h, geometry().optDouble("angle", 0.0), ratio.first.toDouble() / ratio.second))
                                }
                            }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                        ControlSlider(STRAIGHTEN, geometry().optDouble("angle", 0.0).toFloat(), resets) { v ->
                            val (w, h) = dims()
                            val angle = (v * 10).roundToInt() / 10.0
                            setGeometry(DevelopEngine.cropWithAngle(geometry().toString(), angle, w, h))
                            changed()
                        }
                        Text(L.t("Trascina angoli e lati del riquadro sulla foto; dentro il riquadro lo sposti.",
                            "Drag the frame's corners and edges on the photo; drag inside it to move it."), color = C.muted, fontSize = 12.sp)
                    }
                }
            }
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
private fun CropOverlay(imgW: Int, imgH: Int, tick: Int, rect: () -> JSONObject?, current: () -> String,
                        onDrag: (String, Int, Double, Double, Double, Double) -> Unit) {
    val density = LocalDensity.current
    val grabRadius = with(density) { 36.dp.toPx() }
    var box by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    // la foto mostrata (ContentScale.Fit) dentro lo spazio
    fun shownRect(): androidx.compose.ui.geometry.Rect {
        val bw = box.width.toFloat()
        val bh = box.height.toFloat()
        if (bw <= 0f || bh <= 0f || imgW <= 0 || imgH <= 0) return androidx.compose.ui.geometry.Rect.Zero
        val s = minOf(bw / imgW, bh / imgH)
        val w = imgW * s
        val h = imgH * s
        return androidx.compose.ui.geometry.Rect((bw - w) / 2f, (bh - h) / 2f, (bw + w) / 2f, (bh + h) / 2f)
    }
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
            val shade = Color(0x99000000)
            // fuori dal ritaglio, scuro
            drawRect(shade, androidx.compose.ui.geometry.Offset(img.left, img.top), androidx.compose.ui.geometry.Size(img.width, t - img.top))
            drawRect(shade, androidx.compose.ui.geometry.Offset(img.left, b), androidx.compose.ui.geometry.Size(img.width, img.bottom - b))
            drawRect(shade, androidx.compose.ui.geometry.Offset(img.left, t), androidx.compose.ui.geometry.Size(l - img.left, b - t))
            drawRect(shade, androidx.compose.ui.geometry.Offset(ri, t), androidx.compose.ui.geometry.Size(img.right - ri, b - t))
            // griglia dei terzi
            for (i in 1..2) {
                val x = l + (ri - l) * i / 3f
                val y = t + (b - t) * i / 3f
                drawLine(Color(0x66FFFFFF), androidx.compose.ui.geometry.Offset(x, t), androidx.compose.ui.geometry.Offset(x, b), 1.5f)
                drawLine(Color(0x66FFFFFF), androidx.compose.ui.geometry.Offset(l, y), androidx.compose.ui.geometry.Offset(ri, y), 1.5f)
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

/** La geometria di un ritaglio che prende tutta la foto, girata di `angle` gradi. */
private fun unitGeometry(angle: Double): JSONObject = JSONObject()
    .put("rect", JSONObject().put("x0", 0.0).put("y0", 0.0).put("x1", 1.0).put("y1", 1.0)).put("angle", angle)

/** Un cursore con nome e valore; un tocco sul valore lo riporta a quello di base. */
@Composable
private fun ControlSlider(c: DevControl, value: Float, resets: Int, onChange: (Float) -> Unit) {
    var v by remember(c.path, resets) { mutableStateOf(value.coerceIn(c.min, c.max)) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(L.t(c.it, c.en), color = C.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(if (c.decimals == 0) v.roundToInt().toString() else "%.${c.decimals}f".format(v), color = C.muted, fontSize = 13.sp,
                modifier = Modifier.clickable { v = c.default; onChange(c.default) }.padding(horizontal = 6.dp))
        }
        Slider(value = v, onValueChange = { v = it; onChange(it) }, valueRange = c.min..c.max, modifier = Modifier.height(30.dp),
            colors = SliderDefaults.colors(thumbColor = C.accent, activeTrackColor = C.accent, inactiveTrackColor = C.card))
    }
}

/** I profili della libreria (LUT), prima quelli della fotocamera della foto; quello in uso acceso. */
@Composable
private fun ProfilePicker(profiles: List<DevelopProfiles.Profile>, current: String, camera: String, onPick: (DevelopProfiles.Profile) -> Unit) {
    val luts = profiles.filter { it.kind == "lut" }
    val mine = luts.filter { camera.isNotEmpty() && (camera.contains(it.group, ignoreCase = true) || it.group.contains(camera, ignoreCase = true)) }
    val shown = (mine.ifEmpty { luts }).sortedBy { it.name }
    if (current.isNotEmpty() && shown.none { it.id == current })
        Text(L.t("In uso: ", "In use: ") + (profiles.firstOrNull { it.id == current }?.name ?: current), color = C.muted, fontSize = 12.sp)
    if (shown.isEmpty()) Text(L.t("Nessun profilo LUT nella libreria.", "No LUT profiles in the library."), color = C.muted, fontSize = 12.sp)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        shown.forEach { p -> Pill(p.name, p.id == current) { onPick(p) } }
    }
    Spacer(Modifier.width(1.dp))
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
