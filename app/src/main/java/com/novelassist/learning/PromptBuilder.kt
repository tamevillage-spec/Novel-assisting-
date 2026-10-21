package com.novelassist.learning

import com.novelassist.data.BibleCategory
import com.novelassist.data.BibleEntry
import com.novelassist.data.Chapter
import com.novelassist.data.Project
import kotlin.math.roundToInt

/**
 * Builds what the AI sees. The story bible, the living voice description, the
 * story so far and samples of the author's own prose are packed into a size
 * budget that suits the engine (small for on-device models, large for Claude).
 */
class PromptBuilder(private val project: Project, private val budgetChars: Int) {

    private val template = StoryStructures.byId(project.structureTemplateId)

    fun system(chapterId: String?, nearbyText: String): String = buildString {
        appendLine(
            "You are the writing partner inside Novel Assist. The author dictates their novel by voice " +
                "because an injury makes using their hands difficult, so every word you save them matters. " +
                "Your job is to help them write their book in their own voice, not yours."
        )
        appendLine()
        append(voiceSection())
        append(bibleSection(nearbyText))
        append(storySoFar(chapterId))
        append(structureSection())
        append(samplesSection(chapterId, nearbyText))
    }.trim()

    /**
     * A slim system prompt for learning: the chapter itself fills most of the
     * space, so only the premise, the voice notes and (optionally) prose samples go in.
     */
    fun learningSystem(chapterId: String?, includeSamples: Boolean): String = buildString {
        appendLine(
            "You help a novelist keep their story bible and voice profile up to date. " +
                "Be accurate and only report what the text supports."
        )
        if (project.bible.premise.isNotBlank()) appendLine("Premise: ${TextTools.clip(project.bible.premise, 400)}")
        if (project.bible.authorNotes.isNotBlank()) appendLine("The author on their style: ${TextTools.clip(project.bible.authorNotes, 400)}")
        if (includeSamples) append(samplesSection(chapterId, ""))
    }.trim()

    fun continuation(chapter: Chapter, textBefore: String, words: Int): String {
        val window = textBefore.takeLast((budgetChars * 0.3).roundToInt().coerceAtLeast(800))
        val midSentence = textBefore.isNotBlank() && !textBefore.trimEnd().last().let { it in ".!?…\"”" }
        return buildString {
            appendLine("Continue the chapter from exactly where the text stops. Write about $words words of new prose.")
            appendLine(
                "Match the author's voice: their sentence rhythm, point of view, tense, vocabulary and how much " +
                    "dialogue they use. Stay consistent with the story bible. Don't wrap up big story questions or " +
                    "bring in major new characters unless the text is clearly heading there."
            )
            if (midSentence) appendLine("The text stops mid-sentence, so begin by finishing that sentence.")
            if (textBefore.isBlank()) appendLine("This chapter is empty, so write its opening lines.")
            appendLine("Reply with only the new prose: no title, no notes, no quotation marks around it.")
            appendLine()
            appendLine("Chapter: ${chapter.title}")
            appendLine("<text>")
            appendLine(if (window.length < textBefore.length) "…$window" else window)
            appendLine("</text>")
        }
    }

    fun fixDictation(passage: String): String = buildString {
        appendLine(
            "This passage was dictated by voice, so it may contain speech-recognition mistakes: missing " +
                "punctuation, wrong capitals, misheard words, or spoken commands written out as words. Fix only " +
                "those problems. Keep the author's words, word order, voice and meaning. Don't restyle it, and " +
                "don't add or remove content. Use the names in the story bible to fix misheard names."
        )
        appendLine("Reply with only the corrected passage.")
        appendLine()
        appendLine("<passage>")
        appendLine(passage)
        appendLine("</passage>")
    }

    fun brainstorm(chapter: Chapter, textBefore: String): String = buildString {
        appendLine(
            "The author is stuck and wants ideas for what could happen next. Using the story so far, the bible " +
                "and where they are in the structure, suggest three different directions for the next scene, " +
                "each in one or two sentences. Number them 1, 2 and 3. Keep them short: they will be read aloud."
        )
        appendLine()
        appendLine("Chapter: ${chapter.title}")
        appendLine("<recent_text>")
        appendLine(textBefore.takeLast((budgetChars * 0.25).roundToInt().coerceAtLeast(600)))
        appendLine("</recent_text>")
    }

