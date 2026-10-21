package com.novelassist.learning

import java.text.NumberFormat
import java.util.Locale

/** Small, dependency-free text helpers shared by the learning code. */
object TextTools {
    private val WORD = Regex("[\\p{L}]+(?:['’][\\p{L}]+)*|[0-9]+")
    private val SENTENCE_SPLIT = Regex("(?<=[.!?…][\"'”’)\\]]?)\\s+(?=[\"'“‘(\\[]?[\\p{Lu}0-9])|\\n+")
    private val PARAGRAPH_SPLIT = Regex("\\n\\s*")

    /** Tokens for the personal model: words plus the punctuation that shapes rhythm. */
    private val MODEL_TOKEN = Regex("[\\p{L}]+(?:['’][\\p{L}]+)*|[0-9]+|[.,!?;:…—]")

    fun words(text: String): List<String> = WORD.findAll(text).map { it.value }.toList()

    fun wordCount(text: String): Int {
        var count = 0
        var inWord = false
        for (c in text) {
            val isWordChar = c.isLetterOrDigit() || c == '\'' || c == '’'
            if (isWordChar && !inWord) count++
            inWord = isWordChar
        }
        return count
    }

    fun sentences(text: String): List<String> =
        text.split(SENTENCE_SPLIT).map { it.trim() }.filter { s -> s.any { it.isLetterOrDigit() } }

    fun paragraphs(text: String): List<String> =
        text.split(PARAGRAPH_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }

    fun modelTokens(text: String): List<String> = MODEL_TOKEN.findAll(text).map { it.value }.toList()

    fun isPunctuation(token: String): Boolean = token.length == 1 && !token[0].isLetterOrDigit()

    fun isSentenceEnd(token: String): Boolean = token == "." || token == "!" || token == "?" || token == "…"

    /** Rebuilds readable prose from model tokens. */
    fun detokenize(tokens: List<String>): String = buildString {
        tokens.forEach { t ->
            when {
                isEmpty() -> append(t)
                t == "—" -> append("—")
                isPunctuation(t) -> append(t)
                endsWith("—") -> append(t)
                else -> append(' ').append(t)
            }
        }
    }

    /** Removes dialogue in double quotes, leaving the narration. */
    fun narrationOnly(text: String): String =
        text.replace(Regex("\"[^\"\\n]*\"|“[^”\\n]*”"), " ")

    /** Total characters that sit inside double-quoted dialogue. */
    fun dialogueChars(text: String): Int =
        Regex("\"[^\"\\n]*\"|“[^”\\n]*”").findAll(text).sumOf { it.value.length }

    fun lastWords(text: String, n: Int): List<String> {
        val tokens = modelTokens(text.takeLast(400))
        return tokens.takeLast(n)
    }

    /** The paragraph that ends at or contains [cursor]. */
    fun paragraphRangeAt(text: String, cursor: Int): IntRange {
        if (text.isEmpty()) return IntRange.EMPTY
        val c = cursor.coerceIn(0, text.length)
        var start = text.lastIndexOf('\n', (c - 1).coerceAtLeast(0))
        start = if (start < 0) 0 else start + 1
        if (start > c) start = c
        var end = text.indexOf('\n', c)
        if (end < 0) end = text.length
        // Cursor sitting on an empty line: use the previous paragraph instead.
        if (text.substring(start, end).isBlank() && start > 0) {
            return paragraphRangeAt(text, start - 1)
        }
        return start until end
    }

    fun formatCount(n: Int): String = NumberFormat.getIntegerInstance(Locale.getDefault()).format(n)

    fun clip(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max).substringBeforeLast(' ') + "…"

    val STOPWORDS: Set<String> = """
        a about above after again against all almost also am among an and any are aren't around as at
        away back be became because been before being below between both but by came can can't cannot
        come could couldn't did didn't do does doesn't doing don't down during each even ever every few
        for from further get got had hadn't has hasn't have haven't having he he'd he'll he's her here
        here's hers herself him himself his how how's however i i'd i'll i'm i've if in into is isn't it
        it's its itself just know let let's like made make many may me might more most much must mustn't
        my myself never no nor not now of off oh ok okay on once one only or other ought our ours
        ourselves out over own perhaps quite rather really said same say says see seemed shan't she
        she'd she'll she's should shouldn't since so some something still such than that that's the
        their theirs them themselves then there there's these they they'd they'll they're they've thing
        things this those though through thus to too toward towards under until up upon us very was
        wasn't way we we'd we'll we're we've well went were weren't what what's when when's where
        where's whether which while who who's whom whose why why's will with within without won't would
        wouldn't yes yet you you'd you'll you're you've your yours yourself yourselves go going look
        looked back two three into onto just across along
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
}
