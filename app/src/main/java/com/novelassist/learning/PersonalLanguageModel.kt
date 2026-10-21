package com.novelassist.learning

import kotlin.random.Random

/**
 * A small language model trained only on the author's own writing.
 *
 * It counts which words follow which (up to three-word contexts) and blends
 * them, so it can suggest the author's likely next word and continue a line in
 * their own phrasing. It runs instantly on the phone, needs no download and
 * keeps learning as the book grows. Later chapters are weighted more heavily
 * so its suggestions follow the author's prose as it evolves.
 */
class PersonalLanguageModel {
    private val vocab = HashMap<String, String>()
    private val unigrams = HashMap<String, Float>()
    private val bigrams = HashMap<String, HashMap<String, Float>>()
    private val trigrams = HashMap<String, HashMap<String, Float>>()
    /** Most common written form of each word, to restore capitals for names. */
    private val surfaceForms = HashMap<String, HashMap<String, Int>>()
    private var totalUnigram = 0.0

    var trainedTokens: Int = 0
        private set

    val vocabularySize: Int get() = unigrams.size

    private fun intern(s: String): String = vocab.getOrPut(s) { s }

    fun train(text: String, weight: Float = 1f) {
        TextTools.paragraphs(text).forEach { paragraph ->
            val tokens = TextTools.modelTokens(paragraph)
            if (tokens.isEmpty()) return@forEach
            var prev2 = BOS
            var prev1 = BOS
            var sentenceStart = true
            for (raw in tokens) {
                val w = intern(raw.lowercase())
                if (!sentenceStart && raw[0].isLetter()) {
                    surfaceForms.getOrPut(w) { HashMap() }.merge(raw, 1, Int::plus)
                }
                unigrams.merge(w, weight, Float::plus)
                totalUnigram += weight
                bigrams.getOrPut(prev1) { HashMap() }.merge(w, weight, Float::plus)
                trigrams.getOrPut(intern("$prev2 $prev1")) { HashMap() }.merge(w, weight, Float::plus)
                prev2 = prev1
                prev1 = w
                sentenceStart = TextTools.isSentenceEnd(raw)
                if (sentenceStart) {
                    prev2 = BOS
                    prev1 = BOS
                }
                trainedTokens++
            }
        }
    }

    /**
     * Next-word suggestions for the text before the cursor. If the text ends
     * mid-word, suggestions complete that word instead.
     */
    fun suggest(textBefore: String, count: Int = 5): List<String> {
        if (unigrams.isEmpty()) return emptyList()
        val midWord = textBefore.isNotEmpty() && textBefore.last().isLetter()
        val prefix = if (midWord) textBefore.takeLastWhile { it.isLetter() || it == '\'' || it == '’' } else ""
        val before = textBefore.dropLast(prefix.length)
        val (p2, p1) = context(before)
        val atSentenceStart = p1 == BOS
        val scores = scoreCandidates(p2, p1, prefix.lowercase())
        return scores.entries
            .asSequence()
            .filter { !TextTools.isPunctuation(it.key) }
            .filter { prefix.isEmpty() || it.key != prefix.lowercase() }
            .sortedByDescending { it.value }
            .take(count)
            .map { display(it.key, atSentenceStart) }
            .toList()
    }

    /** Continues the text in the author's phrasing. Used when no AI model is set up. */
    fun continueText(textBefore: String, maxWords: Int = 40, random: Random = Random.Default): String {
        if (unigrams.size < 20) return ""
        var (p2, p1) = context(textBefore)
        val out = mutableListOf<String>()
        var words = 0
        var sentenceStart = p1 == BOS
        while (words < maxWords) {
            val scores = scoreCandidates(p2, p1, "")
            if (scores.isEmpty()) break
            val next = sample(scores, random, temperature = 0.75)
            out += if (TextTools.isPunctuation(next)) next else display(next, sentenceStart)
            if (!TextTools.isPunctuation(next)) words++
            sentenceStart = TextTools.isSentenceEnd(next)
            if (sentenceStart) {
                if (words >= maxWords * 0.6) break
                p2 = BOS; p1 = BOS
            } else {
                p2 = p1; p1 = next
            }
        }
        // End on a full sentence when possible.
        val lastEnd = out.indexOfLast { TextTools.isSentenceEnd(it) }
        val trimmed = if (lastEnd >= 3) out.subList(0, lastEnd + 1) else out
        return TextTools.detokenize(trimmed)
    }

