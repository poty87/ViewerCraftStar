package io.github.poty87.viewercraftstar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp

/** Il grigio tenue delle pagine vuote: si vede, ma non chiede attenzione. */
val EmptyTint = Color(0xFF3C3F43)

/** I disegni delle pagine vuote, tutti con lo stesso tratto. */
enum class EmptyGlyph { PHOTOS, DONE, SEARCH, SENT, BELL, CARD, LIBRARY }

/**
 * Pagina vuota: un disegno grande (circa metà della larghezza dello schermo, come uno scheletro della pagina) in grigio
 * tenue, un titolo breve e, sotto, una riga che dice cosa fare. Va al centro dello spazio dove starebbero le foto.
 */
@Composable
fun EmptyState(glyph: EmptyGlyph, title: String, hint: String? = null, modifier: Modifier = Modifier) {
    val screen = LocalConfiguration.current.screenWidthDp.dp
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        EmptyGlyphDrawing(glyph, Modifier.size(min(screen * 0.46f, 220.dp)))
        Spacer(Modifier.height(22.dp))
        Text(title, color = C.muted, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        if (hint != null) {
            Spacer(Modifier.height(6.dp))
            Text(hint, color = Color(0xFF6E7378), fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 19.sp)
        }
    }
}

/** Una pagina vuota che occupa lo spazio sotto l'intestazione di una griglia (circa il 60% dell'altezza). */
@Composable
fun EmptyStateFill(glyph: EmptyGlyph, title: String, hint: String? = null) {
    val h = LocalConfiguration.current.screenHeightDp.dp
    Box(Modifier.fillMaxWidth().height(h * 0.58f), contentAlignment = Alignment.Center) { EmptyState(glyph, title, hint) }
}

/** Il disegno, in [EmptyTint], dentro un quadrato. */
@Composable
fun EmptyGlyphDrawing(glyph: EmptyGlyph, modifier: Modifier, color: Color = EmptyTint) {
    if (glyph == EmptyGlyph.SENT) { UploadNasIcon(modifier, color); return }
    Canvas(modifier) {
        val w = size.width
        val t = w * 0.045f
        when (glyph) {
            EmptyGlyph.PHOTOS -> { backFrame(color, t); photoFrame(color, t) }
            EmptyGlyph.LIBRARY -> { backFrame(color, t); photoFrame(color, t); star(color, Offset(w * 0.78f, w * 0.22f), w * 0.11f) }
            EmptyGlyph.DONE -> { photoFrame(color, t); badge(color, t) { c, r -> check(color, c, r, t) } }
            EmptyGlyph.SEARCH -> { photoFrame(color, t); badge(color, t) { c, r -> lens(color, c, r, t) } }
            EmptyGlyph.BELL -> bell(color, t)
            EmptyGlyph.CARD -> sdCard(color, t)
            EmptyGlyph.SENT -> Unit
        }
    }
}

// --- i pezzi dei disegni (coordinate in proporzione al lato) ---

private fun DrawScope.photoFrame(color: Color, t: Float) {
    val w = size.width
    val left = w * 0.10f; val top = w * 0.24f; val fw = w * 0.70f; val fh = w * 0.56f
    drawRoundRect(color, Offset(left, top), Size(fw, fh), CornerRadius(w * 0.07f), style = Stroke(t))
    // sole e montagne, come in una foto di paesaggio
    drawCircle(color, w * 0.055f, Offset(left + fw * 0.72f, top + fh * 0.28f))
    val m = Path().apply {
        moveTo(left + fw * 0.10f, top + fh * 0.86f)
        lineTo(left + fw * 0.38f, top + fh * 0.44f)
        lineTo(left + fw * 0.58f, top + fh * 0.72f)
        lineTo(left + fw * 0.70f, top + fh * 0.58f)
        lineTo(left + fw * 0.90f, top + fh * 0.86f)
        close()
    }
    drawPath(m, color)
}

private fun DrawScope.backFrame(color: Color, t: Float) {
    val w = size.width
    // una seconda foto dietro, spostata in alto a destra: «tante foto»
    drawRoundRect(color, Offset(w * 0.22f, w * 0.12f), Size(w * 0.70f, w * 0.56f), CornerRadius(w * 0.07f),
        style = Stroke(t), alpha = 0.55f)
}

