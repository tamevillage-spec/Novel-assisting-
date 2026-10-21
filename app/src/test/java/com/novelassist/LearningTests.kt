package com.novelassist

import com.novelassist.ai.AiEngine
import com.novelassist.ai.AiRequest
import com.novelassist.ai.AiTask
import com.novelassist.ai.OnDeviceEngine
import com.novelassist.data.BibleCategory
import com.novelassist.data.BibleEntry
import com.novelassist.data.Chapter
import com.novelassist.data.Project
import com.novelassist.data.StoryBible
import com.novelassist.learning.AiJson
import com.novelassist.learning.EntityExtractor
import com.novelassist.learning.LearningEngine
import com.novelassist.learning.OfflineTools
import com.novelassist.learning.PersonalLanguageModel
import com.novelassist.learning.PromptBuilder
import com.novelassist.learning.StoryStructures
import com.novelassist.learning.StyleAnalyzer
import com.novelassist.learning.TextTools
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class StyleAnalyzerTest {
    @Test
    fun measuresBasicStyle() {
        val m = StyleAnalyzer.analyze(SampleText.chapterOne)
        assertTrue(m.wordCount in 150..320)
        assertEquals("third person", m.pointOfView)
        assertEquals("past tense", m.tense)
        assertTrue("dialogue ratio ${m.dialogueRatio}", m.dialogueRatio in 0.1..0.45)
        assertTrue(m.avgSentenceLength in 6.0..20.0)
        assertTrue(m.lexicalDiversity in 0.4..1.0)
        assertFalse("names are not favourite words", m.favouriteWords.contains("mara"))
    }

    @Test
    fun detectsFirstPersonPresent() {
        val text = "I walk into the kitchen and I see the letter on the table. My hands are cold. I open it. " +
            "It says what I feared. I sit down and I read it again, and then I am crying. I hate that I am crying. " +
            "I put the kettle on because that is what my mother does when the world ends."
        val m = StyleAnalyzer.analyze(text)
        assertEquals("first person", m.pointOfView)
        assertEquals("present tense", m.tense)
    }

    @Test
    fun describesAndTracksTrends() {
        val long = StyleAnalyzer.analyze(SampleText.chapterOne)
        assertTrue(StyleAnalyzer.describe(long).contains("third person"))
        val snaps = listOf(
            com.novelassist.data.StyleSnapshot("a", "One", 0, metrics = long.copy(avgSentenceLength = 20.0)),
            com.novelassist.data.StyleSnapshot("b", "Two", 1, metrics = long.copy(avgSentenceLength = 12.0)),
        )
        val trends = StyleAnalyzer.trends(snaps)
        assertTrue(trends.joinToString(), trends.any { it.startsWith("Sentences are getting shorter") })
    }

    @Test
    fun emptyTextIsSafe() {
        assertEquals(0, StyleAnalyzer.analyze("").wordCount)
        assertTrue(StyleAnalyzer.describe(StyleAnalyzer.analyze("")).startsWith("Not enough"))
    }
}

class PersonalLanguageModelTest {
    private val model = PersonalLanguageModel.fromChapters(listOf(SampleText.chapterOne, SampleText.chapterTwo))

    @Test
    fun predictsAuthorsOwnNextWord() {
        val next = model.suggest("She went back to the Salt ")
        assertEquals("House", next.firstOrNull())
    }

    @Test
    fun restoresNameCapitals() {
        val next = model.suggest("Morning came grey and slow. ")
        assertTrue(next.isNotEmpty())
        assertTrue("sentence start capitalised: $next", next.all { it[0].isUpperCase() })
        assertTrue(model.suggest("thanked him. Captain ").contains("Hale"))
    }

    @Test
    fun completesPartialWords() {
        val next = model.suggest("She watched the ra")
        assertEquals("rain", next.firstOrNull())
    }

    @Test
    fun continuesInAuthorsPhrasing() {
        val text = model.continueText("Mara", maxWords = 25, random = Random(7))
        assertTrue(text.isNotBlank())
        val vocab = TextTools.words(SampleText.chapterOne + " " + SampleText.chapterTwo).map { it.lowercase() }.toSet()
        assertTrue(TextTools.words(text).all { it.lowercase() in vocab })
    }

    @Test
    fun newerChaptersWeighMore() {
        val m = PersonalLanguageModel.fromChapters(listOf("The cat sat down.", "The dog sat down.", "The dog sat down."))
        assertEquals("dog", m.suggest("The ").first().lowercase())
        val reversed = PersonalLanguageModel.fromChapters(listOf("The dog ran.", "The cat ran."))
        assertEquals("cat", reversed.suggest("The ").first().lowercase())
    }

    @Test
    fun emptyModelIsSafe() {
        val m = PersonalLanguageModel.fromChapters(emptyList())
        assertTrue(m.suggest("hello").isEmpty())
        assertEquals("", m.continueText("hello"))
    }
}

