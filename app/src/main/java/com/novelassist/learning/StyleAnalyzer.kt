package com.novelassist.learning

import com.novelassist.data.StyleMetrics
import com.novelassist.data.StyleSnapshot
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Measures how a piece of prose is written: rhythm, dialogue, vocabulary, point
 * of view, tense and the author's recurring words and phrases. Everything runs
 * on the phone with no AI needed.
 */
object StyleAnalyzer {

    private val FIRST_PERSON = setOf("i", "me", "my", "mine", "myself", "we", "us", "our", "ours")
    private val SECOND_PERSON = setOf("you", "your", "yours", "yourself")
    private val THIRD_PERSON = setOf("he", "him", "his", "himself", "she", "her", "hers", "herself", "they", "them", "their", "theirs")
    private val PAST = setOf(
        "was", "were", "had", "did", "said", "thought", "felt", "knew", "looked", "went", "came", "saw",
        "took", "made", "turned", "told", "asked", "found", "seemed", "began", "stood", "sat", "walked",
    )
    private val PRESENT = setOf(
        "is", "are", "am", "has", "does", "says", "thinks", "feels", "knows", "looks", "goes", "comes",
        "sees", "takes", "makes", "turns", "tells", "asks", "finds", "seems", "begins", "stands", "sits", "walks",
    )
    private val NOT_ADVERBS = setOf(
        "only", "family", "early", "reply", "belly", "holy", "ugly", "lonely", "friendly", "lovely", "silly",
        "jelly", "bully", "rally", "ally", "fly", "july", "italy", "lily", "emily", "molly", "sally", "kelly",
        "daily", "likely", "elderly", "curly", "chilly", "hilly", "wily", "oily", "supply", "apply", "rely",
        "assembly", "anomaly", "monopoly", "melancholy", "comply", "imply", "multiply", "butterfly",
    )

    fun analyze(text: String): StyleMetrics {
        val words = TextTools.words(text)
        if (words.isEmpty()) return StyleMetrics()
        val lower = words.map { it.lowercase() }
        val sentences = TextTools.sentences(text)
        val paragraphs = TextTools.paragraphs(text)
        val sentenceLengths = sentences.map { TextTools.wordCount(it) }.filter { it > 0 }
        val avgLen = sentenceLengths.average().takeIf { !it.isNaN() } ?: words.size.toDouble()
        val spread = if (sentenceLengths.size > 1) {
            sqrt(sentenceLengths.sumOf { (it - avgLen) * (it - avgLen) } / sentenceLengths.size)
        } else 0.0

        val narration = TextTools.words(TextTools.narrationOnly(text)).map { it.lowercase() }
        val first = narration.count { it in FIRST_PERSON }
        val second = narration.count { it in SECOND_PERSON }
        val third = narration.count { it in THIRD_PERSON }
        val pov = when {
            narration.size < 30 -> ""
            first > third * 0.6 && first >= 3 -> "first person"
            second > third && second > first && second >= 3 -> "second person"
            else -> "third person"
        }
        val past = narration.count { it in PAST } + narration.count { it.length > 4 && it.endsWith("ed") } * 0.5
        val present = narration.count { it in PRESENT }.toDouble()
        val tense = when {
            narration.size < 30 -> ""
            past > present * 1.6 -> "past tense"
            present > past * 1.6 -> "present tense"
            else -> "mixed tense"
        }

        val adverbs = lower.count { it.length > 4 && it.endsWith("ly") && it !in NOT_ADVERBS }
        val sentenceCount = sentenceLengths.size.coerceAtLeast(1)

        return StyleMetrics(
            wordCount = words.size,
            sentenceCount = sentenceLengths.size,
            paragraphCount = paragraphs.size,
            avgSentenceLength = avgLen.round1(),
            sentenceLengthSpread = spread.round1(),
            avgWordLength = (words.sumOf { it.length }.toDouble() / words.size).round1(),
            lexicalDiversity = movingTypeTokenRatio(lower).round2(),
            dialogueRatio = (TextTools.dialogueChars(text).toDouble() / text.length.coerceAtLeast(1)).round2(),
            adverbsPer100 = (adverbs * 100.0 / words.size).round1(),
            questionRate = (sentences.count { it.trimEnd('"', '”', '\'', '’', ')').endsWith("?") }.toDouble() / sentenceCount).round2(),
            exclamationRate = (sentences.count { it.trimEnd('"', '”', '\'', '’', ')').endsWith("!") }.toDouble() / sentenceCount).round2(),
            commasPerSentence = (text.count { it == ',' }.toDouble() / sentenceCount).round1(),
            dashesPer1000 = ((text.count { it == '—' } + Regex(" - |--").findAll(text).count()) * 1000.0 / words.size).round1(),
            sentencesPerParagraph = (sentenceCount.toDouble() / paragraphs.size.coerceAtLeast(1)).round1(),
            pointOfView = pov,
            tense = tense,
            favouriteWords = favouriteWords(words),
            signaturePhrases = signaturePhrases(sentences),
        )
    }