    fun askBible(question: String): String = buildString {
        appendLine(
            "The author is asking about their own story. Answer from the story bible and story so far. If the " +
                "answer isn't there, say it isn't recorded yet and suggest adding it to the bible. Answer in one " +
                "to three sentences, because it will be read aloud."
        )
        appendLine()
        appendLine("Question: $question")
    }

    fun learnChapter(chapter: Chapter): String {
        val known = project.bible.entries.joinToString("; ") { "${it.name} (${it.category.singular})" }
        val maxChapter = (budgetChars * 0.55).roundToInt()
        val text = if (chapter.text.length <= maxChapter) chapter.text else {
            // Small on-device models can't read a whole chapter: give them the opening and the ending.
            val head = chapter.text.take(maxChapter * 2 / 5)
            val tail = chapter.text.takeLast(maxChapter * 3 / 5)
            "$head\n[… middle of chapter omitted for length …]\n$tail"
        }
        val beats = template.beats.joinToString(", ") { it.name }
        return buildString {
            appendLine("Read this chapter and report what it establishes, so the story bible stays accurate.")
            appendLine("Reply with only JSON in exactly this shape:")
            appendLine(
                """{"summary": "two or three sentences on what happens", "beat": "one of: $beats", """ +
                    """"entities": [{"category": "character|place|plot_thread|theme|world_rule|timeline", """ +
                    """"name": "...", "description": "what this chapter establishes, one or two sentences"}], """ +
                    """"style_notes": "one sentence on anything distinctive in how this chapter is written"}"""
            )
            appendLine(
                "Include characters, places, ongoing plot threads, themes and world rules. For anything already " +
                    "in the bible, include it only if this chapter adds something new, and describe only the new " +
                    "information. Use names exactly as written in the text."
            )
            if (known.isNotBlank()) appendLine("Already in the bible: ${TextTools.clip(known, (budgetChars * 0.1).roundToInt())}")
            appendLine()
            appendLine("Chapter \"${chapter.title}\":")
            appendLine("<chapter>")
            appendLine(text)
            appendLine("</chapter>")
        }
    }

    fun voiceDescription(metricsText: String, trends: List<String>, styleNotes: String): String = buildString {
        appendLine(
            "Write a living style guide for this author: a description precise enough that another writer could " +
                "imitate them. Cover point of view, tense, sentence rhythm, dialogue, vocabulary, imagery, tone and " +
                "habits. If the trends show the voice changing, say how. Use the measurements, the author's own " +
                "notes, the previous description and the samples of their prose."
        )
        appendLine("Write one paragraph of 120 to 180 words about \"the author\". Reply with only the paragraph.")
        appendLine()
        appendLine("Measurements: $metricsText")
        if (trends.isNotEmpty()) appendLine("Trends: ${trends.joinToString(" ")}")
        if (styleNotes.isNotBlank()) appendLine("Notes from the latest chapter: $styleNotes")
        if (project.bible.voiceDescription.isNotBlank()) {
            appendLine("Previous description: ${project.bible.voiceDescription}")
        }
    }

    // ---- sections ----

    private fun voiceSection(): String = buildString {
        val bible = project.bible
        val combined = StyleAnalyzer.combine(project.styleHistory.map { it.metrics })
        if (bible.voiceDescription.isBlank() && bible.authorNotes.isBlank() && combined.wordCount == 0) return ""
        appendLine("## The author's voice")
        if (bible.voiceDescription.isNotBlank()) appendLine(TextTools.clip(bible.voiceDescription, 1400))
        if (bible.authorNotes.isNotBlank()) appendLine("In the author's own words: ${TextTools.clip(bible.authorNotes, 700)}")
        if (combined.wordCount > 0) appendLine("Measured: ${metricsLine(combined)}")
        appendLine()
    }

