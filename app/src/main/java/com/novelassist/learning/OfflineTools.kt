package com.novelassist.learning

import com.novelassist.data.BibleCategory
import com.novelassist.data.Project

/** Simple, no-AI versions of the assistant's helpers, so everything works offline. */
object OfflineTools {

    /** Tidies dictated text: capitals, spacing, "i" → "I", known name spellings. */
    fun cleanDictation(passage: String, names: List<String>): String {
        var s = passage.replace(Regex("[ \\t]+"), " ")
        s = s.replace(Regex(" +([,.;:!?…])"), "$1")
        s = s.replace(Regex("([,;:])(?=[\\p{L}])"), "$1 ")
        s = s.replace(Regex("([.!?])(?=[\\p{L}])"), "$1 ")
        s = s.replace(Regex("\\bi\\b"), "I")
        s = s.replace(Regex("\\bi(['’](?:m|d|ll|ve))\\b"), "I$1")
        names.filter { it.length > 2 }.forEach { name ->
            s = s.replace(Regex("\\b${Regex.escape(name)}\\b", RegexOption.IGNORE_CASE), name)
        }
        // Capitalise the start of each sentence and paragraph.
        s = Regex("(^|[.!?…]\\s+|\\n\\s*)([\"“‘(]?)(\\p{Ll})").replace(s) { m ->
            m.groupValues[1] + m.groupValues[2] + m.groupValues[3].uppercase()
        }
        s = s.trimEnd()
        if (s.isNotEmpty() && s.last().isLetterOrDigit()) s += "."
        return s
    }

    /** Answers a question by searching the story bible and chapter summaries. */
    fun answer(project: Project, question: String): String {
        val terms = TextTools.words(question).map { it.lowercase() }
            .filter { it.length > 2 && it !in TextTools.STOPWORDS }
        if (terms.isEmpty()) return "Ask me about a character, place or event in your story."
        val entryHits = project.bible.entries.map { e ->
            val hay = (e.name + " " + e.aliases.joinToString(" ") + " " + e.description).lowercase()
            e to terms.count { hay.contains(it) } + if (terms.any { e.name.lowercase().contains(it) }) 3 else 0
        }.filter { it.second > 0 }.sortedByDescending { it.second }
        if (entryHits.isNotEmpty()) {
            return entryHits.take(2).joinToString(" ") { (e, _) ->
                "${e.name}: ${e.description.ifBlank { "is in the bible but has no description yet." }}"
            }
        }
        val chapterHit = project.chapters.map { ch ->
            ch to terms.count { ch.summary.lowercase().contains(it) || ch.text.lowercase().contains(it) }
        }.filter { it.second > 0 }.maxByOrNull { it.second }
        return if (chapterHit != null) {
            val ch = chapterHit.first
            "That comes up in ${ch.title}." + if (ch.summary.isNotBlank()) " ${ch.summary}" else ""
        } else {
            "I can't find that in the story bible yet. You can add it by saying “bible note” and then the detail."
        }
    }

    /** Ideas built from the structure template, the cast and the open plot threads. */
    fun brainstorm(project: Project): String {
        val template = StoryStructures.byId(project.structureTemplateId)
        val pos = StoryStructures.position(template, project.wordCount, project.targetWordCount)
        val characters = project.bible.entries.filter { it.category == BibleCategory.CHARACTER }
            .sortedByDescending { it.mentionCount }.map { it.name }
        val threads = project.bible.entries.filter { it.category == BibleCategory.PLOT_THREAD }.map { it.name }
        val places = project.bible.entries.filter { it.category == BibleCategory.PLACE }.map { it.name }
        val hero = characters.getOrNull(0) ?: "your main character"
        val other = characters.getOrNull(1) ?: "someone they trust"
        val place = places.firstOrNull() ?: "somewhere unexpected"
        val target = pos.next ?: pos.current
        val ideas = mutableListOf(
            "Work toward the ${target.name.lowercase()}: ${target.description}",
            "Put $hero and $other in a room where one of them must admit something they've been hiding.",
            if (threads.isNotEmpty()) "Pull on the thread “${threads.first()}”: make it cost $hero something now."
            else "Bring back a detail from an early chapter and show it meant more than it seemed.",
            "Move the next scene to $place and let the setting create the problem.",
        )
        return ideas.take(3).mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
    }
}
