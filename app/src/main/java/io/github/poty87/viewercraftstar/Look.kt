package io.github.poty87.viewercraftstar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Anteprima APPROSSIMATA di un preset sul telefono, applicata all'anteprima della fotocamera (l'unica immagine che il
 * telefono sa leggere da un DNG della GR IV):
 *
 * 1. se il preset cambia profilo: tabella "stile di scatto -> stile del preset" calcolata sul PC dalle foto di
 *    riferimento (tools/make_transfers.py);
 * 2. le regolazioni del preset (luce, curve, colore, miscelatore, color grading…) con formule semplici, non quelle di
 *    LightCraft.
 *
 * Tutto il colore finisce in una sola tabella 3D che la scheda grafica applica (shader AGSL), più vignettatura e grana.
 * Il risultato vero lo dà LightCraft: qui serve solo a farsi un'idea.
 */
object Look {
    const val N = 25 // lato della tabella che arriva allo shader
    private val MONO = setOf(258, 259, 260, 261) // Monotone, Soft Monotone, Hard Monotone, Hi-Contrast B&W

    class Spec(val lut: Bitmap, val vignette: Float, val grain: Float, val grainSize: Float, val note: String?)

    private val cache = LruCache<String, Spec>(24)

    /** La resa di un preset per una foto dello stile `style` (null = nessun cambiamento). */
    fun spec(context: Context, style: Int?, presetId: String): Spec? {
        if (presetId.isEmpty()) return null
        val key = "$style|$presetId"
        cache.get(key)?.let { return it }
        val settings = Catalog.settings(context, presetId) ?: return null
        val target = settings.optJSONObject("profile")?.optString("id")?.let { Transfers.styleOf(context, it) }
        val transfer = if (target != null && style != null && target != style) Transfers.table(context, style, target) else null
        var note: String? = null
        if (target != null && style != null && target != style && transfer == null)
            note = L.t("Anteprima del profilo non disponibile per questo stile", "Profile preview not available for this style")
        if (target != null && style == null) note = L.t("Stile di scatto non riconosciuto", "Shooting style not recognised")
        if (style in MONO && target != null && target !in MONO)
            note = L.t("Scattata in bianco e nero: l'anteprima non può ricostruire i colori (LightCraft sì)",
                "Shot in black and white: the preview can't rebuild the colours (LightCraft can)")
        val ops = Ops(settings)
        val pixels = IntArray(N * N * N)
        val c = FloatArray(3)
        for (r in 0 until N) for (g in 0 until N) for (b in 0 until N) {
            c[0] = r / (N - 1f); c[1] = g / (N - 1f); c[2] = b / (N - 1f)
            if (transfer != null) Transfers.apply(transfer, c)
            ops.apply(c)
            // disposizione per lo shader: x = r + b*N, y = g
            pixels[g * N * N + b * N + r] = (0xFF shl 24) or (q(c[0]) shl 16) or (q(c[1]) shl 8) or q(c[2])
        }
        val lut = Bitmap.createBitmap(pixels, N * N, N, Bitmap.Config.ARGB_8888)
        val v = settings.optJSONObject("vignette")
        val gr = settings.optJSONObject("grain")
        return Spec(lut, (v?.optDouble("amount", 0.0) ?: 0.0).toFloat() / 100f,
            (gr?.optDouble("amount", 0.0) ?: 0.0).toFloat() / 100f,
            1f + (gr?.optDouble("size", 25.0) ?: 25.0).toFloat() / 25f, note).also { cache.put(key, it) }
    }