    /** Weighted average of several chapters' metrics (by word count). */
    fun combine(all: List<StyleMetrics>): StyleMetrics {
        val list = all.filter { it.wordCount > 0 }
        if (list.isEmpty()) return StyleMetrics()
        val total = list.sumOf { it.wordCount }.toDouble()
        fun avg(f: (StyleMetrics) -> Double) = list.sumOf { f(it) * it.wordCount } / total
        fun mode(f: (StyleMetrics) -> String) =
            list.filter { f(it).isNotEmpty() }.groupBy(f).maxByOrNull { (_, v) -> v.sumOf { it.wordCount } }?.key.orEmpty()
        fun topList(f: (StyleMetrics) -> List<String>) =
            list.flatMap(f).groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(12)
        return StyleMetrics(
            wordCount = total.toInt(),
            sentenceCount = list.sumOf { it.sentenceCount },
            paragraphCount = list.sumOf { it.paragraphCount },
            avgSentenceLength = avg { it.avgSentenceLength }.round1(),
            sentenceLengthSpread = avg { it.sentenceLengthSpread }.round1(),
            avgWordLength = avg { it.avgWordLength }.round1(),
            lexicalDiversity = avg { it.lexicalDiversity }.round2(),
            dialogueRatio = avg { it.dialogueRatio }.round2(),
            adverbsPer100 = avg { it.adverbsPer100 }.round1(),
            questionRate = avg { it.questionRate }.round2(),
            exclamationRate = avg { it.exclamationRate }.round2(),
            commasPerSentence = avg { it.commasPerSentence }.round1(),
            dashesPer1000 = avg { it.dashesPer1000 }.round1(),
            sentencesPerParagraph = avg { it.sentencesPerParagraph }.round1(),
            pointOfView = mode { it.pointOfView },
            tense = mode { it.tense },
            favouriteWords = topList { it.favouriteWords },
            signaturePhrases = topList { it.signaturePhrases }.take(8),
        )
    }

    /** A plain-English portrait of the prose, built only from the numbers. */
    fun describe(m: StyleMetrics): String {
        if (m.wordCount < 50) return "Not enough writing yet. Dictate a few paragraphs and tap Learn."
        val parts = mutableListOf<String>()
        val voice = listOf(m.pointOfView, m.tense).filter { it.isNotEmpty() }.joinToString(", ")
        if (voice.isNotEmpty()) parts += "Written in $voice."
        val rhythm = when {
            m.avgSentenceLength < 10 -> "short, punchy sentences"
            m.avgSentenceLength < 16 -> "medium-length sentences"
            m.avgSentenceLength < 24 -> "long, flowing sentences"
            else -> "very long, layered sentences"
        }
        val variety = when {
            m.sentenceLengthSpread > m.avgSentenceLength * 0.7 -> "with strong variety (short beats mixed with long lines)"
            m.sentenceLengthSpread > m.avgSentenceLength * 0.4 -> "with some variety"
            else -> "with an even, steady rhythm"
        }
        parts += "Mostly $rhythm (about ${m.avgSentenceLength.roundToInt()} words) $variety."
        parts += when {
            m.dialogueRatio > 0.45 -> "Dialogue-driven: about ${pct(m.dialogueRatio)} of the text is speech."
            m.dialogueRatio > 0.2 -> "A balance of narration and dialogue (${pct(m.dialogueRatio)} speech)."
            m.dialogueRatio > 0.03 -> "Narration-led, with dialogue used sparingly (${pct(m.dialogueRatio)})."
            else -> "Almost all narration, very little dialogue."
        }
        parts += when {
            m.lexicalDiversity > 0.75 -> "Rich, varied vocabulary."
            m.lexicalDiversity > 0.62 -> "A comfortable, varied vocabulary."
            else -> "Plain, repeated vocabulary that keeps the reading easy."
        }
        if (m.adverbsPer100 > 2.5) parts += "Leans on adverbs."
        else if (m.adverbsPer100 < 0.8) parts += "Sparing with adverbs."
        if (m.commasPerSentence > 2.0) parts += "Comma-rich, clause-building sentences."
        if (m.dashesPer1000 > 4) parts += "Likes dashes for interruptions and asides."
        if (m.questionRate > 0.12) parts += "Asks a lot of questions."
        if (m.sentencesPerParagraph < 2.2) parts += "Short paragraphs keep the pace quick."
        else if (m.sentencesPerParagraph > 6) parts += "Long, immersive paragraphs."
        if (m.favouriteWords.isNotEmpty()) parts += "Favourite words: ${m.favouriteWords.take(8).joinToString(", ")}."
        if (m.signaturePhrases.isNotEmpty()) parts += "Recurring phrases: ${m.signaturePhrases.take(4).joinToString("; ") { "“$it”" }}."
        return parts.joinToString(" ")
    }

