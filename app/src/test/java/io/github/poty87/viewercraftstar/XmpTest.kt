package io.github.poty87.viewercraftstar

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Scrive due .xmp di prova (tutto pieno / tutto vuoto) per controllarli con lightcraft-cli sul PC. */
class XmpTest {
    @Test
    fun writesSamples() {
        val out = File(System.getProperty("vcs.out") ?: "build/xmp-samples").apply { mkdirs() }
        val full = PhotoMeta(
            flag = Flag.REJECT, rating = 5, label = ColorLabel.PURPLE, title = "Titolo <prova> & \"virgolette\"",
            caption = "Didascalia àèìòù", keywords = listOf("mare", "viaggio|Italia"), creator = "Autore", copyright = "© 2026",
        )
        File(out, "full.xmp").writeText(Xmp.build(full))
        File(out, "empty.xmp").writeText(Xmp.build(PhotoMeta()))
        assertTrue(File(out, "full.xmp").readText().contains("<lc:flag>reject</lc:flag>"))
    }
}
