package com.novelassist.voice

import com.novelassist.learning.TextTools

/**
 * Turns raw speech-recognition text into prose: spoken punctuation ("comma",
 * "new paragraph") becomes symbols, and each new chunk joins the existing text
 * with sensible spacing and capitals.
 */
object DictationFormatter {

    private data class Spoken(val pattern: Regex, val symbol: String)

    // Determiners before "period"/"comma"/"colon" mean the word is a noun ("a period of time").
    private const val NOT_AFTER = "(?<!\\b(?:a|an|the|this|that|of|one|every|each|my|his|her|their|our|your|its)\\s)"

    private val spoken = listOf(
        Spoken(Regex("\\b(?:new|next) paragraph\\b", RegexOption.IGNORE_CASE), "\n\n"),
        Spoken(Regex("\\bnew line\\b", RegexOption.IGNORE_CASE), "\n"),
        Spoken(Regex("\\bquestion mark\\b", RegexOption.IGNORE_CASE), "?"),
        Spoken(Regex("\\bexclamation (?:mark|point)\\b", RegexOption.IGNORE_CASE), "!"),
        Spoken(Regex("\\bfull stop\\b", RegexOption.IGNORE_CASE), "."),
        Spoken(Regex("$NOT_AFTER\\bperiod\\b(?=\\s*$|\\s+[\\p{L}])", RegexOption.IGNORE_CASE), "."),
        Spoken(Regex("$NOT_AFTER\\bcomma\\b", RegexOption.IGNORE_CASE), ","),
        Spoken(Regex("\\bsemi ?colon\\b", RegexOption.IGNORE_CASE), ";"),
        Spoken(Regex("$NOT_AFTER\\bcolon\\b", RegexOption.IGNORE_CASE), ":"),
        Spoken(Regex("\\b(?:ellipsis|dot dot dot)\\b", RegexOption.IGNORE_CASE), "…"),
        Spoken(Regex("\\b(?:em dash|long dash)\\b", RegexOption.IGNORE_CASE), "—"),
        Spoken(Regex("\\b(?:open|begin|start) quote\\b", RegexOption.IGNORE_CASE), "“"),
        Spoken(Regex("\\b(?:close|end) quote\\b|\\bunquote\\b", RegexOption.IGNORE_CASE), "”"),
        Spoken(Regex("\\b(?:open) (?:bracket|paren|parenthesis)\\b", RegexOption.IGNORE_CASE), "("),
        Spoken(Regex("\\b(?:close) (?:bracket|paren|parenthesis)\\b", RegexOption.IGNORE_CASE), ")"),
    )

    fun applySpokenPunctuation(text: String): String {
        var s = text
        spoken.forEach { s = it.pattern.replace(s, it.symbol) }
        return tidy(s)
    }

    /** Fixes spacing around punctuation and capitalises after sentence ends. */
    fun tidy(text: String): String {
        var s = text
        s = s.replace(Regex("[ \\t]+"), " ")
        s = s.replace(Regex(" *\\n *"), "\n")
        s = s.replace(Regex(" +([,.;:!?…)”])"), "$1")
        s = s.replace(Regex("([(“]) +"), "$1")
        s = s.replace(Regex("([,;:!?…])(?=[\\p{L}“])"), "$1 ")
        s = s.replace(Regex("\\.(?=[\\p{L}“])"), ". ")
        s = s.replace(Regex("([\\p{L}0-9,.!?…])“"), "$1 “")
        s = s.replace(Regex("”(?=[\\p{L}])"), "” ")
        s = s.replace(" — ", "—").replace(Regex(" ?— ?"), "—")
        s = s.replace(Regex("\\bi\\b"), "I").replace(Regex("\\bi(['’](?:m|d|ll|ve))\\b"), "I$1")
        s = Regex("([.!?…][”\"]?\\s+|\\n\\s*|^\\s*“)(\\p{Ll})").replace(s) { m ->
            m.groupValues[1] + m.groupValues[2].uppercase()
        }
        return s
    }

    /**
     * Joins a new dictated chunk onto the text before the cursor. Returns the
     * text to insert (with any leading space).
     */
    fun prepareInsert(before: String, chunk: String, spokenPunctuation: Boolean): String {
        var insert = chunk.trim()
        if (insert.isEmpty()) return ""
        if (spokenPunctuation) insert = applySpokenPunctuation(insert) else insert = tidy(insert)
        if (insert.isEmpty()) return ""

        val trimmedBefore = before.trimEnd(' ', '\t')
        val startsWithBreak = insert.startsWith("\n")
        val startsSentence = trimmedBefore.isEmpty() ||
            trimmedBefore.endsWith("\n") ||
            Regex("[.!?…][”\"’')]?$").containsMatchIn(trimmedBefore) ||
            (trimmedBefore.endsWith("“") && trimmedBefore.dropLast(1).trimEnd().let { it.isEmpty() || it.last() in ".!?…\n" })

        val firstWordIndex = insert.indexOfFirst { it.isLetter() }
        if (firstWordIndex >= 0) {
            val firstWord = insert.drop(firstWordIndex).takeWhile { it.isLetter() || it == '\'' || it == '’' }
            insert = if (startsSentence) {
                insert.substring(0, firstWordIndex) + insert[firstWordIndex].uppercase() + insert.substring(firstWordIndex + 1)
            } else if (firstWord.length > 1 && firstWord[0].isUpperCase() && firstWord.drop(1).all { it.isLowerCase() || it == '\'' || it == '’' } &&
                firstWord.lowercase() in TextTools.STOPWORDS && firstWord.lowercase() != "i" && !firstWord.lowercase().startsWith("i'") && !firstWord.lowercase().startsWith("i’")
            ) {
                // Recognisers capitalise each utterance; mid-sentence common words go back to lower case.
                insert.substring(0, firstWordIndex) + insert[firstWordIndex].lowercase() + insert.substring(firstWordIndex + 1)
            } else insert
        }

        val needsSpace = before.isNotEmpty() &&
            !before.last().isWhitespace() &&
            before.last() !in "“(—\n" &&
            !startsWithBreak &&
            insert.first() !in ",.;:!?…)”—"
        return (if (needsSpace) " " else "") + insert
    }
}
