package com.novelassist.learning

import com.novelassist.data.Chapter
import com.novelassist.data.Project
import kotlin.math.abs
import kotlin.math.roundToInt

data class Beat(val name: String, val position: Double, val description: String)

data class StructureTemplate(
    val id: String,
    val name: String,
    val description: String,
    val beats: List<Beat>,
)

data class StoryPosition(
    val progress: Double,
    val current: Beat,
    val next: Beat?,
    val wordsToNext: Int,
)

/** Common story shapes, with each beat placed at its usual point in the book. */
object StoryStructures {
    val threeAct = StructureTemplate(
        "three_act", "Three-act structure",
        "Setup, confrontation, resolution. The classic shape most novels follow.",
        listOf(
            Beat("Setup", 0.0, "Show the hero's ordinary world, what they want, and what's wrong."),
            Beat("Inciting incident", 0.12, "Something happens that knocks life off balance."),
            Beat("Plot point one", 0.25, "The hero commits and steps into the main conflict. No going back."),
            Beat("Rising action", 0.30, "Obstacles grow. Allies and enemies show themselves."),
            Beat("Midpoint", 0.50, "A major reveal or reversal. The hero shifts from reacting to acting."),
            Beat("Plot point two", 0.75, "Everything falls apart. The lowest point."),
            Beat("Climax", 0.88, "The final confrontation, where the hero uses everything they've learned."),
            Beat("Resolution", 0.96, "The new normal. Show how the hero and world have changed."),
        ),
    )

    val saveTheCat = StructureTemplate(
        "save_the_cat", "Save the Cat (15 beats)",
        "A detailed beat sheet popular with novelists and screenwriters.",
        listOf(
            Beat("Opening image", 0.0, "A snapshot of the hero's life before the story changes it."),
            Beat("Theme stated", 0.05, "Someone hints at the lesson the hero must learn."),
            Beat("Setup", 0.06, "The hero's world, flaws, and what's missing."),
            Beat("Catalyst", 0.10, "The life-changing event."),
            Beat("Debate", 0.12, "The hero hesitates: should they go?"),
            Beat("Break into two", 0.20, "The hero chooses to enter a new world or situation."),
            Beat("B story", 0.22, "A new relationship that carries the theme."),
            Beat("Fun and games", 0.25, "The promise of the premise: the story readers came for."),
            Beat("Midpoint", 0.50, "A false victory or false defeat. Stakes rise."),
            Beat("Bad guys close in", 0.55, "Pressure from outside and doubt from within."),
            Beat("All is lost", 0.75, "The lowest moment, often with a sense of death or loss."),
            Beat("Dark night of the soul", 0.78, "The hero wallows, then finds the truth."),
            Beat("Break into three", 0.80, "Armed with the lesson, the hero finds the solution."),
            Beat("Finale", 0.82, "The hero proves they've changed and wins (or loses) for good."),
            Beat("Final image", 0.99, "The opposite of the opening image: proof of change."),
        ),
    )

    val herosJourney = StructureTemplate(
        "heros_journey", "The Hero's Journey",
        "Twelve stages of a mythic adventure: departure, ordeal, return.",
        listOf(
            Beat("Ordinary world", 0.0, "The hero at home, before the adventure."),
            Beat("Call to adventure", 0.08, "A challenge or opportunity appears."),
            Beat("Refusal of the call", 0.12, "Fear or duty holds the hero back."),
            Beat("Meeting the mentor", 0.17, "Someone gives advice, a gift, or courage."),
            Beat("Crossing the threshold", 0.25, "The hero leaves the known world."),
            Beat("Tests, allies, enemies", 0.30, "The hero learns the rules of the new world."),
            Beat("Approach to the inmost cave", 0.42, "Preparing for the central ordeal."),
            Beat("The ordeal", 0.50, "The greatest challenge so far; a kind of death and rebirth."),
            Beat("Reward", 0.60, "The hero takes the prize, but danger remains."),
            Beat("The road back", 0.75, "The hero heads home, chased by consequences."),
            Beat("Resurrection", 0.88, "A final test where everything is at stake."),
            Beat("Return with the elixir", 0.96, "The hero comes home changed, bringing something of value."),
        ),
    )

    val sevenPoint = StructureTemplate(
        "seven_point", "Seven-point structure",
        "Plan from the ending backwards: start in the opposite state from where you finish.",
        listOf(
            Beat("Hook", 0.0, "The hero's starting state, the opposite of the resolution."),
            Beat("Plot turn one", 0.15, "The conflict arrives and the world changes."),
            Beat("Pinch one", 0.35, "The antagonist applies pressure; things get worse."),
            Beat("Midpoint", 0.50, "The hero moves from reaction to action."),
            Beat("Pinch two", 0.62, "More pressure; the hero loses something important."),
            Beat("Plot turn two", 0.80, "The hero finds the final piece needed to win."),
            Beat("Resolution", 0.95, "The climax and the hero's new state."),
        ),
    )

