package io.github.poty87.viewercraftstar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.foundation.verticalScroll

/** Un percorso lungo accorciato nel mezzo con i puntini, così si vedono l'inizio e la cartella finale. */
private fun middleEllipsis(text: String, max: Int): String {
    if (text.length <= max || max < 8) return text
    val keepEnd = (max - 1) * 2 / 3
    val keepStart = max - 1 - keepEnd
    return text.take(keepStart) + "…" + text.takeLast(keepEnd)
}

/**
 * Una cartella nelle Impostazioni: il nome, il percorso completo (accorciato con i puntini se lungo; un tocco per
 * scriverlo a mano) e "Sfoglia" per sceglierla. `locked`: al posto del pulsante, cosa serve per sbloccarla.
 */
@Composable
fun PathRow(label: String, path: String, onBrowse: () -> Unit, onEdit: (() -> Unit)?, locked: String? = null) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = C.muted, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            BoxWithConstraints(Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                .then(if (onEdit != null && locked == null) Modifier.clickable { onEdit() } else Modifier)
                .padding(vertical = 6.dp)) {
                // quanti caratteri stanno davvero nella riga (misurati), lasciando posto alla matita
                val measurer = androidx.compose.ui.text.rememberTextMeasurer()
                val style = androidx.compose.material3.LocalTextStyle.current.merge(androidx.compose.ui.text.TextStyle(fontSize = 15.sp))
                val room = with(androidx.compose.ui.platform.LocalDensity.current) { (maxWidth - 30.dp).roundToPx() }
                val full = path.ifEmpty { L.t("(non scelta)", "(not chosen)") }
                val shown = remember(full, room) {
                    var n = full.length
                    var t = full
                    while (n > 8 && measurer.measure(t, style, softWrap = false).size.width > room) { n--; t = middleEllipsis(full, n) }
                    t
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(shown, color = if (path.isEmpty() || locked != null) C.muted else C.text, style = style, maxLines = 1,
                        softWrap = false, modifier = Modifier.weight(1f, fill = false))
                    if (onEdit != null && locked == null) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Filled.Edit, L.t("Scrivi il percorso", "Type the path"), tint = C.muted, modifier = Modifier.size(15.dp))
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            if (locked == null) PillButton(onClick = onBrowse) { Text(L.t("Sfoglia", "Browse")) }
        }
        if (locked != null) Text(locked, color = C.muted, fontSize = 12.sp)
    }
}

/** Scrivere un percorso a mano (per chi lo conosce già). */
@Composable
fun PathEditDialog(title: String, value: String, hint: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(value) }
    BlurDialog(onDismiss) {
        DialogTitle(title)
        OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(hint, fontSize = 13.sp) })
        DialogButtons(L.t("Annulla", "Cancel") to onDismiss, main = L.t("Salva", "Save") to { onSave(text.trim()) },
            enabled = text.isNotBlank())
    }
}

/**
 * Sceglie una cartella del NAS: prima la cartella condivisa, poi le sottocartelle, una alla volta. "Scegli" prende
 * quella in cui si è arrivati. `start`: (condivisione, percorso) da cui partire, se c'è.
 */
