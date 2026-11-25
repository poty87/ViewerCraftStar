package io.github.poty87.viewercraftstar

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Selezione trascinando il dito, come nella Galleria di Android: tieni premuta una foto (si seleziona) e, senza alzare
 * il dito, scorri sulle altre: si selezionano anche loro (o si deselezionano, se la prima era già selezionata). Vicino
 * al bordo alto o basso la galleria scorre da sola. Le foto si registrano con [dragSelectTarget].
 */
object DragSelect {
    /**
     * Il posto di ogni foto sullo schermo: si tiene il riferimento al suo layout e la posizione si calcola solo quando
     * serve (al tocco), non a ogni fotogramma mentre la galleria scorre.
     */
    val bounds = HashMap<Int, LayoutCoordinates>()

    fun hit(p: Offset): Int? = bounds.entries.firstOrNull { (_, c) -> c.isAttached && c.boundsInRoot().contains(p) }?.key
}

/** La cella di una foto: tiene aggiornata la sua posizione per la selezione trascinando. */
fun Modifier.dragSelectTarget(id: Int): Modifier = composed {
    DisposableEffect(id) { onDispose { DragSelect.bounds.remove(id) } }
    onGloballyPositioned { DragSelect.bounds[id] = it }
}

/** Il contenitore della galleria: pressione lunga e trascinamento selezionano le foto toccate. */
@Composable
fun Modifier.dragSelect(lib: LibraryViewModel, state: LazyListState): Modifier {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var origin = Offset.Zero
    var height = 0f
    return this
        .onGloballyPositioned { origin = it.positionInRoot(); height = it.size.height.toFloat() }
        .pointerInput(lib) {
            val edge = 90.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // pressione lunga: il dito resta fermo per il tempo della pressione lunga (se si alza o si muove, è un
                // tocco o uno scorrimento normale e non si fa niente)
                val moved = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                        if (!ch.pressed) return@withTimeoutOrNull true
                        if ((ch.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull true
                    }
                    @Suppress("UNREACHABLE_CODE") true
                }
                if (moved != null) return@awaitEachGesture
                val first = DragSelect.hit(down.position + origin) ?: return@awaitEachGesture
                // la prima foto decide: se non era selezionata si seleziona (e così le altre), se lo era si deseleziona
                val adding = first !in lib.selected
                fun apply(id: Int) {
                    lib.selected = if (adding) lib.selected + id else lib.selected - id
                }
                apply(first)
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                var last = first
                var pos = down.position
                var auto: Job? = null
                try {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        ch.consume() // da qui il dito seleziona: niente scorrimento né tocco sulle foto
                        if (!ch.pressed) break
                        pos = ch.position
                        DragSelect.hit(pos + origin)?.let { id -> if (id != last) { apply(id); last = id } }
                        // vicino ai bordi la galleria scorre da sola, e intanto si continua a selezionare
                        val speed = when {
                            pos.y < edge -> -(edge - pos.y) / edge * 28f
                            pos.y > height - edge -> (pos.y - (height - edge)) / edge * 28f
                            else -> 0f
                        }
                        auto?.cancel()
                        auto = if (speed == 0f) null else scope.launch {
                            while (isActive) {
                                state.scrollBy(speed)
                                DragSelect.hit(pos + origin)?.let { id -> if (id != last) { apply(id); last = id } }
                                delay(16)
                            }
                        }
                    }
                } finally {
                    auto?.cancel()
                }
            }
        }
}
