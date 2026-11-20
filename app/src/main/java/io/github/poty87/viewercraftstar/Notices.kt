package io.github.poty87.viewercraftstar

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Le notifiche dell'app: compaiono in sovrimpressione come i messaggi di una chat (verde, giallo, rosso o neutro
 * tenui), restano cambiando pagina, si chiudono da sole dopo 4 secondi, con la X o con uno swipe. Restano
 * consultabili nella pagina Notifiche finché non le svuoti.
 */
object Notices {
    data class Notice(val id: Long, val level: String, val text: String, val time: Long)

    private const val KEEP = 100
    private const val SHOW_MS = 4000L

    /** In sovrimpressione adesso. */
    val live = mutableStateListOf<Notice>()
    /** Tutte, la più recente prima. */
    val history = mutableStateListOf<Notice>()
    var unread by mutableIntStateOf(0); private set
    private var prefs: android.content.SharedPreferences? = null
    private var next = System.currentTimeMillis()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences("notices", Context.MODE_PRIVATE)
        prefs = p
        runCatching {
            val a = JSONArray(p.getString("history", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                history += Notice(o.getLong("id"), o.getString("level"), o.getString("text"), o.getLong("time"))
            }
        }
        unread = p.getInt("unread", 0)
    }

    fun post(level: String, text: String) {
        if (text.isBlank()) return
        // lo stesso messaggio ripetuto non si accumula: prende il posto del precedente
        live.removeAll { it.text == text }
        history.firstOrNull()?.let { if (it.text == text) history.removeAt(0) else unread++ } ?: unread++
        val n = Notice(next++, level, text, System.currentTimeMillis())
        live += n
        while (live.size > 3) live.removeAt(0)
        history.add(0, n)
        while (history.size > KEEP) history.removeAt(history.size - 1)
        save()
    }

    fun dismiss(n: Notice) { live.remove(n) }

    fun seen() { if (unread != 0) { unread = 0; save() } }

    fun clear() { history.clear(); live.clear(); unread = 0; save() }

    private fun save() {
        val a = JSONArray()
        history.forEach { a.put(JSONObject().put("id", it.id).put("level", it.level).put("text", it.text).put("time", it.time)) }
        prefs?.edit()?.putString("history", a.toString())?.putInt("unread", unread)?.apply()
    }

    internal const val SHOW = SHOW_MS
}

/** Colori tenui: (sfondo, accento). */
private fun noticeColors(level: String): Pair<Color, Color> = when (level) {
    "ok" -> Color(0xF0193524) to Color(0xFF4ADE80)
    "warn" -> Color(0xF03A2F14) to Color(0xFFFBBF24)
    "error" -> Color(0xF0402024) to Color(0xFFF87171)
    else -> Color(0xF0263040) to Color(0xFF60A5FA)
}

/** Le notifiche in sovrimpressione, in basso sopra la barra, una sotto l'altra come in una chat. */
@Composable
fun NoticeOverlay(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.End) {
        for (n in Notices.live) key(n.id) { NoticeBubble(n) }
    }
}

