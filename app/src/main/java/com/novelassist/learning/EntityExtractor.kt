package com.novelassist.learning

import com.novelassist.data.BibleCategory
import com.novelassist.data.BibleEntry

/**
 * Finds the names of people and places in a chapter without any AI, so the
 * story bible can grow even fully offline. It looks for capitalised words that
 * aren't just the start of a sentence, then uses nearby words ("said",
 * "in the", "Captain") to guess what kind of thing each one is.
 */
object EntityExtractor {

    data class Candidate(
        val name: String,
        val category: BibleCategory,
        val count: Int,
        val firstMention: String,
    )

    private val NAME = Regex("\\b(?:(?:Mr|Mrs|Ms|Dr|St)\\.?\\s+)?[\\p{Lu}][\\p{L}’'-]+(?:\\s+(?:of\\s+|de\\s+|van\\s+|von\\s+)?[\\p{Lu}][\\p{L}’'-]+)*")
    private val SPEECH_VERBS = "said|asked|replied|whispered|shouted|muttered|murmured|called|cried|answered|thought|smiled|nodded|laughed|sighed|frowned|grinned|snapped|yelled|told|insisted|admitted|wondered"
    private val TITLES = setOf(
        "mr", "mrs", "ms", "miss", "dr", "doctor", "lord", "lady", "captain", "king", "queen", "prince",
        "princess", "aunt", "uncle", "sir", "madam", "father", "mother", "sister", "brother", "professor",
        "detective", "officer", "sergeant", "general", "master", "mistress", "grandma", "grandpa",
    )
    private val PLACE_WORDS = setOf(
        "street", "road", "lane", "avenue", "city", "town", "village", "river", "mountain", "mountains",
        "forest", "wood", "woods", "castle", "hall", "lake", "sea", "bay", "valley", "isle", "island",
        "kingdom", "inn", "tavern", "station", "park", "square", "bridge", "tower", "palace", "harbour",
        "harbor", "port", "hill", "hills", "desert", "marsh", "falls", "manor", "abbey", "church", "school",
        "academy", "hospital", "cafe", "café", "bar", "market", "county", "empire", "republic", "coast",
    )
    private val PLACE_PREPOSITIONS = Regex("\\b(?:in|at|to|from|into|toward|towards|across|through|near|outside|inside|beyond|reached|left|visited)\\s+(?:the\\s+)?$")
    private val NOT_NAMES = setOf(
        "i", "i'm", "i'd", "i'll", "i've", "the", "a", "an", "and", "but", "or", "so", "then", "when", "while",
        "if", "he", "she", "they", "we", "you", "it", "his", "her", "their", "our", "my", "your", "its",
        "this", "that", "these", "those", "there", "here", "what", "why", "how", "who", "where", "which",
        "yes", "no", "not", "oh", "ah", "okay", "ok", "well", "now", "just", "maybe", "perhaps", "still",
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "january", "february",
        "march", "april", "may", "june", "july", "august", "september", "october", "november", "december",
        "god", "chapter", "part", "book", "after", "before", "as", "at", "in", "on", "for", "with", "from",
        "even", "only", "every", "all", "some", "one", "two", "nothing", "everything", "someone", "something",
        "sorry", "please", "thanks", "hello", "hey", "hi", "good", "great", "come", "let", "don't", "can't",
        "won't", "didn't", "wasn't", "isn't", "it's", "that's", "there's", "what's", "she'd", "he'd", "they'd",
    ) + TextTools.STOPWORDS

    fun extract(text: String, minCount: Int = 2): List<Candidate> {
        if (text.isBlank()) return emptyList()
        val counts = LinkedHashMap<String, Int>()
        val firstAt = HashMap<String, Int>()
        val sentenceStarts = sentenceStartIndices(text)

        NAME.findAll(text).forEach { m ->
            var name = m.value.trim().trimEnd('’', '\'', '-')
            if (name.endsWith("’s") || name.endsWith("'s")) name = name.dropLast(2)
            val words = name.split(Regex("\\s+"))
            // Drop a sentence-initial common word ("The Harbour" -> "Harbour"? keep; "Then Mara" -> "Mara").
            val cleaned = words.dropWhile { it.lowercase() in NOT_NAMES && it.lowercase() !in TITLES }
            if (cleaned.isEmpty()) return@forEach
            val candidate = cleaned.joinToString(" ")
            val lowerFirst = cleaned.first().lowercase().trimEnd('.')
            if (cleaned.size == 1 && (lowerFirst in NOT_NAMES || lowerFirst in TITLES)) return@forEach
            if (candidate.length < 2) return@forEach
            val start = m.range.first + (m.value.length - m.value.trimStart().length)
            val atSentenceStart = start in sentenceStarts && cleaned.size == words.size
            // A word that only ever appears at the start of a sentence is probably not a name.
            if (atSentenceStart && cleaned.size == 1 && !appearsMidSentence(text, candidate, sentenceStarts)) return@forEach
            counts[candidate] = (counts[candidate] ?: 0) + 1
            if (candidate !in firstAt) firstAt[candidate] = m.range.first
        }

        val shortForms = mergeShortForms(counts)

        return counts.entries
            .filter { it.value >= minCount }
            .map { (name, count) ->
                Candidate(
                    name = name,
                    category = classify(text, listOf(name) + shortForms[name].orEmpty()),
                    count = count,
                    firstMention = sentenceAround(text, firstAt[name] ?: text.indexOf(name)),
                )
            }
            .sortedByDescending { it.count }
    }