    private fun q(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    // ---------------------------------------------------------------- shader

    private const val AGSL = """
        uniform shader image;
        uniform shader lut;
        uniform float n;
        uniform float2 size;
        uniform float vignette;
        uniform float grain;
        uniform float grainSize;
        half4 main(float2 p) {
            half4 src = image.eval(p);
            if (src.a <= 0.0) return src;
            float3 c = clamp(float3(src.rgb) / float(src.a), 0.0, 1.0) * (n - 1.0);
            float b0 = floor(c.b);
            float b1 = min(b0 + 1.0, n - 1.0);
            float fb = c.b - b0;
            float2 rg = float2(c.r + 0.5, c.g + 0.5);
            float3 o = mix(float3(lut.eval(float2(rg.x + b0 * n, rg.y)).rgb),
                           float3(lut.eval(float2(rg.x + b1 * n, rg.y)).rgb), fb);
            float2 q = p / size - 0.5;
            float d = length(q * float2(size.x / max(size.x, size.y), size.y / max(size.x, size.y))) * 1.41421;
            o *= 1.0 + vignette * smoothstep(0.35, 1.0, d);
            float2 cell = floor(p / grainSize);
            float h = fract(sin(dot(cell, float2(12.9898, 78.233))) * 43758.5453) - 0.5;
            o += h * grain * 0.18;
            return half4(half3(clamp(o, 0.0, 1.0) * float(src.a)), src.a);
        }
    """

    /** L'effetto per un'immagine larga w e alta h (pixel dello strato su cui si applica). */
    fun effect(spec: Spec, w: Float, h: Float): RenderEffect {
        val shader = RuntimeShader(AGSL)
        val lut = BitmapShader(spec.lut, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            filterMode = BitmapShader.FILTER_MODE_LINEAR
        }
        shader.setInputShader("lut", lut)
        shader.setFloatUniform("n", N.toFloat())
        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("vignette", spec.vignette)
        shader.setFloatUniform("grain", spec.grain)
        shader.setFloatUniform("grainSize", spec.grainSize)
        return RenderEffect.createRuntimeShaderEffect(shader, "image")
    }

    // ---------------------------------------------------------------- regolazioni del preset (approssimate)

    private class Ops(s: JSONObject) {
        private val wb = s.optJSONObject("wb")
        private val light = s.optJSONObject("light")
        private val curve = s.optJSONObject("curve")
        private val color = s.optJSONObject("color")
        private val mixer = s.optJSONObject("mixer")
        private val calibration = s.optJSONObject("calibration")
        private val grading = s.optJSONObject("grading")
        private val effects = s.optJSONObject("effects")
        private val mono = s.optString("treatment") in setOf("bw", "monochrome", "blackWhite", "black_white")

        private val mul: FloatArray = run {
            // bilanciamento: il preset dà una temperatura assoluta, la foto è già bilanciata sul valore di scatto
            // (sulle GR IV LightCraft legge di solito 4800–4900 K): si applica la differenza in mired
            if (wb == null || wb.optString("mode") != "custom") floatArrayOf(1f, 1f, 1f) else {
                val t = wb.optDouble("temp", 5500.0).coerceIn(2000.0, 50000.0)
                val shift = ((1e6 / 4850.0 - 1e6 / t) / 100.0).coerceIn(-1.0, 1.0).toFloat()
                val tint = (wb.optDouble("tint", 0.0) / 100.0).toFloat()
                val m = floatArrayOf(1f + 0.30f * shift, 1f - 0.22f * tint, 1f - 0.30f * shift)
                val y = 0.2126f * m[0] + 0.7152f * m[1] + 0.0722f * m[2]
                floatArrayOf(m[0] / y, m[1] / y, m[2] / y)
            }
        }
        private val exposure = 2f.pow(light?.optDouble("exposure", 0.0)?.toFloat() ?: 0f)
        private fun l(k: String) = (light?.optDouble(k, 0.0) ?: 0.0).toFloat() / 100f
        private fun cv(k: String) = (curve?.optDouble(k, 0.0) ?: 0.0).toFloat() / 100f
        private val master = points(curve?.optJSONArray("master"))
        private val red = points(curve?.optJSONArray("red"))
        private val green = points(curve?.optJSONArray("green"))
        private val blue = points(curve?.optJSONArray("blue"))
        private val saturation = (color?.optDouble("saturation", 0.0) ?: 0.0).toFloat() / 100f
        private val vibrance = (color?.optDouble("vibrance", 0.0) ?: 0.0).toFloat() / 100f
        private val dehaze = (effects?.optDouble("dehaze", 0.0) ?: 0.0).toFloat() / 100f
        private val clarity = (effects?.optDouble("clarity", 0.0) ?: 0.0).toFloat() / 100f

        // miscelatore: centro della tinta (gradi) di ogni banda
        private val bands = listOf("red" to 0f, "orange" to 30f, "yellow" to 60f, "green" to 120f,
            "aqua" to 180f, "blue" to 240f, "purple" to 270f, "magenta" to 310f)

        fun apply(c: FloatArray) {
            // luce: in lineare
            for (i in 0..2) c[i] = lin(c[i]) * mul[i] * exposure
            for (i in 0..2) c[i] = enc(c[i].coerceIn(0f, 4f)).coerceIn(0f, 1f)
            // toni sulla luminanza, conservando la tinta
            val y0 = luma(c)
            var y = y0
            y = 0.5f + (y - 0.5f) * (1f + 0.55f * l("contrast") + 0.25f * clarity + 0.35f * dehaze)
            y += l("highlights") * 0.12f * smooth(0.45f, 0.95f, y0)
            y += l("shadows") * 0.12f * (1f - smooth(0.05f, 0.55f, y0))
            y += l("whites") * 0.12f * smooth(0.65f, 1f, y0)
            y += l("blacks") * 0.12f * (1f - smooth(0f, 0.3f, y0))
            y += cv("highlights") * 0.12f * smooth(0.6f, 1f, y0) + cv("lights") * 0.12f * bell(y0, 0.62f) +
                cv("darks") * 0.12f * bell(y0, 0.38f) + cv("shadows") * 0.12f * (1f - smooth(0f, 0.4f, y0))
            y -= 0.04f * dehaze
            y = y.coerceIn(0f, 1f)
            if (y0 > 1e-4f) { val k = y / y0; for (i in 0..2) c[i] = (c[i] * k).coerceIn(0f, 1f) }
            else for (i in 0..2) c[i] = y
            // curve a punti
            if (master != null) for (i in 0..2) c[i] = curveAt(master, c[i])
            if (red != null) c[0] = curveAt(red, c[0])
            if (green != null) c[1] = curveAt(green, c[1])
            if (blue != null) c[2] = curveAt(blue, c[2])
            // colore: HSL
            val hsl = toHsl(c)
            var hue = hsl[0]; var sat = hsl[1]; var lum = hsl[2]
            if (mixer != null || calibration != null) {
                var dh = 0f; var ds = 0f; var dl = 0f
                for ((name, center) in bands) {
                    val w = max(0f, 1f - hueDist(hue, center) / 45f)
                    if (w <= 0f) continue
                    val b = mixer?.optJSONObject(name)
                    dh += w * (b?.optDouble("hue", 0.0) ?: 0.0).toFloat() * 0.30f
                    ds += w * (b?.optDouble("sat", 0.0) ?: 0.0).toFloat() / 100f
                    dl += w * (b?.optDouble("lum", 0.0) ?: 0.0).toFloat() / 100f
                }
                if (calibration != null) for ((name, center) in listOf("red" to 0f, "green" to 120f, "blue" to 240f)) {
                    val w = max(0f, 1f - hueDist(hue, center) / 60f)
                    dh += w * calibration.optDouble("${name}_hue", 0.0).toFloat() * 0.15f
                    ds += w * calibration.optDouble("${name}_sat", 0.0).toFloat() / 200f
                }
                hue = (hue + dh + 360f) % 360f
                sat = (sat * (1f + ds)).coerceIn(0f, 1f)
                lum = (lum + dl * 0.18f * sat).coerceIn(0f, 1f)
            }
            sat = (sat * (1f + saturation + 0.25f * dehaze)).coerceIn(0f, 1f)
            sat = (sat * (1f + vibrance * (1f - sat))).coerceIn(0f, 1f)
            fromHsl(hue, sat, lum, c)
            // color grading: tinte su ombre, mezzitoni, luci
            if (grading != null) {
                val yy = luma(c)
                tint(c, grading.optJSONObject("shadows"), (1f - yy) * (1f - yy))
                tint(c, grading.optJSONObject("midtones"), 4f * yy * (1f - yy))
                tint(c, grading.optJSONObject("highlights"), yy * yy)
                tint(c, grading.optJSONObject("global"), 1f)
            }
            if (mono) { val g = luma(c); c[0] = g; c[1] = g; c[2] = g }
        }

        private fun tint(c: FloatArray, o: JSONObject?, w: Float) {
            if (o == null) return
            val s = o.optDouble("sat", 0.0).toFloat() / 100f
            val lumShift = o.optDouble("lum", 0.0).toFloat() / 100f
            if (s == 0f && lumShift == 0f) return
            val h = Math.toRadians(o.optDouble("hue", 0.0)).toFloat()
            // direzione della tinta (rosso a 0°, verde a 120°, blu a 240°)
            val tr = cos(h); val tg = cos(h - 2.0944f); val tb = cos(h - 4.1888f)
            val k = 0.10f * s * w
            c[0] = (c[0] + k * tr + 0.08f * lumShift * w).coerceIn(0f, 1f)
            c[1] = (c[1] + k * tg + 0.08f * lumShift * w).coerceIn(0f, 1f)
            c[2] = (c[2] + k * tb + 0.08f * lumShift * w).coerceIn(0f, 1f)
        }
    }

    private fun points(a: JSONArray?): FloatArray? {
        if (a == null || a.length() < 2) return null
        val pts = (0 until a.length()).map { a.getJSONObject(it) }.map { it.optDouble("x").toFloat() to it.optDouble("y").toFloat() }
            .sortedBy { it.first }
        // curva campionata su 256 valori, interpolazione lineare tra i punti
        return FloatArray(256) { i ->
            val x = i / 255f
            val k = pts.indexOfFirst { it.first >= x }
            when {
                k <= 0 -> pts.first().second
                else -> { val (x0, y0) = pts[k - 1]; val (x1, y1) = pts[k]; y0 + (y1 - y0) * ((x - x0) / max(1e-6f, x1 - x0)) }
            }.coerceIn(0f, 1f)
        }
    }

    private fun curveAt(t: FloatArray, v: Float): Float {
        val p = v.coerceIn(0f, 1f) * 255f
        val i = min(254, floor(p).toInt())
        return t[i] + (t[i + 1] - t[i]) * (p - i)
    }

    private fun lin(v: Float) = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    private fun enc(v: Float) = if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f
    private fun luma(c: FloatArray) = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]
    private fun smooth(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
    private fun bell(x: Float, c: Float) = exp(-((x - c) * (x - c)) / 0.02f)
    private fun hueDist(a: Float, b: Float): Float { val d = abs(a - b) % 360f; return min(d, 360f - d) }

    private fun toHsl(c: FloatArray): FloatArray {
        val mx = max(c[0], max(c[1], c[2])); val mn = min(c[0], min(c[1], c[2]))
        val l = (mx + mn) / 2f
        if (mx - mn < 1e-6f) return floatArrayOf(0f, 0f, l)
        val d = mx - mn
        val s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
        val h = when (mx) {
            c[0] -> ((c[1] - c[2]) / d + (if (c[1] < c[2]) 6f else 0f))
            c[1] -> ((c[2] - c[0]) / d + 2f)
            else -> ((c[0] - c[1]) / d + 4f)
        } * 60f
        return floatArrayOf(h, s, l)
    }

    private fun fromHsl(h: Float, s: Float, l: Float, out: FloatArray) {
        if (s <= 0f) { out[0] = l; out[1] = l; out[2] = l; return }
        val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun f(t0: Float): Float {
            var t = t0
            if (t < 0) t += 1f; if (t > 1) t -= 1f
            return when { t < 1f / 6 -> p + (q - p) * 6 * t; t < 0.5f -> q; t < 2f / 3 -> p + (q - p) * (2f / 3 - t) * 6; else -> p }
        }
        val hh = h / 360f
        out[0] = f(hh + 1f / 3); out[1] = f(hh); out[2] = f(hh - 1f / 3)
    }

    @Suppress("unused") private fun unusedSin() = sin(0.0)
}

/** Le tabelle "stile A -> stile B" calcolate sul PC (assets/transfers.bin, griglia 17, ordine [r][g][b]). */
object Transfers {
    private var tables: Map<Long, ByteArray>? = null
    private var grid = 17
    private var slugs: Map<String, Int> = emptyMap()

