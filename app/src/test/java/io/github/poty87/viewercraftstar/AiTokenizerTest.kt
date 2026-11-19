package io.github.poty87.viewercraftstar

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Il tokenizzatore Kotlin deve dare gli stessi id della libreria `tokenizers` (Python) usata per preparare il modello.
 * File in VCS_TOKENIZER (tokenizer.json del telefono) e VCS_TOKENIZER_EXPECTED (frasi e id attesi); senza, non fa niente.
 */
class AiTokenizerTest {
    @Test
    fun sameIdsAsPython() {
        val tokPath = System.getenv("VCS_TOKENIZER") ?: ""
        val expPath = System.getenv("VCS_TOKENIZER_EXPECTED") ?: ""
        assumeTrue(File(tokPath).exists() && File(expPath).exists())
        val tok = AiTokenizer.load(File(tokPath))
        val cases = JSONArray(File(expPath).readText(Charsets.UTF_8))
        val wrong = ArrayList<String>()
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val want = c.getJSONArray("ids").let { a -> IntArray(a.length()) { a.getInt(it) } }
            val got = tok.encode(c.getString("text"))
            if (!want.contentEquals(got)) wrong += "«${c.getString("text")}» atteso ${want.toList()} ottenuto ${got.toList()}"
        }
        File(System.getProperty("java.io.tmpdir"), "vcs-tokenizer-report.txt")
            .writeText("${cases.length()} frasi, diverse ${wrong.size}\n" + wrong.joinToString("\n"))
        assertEquals(wrong.joinToString("\n"), 0, wrong.size)
    }
}