    /** True when [name] (or one of its aliases) is already in the bible. */
    fun isKnown(name: String, entries: List<BibleEntry>): BibleEntry? {
        val n = name.lowercase()
        return entries.firstOrNull { e ->
            val names = (listOf(e.name) + e.aliases).map { it.lowercase() }
            names.any { it == n || it.split(" ").contains(n) || n.split(" ").contains(it) }
        }
    }

    fun countMentions(text: String, entry: BibleEntry): Int =
        (listOf(entry.name) + entry.aliases).filter { it.isNotBlank() }.sumOf { alias ->
            Regex("\\b${Regex.escape(alias)}\\b", RegexOption.IGNORE_CASE).findAll(text).count()
        }

    /** Scores every form of the name ("Mara Vance" and "Mara") for person and place clues. */
    private fun classify(text: String, forms: List<String>): BibleCategory {
        val words = forms.first().lowercase().split(" ")
        if (words.first().trimEnd('.') in TITLES) return BibleCategory.CHARACTER
        if (words.any { it in PLACE_WORDS }) return BibleCategory.PLACE
        var person = 0
        var place = 0
        for (form in forms) {
            val esc = Regex.escape(form)
            person += Regex("\\b$esc\\s+(?:$SPEECH_VERBS)\\b").findAll(text).count() * 2
            person += Regex("\\b(?:$SPEECH_VERBS)\\s+$esc\\b").findAll(text).count() * 2
            person += Regex("\\b$esc(?:’s|'s)\\s+(?:eyes|face|hand|hands|voice|mouth|hair|smile|mother|father|heart)").findAll(text).count()
            person += Regex("\\b$esc\\s+(?:was|had|felt|knew|looked|turned|walked|ran|stood|sat|came|went|thanked|woke|smiled)\\b").findAll(text).count()
            person += Regex(",\\s*$esc[.!?,]").findAll(text).count()
            Regex("\\b$esc\\b").findAll(text).forEach { m ->
                val before = text.substring((m.range.first - 30).coerceAtLeast(0), m.range.first)
                if (PLACE_PREPOSITIONS.containsMatchIn(before)) place += 2
            }
        }
        return when {
            person > place && person > 0 -> BibleCategory.CHARACTER
            place > person -> BibleCategory.PLACE
            else -> BibleCategory.OTHER
        }
    }

    /**
     * "Mara" and "Mara Vance" are the same person: fold the short form into the
     * full name. Returns the short forms found for each full name.
     */
    private fun mergeShortForms(counts: LinkedHashMap<String, Int>): Map<String, List<String>> {
        val names = counts.keys.toList()
        val forms = HashMap<String, MutableList<String>>()
        for (short in names) {
            if (short.contains(' ')) continue
            val full = names.filter { it != short && it.split(' ').contains(short) }
            if (full.size == 1) {
                counts[full[0]] = (counts[full[0]] ?: 0) + (counts[short] ?: 0)
                counts.remove(short)
                forms.getOrPut(full[0]) { mutableListOf() } += short
            }
        }
        return forms
    }

    private fun sentenceStartIndices(text: String): Set<Int> {
        val result = HashSet<Int>()
        var expectStart = true
        text.forEachIndexed { i, c ->
            if (expectStart && c.isLetter()) {
                result += i
                expectStart = false
            } else if (c == '.' || c == '!' || c == '?' || c == '\n' || c == '…' || c == '"' || c == '“' || c == '”') {
                expectStart = true
            } else if (c.isLetterOrDigit()) {
                expectStart = false
            }
        }
        return result
    }

    private fun appearsMidSentence(text: String, name: String, starts: Set<Int>): Boolean =
        Regex("\\b${Regex.escape(name)}\\b").findAll(text).any { it.range.first !in starts }

    private fun sentenceAround(text: String, index: Int): String {
        if (index < 0) return ""
        val start = text.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'), index).let { if (it < 0) 0 else it + 1 }
        val endIdx = text.indexOfAny(charArrayOf('.', '!', '?', '\n'), index).let { if (it < 0) text.length else it + 1 }
        return TextTools.clip(text.substring(start, endIdx).trim(), 220)
    }
}