class EntityExtractorTest {
    @Test
    fun findsCharactersAndPlaces() {
        val found = EntityExtractor.extract(SampleText.chapterOne + "\n\n" + SampleText.chapterTwo)
        val byName = found.associateBy { it.name }
        assertEquals(BibleCategory.CHARACTER, byName["Mara Vance"]?.category)
        assertEquals(BibleCategory.CHARACTER, byName["Tobias"]?.category)
        assertEquals(BibleCategory.CHARACTER, byName["Captain Hale"]?.category)
        assertEquals(BibleCategory.PLACE, byName["Port Elling"]?.category)
        assertTrue(found.none { it.name == "The" || it.name == "She" || it.name == "Inside" })
    }

    @Test
    fun recognisesKnownEntries() {
        val entries = listOf(BibleEntry(category = BibleCategory.CHARACTER, name = "Mara Vance", aliases = listOf("Mara")))
        assertNotNull(EntityExtractor.isKnown("Mara", entries))
        assertNotNull(EntityExtractor.isKnown("Mara Vance", entries))
        assertEquals(null, EntityExtractor.isKnown("Tobias", entries))
        assertTrue(EntityExtractor.countMentions(SampleText.chapterOne, entries[0]) >= 4)
    }
}

class StructureTest {
    @Test
    fun findsCurrentBeat() {
        val pos = StoryStructures.position(StoryStructures.threeAct, 40_000, 80_000)
        assertEquals("Midpoint", pos.current.name)
        assertEquals("Plot point two", pos.next?.name)
        assertEquals(20_000, pos.wordsToNext)
    }

    @Test
    fun everyTemplateIsOrdered() {
        StoryStructures.all.forEach { t ->
            assertEquals(t.name, t.beats.map { it.position }.sorted(), t.beats.map { it.position })
            assertEquals(0.0, t.beats.first().position, 0.0)
        }
    }

    @Test
    fun insightsCompareLabelledBeats() {
        val chapters = (1..4).map { Chapter(title = "Chapter $it", text = "word ".repeat(2500)) }
        val p = Project(
            title = "T",
            chapters = chapters.mapIndexed { i, c -> if (i == 0) c.copy(beat = "Midpoint") else c },
            targetWordCount = 10_000,
        )
        val insights = StoryStructures.insights(p)
        assertTrue(insights.joinToString("\n"), insights.any { it.startsWith("Your midpoint lands at 13% (Chapter 1)") })
    }
}

class AiJsonTest {
    @Test
    fun parsesCleanJson() {
        val r = AiJson.parseLearn(
            """{"summary":"Mara returns.","beat":"Setup","entities":[{"category":"character","name":"Tobias","description":"An old friend."}],"style_notes":"Spare."}"""
        )
        assertEquals("Mara returns.", r.summary)
        assertEquals("Setup", r.beat)
        assertEquals(BibleCategory.CHARACTER, r.entities.single().category)
        assertEquals("Spare.", r.styleNotes)
    }

    @Test
    fun toleratesFencesAndTrailingCommas() {
        val r = AiJson.parseLearn(
            "Here you go:\n```json\n{\"summary\": \"A storm.\", \"entities\": [{\"category\": \"place\", \"name\": \"Salt House\", \"description\": \"An inn.\",},],}\n```"
        )
        assertEquals("A storm.", r.summary)
        assertEquals(BibleCategory.PLACE, r.entities.single().category)
    }

    @Test
    fun fallsBackToRegex() {
        val r = AiJson.parseLearn("""broken {"summary": "Half a reply", "beat": "Midpoint" ...""")
        assertEquals("Half a reply", r.summary)
        assertEquals("Midpoint", r.beat)
    }

    @Test
    fun stripsThinking() {
        assertEquals("Hello.", OnDeviceEngine.cleanOutput("<think>hmm</think>Hello.<end_of_turn>"))
        assertEquals("", OnDeviceEngine.cleanOutput("<think>still thinking"))
    }
}

/** A stand-in AI that answers learning requests with fixed JSON. */
private class FakeEngine(private val reply: (AiRequest) -> String) : AiEngine {
    val requests = mutableListOf<AiRequest>()
    override val displayName = "Fake AI"
    override val contextBudgetChars = 20_000
    override fun isReady() = true
    override fun stream(request: AiRequest): Flow<String> {
        requests += request
        return flowOf(reply(request))
    }
}

class LearningEngineTest {
    private fun project() = Project(
        title = "The Salt House",
        chapters = listOf(
            Chapter(title = "Chapter 1", text = SampleText.chapterOne),
            Chapter(title = "Chapter 2", text = SampleText.chapterTwo),
        ),
        targetWordCount = 5_000,
    )

    @Test
    fun learnsOfflineWithoutAi() = runBlocking {
        val p = project()
        val result = LearningEngine().learn(p, p.chapters[0].id, engine = null)
        val learned = result.project
        assertEquals(1, learned.styleHistory.size)
        assertTrue(learned.chapters[0].summary.isNotBlank())
        assertTrue(learned.chapters[0].beat.isNotBlank())
        assertFalse(learned.chapters[0].needsLearning)
        assertTrue(learned.proposals.any { it.name == "Tobias" })
        assertTrue(learned.bible.voiceDescription.contains("third person"))
        assertEquals(1, learned.bible.voiceHistory.size)
        assertEquals(1, learned.learningLog.size)
    }

