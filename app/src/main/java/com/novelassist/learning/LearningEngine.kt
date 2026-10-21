package com.novelassist.learning

import com.novelassist.ai.AiEngine
import com.novelassist.ai.AiException
import com.novelassist.ai.AiRequest
import com.novelassist.ai.AiTask
import com.novelassist.data.BibleCategory
import com.novelassist.data.BibleProposal
import com.novelassist.data.Chapter
import com.novelassist.data.LearningEvent
import com.novelassist.data.Project
import com.novelassist.data.StyleSnapshot
import com.novelassist.data.VoiceVersion
import com.novelassist.data.now
import kotlinx.coroutines.flow.last

data class LearnResult(
    val project: Project,
    val message: String,
    val newProposals: Int,
)

/**
 * The slow-learning loop. Each time a chapter is learned:
 *  1. its style is measured and added to the prose history,
 *  2. new people, places and threads are proposed for the story bible,
 *  3. it gets a summary and a story-beat label,
 *  4. the living description of the author's voice is rewritten.
 * Steps 2-4 use AI when one is set up, and simpler on-phone methods otherwise.
 */
class LearningEngine {

    suspend fun learn(project: Project, chapterId: String, engine: AiEngine?): LearnResult {
        val chapter = project.chapter(chapterId) ?: return LearnResult(project, "Chapter not found.", 0)
        if (chapter.text.isBlank()) return LearnResult(project, "${chapter.title} is empty, so there's nothing to learn yet.", 0)
        val index = project.chapters.indexOf(chapter)
        val notes = mutableListOf<String>()

        // 1. Style
        val metrics = StyleAnalyzer.analyze(chapter.text)
        var p = project.copy(
            styleHistory = (project.styleHistory.filter { it.chapterId != chapterId } +
                StyleSnapshot(chapterId, chapter.title, index, metrics = metrics)).sortedBy { it.chapterIndex },
        )
        p = refreshMentions(p)

        // 2. Offline name spotting
        val proposals = heuristicProposals(p, chapter).toMutableList()

        // 3. AI reading of the chapter
        var learned = LearnedChapter()
        var aiError: String? = null
        if (engine != null) {
            try {
                val builder = PromptBuilder(p, engine.contextBudgetChars)
                val reply = engine.stream(
                    AiRequest(
                        task = AiTask.LEARN,
                        system = builder.learningSystem(chapterId, includeSamples = false),
                        prompt = builder.learnChapter(chapter),
                        maxOutputTokens = 900,
                        temperature = 0.2,
                    )
                ).last()
                learned = AiJson.parseLearn(reply)
                mergeAiEntities(p, chapter, learned.entities, proposals)
            } catch (e: AiException) {
                aiError = e.message
            }
        }

        val template = StoryStructures.byId(p.structureTemplateId)
        val position = StoryStructures.chapterPositions(p.chapters, p.targetWordCount).getOrElse(index) { 0.0 }
        val summary = learned.summary.ifBlank { extractiveSummary(chapter.text) }
        val beat = learned.beat.ifBlank { StoryStructures.expectedBeat(template, position).name }
        p = p.updateChapter(chapterId) {
            it.copy(
                summary = summary,
                beat = if (it.beatIsManual) it.beat else beat,
                learnedAt = now(),
                learnedTextHash = it.text.hashCode(),
            )
        }

        // Keep pending proposals unique.
        val pending = p.proposals.toMutableList()
        var added = 0
        for (prop in proposals) {
            val dup = pending.indexOfFirst {
                it.name.equals(prop.name, true) && it.category == prop.category && it.existingEntryId == prop.existingEntryId
            }
            if (dup >= 0) {
                if (prop.fromAi && !pending[dup].fromAi) pending[dup] = prop
            } else {
                pending += prop
                added++
            }
        }
        p = p.copy(proposals = pending)

        // 4. The living voice description
        val combined = StyleAnalyzer.combine(p.styleHistory.map { it.metrics })
        val trends = StyleAnalyzer.trends(p.styleHistory)
        var voice = StyleAnalyzer.describe(combined) +
            (if (trends.isNotEmpty()) " Lately: " + trends.joinToString(" ") else "")
        var voiceSource = "measurements"
        if (engine != null && aiError == null && combined.wordCount >= 150) {
            try {
                val builder = PromptBuilder(p, engine.contextBudgetChars)
                val reply = engine.stream(
                    AiRequest(
                        task = AiTask.VOICE,
                        system = builder.learningSystem(chapterId, includeSamples = true),
                        prompt = builder.voiceDescription(PromptBuilder.metricsLine(combined), trends, learned.styleNotes),
                        maxOutputTokens = 400,
                        temperature = 0.4,
                    )
                ).last().trim()
                if (reply.length > 80) {
                    voice = reply
                    voiceSource = engine.displayName
                }
            } catch (e: AiException) {
                aiError = e.message
            }
        }
        if (voice != p.bible.voiceDescription) {
            val history = (p.bible.voiceHistory + VoiceVersion(
                text = voice,
                chapterCount = p.styleHistory.size,
                wordCount = combined.wordCount,
                source = voiceSource,
            )).takeLast(60)
            p = p.copy(bible = p.bible.copy(voiceDescription = voice, voiceHistory = history))
            notes += "voice profile updated"
        }

        if (added > 0) notes += "$added new thing${if (added == 1) "" else "s"} for the story bible to review"
        val words = TextTools.formatCount(metrics.wordCount)
        val message = buildString {
            append("Learned ${chapter.title} ($words words)")
            if (notes.isNotEmpty()) append(": ").append(notes.joinToString(", "))
            append(".")
            if (aiError != null) append(" AI wasn't available, so I used the built-in learning. ($aiError)")
        }
        p = p.copy(learningLog = (p.learningLog + LearningEvent(chapterTitle = chapter.title, summary = message)).takeLast(100))
        return LearnResult(p, message, added)
    }

