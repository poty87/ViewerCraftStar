package io.github.poty87.viewercraftstar

import org.json.JSONObject
import java.io.File
import java.text.Normalizer

/**
 * Il tokenizzatore della ricerca IA: Unigram (SentencePiece, come XLM-R / MetaCLIP 2), letto dal tokenizer.json che
 * PresetCraftAppLicator prepara per il telefono (vocabolario ridotto a italiano e inglese).
 *
 * Come la libreria `tokenizers` con quel file: normalizzazione NFKC (al posto della tabella "Precompiled" di
 * SentencePiece, equivalente per i testi normali), parole separate dagli spazi, "▁" davanti a ogni parola, per ogni
 * parola la scomposizione in pezzi con il punteggio più alto (Viterbi), i caratteri sconosciuti diventano <unk> (più
 * <unk> di fila uno solo), poi <s> … </s>.
 */
class AiTokenizer(json: String) {
    private val pieces = HashMap<String, Int>()
    private val scores: FloatArray
    private val maxLen: Int
    val unk: Int
    val bos: Int
    val eos: Int
    private val unkScore: Float

    init {
        val js = JSONObject(json)
        val model = js.getJSONObject("model")
        val vocab = model.getJSONArray("vocab")
        scores = FloatArray(vocab.length())
        var longest = 1
        var minScore = Float.MAX_VALUE
        for (i in 0 until vocab.length()) {
            val e = vocab.getJSONArray(i)
            val piece = e.getString(0)
            val score = e.getDouble(1).toFloat()
            pieces.putIfAbsent(piece, i)
            scores[i] = score
            if (score < minScore) minScore = score
            longest = maxOf(longest, piece.length)
        }
        maxLen = longest
        unk = model.optInt("unk_id", 3)
        unkScore = minScore - 10f // come tokenizers (kUnkPenalty)
        val added = HashMap<String, Int>()
        js.optJSONArray("added_tokens")?.let { a ->
            for (i in 0 until a.length()) a.getJSONObject(i).let { added[it.getString("content")] = it.getInt("id") }
        }
        bos = added["<s>"] ?: pieces["<s>"] ?: 0
        eos = added["</s>"] ?: pieces["</s>"] ?: 2
    }

    /** Gli id per il modello: <s>, i pezzi, </s>. */
    fun encode(text: String): IntArray {
        val norm = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val out = ArrayList<Int>()
        out += bos
        for (word in norm.split(Regex("\\s+"))) {
            if (word.isEmpty()) continue
            out += viterbi("▁" + word)
        }
        out += eos
        return out.toIntArray()
    }

    private fun viterbi(s: String): List<Int> {
        val n = s.length
        val best = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val from = IntArray(n + 1) { -1 }
        val tok = IntArray(n + 1) { -1 }
        best[0] = 0f
        for (i in 0 until n) {
            if (best[i] == Float.NEGATIVE_INFINITY) continue
            val limit = minOf(n, i + maxLen)
            for (j in i + 1..limit) {
                val id = pieces[s.substring(i, j)] ?: continue
                val sc = best[i] + scores[id]
                if (sc > best[j]) { best[j] = sc; from[j] = i; tok[j] = id }
            }
            // un carattere senza pezzo che lo copra da solo: <unk> (con la penalità di tokenizers)
            val single = pieces.containsKey(s.substring(i, i + 1))
            if (!single) {
                val sc = best[i] + unkScore
                if (sc > best[i + 1]) { best[i + 1] = sc; from[i + 1] = i; tok[i + 1] = unk }
            }
        }
        val ids = ArrayList<Int>()
        var at = n
        while (at > 0) {
            val id = tok[at]
            if (!(id == unk && ids.isNotEmpty() && ids.last() == unk)) ids += id // <unk> di fila: uno solo
            at = from[at]
        }
        ids.reverse()
        return ids
    }

    companion object {
        fun load(file: File) = AiTokenizer(file.readText(Charsets.UTF_8))
    }
}