    /** How the prose is changing: compares the earliest chapters with the latest ones. */
    fun trends(snapshots: List<StyleSnapshot>): List<String> {
        val ordered = snapshots.filter { it.metrics.wordCount >= 150 }.sortedBy { it.chapterIndex }
        if (ordered.size < 2) return emptyList()
        val third = (ordered.size / 3).coerceAtLeast(1)
        val early = combine(ordered.take(third).map { it.metrics })
        val late = combine(ordered.takeLast(third).map { it.metrics })
        val out = mutableListOf<String>()
        fun compare(name: String, a: Double, b: Double, up: String, down: String, fmt: (Double) -> String) {
            if (a <= 0.0 && b <= 0.0) return
            val change = (b - a) / a.coerceAtLeast(0.01)
            if (abs(change) >= 0.12) out += "${if (change > 0) up else down}: ${fmt(a)} → ${fmt(b)} $name."
        }
        compare("words per sentence", early.avgSentenceLength, late.avgSentenceLength,
            "Sentences are getting longer", "Sentences are getting shorter") { it.roundToInt().toString() }
        compare("of the text is dialogue", early.dialogueRatio, late.dialogueRatio,
            "More dialogue", "Less dialogue") { pct(it) }
        compare("vocabulary variety", early.lexicalDiversity, late.lexicalDiversity,
            "Vocabulary is widening", "Vocabulary is getting simpler") { "%.2f".format(it) }
        compare("adverbs per 100 words", early.adverbsPer100, late.adverbsPer100,
            "More adverbs", "Fewer adverbs") { "%.1f".format(it) }
        compare("sentences per paragraph", early.sentencesPerParagraph, late.sentencesPerParagraph,
            "Paragraphs are getting longer", "Paragraphs are getting shorter") { "%.1f".format(it) }
        if (early.tense.isNotEmpty() && late.tense.isNotEmpty() && early.tense != late.tense) {
            out += "Tense shifted from ${early.tense} to ${late.tense}."
        }
        if (early.pointOfView.isNotEmpty() && late.pointOfView.isNotEmpty() && early.pointOfView != late.pointOfView) {
            out += "Point of view shifted from ${early.pointOfView} to ${late.pointOfView}."
        }
        val newWords = late.favouriteWords.filter { it !in early.favouriteWords }.take(5)
        if (newWords.isNotEmpty()) out += "New favourite words lately: ${newWords.joinToString(", ")}."
        return out
    }

    private fun favouriteWords(words: List<String>): List<String> {
        // Words that are usually capitalised are names; the bible handles those.
        val caps = HashMap<String, Int>()
        val counts = HashMap<String, Int>()
        words.forEach { w ->
            val l = w.lowercase()
            if (l.length >= 4 && l !in TextTools.STOPWORDS && !l[0].isDigit()) {
                counts[l] = (counts[l] ?: 0) + 1
                if (w[0].isUpperCase()) caps[l] = (caps[l] ?: 0) + 1
            }
        }
        val min = if (words.size > 2000) 3 else 2
        return counts.entries
            .filter { (w, c) -> c >= min && (caps[w] ?: 0) < c * 0.5 }
            .sortedByDescending { it.value }
            .take(12)
            .map { it.key }
    }

    /** Three-word phrases the author repeats, counted within sentences and leaving out names. */
    private fun signaturePhrases(sentences: List<String>): List<String> {
        val total = sentences.sumOf { TextTools.wordCount(it) }
        if (total < 60) return emptyList()
        val counts = HashMap<String, Int>()
        for (sentence in sentences) {
            val words = TextTools.words(sentence)
            for (i in 0 until words.size - 2) {
                val trio = words.subList(i, i + 3)
                // A capital after the first word of a sentence is a name, which the bible tracks instead.
                if (trio.withIndex().any { (j, w) -> i + j > 0 && w[0].isUpperCase() && w != "I" }) continue
                val lower = trio.map { it.lowercase() }
                if (lower.none { it !in TextTools.STOPWORDS }) continue
                val key = lower.joinToString(" ")
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        val min = if (total > 3000) 3 else 2
        return counts.entries.filter { it.value >= min }.sortedByDescending { it.value }.take(8).map { it.key }
    }

    /** Moving-average type/token ratio: a vocabulary measure that doesn't depend on length. */
    private fun movingTypeTokenRatio(words: List<String>, window: Int = 100): Double {
        if (words.isEmpty()) return 0.0
        if (words.size <= window) return words.toSet().size.toDouble() / words.size
        val counts = HashMap<String, Int>()
        for (i in 0 until window) counts[words[i]] = (counts[words[i]] ?: 0) + 1
        var sum = counts.size.toDouble() / window
        var n = 1
        for (i in window until words.size) {
            val out = words[i - window]
            val c = counts[out]!! - 1
            if (c == 0) counts.remove(out) else counts[out] = c
            counts[words[i]] = (counts[words[i]] ?: 0) + 1
            sum += counts.size.toDouble() / window
            n++
        }
        return sum / n
    }

    fun pct(v: Double): String = "${(v * 100).roundToInt()}%"
    private fun Double.round1() = (this * 10).roundToInt() / 10.0
    private fun Double.round2() = (this * 100).roundToInt() / 100.0
}