    val kishotenketsu = StructureTemplate(
        "kishotenketsu", "Kishōtenketsu (four parts)",
        "A structure built on contrast and surprise rather than conflict.",
        listOf(
            Beat("Ki: introduction", 0.0, "Introduce the characters and their world."),
            Beat("Shō: development", 0.25, "Deepen what we know, without major change."),
            Beat("Ten: twist", 0.50, "An unexpected turn that reframes everything."),
            Beat("Ketsu: reconciliation", 0.75, "Bring the parts together into a new understanding."),
        ),
    )

    val all = listOf(threeAct, saveTheCat, herosJourney, sevenPoint, kishotenketsu)

    fun byId(id: String): StructureTemplate = all.firstOrNull { it.id == id } ?: threeAct

    fun position(template: StructureTemplate, wordsSoFar: Int, targetWords: Int): StoryPosition {
        val total = targetWords.coerceAtLeast(1000)
        val progress = (wordsSoFar.toDouble() / total).coerceIn(0.0, 1.0)
        val current = template.beats.lastOrNull { it.position <= progress + 1e-9 } ?: template.beats.first()
        val next = template.beats.firstOrNull { it.position > progress + 1e-9 }
        val toNext = next?.let { ((it.position - progress) * total).roundToInt() } ?: 0
        return StoryPosition(progress, current, next, toNext)
    }

    /** Where each chapter's midpoint falls in the planned book (0..1). */
    fun chapterPositions(chapters: List<Chapter>, targetWords: Int): List<Double> {
        val counts = chapters.map { TextTools.wordCount(it.text) }
        val total = maxOf(targetWords, counts.sum(), 1)
        var running = 0
        return counts.map { c ->
            val mid = running + c / 2.0
            running += c
            mid / total
        }
    }

    /** The beat a chapter at [position] would usually play. */
    fun expectedBeat(template: StructureTemplate, position: Double): Beat =
        template.beats.lastOrNull { it.position <= position + 1e-9 } ?: template.beats.first()

    /**
     * What the author's own pattern looks like so far: where they placed the
     * beats they've written, chapter length rhythm, and pacing.
     */
    fun insights(project: Project): List<String> {
        val template = byId(project.structureTemplateId)
        val chapters = project.chapters.filter { it.text.isNotBlank() }
        if (chapters.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        val positions = chapterPositions(project.chapters, project.targetWordCount)
        val counts = project.chapters.map { TextTools.wordCount(it.text) }

        // Beats the author (or AI) labelled, compared with where the template puts them.
        project.chapters.forEachIndexed { i, ch ->
            if (ch.beat.isBlank()) return@forEachIndexed
            val match = template.beats.firstOrNull { b ->
                b.name.equals(ch.beat, true) || ch.beat.contains(b.name, true) || b.name.contains(ch.beat, true)
            } ?: return@forEachIndexed
            val actual = positions[i]
            val diff = actual - match.position
            if (match.position > 0 && abs(diff) >= 0.05) {
                val pct = (actual * 100).roundToInt()
                val expected = (match.position * 100).roundToInt()
                out += "Your ${match.name.lowercase()} lands at $pct% (${ch.title}); the usual spot is around $expected%. " +
                    if (diff < 0) "That's early, which quickens the pace." else "That's late, which gives the build-up more room."
            }
        }

        val written = counts.filter { it > 0 }
        if (written.size >= 2) {
            val avg = written.average().roundToInt()
            out += "Chapters average ${TextTools.formatCount(avg)} words."
            if (written.size >= 4) {
                val early = written.take(written.size / 2).average()
                val late = written.takeLast(written.size / 2).average()
                val change = (late - early) / early.coerceAtLeast(1.0)
                if (change > 0.2) out += "Recent chapters are longer (${early.roundToInt()} → ${late.roundToInt()} words): the story may be slowing down to linger."
                if (change < -0.2) out += "Recent chapters are shorter (${early.roundToInt()} → ${late.roundToInt()} words): the pace is picking up."
            }
        }

        val snapshots = project.styleHistory.associateBy { it.chapterId }
        val dialogueHeavy = project.chapters.filter { (snapshots[it.id]?.metrics?.dialogueRatio ?: 0.0) > 0.45 }
        if (dialogueHeavy.isNotEmpty() && dialogueHeavy.size < chapters.size) {
            out += "Dialogue-heavy chapters: ${dialogueHeavy.joinToString(", ") { it.title }}."
        }
        return out
    }
}