@Composable
fun NasFolderDialog(title: String, cfg: NasConfig, start: Pair<String, String>?, onDismiss: () -> Unit,
                    onPick: (String, String) -> Unit) {
    var share by remember { mutableStateOf(start?.first?.takeIf { it.isNotEmpty() }) }
    var path by remember { mutableStateOf(start?.second ?: "") }
    var items by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(share, path) {
        items = null; error = null
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val sh = share
                if (sh == null) NasBrowse.shares(cfg.host, cfg.user, cfg.password)
                else NasBrowse.folders(cfg.host, cfg.user, cfg.password, sh, path)
            }
        }
        r.onSuccess { items = it }.onFailure {
            // una cartella di partenza che non c'è più: si riparte dalla condivisione
            if (share != null && path.isNotEmpty()) path = "" else error = it.message ?: it.javaClass.simpleName
        }
    }
    BlurDialog(onDismiss) {
        DialogTitle(title)
        // dove si è: condivisione › cartella › …
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (share != null) Icon(Icons.AutoMirrored.Filled.ArrowBack, L.t("Su", "Up"), tint = C.text,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable {
                    if (path.isEmpty()) share = null else path = path.substringBeforeLast('\\', "")
                }.padding(6.dp).size(20.dp))
            Text(if (share == null) L.t("Cartelle condivise del NAS", "NAS shared folders")
                else (listOf(share!!) + path.split('\\').filter { it.isNotEmpty() }).joinToString(" › "),
                color = C.muted, fontSize = 13.sp, maxLines = 2, modifier = Modifier.padding(start = 4.dp))
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 340.dp).clip(RoundedCornerShape(12.dp)).background(C.card)) {
            val list = items
            when {
                error != null -> Text(L.t("Non riesco a leggere il NAS: ", "Can't read the NAS: ") + error, color = C.muted,
                    fontSize = 13.sp, modifier = Modifier.padding(14.dp))
                list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp), color = C.accent, strokeWidth = 2.dp)
                list.isEmpty() -> Text(if (share == null) L.t("Nessuna cartella condivisa visibile", "No shared folders visible")
                    else L.t("Nessuna sottocartella", "No subfolders"), color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(14.dp))
                else -> LazyColumn(Modifier.fillMaxWidth()) {
                    items(list) { name ->
                        Row(Modifier.fillMaxWidth().clickable {
                            if (share == null) { share = name; path = "" } else path = if (path.isEmpty()) name else "$path\\$name"
                        }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, color = C.text, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
                            Icon(Icons.Filled.KeyboardArrowRight, null, tint = C.muted, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
        val here = share
        DialogButtons(L.t("Annulla", "Cancel") to onDismiss,
            main = L.t("Scegli questa cartella", "Choose this folder") to { if (here != null) onPick(here, path) },
            enabled = here != null)
    }
}

/**
 * Il percorso relativo (es. "Pictures/VCS_Import_Photo/") di una cartella scelta con il selettore di Android, oppure
 * null se non sta nella memoria interna dentro Pictures o DCIM (le sole cartelle di foto che l'app può usare).
 */
fun treeToRelative(uri: android.net.Uri): String? {
    val id = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    if (!id.startsWith("primary:")) return null
    val rel = id.removePrefix("primary:").trim('/')
    val top = rel.substringBefore('/')
    if (!top.equals("Pictures", ignoreCase = true) && !top.equals("DCIM", ignoreCase = true)) return null
    return "$rel/"
}

/**
 * Backup delle impostazioni: «Salva backup» scrive uno zip nella cartella di servizio (backups\ViewerCraftStar);
 * «Ripristina…» elenca quelli sul NAS, chiede conferma, ripristina e riavvia l'app.
 */
@androidx.compose.runtime.Composable
fun BackupCard(vm: AppViewModel, ready: Boolean, beforeSave: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var busy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var list by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<List<ConfigBackup.Entry>?>(null) }
    var chosen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    val s = vm.settings
    val fmt = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.ITALY)
    androidx.compose.material3.Text(L.t("Uno zip nella cartella di servizio (backups › ViewerCraftStar) con impostazioni, stelle e metadati delle foto del telefono e modifiche alle librerie non ancora applicate. La password del NAS non c'è: dopo un ripristino su un telefono nuovo va riscritta. Restano gli ultimi 20.",
        "A zip in the service folder (backups › ViewerCraftStar) with settings, stars and metadata of the phone's photos and library edits not applied yet. The NAS password isn't in it: after restoring on a new phone, type it again. The last 20 are kept."),
        color = C.muted, fontSize = 12.sp)
    if (!ready) androidx.compose.material3.Text(L.t("Serve la cartella di servizio e il NAS raggiungibile (prova la connessione).",
        "Needs the service folder and the NAS reachable (test the connection)."), color = C.muted, fontSize = 12.sp)
    androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        PillButton(onClick = {
            beforeSave(); busy = true
            scope.launch {
                runCatching { ConfigBackup.save(context, s) }
                    .onSuccess { Notices.post("ok", L.t("Backup salvato: $it", "Backup saved: $it")) }
                    .onFailure { Notices.post("error", L.t("Backup non riuscito: ", "Backup failed: ") + (it.message ?: it.javaClass.simpleName)) }
                busy = false
            }
        }, primary = true, enabled = ready && !busy) { androidx.compose.material3.Text(L.t("Salva backup", "Save backup")) }
        PillButton(onClick = {
            busy = true
            scope.launch {
                runCatching { ConfigBackup.list(s) }
                    .onSuccess { l -> if (l.isEmpty()) Notices.post("info", L.t("Nessun backup sul NAS.", "No backups on the NAS.")) else { list = l; chosen = l.first().name } }
                    .onFailure { Notices.post("error", L.t("Non riesco a leggere i backup: ", "Can't read the backups: ") + (it.message ?: it.javaClass.simpleName)) }
                busy = false
            }
        }, enabled = ready && !busy) { androidx.compose.material3.Text(L.t("Ripristina…", "Restore…")) }
        if (busy) androidx.compose.material3.CircularProgressIndicator(androidx.compose.ui.Modifier.size(18.dp), color = C.accent, strokeWidth = 2.dp)
    }
    val l = list
    if (l != null) BlurDialog(onDismiss = { list = null }) {
        TitleText(L.t("Ripristina un backup", "Restore a backup"))
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.heightIn(max = 360.dp)
            .verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            for (e in l) androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.fillMaxWidth().clickable { chosen = e.name },
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.material3.RadioButton(chosen == e.name, { chosen = e.name },
                    colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = C.accent))
                androidx.compose.material3.Text(fmt.format(java.util.Date(e.time)) + "  ·  ${(e.size + 1023) / 1024} KB", color = C.text)
            }
        }
        DialogButtons(L.t("Annulla", "Cancel") to { list = null }, main = L.t("Ripristina", "Restore") to {
            val name = chosen
            list = null
            Confirm.show(L.t("Ripristinare questo backup?", "Restore this backup?"),
                L.t("Impostazioni, metadati delle foto del telefono e modifiche alle librerie in attesa tornano come nel backup: quelli di adesso vengono sostituiti. La password del NAS resta quella di adesso. Poi l'app si riavvia.",
                    "Settings, metadata of the phone's photos and pending library edits go back to the backup: the current ones are replaced. The NAS password stays the current one. Then the app restarts."),
                L.t("Ripristina", "Restore"), danger = false) {
                busy = true
                scope.launch {
                    runCatching { ConfigBackup.restore(context, s, name) }
                        .onSuccess { ConfigBackup.restart(context) }
                        .onFailure { Notices.post("error", L.t("Ripristino non riuscito: ", "Restore failed: ") + (it.message ?: it.javaClass.simpleName)); busy = false }
                }
            }
        })
    }
}