    private fun refreshMentions(p: Project): Project {
        if (p.bible.entries.isEmpty()) return p
        val all = p.chapters.joinToString("\n") { it.text }
        val entries = p.bible.entries.map { e -> e.copy(mentionCount = EntityExtractor.countMentions(all, e)) }
        return p.copy(bible = p.bible.copy(entries = entries))
    }

    private fun heuristicProposals(p: Project, chapter: Chapter): List<BibleProposal> {
        val rejected = p.rejectedNames.map { it.lowercase() }.toSet()
        return EntityExtractor.extract(chapter.text)
            .filter { EntityExtractor.isKnown(it.name, p.bible.entries) == null }
            .filter { it.name.lowercase() !in rejected }
            .filter { it.category != BibleCategory.OTHER || it.count >= 4 }
            .take(12)
            .map {
                BibleProposal(
                    category = it.category,
                    name = it.name,
                    description = if (it.firstMention.isNotBlank()) "First mention: ${it.firstMention}" else "",
                    sourceChapterId = chapter.id,
                    reason = "Mentioned ${it.count} times in ${chapter.title}",
                )
            }
    }

    private fun mergeAiEntities(
        p: Project,
        chapter: Chapter,
        entities: List<LearnedEntity>,
        proposals: MutableList<BibleProposal>,
    ) {
        for (e in entities.take(25)) {
            if (e.description.isBlank()) continue
            val known = EntityExtractor.isKnown(e.name, p.bible.entries)
            if (known != null) {
                // Only propose an update when the AI found something not already written down.
                val overlap = e.description.lowercase().split(Regex("\\W+")).filter { it.length > 4 }
                val existing = known.description.lowercase()
                val newWords = overlap.count { it !in existing }
                if (overlap.isNotEmpty() && newWords >= overlap.size / 2) {
                    proposals += BibleProposal(
                        category = known.category,
                        name = known.name,
                        description = e.description,
                        existingEntryId = known.id,
                        sourceChapterId = chapter.id,
                        reason = "New detail from ${chapter.title}",
                        fromAi = true,
                    )
                }
            } else {
                if (p.rejectedNames.any { it.equals(e.name, true) }) continue
                proposals.removeAll { it.name.equals(e.name, true) && !it.fromAi }
                proposals += BibleProposal(
                    category = e.category,
                    name = e.name,
                    description = e.description,
                    sourceChapterId = chapter.id,
                    reason = "Noticed in ${chapter.title}",
                    fromAi = true,
                )
            }
        }
    }

    companion object {
        /** A no-AI summary: the opening and closing lines of the chapter. */
        fun extractiveSummary(text: String): String {
            val sentences = TextTools.sentences(text)
            if (sentences.isEmpty()) return ""
            if (sentences.size <= 2) return TextTools.clip(sentences.joinToString(" "), 300)
            return TextTools.clip(sentences.first(), 150) + " … " + TextTools.clip(sentences.last(), 150)
        }
    }
}
