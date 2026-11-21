package io.github.poty87.viewercraftstar

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Il rosso del cestino (come il tondo del cestino in Inviate). */
val TrashRed = Color(0xFFF87171)

/** Il pallino rosso «ci sono foto nel cestino»: sui tre puntini delle Librerie e sulla voce del menu. */
@Composable
fun TrashDot(modifier: Modifier = Modifier) {
    Box(modifier.size(9.dp).clip(CircleShape).background(TrashRed))
}

/**
 * Il cestino di LightCraft della libreria aperta (dal menu ⋮): le foto eliminate, dal telefono o in LightCraft, che
 * restano qui finché CleanCraftNas non cancella i file dal NAS. Si selezionano come in galleria (tocco, o pressione
 * lunga trascinando) e «Ripristina» le rimette in galleria, nella loro pila e al loro posto (lo fa il PC di casa).
 */
@Composable
fun LibraryTrash(lib: LibraryViewModel, modifier: Modifier = Modifier) {
    BackHandler { lib.closeTrash() }
    val photos = lib.trashPhotos()
    val state = rememberLazyListState()
    Column(modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { lib.closeTrash() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L.t("Indietro", "Back"), tint = C.text) }
            Column(Modifier.weight(1f)) {
                TitleText(L.t("Cestino", "Trash"))
                Text(L.t("${photos.size} foto · ${lib.index?.library.orEmpty()}", "${photos.size} photos · ${lib.index?.library.orEmpty()}"),
                    color = C.muted, fontSize = 12.sp, maxLines = 1)
            }
        }
        if (photos.isNotEmpty()) Text(L.t("Restano qui finché CleanCraftNas non cancella i file dal NAS. Ripristinate, tornano nella loro pila.",
            "They stay here until CleanCraftNas deletes the files from the NAS. Restored, they go back to their stack."),
            color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        if (photos.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(EmptyGlyph.DONE, L.t("Cestino vuoto", "Trash is empty"),
                L.t("Qui arrivano le foto che metti nel cestino di LightCraft.", "Photos you move to LightCraft's trash end up here."))
        } else BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = 3
            LazyColumn(Modifier.fillMaxSize().dragSelect(lib, state), state = state,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 3.dp, end = 3.dp, bottom = 140.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                items(photos.chunked(columns), key = { row -> row.first().id }) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        row.forEach { p -> TrashCell(lib, p, Modifier.weight(1f)) }
                        repeat(columns - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrashCell(lib: LibraryViewModel, p: LibPhoto, modifier: Modifier) {
    val chosen = p.id in lib.selected
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(2.dp))
        .then(if (chosen) Modifier.border(3.dp, Color.White, RoundedCornerShape(2.dp)) else Modifier)
        .dragSelectTarget(p.id)
        .clickable { lib.toggleSelect(p.id) }) {
        RemoteImage(lib, p, RemotePreview.THUMB, Modifier.fillMaxSize(), crop = true)
        formatBadge(p.name)?.let {
            Text(it, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).clip(RoundedCornerShape(3.dp))
                    .background(Color(0x99000000)).padding(horizontal = 4.dp, vertical = 1.dp))
        }
        // eliminata dal telefono e non ancora arrivata in LightCraft
        if (lib.edits[p.id]?.trash == true) Box(Modifier.align(Alignment.TopCenter).padding(6.dp).size(10.dp)
            .clip(CircleShape).background(Color(0xFFFB923C)))
        if (chosen) Box(Modifier.fillMaxSize().background(C.accent.copy(alpha = 0.33f)), contentAlignment = Alignment.Center) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(C.accent), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, null, tint = Color.Black, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** La barra della selezione nel cestino: quante, e «Ripristina». */
@Composable
fun TrashSelectionBar(lib: LibraryViewModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().background(BarBg).navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { lib.clearSelection() }) { Icon(Icons.Filled.Close, L.t("Annulla", "Cancel"), tint = C.text) }
            Text(L.t("${lib.selected.size} selezionate", "${lib.selected.size} selected"), color = C.text, fontSize = 15.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            PillButton(onClick = { lib.restoreFromTrash(lib.selected) }, primary = true) { Text(L.t("Ripristina", "Restore")) }
        }
    }
}