    private fun context(textBefore: String): Pair<String, String> {
        val tokens = TextTools.lastWords(textBefore, 2).map { it.lowercase() }
        val p1 = tokens.lastOrNull()?.takeUnless { TextTools.isSentenceEnd(it) } ?: return BOS to BOS
        val p2 = tokens.dropLast(1).lastOrNull()?.takeUnless { TextTools.isSentenceEnd(it) } ?: BOS
        return p2 to p1
    }

    private fun scoreCandidates(p2: String, p1: String, prefix: String): Map<String, Double> {
        val tri = trigrams["$p2 $p1"]
        val bi = bigrams[p1]
        val triTotal = tri?.values?.sum()?.toDouble() ?: 0.0
        val biTotal = bi?.values?.sum()?.toDouble() ?: 0.0
        val candidates = HashSet<String>()
        tri?.keys?.let(candidates::addAll)
        bi?.keys?.let(candidates::addAll)
        if (prefix.isNotEmpty() || candidates.size < 8) {
            unigrams.entries.asSequence()
                .filter { prefix.isEmpty() || it.key.startsWith(prefix) }
                .sortedByDescending { it.value }
                .take(40)
                .forEach { candidates += it.key }
        }
        val lTri = if (triTotal > 0) 0.6 else 0.0
        val lBi = if (biTotal > 0) (if (triTotal > 0) 0.3 else 0.75) else 0.0
        val lUni = 1.0 - lTri - lBi
        val result = HashMap<String, Double>()
        for (c in candidates) {
            if (prefix.isNotEmpty() && !c.startsWith(prefix)) continue
            val pt = if (triTotal > 0) (tri!![c] ?: 0f) / triTotal else 0.0
            val pb = if (biTotal > 0) (bi!![c] ?: 0f) / biTotal else 0.0
            val pu = (unigrams[c] ?: 0f) / totalUnigram
            result[c] = lTri * pt + lBi * pb + lUni * pu
        }
        return result
    }

    private fun sample(scores: Map<String, Double>, random: Random, temperature: Double): String {
        val top = scores.entries.sortedByDescending { it.value }.take(12)
        val weights = top.map { Math.pow(it.value.coerceAtLeast(1e-9), 1.0 / temperature) }
        var r = random.nextDouble() * weights.sum()
        for ((i, e) in top.withIndex()) {
            r -= weights[i]
            if (r <= 0) return e.key
        }
        return top.first().key
    }

    private fun display(word: String, sentenceStart: Boolean): String {
        if (word == "i" || word.startsWith("i'") || word.startsWith("i’")) return word.replaceFirstChar { it.uppercase() }
        val forms = surfaceForms[word]
        val best = forms?.maxByOrNull { it.value }?.key
        val capitalisedMostly = best != null && best[0].isUpperCase() && forms.getValue(best) >= (forms.values.sum() * 0.6)
        return when {
            capitalisedMostly -> best
            sentenceStart -> word.replaceFirstChar { it.uppercase() }
            else -> word
        }
    }

    companion object {
        private const val BOS = "<s>"

        /**
         * Trains on the whole manuscript. Chapter weight rises from 1.0 for the
         * first chapter to 2.0 for the latest, so newer prose counts double.
         */
        fun fromChapters(texts: List<String>, extra: List<String> = emptyList()): PersonalLanguageModel {
            val model = PersonalLanguageModel()
            val n = texts.size
            texts.forEachIndexed { i, t ->
                val weight = if (n <= 1) 1f else 1f + i.toFloat() / (n - 1)
                model.train(t, weight)
            }
            extra.forEach { model.train(it, 0.5f) }
            return model
        }
    }
}