/** Il tondo pieno in basso a destra, con il segno ritagliato dentro (in nero). */
private fun DrawScope.badge(color: Color, t: Float, mark: DrawScope.(Offset, Float) -> Unit) {
    val w = size.width
    val c = Offset(w * 0.76f, w * 0.76f); val r = w * 0.19f
    drawCircle(Color.Black, r + t * 1.4f, c)
    drawCircle(color, r, c)
    mark(c, r)
}

private fun DrawScope.check(color: Color, c: Offset, r: Float, t: Float) {
    val p = Path().apply {
        moveTo(c.x - r * 0.48f, c.y + r * 0.02f)
        lineTo(c.x - r * 0.12f, c.y + r * 0.38f)
        lineTo(c.x + r * 0.50f, c.y - r * 0.32f)
    }
    drawPath(p, Color.Black, style = Stroke(t * 1.3f, cap = StrokeCap.Round))
}

private fun DrawScope.lens(color: Color, c: Offset, r: Float, t: Float) {
    val lc = Offset(c.x - r * 0.12f, c.y - r * 0.12f)
    drawCircle(Color.Black, r * 0.36f, lc, style = Stroke(t * 1.1f))
    drawLine(Color.Black, Offset(lc.x + r * 0.27f, lc.y + r * 0.27f), Offset(c.x + r * 0.48f, c.y + r * 0.48f),
        strokeWidth = t * 1.3f, cap = StrokeCap.Round)
}

private fun DrawScope.star(color: Color, c: Offset, r: Float) {
    val p = Path()
    for (i in 0 until 10) {
        val a = Math.PI / 2 + i * Math.PI / 5
        val rr = if (i % 2 == 0) r else r * 0.45f
        val x = c.x + (rr * Math.cos(a)).toFloat(); val y = c.y - (rr * Math.sin(a)).toFloat()
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    drawCircle(Color.Black, r * 1.15f, c)
    drawPath(p, color)
}

private fun DrawScope.bell(color: Color, t: Float) {
    val w = size.width
    val p = Path().apply {
        moveTo(w * 0.22f, w * 0.70f)
        lineTo(w * 0.78f, w * 0.70f)
        lineTo(w * 0.70f, w * 0.60f)
        lineTo(w * 0.70f, w * 0.42f)
        cubicTo(w * 0.70f, w * 0.26f, w * 0.60f, w * 0.18f, w * 0.50f, w * 0.18f)
        cubicTo(w * 0.40f, w * 0.18f, w * 0.30f, w * 0.26f, w * 0.30f, w * 0.42f)
        lineTo(w * 0.30f, w * 0.60f)
        close()
    }
    drawPath(p, color, style = Stroke(t, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    drawCircle(color, w * 0.04f, Offset(w * 0.50f, w * 0.13f))
    drawArc(color, 0f, 180f, false, Offset(w * 0.42f, w * 0.70f), Size(w * 0.16f, w * 0.14f), style = Stroke(t))
}

private fun DrawScope.sdCard(color: Color, t: Float) {
    val w = size.width
    val p = Path().apply {
        moveTo(w * 0.36f, w * 0.14f)
        lineTo(w * 0.74f, w * 0.14f)
        quadraticTo(w * 0.80f, w * 0.14f, w * 0.80f, w * 0.20f)
        lineTo(w * 0.80f, w * 0.80f)
        quadraticTo(w * 0.80f, w * 0.86f, w * 0.74f, w * 0.86f)
        lineTo(w * 0.26f, w * 0.86f)
        quadraticTo(w * 0.20f, w * 0.86f, w * 0.20f, w * 0.80f)
        lineTo(w * 0.20f, w * 0.30f)
        close()
    }
    drawPath(p, color, style = Stroke(t, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    for (i in 0 until 4) {
        val x = w * (0.38f + i * 0.10f)
        drawLine(color, Offset(x, w * 0.22f), Offset(x, w * 0.34f), strokeWidth = t, cap = StrokeCap.Round)
    }
}