    @Test
    fun voiceHistoryGrowsAsMoreIsLearned() = runBlocking {
        val p = project()
        val engine = LearningEngine()
        val first = engine.learn(p, p.chapters[0].id, null).project
        val second = engine.learn(first, first.chapters[1].id, null).project
        assertEquals(2, second.styleHistory.size)
        assertEquals(2, second.bible.voiceHistory.size)
    }

    @Test
    fun usesAiWhenAvailableAndSkipsRejectedNames() = runBlocking {
        val ai = FakeEngine { req ->
            when (req.task) {
                AiTask.LEARN -> """{"summary":"Mara comes home and hears her father was murdered.","beat":"Inciting incident",
                    "entities":[{"category":"character","name":"Tobias","description":"Her father's old friend, frail now."},
                    {"category":"plot_thread","name":"Who killed Mara's father","description":"Tobias believes it wasn't an accident."},
                    {"category":"place","name":"Salt House","description":"An inn above the harbour."}],
                    "style_notes":"Short sentences, sea imagery."}"""
                AiTask.VOICE -> "The author writes in a spare, rain-soaked close third person, past tense, with short declarative sentences and sea imagery that carries the mood. Dialogue is clipped and loaded."
                else -> ""
            }
        }
        val p = project().copy(rejectedNames = listOf("Salt House"))
        val result = LearningEngine().learn(p, p.chapters[0].id, ai).project
        assertEquals("Mara comes home and hears her father was murdered.", result.chapters[0].summary)
        assertEquals("Inciting incident", result.chapters[0].beat)
        assertTrue(result.proposals.any { it.category == BibleCategory.PLOT_THREAD && it.fromAi })
        assertTrue(result.proposals.none { it.name == "Salt House" })
        assertTrue(result.bible.voiceDescription.startsWith("The author writes"))
        assertEquals("Fake AI", result.bible.voiceHistory.last().source)
        assertEquals(listOf(AiTask.LEARN, AiTask.VOICE), ai.requests.map { it.task })
    }

    @Test
    fun manualBeatIsKept() = runBlocking {
        val p = project().let { it.updateChapter(it.chapters[0].id) { c -> c.copy(beat = "Climax", beatIsManual = true) } }
        val result = LearningEngine().learn(p, p.chapters[0].id, null).project
        assertEquals("Climax", result.chapters[0].beat)
    }
}

class PromptBuilderTest {
    @Test
    fun includesBibleVoiceAndFitsBudget() {
        val p = Project(
            title = "T",
            chapters = listOf(
                Chapter(title = "Chapter 1", text = SampleText.chapterOne, summary = "Mara returns home."),
                Chapter(title = "Chapter 2", text = SampleText.chapterTwo),
            ),
            bible = StoryBible(
                premise = "A woman investigates her father's death.",
                voiceDescription = "Spare, rainy close third.",
                entries = listOf(
                    BibleEntry(category = BibleCategory.CHARACTER, name = "Tobias", description = "Old friend."),
                    BibleEntry(category = BibleCategory.CHARACTER, name = "Unrelated", description = "x".repeat(5000)),
                ),
            ),
        )
        val builder = PromptBuilder(p, 9_000)
        val system = builder.system(p.chapters[1].id, SampleText.chapterTwo)
        assertTrue(system.contains("Spare, rainy close third."))
        assertTrue(system.contains("Tobias"))
        assertTrue(system.contains("Mara returns home."))
        assertTrue(system.contains("Premise: A woman investigates"))
        assertTrue("system length ${system.length}", system.length < 9_000)
        val prompt = builder.continuation(p.chapters[1], "She opened the door and", 100)
        assertTrue(prompt.contains("mid-sentence"))
        // Learning must fit a 4,096-token on-device model: slim system + chapter + reply.
        val longChapter = Chapter(title = "Long", text = SampleText.chapterOne.repeat(12))
        val learnTotal = builder.learningSystem(longChapter.id, false).length + builder.learnChapter(longChapter).length
        assertTrue("learning prompt is $learnTotal chars", learnTotal < 7_500)
    }
}

class OfflineToolsTest {
    @Test
    fun cleansDictation() {
        assertEquals(
            "I went to see Tobias. He was asleep, so I left.",
            OfflineTools.cleanDictation("i went to see tobias .he was asleep ,so i left", listOf("Tobias")),
        )
    }

    @Test
    fun answersFromBible() {
        val p = Project(
            title = "T",
            bible = StoryBible(entries = listOf(BibleEntry(category = BibleCategory.CHARACTER, name = "Mara", description = "Her eyes are grey as the sea."))),
        )
        assertTrue(OfflineTools.answer(p, "What colour are Mara's eyes?").contains("grey"))
        assertTrue(OfflineTools.brainstorm(p).startsWith("1."))
    }
}