    private fun load(context: Context) {
        if (tables != null) return
        val found = HashMap<Long, ByteArray>()
        runCatching {
            val bytes = context.assets.open("transfers.bin").use { it.readBytes() }
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (String(bytes, 0, 4, Charsets.US_ASCII) == "VCST") {
                bb.position(4)
                bb.short // versione
                grid = bb.short.toInt()
                val count = bb.short.toInt() and 0xFFFF
                val size = grid * grid * grid * 3
                repeat(count) {
                    val src = bb.short.toInt() and 0xFFFF
                    val dst = bb.short.toInt() and 0xFFFF
                    val data = ByteArray(size).also { bb.get(it) }
                    found[(src.toLong() shl 16) or dst.toLong()] = data
                }
            }
            val info = JSONObject(context.assets.open("transfers.json").use { String(it.readBytes(), Charsets.UTF_8) })
            val st = info.getJSONObject("styles")
            slugs = st.keys().asSequence().associate { st.getJSONObject(it).getString("slug") to it.toInt() }
        }
        tables = found
    }

    /** Lo stile (codice ImageTone) che un profilo di LightCraft riproduce: dal suo id ("lut:…-measured-cinema-yellow"). */
    fun styleOf(context: Context, profileId: String): Int? {
        load(context)
        val id = profileId.lowercase()
        return slugs.entries.filter { id.endsWith("-" + it.key) }.maxByOrNull { it.key.length }?.value
    }

    fun table(context: Context, src: Int, dst: Int): ByteArray? {
        load(context)
        return tables?.get((src.toLong() shl 16) or dst.toLong())
    }

    /** Interpolazione trilineare della tabella sul colore c (0..1), sul posto. */
    fun apply(t: ByteArray, c: FloatArray) {
        val n = grid
        val r = c[0].coerceIn(0f, 1f) * (n - 1); val g = c[1].coerceIn(0f, 1f) * (n - 1); val b = c[2].coerceIn(0f, 1f) * (n - 1)
        val r0 = min(n - 2, r.toInt()); val g0 = min(n - 2, g.toInt()); val b0 = min(n - 2, b.toInt())
        val fr = r - r0; val fg = g - g0; val fb = b - b0
        for (ch in 0..2) {
            var v = 0f
            for (dr in 0..1) for (dg in 0..1) for (db in 0..1) {
                val w = (if (dr == 1) fr else 1 - fr) * (if (dg == 1) fg else 1 - fg) * (if (db == 1) fb else 1 - fb)
                val idx = (((r0 + dr) * n + (g0 + dg)) * n + (b0 + db)) * 3 + ch
                v += w * (t[idx].toInt() and 0xFF)
            }
            c[ch] = v / 255f
        }
    }
}
