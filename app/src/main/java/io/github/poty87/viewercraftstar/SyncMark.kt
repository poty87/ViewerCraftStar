package io.github.poty87.viewercraftstar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** Da inviare al NAS (arancione) e inviata, in attesa del PC di casa (azzurro). */
val SyncToSend = Color(0xFFFB923C)
val SyncSent = Color(0xFF60A5FA)

/** Il colore dello stato di una modifica fatta sul telefono. */
fun syncColor(e: LibEdit?): Color = if ((e?.sent ?: 0L) > 0L) SyncSent else SyncToSend

/**
 * Lo stato di sincronizzazione di una foto modificata sul telefono: due frecce in cerchio nel tondo del colore dello
 * stato. Diverso dai tondi delle etichette colorate, che sono pieni e senza simbolo.
 */
@Composable
fun SyncMark(e: LibEdit?, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    val color = syncColor(e)
    Canvas(modifier.size(size)) {
        drawCircle(color)
        drawCircle(Color(0x66000000), style = Stroke(this.size.width * 0.06f))
        syncArrows(Color.Black)
    }
}

/** Due archi con la punta, uno sopra e uno sotto, che girano nello stesso verso. */
private fun DrawScope.syncArrows(color: Color) {
    val w = size.width
    val c = Offset(w / 2, size.height / 2)
    val r = w * 0.27f
    val t = w * 0.11f
    for (start in listOf(200f, 20f)) {
        val sweep = 120f
        drawArc(color, start, sweep, useCenter = false, topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2),
            style = Stroke(t, cap = StrokeCap.Round))
        // punta alla fine dell'arco, nel verso di rotazione (orario)
        val a = Math.toRadians((start + sweep).toDouble())
        val tip = Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
        val dir = Offset(-sin(a).toFloat(), cos(a).toFloat()) // tangente in senso orario
        val out = Offset(cos(a).toFloat(), sin(a).toFloat()) // verso l'esterno
        val h = w * 0.17f
        val head = Path().apply {
            moveTo(tip.x + dir.x * h * 0.6f, tip.y + dir.y * h * 0.6f)
            lineTo(tip.x - dir.x * h * 0.5f + out.x * h * 0.75f, tip.y - dir.y * h * 0.5f + out.y * h * 0.75f)
            lineTo(tip.x - dir.x * h * 0.5f - out.x * h * 0.75f, tip.y - dir.y * h * 0.5f - out.y * h * 0.75f)
            close()
        }
        drawPath(head, color)
    }
}
