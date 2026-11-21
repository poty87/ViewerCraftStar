package io.github.poty87.viewercraftstar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** "Riduci consigli interfaccia": con l'opzione attiva le spiegazioni lunghe spariscono o diventano una riga. */
object Tips {
    // sempre testi brevi: l'interfaccia attuale non ha più bisogno di quelli lunghi
    var compact by mutableStateOf(true)
}

/** Il testo lungo, oppure quello breve se i consigli sono ridotti (null = nessun testo). */
fun tip(full: String, short: String?): String? = if (Tips.compact) short else full
