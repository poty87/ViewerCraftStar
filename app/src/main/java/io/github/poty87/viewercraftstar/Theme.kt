package io.github.poty87.viewercraftstar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Gli stessi colori delle app desktop (ui_theme.py di CleanCraftNas e PresetCraftAppLicator, tema scuro sv_ttk). */
object C {
    val bg = Color(0xFF1C1C1C)
    val card = Color(0xFF2B2B2B)
    val cardHigh = Color(0xFF353535)
    val text = Color(0xFFE8EAED)
    val muted = Color(0xFF9AA0A6)
    val accent = Color(0xFF60A5FA)
    val border = Color(0xFF5F6368)
    // banner di stato: (sfondo, accento)
    val levels = mapOf(
        "info" to (Color(0xFF262626) to Color(0xFF60A5FA)),
        "ok" to (Color(0xFF16261D) to Color(0xFF4ADE80)),
        "warn" to (Color(0xFF2C2416) to Color(0xFFFBBF24)),
        "error" to (Color(0xFF2D1A1C) to Color(0xFFF87171)),
    )
    // colori tenui come le righe degli elenchi desktop: (testo, sfondo)
    val pick = Color(0xFFDFF2E5) to Color(0xFF24432F)
    val reject = Color(0xFFF5D6D6) to Color(0xFF4A2326)
    val star = Color(0xFFFBBF24)
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.accent, onPrimary = Color(0xFF0B1220), background = C.bg, onBackground = C.text,
            surface = C.bg, onSurface = C.text, surfaceVariant = C.card, onSurfaceVariant = C.muted,
            surfaceContainer = C.card, surfaceContainerHigh = C.cardHigh, surfaceContainerHighest = C.cardHigh,
            surfaceContainerLow = C.card, outline = C.border, secondaryContainer = C.accent,
            onSecondaryContainer = Color.Black,
        ),
        content = content,
    )
}

/** Titolo grande, come "Title.TLabel" delle app desktop. */
@Composable
fun TitleText(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier = modifier, color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)

/** Etichetta di sezione in maiuscolo grigio, come "Muted.TLabel" con .upper(). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), modifier = modifier, color = C.muted, fontSize = 11.sp, letterSpacing = 0.8.sp)

/** Banner di stato con la barra colorata a sinistra, come nelle app desktop. */
@Composable
fun Banner(level: String, text: String, modifier: Modifier = Modifier) {
    if (text.isEmpty()) return
    val (bg, accent) = C.levels[level] ?: C.levels.getValue("info")
    Row(modifier.fillMaxWidth().background(bg), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(44.dp).background(accent))
        Text(text, color = C.text, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
    }
}

@Suppress("unused")
private fun Modifier.fullHeight() = fillMaxHeight()

/** Una sezione delle impostazioni: riquadro con il titolo in evidenza, ben separato dagli altri. */
@Composable
fun SettingsCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth()
        .background(C.card, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(16.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
        Text(title, color = C.accent, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

/**
 * Lo stile unico dei pulsanti dell'app: pillola arrotondata piena, senza bordo. Grigia di base, blu per l'azione
 * principale, rossa per quelle distruttive; `container`/`content` per colori speciali (bandierine).
 */
@Composable
fun PillButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = false,
               danger: Boolean = false, container: Color? = null, content: Color? = null,
               pad: androidx.compose.ui.unit.Dp = 18.dp,
               body: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    // non attivo: grigio chiaro con il testo scuro, così si capisce che c'è ma non si può premere
    val bg = if (!enabled) Color(0xFF8A8A8A) else container ?: when { primary -> C.accent; danger -> Color(0xFFDC2626); else -> C.cardHigh }
    val fg = if (!enabled) Color(0xFF2A2A2A) else content ?: when { primary -> Color.Black; danger -> Color.White; else -> C.text }
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides fg,
        androidx.compose.material3.LocalTextStyle provides androidx.compose.ui.text.TextStyle(fontSize = 14.sp,
            fontWeight = if (primary) FontWeight.Bold else FontWeight.Medium)) {
        Row(modifier.height(44.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = pad),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically, content = body)
    }
}

/** Lo stile unico delle opzioni da scegliere: pillola arrotondata, blu se scelta, grigia altrimenti. */
@Composable
fun RoundChip(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
              leading: (@Composable () -> Unit)? = null, label: @Composable () -> Unit) {
    val fg = if (selected) Color.Black else C.text
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides fg,
        androidx.compose.material3.LocalTextStyle provides androidx.compose.ui.text.TextStyle(fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)) {
        Row(modifier.height(36.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
            .background(if (selected) C.accent else C.card).clickable(onClick = onClick).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { leading() }
                androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
            }
            label()
        }
    }
}

/** Altezza della barra in basso: lo spazio da lasciare in fondo alle liste, che le scorrono sotto. */
val LocalBarSpace = androidx.compose.runtime.compositionLocalOf { 0.dp }
/** Quanto della barra in basso si vede adesso (0 quando è nascosta): i pulsanti tondi le stanno sopra. */
// letta solo mentre si dispone (offset), non mentre si compone: l'animazione della barra non ricompone le pagine
val LocalBarShown = androidx.compose.runtime.staticCompositionLocalOf<() -> androidx.compose.ui.unit.Dp> { { 0.dp } }
/** Il fondo delle barre in basso: leggermente traslucido, si intravede quello che c'è sotto. */
val BarBg = Color(0xD9111111)
