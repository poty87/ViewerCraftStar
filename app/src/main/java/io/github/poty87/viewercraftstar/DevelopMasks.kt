package io.github.poty87.viewercraftstar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Le maschere di LightCraft nelle impostazioni di sviluppo (`masks`: elenco di {id, name, visible, invert, on_photo,
 * components: [{op, invert, shape}], adjust}). Le coordinate delle forme sono normalizzate sulla foto com'è scattata
 * (prima di obiettivo, ritaglio e raddrizzamento: `on_photo`); i raggi del radiale sono in unità del lato lungo.
 */
internal object Masks {
    fun list(s: JSONObject): JSONArray = s.optJSONArray("masks") ?: JSONArray().also { s.put("masks", it) }

    fun all(s: JSONObject?): List<JSONObject> {
        val a = s?.optJSONArray("masks") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    fun find(s: JSONObject?, id: Int): JSONObject? = all(s).firstOrNull { it.optInt("id", -1) == id }

    /** La forma della maschera (la prima: le maschere fatte sul telefono ne hanno una). */
    /**
     * La forma aperta della maschera aperta (una maschera può unirne più d'una: aggiungi, sottrai, interseca). L'editor
     * la imposta quando si sceglie una forma; le funzioni qui sotto lavorano su quella.
     */
    @Volatile var part = 0

    fun components(m: JSONObject?): List<JSONObject> {
        val a = m?.optJSONArray("components") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    /** La forma `i` della maschera (di base quella aperta; la prima se non c'è). */
    fun shape(m: JSONObject?, i: Int = part): JSONObject? {
        val c = components(m)
        return (c.getOrNull(i) ?: c.firstOrNull())?.optJSONObject("shape")
    }

    fun kind(m: JSONObject?, i: Int = part): String = shape(m, i)?.optString("kind").orEmpty()

    /** Si può cambiare la forma sul telefono (le altre si vedono e se ne regolano le correzioni). */
    fun editable(m: JSONObject?, i: Int = part): Boolean = kind(m, i) in EDITABLE

    val EDITABLE = setOf("linear", "radial", "brush", "luminanceRange", "colorRange", "sky", "subject", "background")

    /** Aggiunge alla maschera una forma con `op` (add, subtract, intersect); torna il suo indice. */
    fun addComponent(m: JSONObject, op: String, shape: JSONObject): Int {
        val a = m.optJSONArray("components") ?: JSONArray().also { m.put("components", it) }
        a.put(JSONObject().put("op", op).put("invert", false).put("shape", shape))
        return a.length() - 1
    }

    fun removeComponent(m: JSONObject, i: Int) {
        val keep = components(m).filterIndexed { k, _ -> k != i }
        m.put("components", JSONArray().apply { keep.forEach { put(it) } })
    }

    /** Come si scrive l'unione di una forma: aggiungi (+), sottrai (−), interseca (∩). */
    fun opSymbol(op: String) = when (op) { "subtract" -> "− "; "intersect" -> "∩ "; else -> "" }

    /** Cielo, soggetto, sfondo: li trova LightCraft (dalle maschere salvate nel file, se ci sono, o con una stima). */
    fun auto(kind: String): JSONObject = JSONObject().put("kind", kind)

    fun kindName(kind: String): String = when (kind) {
        "linear" -> L.t("Lineare", "Linear")
        "radial" -> L.t("Radiale", "Radial")
        "brush" -> L.t("Pennello", "Brush")
        "colorRange" -> L.t("Colore", "Color range")
        "luminanceRange" -> L.t("Luminanza", "Luminance range")
        "depthRange" -> L.t("Profondità", "Depth range")
        "subject" -> L.t("Soggetto", "Subject")
        "sky" -> L.t("Cielo", "Sky")
        "background" -> L.t("Sfondo", "Background")
        "object" -> L.t("Oggetto", "Object")
        "prompt" -> L.t("Descrizione", "Description")
        "people" -> L.t("Persone", "People")
        "landscape" -> L.t("Paesaggio", "Landscape")
        else -> kind
    }

    /** Aggiunge una maschera con questa forma; torna il suo id. */
    fun add(s: JSONObject, shape: JSONObject): Int {
        val a = list(s)
        val id = (all(s).maxOfOrNull { it.optInt("id") } ?: 0) + 1
        val names = all(s).map { it.optString("name") }.toSet()
        var n = a.length() + 1
        while (L.t("Maschera $n", "Mask $n") in names) n++
        a.put(JSONObject().put("id", id).put("name", L.t("Maschera $n", "Mask $n")).put("visible", true).put("invert", false)
            .put("on_photo", true)
            .put("components", JSONArray().put(JSONObject().put("op", "add").put("invert", false).put("shape", shape)))
            .put("adjust", JSONObject().put("amount", 100.0)))
        return id
    }

    fun remove(s: JSONObject, id: Int) {
        val keep = all(s).filter { it.optInt("id") != id }
        s.put("masks", JSONArray().apply { keep.forEach { put(it) } })
    }

    fun duplicate(s: JSONObject, id: Int): Int? {
        val m = find(s, id) ?: return null
        val copy = JSONObject(m.toString())
        val nid = (all(s).maxOfOrNull { it.optInt("id") } ?: 0) + 1
        copy.put("id", nid).put("name", m.optString("name") + L.t(" copia", " copy"))
        list(s).put(copy)
        return nid
    }

    fun linear(start: DoubleArray, end: DoubleArray): JSONObject = JSONObject().put("kind", "linear")
        .put("start", point(start[0], start[1])).put("end", point(end[0], end[1]))

    /** Pennello vuoto: i tratti si aggiungono disegnando. */
    fun brush(): JSONObject = JSONObject().put("kind", "brush").put("strokes", JSONArray())

    /** Luminanza: come la crea LightCraft (le luci, da 0,6 a 1). */
    fun luminance(): JSONObject = JSONObject().put("kind", "luminanceRange").put("lo", 0.6).put("hi", 1.0)
        .put("lo_feather", 0.1).put("hi_feather", 0.1)

    /** Colore: i colori si prendono toccando la foto. */
    fun color(): JSONObject = JSONObject().put("kind", "colorRange").put("samples", JSONArray()).put("refine", 50.0)

    /** Un colore OkLab come colore dello schermo (per il campione nel pannello). */
    fun okToColor(l: Double, a: Double, b: Double): Color {
        val l1 = l + 0.3963377774 * a + 0.2158037573 * b
        val m1 = l - 0.1055613458 * a - 0.0638541728 * b
        val s1 = l - 0.0894841775 * a - 1.2914855480 * b
        val (L, M, S) = Triple(l1 * l1 * l1, m1 * m1 * m1, s1 * s1 * s1)
        // da Rec.2020 lineare (lo spazio di LightCraft) a sRGB lineare, poi la curva sRGB
        val r20 = 4.0767416621 * L - 3.3077115913 * M + 0.2309699292 * S
        val g20 = -1.2684380046 * L + 2.6097574011 * M - 0.3413193965 * S
        val b20 = -0.0041960863 * L - 0.7034186147 * M + 1.7076147010 * S
        fun enc(v: Double) = (if (v <= 0.0031308) 12.92 * v else 1.055 * Math.pow(v, 1 / 2.4) - 0.055).coerceIn(0.0, 1.0).toFloat()
        return Color(enc(r20), enc(g20), enc(b20))
    }

    fun radial(cx: Double, cy: Double, rx: Double, ry: Double): JSONObject = JSONObject().put("kind", "radial")
        .put("center", point(cx, cy)).put("rx", rx).put("ry", ry).put("angle", 0.0).put("feather", 50.0).put("invert", false)

    fun point(x: Double, y: Double): JSONObject = JSONObject().put("x", x).put("y", y)
}

/** Un cursore delle correzioni di una maschera (`adjust` di LightCraft) con il suo intervallo (come `mask.adjust`). */
internal class MaskControl(val key: String, val it: String, val en: String, val min: Float, val max: Float, val step: Float = 1f,
                           val decimals: Int = 0, val default: Float = 0f)

internal val MASK_AMOUNT = MaskControl("amount", "Quantità", "Amount", 0f, 200f, default = 100f)

/** Le correzioni di una maschera, nelle stesse sotto-schede dell'editor. */
internal val MASK_GROUPS = listOf(
    Triple("Luce", "Light", listOf(
        MaskControl("exposure", "Esposizione", "Exposure", -4f, 4f, 0.01f, 2),
        MaskControl("contrast", "Contrasto", "Contrast", -100f, 100f),
        MaskControl("highlights", "Alte luci", "Highlights", -100f, 100f),
        MaskControl("shadows", "Ombre", "Shadows", -100f, 100f),
        MaskControl("whites", "Bianchi", "Whites", -100f, 100f),
        MaskControl("blacks", "Neri", "Blacks", -100f, 100f))),
    Triple("Colore", "Color", listOf(
        MaskControl("temp", "Temperatura", "Temperature", -100f, 100f),
        MaskControl("tint", "Tinta", "Tint", -100f, 100f),
        MaskControl("saturation", "Saturazione", "Saturation", -100f, 100f),
        MaskControl("hue", "Tonalità", "Hue", -100f, 100f))),
    Triple("Effetti", "Effects", listOf(
        MaskControl("texture", "Texture", "Texture", -100f, 100f),
        MaskControl("clarity", "Chiarezza", "Clarity", -100f, 100f),
        MaskControl("dehaze", "Foschia", "Dehaze", -100f, 100f))),
    Triple("Dettaglio", "Detail", listOf(
        MaskControl("sharpness", "Nitidezza", "Sharpness", -100f, 100f),
        MaskControl("noise", "Rumore", "Noise", -100f, 100f))),
)

/**
 * Le maniglie della maschera aperta sopra la foto (sviluppata, `bmpW` x `bmpH`, mostrata intera al centro del riquadro):
 * - lineare: tre linee (inizio = effetto pieno, centro, fine = nessun effetto); si trascinano gli estremi o il centro;
 * - radiale: l'ellisse (e quella interna della sfumatura), quattro maniglie per i raggi, una per girarla, dentro per spostarla.
 * `toOut` / `toMask` convertono punti (x, y, …) tra i pixel della foto e le coordinate della maschera (LightCraft);
 * `long` = (sx, sy): da coordinate normalizzate a unità del lato lungo. `onEdit` dopo ogni cambiamento della forma.
 */
@Composable
internal fun MaskOverlay(bmpW: Int, bmpH: Int, tick: Int, mask: () -> JSONObject?, toOut: (DoubleArray) -> DoubleArray?,
                         toMask: (DoubleArray) -> DoubleArray?, long: () -> Pair<Double, Double>, onDragging: (Boolean) -> Unit,
                         onEdit: () -> Unit) {
    val density = LocalDensity.current
    val grab = with(density) { 34.dp.toPx() }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val bw by rememberUpdatedState(bmpW)
    val bh by rememberUpdatedState(bmpH)

    fun shown(): Rect {
        if (box.width == 0 || bw == 0) return Rect.Zero
        val k = minOf(box.width.toFloat() / bw, box.height.toFloat() / bh)
        val w = bw * k
        val h = bh * k
        return Rect((box.width - w) / 2, (box.height - h) / 2, (box.width + w) / 2, (box.height + h) / 2)
    }

    /** Punti in coordinate della maschera → sullo schermo (null se uno non ha posto nella foto). */
    fun screen(pts: DoubleArray): List<Offset?> {
        val r = shown()
        val o = toOut(pts) ?: return List(pts.size / 2) { null }
        return (0 until o.size / 2).map { i ->
            val x = o[2 * i]
            val y = o[2 * i + 1]
            if (x.isNaN() || y.isNaN()) null
            else Offset(r.left + (x * r.width / bw).toFloat(), r.top + (y * r.height / bh).toFloat())
        }
    }

    /** Un punto dello schermo → coordinate della maschera. */
    fun maskAt(p: Offset): Pair<Double, Double>? {
        val r = shown()
        if (r.width <= 0f) return null
        val o = toMask(doubleArrayOf(((p.x - r.left) * bw / r.width).toDouble(), ((p.y - r.top) * bh / r.height).toDouble())) ?: return null
        return o[0] to o[1]
    }

    fun pt(o: JSONObject?) = (o?.optDouble("x", 0.5) ?: 0.5) to (o?.optDouble("y", 0.5) ?: 0.5)

    /** Le linee della lineare (in coordinate della maschera), punti per disegnarle anche se l'obiettivo le curva. */
    fun linearLines(s: JSONObject): List<DoubleArray> {
        val (sx, sy) = long()
        val (ax, ay) = pt(s.optJSONObject("start"))
        val (bx, by) = pt(s.optJSONObject("end"))
        val dx = (bx - ax) * sx
        val dy = (by - ay) * sy
        val len = hypot(dx, dy).coerceAtLeast(1e-6)
        val nx = -dy / len
        val ny = dx / len
        return listOf(ax to ay, (ax + bx) / 2 to (ay + by) / 2, bx to by).map { (px, py) ->
            DoubleArray(2 * 21) { k ->
                val t = (k / 2 - 10) * 0.25 // da -2.5 a +2.5 lati lunghi
                if (k % 2 == 0) px + t * nx / sx else py + t * ny / sy
            }
        }
    }

    /** Ellisse del radiale (scala 1 = bordo, 1 - sfumatura = dove l'effetto è pieno), in coordinate della maschera. */
    fun ellipse(s: JSONObject, scale: Double, t: Double): Pair<Double, Double> {
        val (sx, sy) = long()
        val (cx, cy) = pt(s.optJSONObject("center"))
        val a = Math.toRadians(s.optDouble("angle", 0.0))
        val lx = s.optDouble("rx", 0.2) * scale * cos(t)
        val ly = s.optDouble("ry", 0.15) * scale * sin(t)
        val dx = lx * cos(a) - ly * sin(a)
        val dy = lx * sin(a) + ly * cos(a)
        return cx + dx / sx to cy + dy / sy
    }

    /** Le maniglie sullo schermo: lineare [inizio, fine, centro]; radiale [+x, +y, -x, -y, centro, rotazione]. */
    fun handles(m: JSONObject?): List<Offset?> {
        val s = Masks.shape(m) ?: return emptyList()
        return when (s.optString("kind")) {
            "linear" -> {
                val (ax, ay) = pt(s.optJSONObject("start"))
                val (bx, by) = pt(s.optJSONObject("end"))
                screen(doubleArrayOf(ax, ay, bx, by, (ax + bx) / 2, (ay + by) / 2))
            }
            "radial" -> {
                val pts = ArrayList<Double>()
                for (t in listOf(0.0, Math.PI / 2, Math.PI, 3 * Math.PI / 2)) ellipse(s, 1.0, t).let { pts += it.first; pts += it.second }
                pt(s.optJSONObject("center")).let { pts += it.first; pts += it.second }
                ellipse(s, 1.3, -Math.PI / 2).let { pts += it.first; pts += it.second }
                screen(pts.toDoubleArray())
            }
            else -> emptyList()
        }
    }

    Box(Modifier.fillMaxSize().onSizeChanged { box = it }.pointerInput(Unit) {
        var which = -1
        var start: JSONObject? = null
        var from: Pair<Double, Double>? = null
        detectDragGestures(
            onDragStart = { at ->
                val m = mask()
                val s = Masks.shape(m)
                val hs = handles(m)
                val near = hs.withIndex().filter { it.value != null }.minByOrNull { (it.value!! - at).getDistance() }
                which = if (near != null && (near.value!! - at).getDistance() <= grab) near.index else -1
                if (which < 0 && s != null) {
                    // dentro il radiale, o vicino alla linea centrale della lineare: si sposta tutta
                    val f = maskAt(at)
                    if (f != null && s.optString("kind") == "radial") {
                        val (sx, sy) = long()
                        val (cx, cy) = pt(s.optJSONObject("center"))
                        val a = Math.toRadians(s.optDouble("angle", 0.0))
                        val dx = (f.first - cx) * sx
                        val dy = (f.second - cy) * sy
                        val lx = dx * cos(a) + dy * sin(a)
                        val ly = -dx * sin(a) + dy * cos(a)
                        if (hypot(lx / s.optDouble("rx", 0.2), ly / s.optDouble("ry", 0.15)) <= 1.0) which = 4
                    } else if (s.optString("kind") == "linear") {
                        val mid = hs.getOrNull(2)
                        if (mid != null && (mid - at).getDistance() <= grab * 2.5f) which = 2
                    }
                }
                start = s?.let { JSONObject(it.toString()) }
                from = maskAt(at)
                if (which >= 0 && start != null) onDragging(true)
            },
            onDragEnd = { if (which >= 0) onDragging(false); which = -1 },
            onDragCancel = { if (which >= 0) onDragging(false); which = -1 },
            onDrag = { change, _ ->
                val s0 = start ?: return@detectDragGestures
                val f0 = from ?: return@detectDragGestures
                if (which < 0) return@detectDragGestures
                change.consume()
                val f = maskAt(change.position) ?: return@detectDragGestures
                val s = Masks.shape(mask()) ?: return@detectDragGestures
                val ddx = f.first - f0.first
                val ddy = f.second - f0.second
                fun moved(o: JSONObject?): JSONObject { val (x, y) = pt(o); return Masks.point(x + ddx, y + ddy) }
                when (s0.optString("kind")) {
                    "linear" -> when (which) {
                        0 -> s.put("start", moved(s0.optJSONObject("start")))
                        1 -> s.put("end", moved(s0.optJSONObject("end")))
                        else -> { s.put("start", moved(s0.optJSONObject("start"))); s.put("end", moved(s0.optJSONObject("end"))) }
                    }
                    "radial" -> {
                        val (sx, sy) = long()
                        val (cx, cy) = pt(s0.optJSONObject("center"))
                        val a = Math.toRadians(s0.optDouble("angle", 0.0))
                        val dx = (f.first - cx) * sx
                        val dy = (f.second - cy) * sy
                        val lx = dx * cos(a) + dy * sin(a)
                        val ly = -dx * sin(a) + dy * cos(a)
                        when (which) {
                            0, 2 -> s.put("rx", max(0.01, abs(lx)))
                            1, 3 -> s.put("ry", max(0.01, abs(ly)))
                            5 -> s.put("angle", Math.toDegrees(atan2(dx, -dy)))
                            else -> s.put("center", moved(s0.optJSONObject("center")))
                        }
                    }
                }
                onEdit()
            })
    }) {
        Canvas(Modifier.fillMaxSize()) {
            if (tick < 0) return@Canvas // si ridisegna a ogni modifica (tick)
            val m = mask() ?: return@Canvas
            val s = Masks.shape(m) ?: return@Canvas
            val r = shown()
            if (r.width <= 0f) return@Canvas
            val line = Color.White
            val shadow = Color(0x99000000)
            val w = 1.6.dp.toPx()
            fun poly(pts: List<Offset?>, dashed: Boolean = false) {
                val path = Path()
                var open = false
                for (p in pts) {
                    if (p == null) { open = false; continue }
                    if (!open) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                    open = true
                }
                val fx = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null
                drawPath(path, shadow, style = Stroke(w * 2.2f, pathEffect = fx))
                drawPath(path, line, style = Stroke(w, pathEffect = fx))
            }
            fun knob(p: Offset?, filled: Boolean) {
                p ?: return
                drawCircle(shadow, 11.dp.toPx(), p)
                drawCircle(line, 9.dp.toPx(), p, style = Stroke(w * 1.4f))
                if (filled) drawCircle(line, 5.dp.toPx(), p)
            }
            clipRect(r.left, r.top, r.right, r.bottom) {
                when (s.optString("kind")) {
                    "linear" -> {
                        linearLines(s).forEachIndexed { i, pts -> poly(screen(pts), dashed = i == 1) }
                        val hs = handles(m)
                        knob(hs.getOrNull(0), false)
                        knob(hs.getOrNull(1), false)
                        knob(hs.getOrNull(2), true)
                    }
                    "radial" -> {
                        val f = (s.optDouble("feather", 50.0) / 100.0).coerceIn(0.0, 1.0)
                        for ((scale, dashed) in listOf(1.0 to false, (1.0 - f).coerceAtLeast(0.02) to true)) {
                            val pts = ArrayList<Double>()
                            for (k in 0..64) ellipse(s, scale, 2 * Math.PI * k / 64).let { pts += it.first; pts += it.second }
                            poly(screen(pts.toDoubleArray()), dashed)
                        }
                        val hs = handles(m)
                        for (i in 0..3) knob(hs.getOrNull(i), false)
                        knob(hs.getOrNull(4), true)
                        // rotazione: un tondino piccolo sopra, unito al bordo
                        val top = hs.getOrNull(1)?.let { _ -> screen(ellipse(s, 1.0, -Math.PI / 2).toList().toDoubleArray()).firstOrNull() }
                        val rot = hs.getOrNull(5)
                        if (top != null && rot != null) {
                            drawLine(shadow, top, rot, w * 2.2f)
                            drawLine(line, top, rot, w)
                            drawCircle(shadow, 8.dp.toPx(), rot)
                            drawCircle(line, 6.dp.toPx(), rot)
                        }
                    }
                }
            }
        }
    }
}

private fun Pair<Double, Double>.toList() = listOf(first, second)

/** Dove sta la foto (sviluppata, `bmpW` x `bmpH`) dentro un riquadro `box`: intera, al centro. */
private fun fitRect(box: IntSize, bmpW: Int, bmpH: Int): Rect {
    if (box.width == 0 || bmpW == 0) return Rect.Zero
    val k = minOf(box.width.toFloat() / bmpW, box.height.toFloat() / bmpH)
    val w = bmpW * k
    val h = bmpH * k
    return Rect((box.width - w) / 2, (box.height - h) / 2, (box.width + w) / 2, (box.height + h) / 2)
}

/**
 * Pennello: un dito disegna sulla foto (un tocco fa un punto solo). Mentre si disegna si vede il tratto (rosso, o
 * grigio per Cancella) e il cerchio della punta; alla fine `onStroke` riceve i punti in pixel della foto sviluppata e
 * il raggio della punta negli stessi pixel.
 */
@Composable
internal fun BrushOverlay(bmpW: Int, bmpH: Int, radiusDp: Float, erase: Boolean, onDragging: (Boolean) -> Unit,
                          onStroke: (List<Offset>, Float) -> Unit) {
    val density = LocalDensity.current
    var box by remember { mutableStateOf(IntSize.Zero) }
    val pts = remember { androidx.compose.runtime.mutableStateListOf<Offset>() }
    var cursor by remember { mutableStateOf<Offset?>(null) }
    val r by rememberUpdatedState(with(density) { radiusDp.dp.toPx() })
    val bw by rememberUpdatedState(bmpW)
    val bh by rememberUpdatedState(bmpH)
    fun finish() {
        val rect = fitRect(box, bw, bh)
        if (pts.isNotEmpty() && rect.width > 0f) {
            val k = bw / rect.width
            onStroke(pts.map { Offset((it.x - rect.left) * k, (it.y - rect.top) * k) }, r * k)
        }
        pts.clear()
        cursor = null
    }
    Box(Modifier.fillMaxSize().onSizeChanged { box = it }
        .pointerInput(Unit) { detectTapGestures(onTap = { p -> pts.clear(); pts += p; finish() }) }
        .pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { p -> pts.clear(); pts += p; cursor = p; onDragging(true) },
                onDragEnd = { onDragging(false); finish() },
                onDragCancel = { onDragging(false); pts.clear(); cursor = null },
                onDrag = { change, _ ->
                    change.consume()
                    val p = change.position
                    cursor = p
                    if ((p - pts.last()).getDistance() >= r * 0.2f) pts += p
                })
        }) {
        Canvas(Modifier.fillMaxSize()) {
            val col = if (erase) Color(0x99FFFFFF) else Color(0x99EB4034)
            if (pts.size > 1) {
                val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
                drawPath(path, col, style = Stroke(r * 2, cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
            cursor?.let { c ->
                drawCircle(Color(0x99000000), r, c, style = Stroke(3.dp.toPx()))
                drawCircle(Color.White, r, c, style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}

/** Colore: un tocco sulla foto prende il colore in quel punto (`onPick` riceve il punto in pixel della foto sviluppata). */
@Composable
internal fun ColorPickOverlay(bmpW: Int, bmpH: Int, onPick: (Offset) -> Unit) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    val bw by rememberUpdatedState(bmpW)
    val bh by rememberUpdatedState(bmpH)
    Box(Modifier.fillMaxSize().onSizeChanged { box = it }.pointerInput(Unit) {
        detectTapGestures(onTap = { p ->
            val rect = fitRect(box, bw, bh)
            if (rect.contains(p)) onPick(Offset((p.x - rect.left) * bw / rect.width, (p.y - rect.top) * bh / rect.height))
        })
    })
}

/** «Rinomina»: il nome della maschera. */
@Composable
internal fun MaskNameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    BlurDialog(onDismiss) {
        DialogTitle(L.t("Nome della maschera", "Mask name"))
        OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        DialogButtons(L.t("Annulla", "Cancel") to onDismiss, main = L.t("Salva", "Save") to { onSave(name.trim().ifEmpty { current }) })
    }
}

/**
 * In alto nel pannello delle maschere: le maschere come pillole (accesa quella aperta), «+ Nuova» (Lineare, Radiale) e,
 * per quella aperta, ☰ con Rinomina, Duplica, Inverti, Nascondi/Mostra, Elimina.
 */
@Composable
internal fun MaskBar(masks: List<JSONObject>, selected: Int?, overlay: Boolean, onOverlay: () -> Unit, onSelect: (Int) -> Unit,
                     onNew: (String) -> Unit, onMenu: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            masks.forEach { m ->
                val id = m.optInt("id")
                val hidden = !m.optBoolean("visible", true)
                Pill(m.optString("name").ifEmpty { Masks.kindName(Masks.kind(m)) } + (if (hidden) " ◌" else ""), id == selected) { onSelect(id) }
            }
            Box {
                var open by remember { mutableStateOf(false) }
                Pill(L.t("+ Nuova", "+ New"), false) { open = true }
                DropdownMenu(open, { open = false }) {
                    DropdownMenuItem({ Text(L.t("Lineare", "Linear"), color = C.text) }, onClick = { open = false; onNew("linear") })
                    DropdownMenuItem({ Text(L.t("Radiale", "Radial"), color = C.text) }, onClick = { open = false; onNew("radial") })
                    DropdownMenuItem({ Text(L.t("Pennello", "Brush"), color = C.text) }, onClick = { open = false; onNew("brush") })
                    DropdownMenuItem({ Text(L.t("Luminanza", "Luminance range"), color = C.text) }, onClick = { open = false; onNew("luminanceRange") })
                    DropdownMenuItem({ Text(L.t("Colore", "Color range"), color = C.text) }, onClick = { open = false; onNew("colorRange") })
                    DropdownMenuItem({ Text(L.t("Cielo", "Sky"), color = C.text) }, onClick = { open = false; onNew("sky") })
                    DropdownMenuItem({ Text(L.t("Soggetto", "Subject"), color = C.text) }, onClick = { open = false; onNew("subject") })
                    DropdownMenuItem({ Text(L.t("Sfondo", "Background"), color = C.text) }, onClick = { open = false; onNew("background") })
                }
            }
        }
        // occhio: la maschera colorata sopra la foto (acceso = si vede sempre, spento = solo mentre trascini)
        if (selected != null) Box(Modifier.size(34.dp).clip(CircleShape).background(if (overlay) C.accent else C.card).clickable { onOverlay() },
            contentAlignment = Alignment.Center) {
            EyeIcon(Modifier.size(18.dp), if (overlay) Color.Black else C.text)
        }
        if (selected != null) Box {
            var open by remember { mutableStateOf(false) }
            val m = masks.firstOrNull { it.optInt("id") == selected }
            Box(Modifier.size(34.dp).clip(CircleShape).background(C.card).clickable { open = true }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Menu, null, tint = C.text, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(open, { open = false }) {
                @Composable fun item(label: String, key: String) = DropdownMenuItem({ Text(label, color = C.text) }, onClick = { open = false; onMenu(key) })
                item(L.t("Rinomina", "Rename"), "rename")
                item(L.t("Duplica", "Duplicate"), "duplicate")
                item(if (m?.optBoolean("invert") == true) L.t("Non invertire", "Don't invert") else L.t("Inverti", "Invert"), "invert")
                item(if (m?.optBoolean("visible", true) == false) L.t("Mostra", "Show") else L.t("Nascondi", "Hide"), "visible")
                item(L.t("Elimina", "Delete"), "delete")
            }
        }
    }
}

/** Un occhio (mostra la maschera), disegnato come le altre icone dell'app. */
@Composable
private fun EyeIcon(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val st = Stroke(w * 0.09f)
        val p = Path().apply {
            moveTo(w * 0.05f, h * 0.5f)
            quadraticTo(w * 0.5f, h * 0.05f, w * 0.95f, h * 0.5f)
            quadraticTo(w * 0.5f, h * 0.95f, w * 0.05f, h * 0.5f)
        }
        drawPath(p, color, style = st)
        drawCircle(color, w * 0.14f, Offset(w * 0.5f, h * 0.5f))
    }
}

/**
 * Le forme della maschera aperta (con + / − / ∩ davanti), accesa quella aperta, e «+ Forma»: prima come unirla
 * (Aggiungi, Sottrai, Interseca), poi quale forma. Per la forma aperta, quando ce n'è più d'una: Inverti, Togli.
 */
@Composable
internal fun MaskParts(m: JSONObject, open: Int, kinds: List<Pair<String, String>>, onOpen: (Int) -> Unit, onAdd: (String, String) -> Unit,
                       onInvert: () -> Unit, onRemove: () -> Unit) {
    val parts = Masks.components(m)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        parts.forEachIndexed { i, c ->
            val k = c.optJSONObject("shape")?.optString("kind").orEmpty()
            val label = Masks.opSymbol(c.optString("op")) + Masks.kindName(k) + (if (c.optBoolean("invert")) L.t(" (inv.)", " (inv.)") else "")
            Pill(label, parts.size > 1 && i == open) { onOpen(i) }
        }
        Box {
            var step by remember { mutableStateOf(0) } // 0 chiuso, 1 come unirla, 2 quale forma
            var op by remember { mutableStateOf("add") }
            Pill(L.t("+ Forma", "+ Shape"), false) { step = 1 }
            DropdownMenu(step == 1, { step = 0 }) {
                for ((key, name) in listOf("add" to L.t("Aggiungi…", "Add…"), "subtract" to L.t("Sottrai…", "Subtract…"), "intersect" to L.t("Interseca…", "Intersect…")))
                    DropdownMenuItem({ Text(name, color = C.text) }, onClick = { op = key; step = 2 })
            }
            DropdownMenu(step == 2, { step = 0 }) {
                for ((kind, name) in kinds) DropdownMenuItem({ Text(name, color = C.text) }, onClick = { step = 0; onAdd(op, kind) })
            }
        }
        if (parts.size > 1) {
            Pill(L.t("Inverti forma", "Invert shape"), parts.getOrNull(open)?.optBoolean("invert") == true) { onInvert() }
            Pill(L.t("Togli forma", "Remove shape"), false) { onRemove() }
        }
    }
}

/** Riga di testo piccolo e spento (spiegazioni nel pannello delle maschere). */
@Composable
internal fun MaskHint(text: String) = Text(text, color = C.muted, fontSize = 12.sp)

/** Le sotto-schede delle correzioni della maschera (Luce | Colore | Effetti | Dettaglio). */
@Composable
internal fun MaskTabs(selected: Int, changed: (Int) -> Boolean, onSelect: (Int) -> Unit, tab: @Composable (String, Boolean, Boolean, () -> Unit) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        MASK_GROUPS.forEachIndexed { i, g -> tab(L.t(g.first, g.second), i == selected, changed(i)) { onSelect(i) } }
    }
}

/** (usato dal pannello) colonna con spaziatura delle righe del pannello delle maschere. */
@Composable
internal fun MaskColumn(content: @Composable () -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
