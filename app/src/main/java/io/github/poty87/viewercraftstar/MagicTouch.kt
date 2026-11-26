package io.github.poty87.viewercraftstar

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.zip.Deflater
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Maschera «Oggetto (IA)»: MagicTouch (Google MediaPipe, `assets/magic_touch.tflite`, Apache-2.0) scontorna quello che
 * si tocca. Gira con LiteRT sulla GPU del telefono (circa 20 ms; sulla CPU circa 35 ms se la GPU non parte).
 *
 * Ingresso `[1,512,512,4]`: la foto sviluppata **senza ritaglio e senza maschere**, stirata a 512×512, colori 0..1,
 * e nel 4° canale un quadratino 3×3 a 1 per ogni tocco (così come lo prepara MediaPipe: verificato sul PC su 15 foto,
 * IoU 0,97 con MediaPipe). Uscita `[1,512,512,1]`: la probabilità che ogni punto sia l'oggetto.
 *
 * Il risultato diventa una forma `object` di LightCraft: `hint` = i tocchi (coordinate della foto senza ritaglio:
 * sul PC LightCraft può ricalcolarla con il suo SAM 3), `seg` = lo scontorno come lo salva LightCraft (griglia
 * quadrata di logit ×8 in i8, deflate grezzo, base64), che LightCraft e il motore del telefono disegnano senza modello.
 */
object MagicTouch {
    private const val IN = 512
    private const val SIDE = 256 // lato della griglia salvata (LightCraft accetta fino a 1024; SAM 3 ne usa 288)
    private const val TAG = "MagicTouch"

    /** Un thread solo: la GPU di LiteRT va usata dal thread che l'ha preparata. */
    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "magic-touch") }.asCoroutineDispatcher()
    private var itp: Interpreter? = null
    private var gpu: GpuDelegate? = null

    /** Se lo scontorno gira sulla GPU (dopo il primo uso). */
    @Volatile var onGpu = false; private set

    private fun interpreter(context: Context): Interpreter {
        itp?.let { return it }
        val bytes = context.assets.open("magic_touch.tflite").use { it.readBytes() }
        val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).apply { rewind() }
        val made = runCatching {
            val d = GpuDelegate()
            Interpreter(model, Interpreter.Options().addDelegate(d)).also { gpu = d; onGpu = true }
        }.getOrElse { e ->
            Log.w(TAG, "GPU non disponibile, uso la CPU: $e")
            gpu?.close(); gpu = null; onGpu = false
            Interpreter(model.apply { rewind() }, Interpreter.Options().setNumThreads(4))
        }
        itp = made
        return made
    }

    /**
     * La probabilità (512×512, righe dall'alto) che ogni punto sia l'oggetto toccato. `photo`: la foto sviluppata senza
     * ritaglio e senza maschere (qualsiasi misura); `taps`: i tocchi in frazioni di quella foto (0..1).
     */
    suspend fun probability(context: Context, photo: Bitmap, taps: List<Pair<Double, Double>>): FloatArray = withContext(thread) {
        val itp = interpreter(context)
        val img = Bitmap.createScaledBitmap(photo, IN, IN, true)
        val px = IntArray(IN * IN).also { img.getPixels(it, 0, IN, 0, 0, IN, IN) }
        if (img !== photo) img.recycle()
        val tap = FloatArray(IN * IN)
        for ((x, y) in taps) {
            val cx = (x * IN).toInt().coerceIn(0, IN - 1)
            val cy = (y * IN).toInt().coerceIn(0, IN - 1)
            for (yy in cy - 1..cy + 1) for (xx in cx - 1..cx + 1)
                if (xx in 0 until IN && yy in 0 until IN) tap[yy * IN + xx] = 1f
        }
        val inp = ByteBuffer.allocateDirect(IN * IN * 4 * 4).order(ByteOrder.nativeOrder())
        val fb = inp.asFloatBuffer()
        for (i in 0 until IN * IN) {
            val p = px[i]
            fb.put(((p shr 16) and 0xFF) / 255f).put(((p shr 8) and 0xFF) / 255f).put((p and 0xFF) / 255f).put(tap[i])
        }
        val out = ByteBuffer.allocateDirect(IN * IN * 4).order(ByteOrder.nativeOrder())
        itp.run(inp, out)
        out.rewind()
        FloatArray(IN * IN).also { out.asFloatBuffer().get(it) }
    }

    /** Lo scontorno come lo salva LightCraft (`SegMask`): griglia SIDE×SIDE di logit ×8 in i8, deflate grezzo, base64. */
    fun segMask(prob: FloatArray): JSONObject {
        val k = IN / SIDE
        val q = ByteArray(SIDE * SIDE)
        for (y in 0 until SIDE) for (x in 0 until SIDE) {
            var s = 0f
            for (dy in 0 until k) for (dx in 0 until k) s += prob[(y * k + dy) * IN + x * k + dx]
            val p = (s / (k * k)).coerceIn(1e-4f, 1 - 1e-4f)
            q[y * SIDE + x] = (ln(p / (1 - p)) * 8).roundToInt().coerceIn(-127, 127).toByte()
        }
        val d = Deflater(8, true) // deflate grezzo, come miniz_oxide in LightCraft
        d.setInput(q)
        d.finish()
        val buf = ByteArray(SIDE * SIDE + 1024)
        val n = d.deflate(buf)
        d.end()
        return JSONObject().put("side", SIDE).put("data", Base64.encodeToString(buf, 0, n, Base64.NO_WRAP))
    }

    /** I tocchi salvati in una forma `object` (frazioni della foto senza ritaglio). */
    fun taps(shape: JSONObject): List<Pair<Double, Double>> {
        val a = shape.optJSONArray("hint") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { it.optDouble("x") to it.optDouble("y") }
    }

    /** Una forma `object` vuota: si riempie toccando la foto. */
    fun emptyShape(): JSONObject = JSONObject().put("kind", "object").put("hint", JSONArray())
}