    private fun bibleSection(nearbyText: String): String {
        val bible = project.bible
        if (bible.entries.isEmpty() && bible.premise.isBlank()) return ""
        val limit = (budgetChars * 0.3).roundToInt()
        val nearby = nearbyText.takeLast(4000)
        val ranked = bible.entries.sortedWith(
            compareByDescending<BibleEntry> { it.pinned }
                .thenByDescending { e -> (listOf(e.name) + e.aliases).any { a -> a.isNotBlank() && nearby.contains(a, ignoreCase = true) } }
                .thenByDescending { it.mentionCount }
        )
        val chosen = mutableListOf<BibleEntry>()
        var used = 0
        for (e in ranked) {
            val cost = e.name.length + e.description.length.coerceAtMost(400) + 8
            if (used + cost > limit) continue
            chosen += e
            used += cost
        }
        return buildString {
            appendLine("## Story bible")
            if (bible.premise.isNotBlank()) appendLine("Premise: ${TextTools.clip(bible.premise, 600)}")
            BibleCategory.entries.forEach { cat ->
                val items = chosen.filter { it.category == cat }
                if (items.isEmpty()) return@forEach
                appendLine("${cat.label}:")
                items.forEach { e ->
                    val aka = if (e.aliases.isNotEmpty()) " (also: ${e.aliases.joinToString()})" else ""
                    appendLine("- ${e.name}$aka: ${TextTools.clip(e.description, 400)}")
                }
            }
            appendLine()
        }
    }

    private fun storySoFar(chapterId: String?): String {
        val index = project.chapters.indexOfFirst { it.id == chapterId }.let { if (it < 0) project.chapters.size else it }
        val earlier = project.chapters.take(index).filter { it.summary.isNotBlank() }
        if (earlier.isEmpty()) return ""
        val limit = (budgetChars * 0.15).roundToInt()
        val lines = earlier.map { "- ${it.title}: ${it.summary}" }.toMutableList()
        // Keep the most recent chapters in full; shorten older ones until it fits.
        var i = 0
        while (lines.sumOf { it.length } > limit && i < lines.size - 2) {
            lines[i] = TextTools.clip(lines[i], 90)
            i++
        }
        while (lines.sumOf { it.length } > limit && lines.size > 1) lines.removeAt(0)
        return "## Story so far\n" + lines.joinToString("\n") + "\n\n"
    }

    private fun structureSection(): String {
        val words = project.wordCount
        if (words == 0) return ""
        val pos = StoryStructures.position(template, words, project.targetWordCount)
        return buildString {
            appendLine("## Where the story is")
            append("${template.name}: about ${(pos.progress * 100).roundToInt()}% through a planned ")
            append("${TextTools.formatCount(project.targetWordCount)} words, in \"${pos.current.name}\" (${pos.current.description})")
            if (pos.next != null) append(" Next beat: ${pos.next.name}.")
            appendLine()
            appendLine()
        }
    }

    private fun samplesSection(chapterId: String?, nearbyText: String): String {
        val limit = (budgetChars * 0.15).roundToInt()
        if (limit < 300) return ""
        val tail = nearbyText.takeLast(1500)
        // The author's longer paragraphs from recent chapters, skipping what's already in view.
        val pool = project.chapters.asReversed()
            .flatMap { ch -> TextTools.paragraphs(ch.text).map { ch.id to it } }
            .filter { (_, p) -> p.length in 200..900 && !tail.contains(p.take(60)) }
            .take(30)
            .sortedByDescending { (id, p) -> (if (id == chapterId) 0 else 200) + p.length }
        val chosen = mutableListOf<String>()
        var used = 0
        for ((_, p) in pool) {
            if (used + p.length > limit) continue
            chosen += p
            used += p.length
            if (chosen.size == 3) break
        }
        if (chosen.isEmpty()) return ""
        return buildString {
            appendLine("## Samples of the author's own prose")
            chosen.forEach { appendLine("<sample>$it</sample>") }
            appendLine()
        }
    }

    companion object {
        fun metricsLine(m: com.novelassist.data.StyleMetrics): String = listOfNotNull(
            m.pointOfView.takeIf { it.isNotEmpty() },
            m.tense.takeIf { it.isNotEmpty() },
            "${m.avgSentenceLength.roundToInt()} words per sentence on average",
            "${StyleAnalyzer.pct(m.dialogueRatio)} dialogue",
            "vocabulary variety %.2f".format(m.lexicalDiversity),
            "%.1f adverbs per 100 words".format(m.adverbsPer100),
            "%.1f sentences per paragraph".format(m.sentencesPerParagraph),
            m.favouriteWords.takeIf { it.isNotEmpty() }?.let { "favourite words: ${it.take(8).joinToString()}" },
        ).joinToString("; ")
    }
}