@Composable
private fun NoticeBubble(n: Notices.Notice) {
    val (bg, accent) = noticeColors(n.level)
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    var dx by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(n.id) { delay(Notices.SHOW); Notices.dismiss(n) }
    AnimatedVisibility(shown, enter = fadeIn(tween(200)) + slideInVertically(tween(220)) { it / 2 }) {
        Row(Modifier.widthIn(max = 520.dp).fillMaxWidth()
            .graphicsLayer { translationX = dx; alpha = (1f - abs(dx) / 600f).coerceIn(0f, 1f) }
            .pointerInput(n.id) {
                detectHorizontalDragGestures(
                    onDragEnd = { if (abs(dx) > 180f) Notices.dismiss(n) else dx = 0f },
                    onDragCancel = { dx = 0f }) { _, d -> dx += d }
            }
            .clip(RoundedCornerShape(14.dp)).background(bg),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(40.dp).background(accent))
            Text(n.text, color = C.text, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(start = 12.dp, top = 10.dp, bottom = 10.dp))
            IconButton(onClick = { Notices.dismiss(n) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Close, L.t("Chiudi", "Close"), tint = C.muted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** La pagina Notifiche: tutte quelle arrivate, la più recente in alto; si possono svuotare. */
@Composable
fun NoticesScreen() {
    LaunchedEffect(Notices.history.size) { Notices.seen() }
    val fmt = remember { java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.ITALY) }
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, top = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TitleText(L.t("Notifiche", "Notifications"), Modifier.weight(1f))
            if (Notices.history.isNotEmpty()) IconButton(onClick = {
                Confirm.show(L.t("Svuotare le notifiche?", "Clear the notifications?"),
                    if (Notices.history.size == 1) L.t("Tolgo la notifica da questa pagina. Le foto e le librerie non cambiano.",
                        "I remove the notification from this page. Photos and libraries don't change.")
                    else L.t("Tolgo da questa pagina tutte le ${Notices.history.size} notifiche. Le foto e le librerie non cambiano.",
                        "I remove all ${Notices.history.size} notifications from this page. Photos and libraries don't change."),
                    L.t("Svuota", "Clear")) { Notices.clear() }
            }) {
                Icon(Icons.Filled.Delete, L.t("Svuota", "Clear all"), tint = C.text)
            }
        }
        if (Notices.history.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Notifications, null, tint = C.muted, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(8.dp))
                Text(L.t("Nessuna notifica", "No notifications"), color = C.muted, fontSize = 14.sp)
            }
        }
        else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 10.dp, bottom = 10.dp + LocalBarSpace.current),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Notices.history, key = { it.id }) { n ->
                val (bg, accent) = noticeColors(n.level)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg)) {
                    Box(Modifier.width(4.dp).height(56.dp).background(accent))
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(fmt.format(java.util.Date(n.time)), color = C.muted, fontSize = 11.sp)
                        Text(n.text, color = C.text, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/** Quante finestre sfocate sono aperte: finché ce n'è una, l'app dietro è sfocata. */
object Blur {
    var depth by mutableIntStateOf(0)
}

/**
 * La finestra comune dell'app: un riquadro arrotondato al centro, sopra l'interfaccia sfocata e scurita. Un tocco fuori
 * o il tasto indietro la chiudono.
 */
@Composable
fun BlurDialog(onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    androidx.compose.runtime.DisposableEffect(Unit) { Blur.depth++; onDispose { Blur.depth-- } }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        androidx.compose.runtime.SideEffect { window?.setDimAmount(0f) }
        Box(Modifier.fillMaxSize().background(Color(0x8C000000))
            .clickable(remember { MutableInteractionSource() }, null) { onDismiss() }
            .imePadding(), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(20.dp).widthIn(max = 480.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF1C1C1C))
                .clickable(remember { MutableInteractionSource() }, null) {}
                .padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

@Composable
fun DialogTitle(text: String) = Text(text, color = C.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)

@Composable
fun DialogText(text: String) = Text(text, color = C.muted, fontSize = 14.sp)

/** I pulsanti in fondo alla finestra, a destra: i secondari come testo, il principale pieno (rosso se distruttivo). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DialogButtons(vararg secondary: Pair<String, () -> Unit>, main: Pair<String, () -> Unit>? = null, danger: Boolean = false,
                  enabled: Boolean = true) {
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((label, action) in secondary) PillButton(onClick = action) { Text(label, maxLines = 1) }
        main?.let { (label, action) ->
            PillButton(onClick = action, enabled = enabled, primary = !danger, danger = danger) { Text(label, maxLines = 1) }
        }
    }
}

/** Icona tonda rossa del cestino in cima alle conferme distruttive. */
@Composable
fun DangerIcon() = Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0x33F87171)), contentAlignment = Alignment.Center) {
    Icon(Icons.Filled.Delete, null, tint = Color(0xFFF87171))
}

/** Una conferma a tutto schermo, sopra l'interfaccia sfocata. */
object Confirm {
    data class Ask(val title: String, val text: String, val yes: String, val danger: Boolean, val onYes: () -> Unit)
    var ask by mutableStateOf<Ask?>(null)
    fun show(title: String, text: String, yes: String, danger: Boolean = true, onYes: () -> Unit) {
        ask = Ask(title, text, yes, danger, onYes)
    }
}

@Composable
fun ConfirmOverlay() {
    val a = Confirm.ask ?: return
    BlurDialog({ Confirm.ask = null }) {
        if (a.danger) DangerIcon()
        DialogTitle(a.title)
        DialogText(a.text)
        DialogButtons(L.t("Annulla", "Cancel") to { Confirm.ask = null },
            main = a.yes to { Confirm.ask = null; a.onYes() }, danger = a.danger)
    }
}
