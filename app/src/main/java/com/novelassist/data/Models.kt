package com.novelassist.data

import kotlinx.serialization.Serializable
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
fun now(): Long = System.currentTimeMillis()

/** The kinds of things a story bible keeps track of. */
@Serializable
enum class BibleCategory(val label: String, val singular: String) {
    CHARACTER("Characters", "character"),
    PLACE("Places", "place"),
    PLOT_THREAD("Plot threads", "plot thread"),
    THEME("Themes", "theme"),
    WORLD_RULE("World rules", "world rule"),
    TIMELINE("Timeline", "event"),
    STYLE_NOTE("Style notes", "style note"),
    OTHER("Other", "note");

    companion object {
        fun fromLoose(text: String?): BibleCategory {
            val t = text.orEmpty().lowercase()
            return when {
                t.startsWith("char") || t == "person" || t == "people" -> CHARACTER
                t.startsWith("place") || t.startsWith("loc") || t == "setting" -> PLACE
                t.contains("thread") || t.contains("plot") || t.contains("arc") -> PLOT_THREAD
                t.startsWith("theme") -> THEME
                t.contains("rule") || t.contains("world") || t.contains("magic") -> WORLD_RULE
                t.contains("time") || t.contains("event") -> TIMELINE
                t.contains("style") || t.contains("voice") -> STYLE_NOTE
                else -> OTHER
            }
        }
    }
}

@Serializable
data class BibleEntry(
    val id: String = newId(),
    val category: BibleCategory,
    val name: String,
    val description: String = "",
    val aliases: List<String> = emptyList(),
    val firstChapterId: String? = null,
    val mentionCount: Int = 0,
    /** Pinned entries are always given to the AI, even when not mentioned nearby. */
    val pinned: Boolean = false,
    val createdAt: Long = now(),
    val updatedAt: Long = now(),
)

/** Something the learning engine noticed and wants the author to approve. */
@Serializable
data class BibleProposal(
    val id: String = newId(),
    val category: BibleCategory,
    val name: String,
    val description: String,
    /** Set when this proposal adds detail to an existing entry. */
    val existingEntryId: String? = null,
    val sourceChapterId: String? = null,
    val reason: String = "",
    val fromAi: Boolean = false,
    val createdAt: Long = now(),
)

/** One dated version of the living description of the author's prose. */
@Serializable
data class VoiceVersion(
    val text: String,
    val createdAt: Long = now(),
    val chapterCount: Int = 0,
    val wordCount: Int = 0,
    val source: String = "",
)

@Serializable
data class StoryBible(
    val premise: String = "",
    val entries: List<BibleEntry> = emptyList(),
    /** The current living description of how this author writes. */
    val voiceDescription: String = "",
    /** Every earlier version, oldest first, so the evolution is visible. */
    val voiceHistory: List<VoiceVersion> = emptyList(),
    /** The author's own words about their style and intentions. */
    val authorNotes: String = "",
)

@Serializable
data class StyleMetrics(
    val wordCount: Int = 0,
    val sentenceCount: Int = 0,
    val paragraphCount: Int = 0,
    val avgSentenceLength: Double = 0.0,
    val sentenceLengthSpread: Double = 0.0,
    val avgWordLength: Double = 0.0,
    val lexicalDiversity: Double = 0.0,
    val dialogueRatio: Double = 0.0,
    val adverbsPer100: Double = 0.0,
    val questionRate: Double = 0.0,
    val exclamationRate: Double = 0.0,
    val commasPerSentence: Double = 0.0,
    val dashesPer1000: Double = 0.0,
    val sentencesPerParagraph: Double = 0.0,
    val pointOfView: String = "",
    val tense: String = "",
    val favouriteWords: List<String> = emptyList(),
    val signaturePhrases: List<String> = emptyList(),
)

@Serializable
data class StyleSnapshot(
    val chapterId: String,
    val chapterTitle: String,
    val chapterIndex: Int,
    val takenAt: Long = now(),
    val metrics: StyleMetrics,
)

@Serializable
data class Chapter(
    val id: String = newId(),
    val title: String,
    val text: String = "",
    val summary: String = "",
    /** The story beat this chapter plays, as learned or labelled. */
    val beat: String = "",
    /** True when the author set the beat themselves, so learning won't overwrite it. */
    val beatIsManual: Boolean = false,
    val createdAt: Long = now(),
    val updatedAt: Long = now(),
    val learnedAt: Long = 0,
    val learnedTextHash: Int = 0,
) {
    val needsLearning: Boolean get() = text.isNotBlank() && learnedTextHash != text.hashCode()
}

@Serializable
data class LearningEvent(
    val at: Long = now(),
    val chapterTitle: String,
    val summary: String,
)

@Serializable
data class Project(
    val id: String = newId(),
    val title: String,
    val createdAt: Long = now(),
    val updatedAt: Long = now(),
    val chapters: List<Chapter> = listOf(Chapter(title = "Chapter 1")),
    val bible: StoryBible = StoryBible(),
    val styleHistory: List<StyleSnapshot> = emptyList(),
    val proposals: List<BibleProposal> = emptyList(),
    val structureTemplateId: String = "three_act",
    val targetWordCount: Int = 80_000,
    val learningLog: List<LearningEvent> = emptyList(),
    /** Names the author turned down, so they aren't proposed again. */
    val rejectedNames: List<String> = emptyList(),
) {
    val wordCount: Int get() = chapters.sumOf { com.novelassist.learning.TextTools.wordCount(it.text) }

    fun chapter(id: String?): Chapter? = chapters.firstOrNull { it.id == id }

    fun updateChapter(id: String, change: (Chapter) -> Chapter): Project =
        copy(chapters = chapters.map { if (it.id == id) change(it) else it }, updatedAt = now())
}

data class ProjectSummary(
    val id: String,
    val title: String,
    val chapterCount: Int,
    val wordCount: Int,
    val updatedAt: Long,
)
