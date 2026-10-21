package com.novelassist

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.novelassist.data.BibleCategory
import com.novelassist.data.BibleEntry
import com.novelassist.data.Chapter
import com.novelassist.data.Project
import com.novelassist.data.StoryBible
import com.novelassist.ui.AppViewModel
import com.novelassist.ui.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Opens the real app on a simulated phone, walks through every screen with a
 * sample novel, and saves a screenshot of each to build/screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class ScreensTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun shot(name: String) = compose.onRoot().captureRoboImage("build/screenshots/$name.png")

    /** Waits for background work, running the main thread's queue while it waits. */
    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("Timed out waiting for the app")
    }

    @Test
    fun walkThroughEveryScreen() {
        val app = ApplicationProvider.getApplicationContext<NovelAssistApp>()
        val project = Project(
            title = "The Salt House",
            chapters = listOf(
                Chapter(title = "Chapter 1", text = SampleText.chapterOne),
                Chapter(title = "Chapter 2", text = SampleText.chapterTwo),
            ),
            bible = StoryBible(
                premise = "A woman returns to her fishing town to learn who killed her father.",
                entries = listOf(
                    BibleEntry(category = BibleCategory.CHARACTER, name = "Mara Vance", description = "Came home after ten years. Stubborn, watchful.", pinned = true),
                ),
            ),
            targetWordCount = 60_000,
        )
        app.projects.save(project)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: AppViewModel
            scenario.onActivity { vm = it.viewModel }

            // Library
            waitFor(5_000) { vm.projects.isNotEmpty() }
            compose.waitForIdle()
            shot("1-library")

            // Write
            compose.onNodeWithText("The Salt House").performClick()
            waitFor { vm.screen == Screen.WRITE && vm.editor.text.isNotEmpty() }
            waitFor(10_000) { vm.predictions.isNotEmpty() }
            compose.waitForIdle()
            shot("2-write")

            // A suggestion from the built-in personal model
            compose.onNodeWithText("Continue").performClick()
            waitFor(10_000) { vm.suggestion?.streaming == false }
            compose.waitForIdle()
            shot("3-write-suggestion")
            assertTrue(vm.suggestion?.error ?: "", vm.suggestion?.text?.isNotBlank() == true)
            compose.onNodeWithText("Accept").performClick()
            compose.waitForIdle()

            // Learn both chapters, then look at the bible
            compose.runOnIdle { vm.learnAll() }
            waitFor(20_000) { vm.project?.chapters?.none { it.needsLearning } == true && vm.learningStatus == null }
            compose.runOnIdle { vm.screen = Screen.BIBLE }
            waitFor(5_000) { compose.onAllNodes(hasText("Keep all")).fetchSemanticsNodes().isNotEmpty() }
            shot("4-bible")

            compose.runOnIdle { vm.screen = Screen.VOICE }
            compose.waitForIdle()
            shot("5-voice")

            compose.runOnIdle { vm.screen = Screen.STRUCTURE }
            compose.waitForIdle()
            shot("6-structure")

            compose.runOnIdle { vm.screen = Screen.SETTINGS }
            compose.waitForIdle()
            shot("7-settings")

            compose.runOnIdle { vm.showHelp = true; vm.screen = Screen.WRITE }
            compose.waitForIdle()
            shot("8-help")

            val learned = vm.project!!
            assertTrue(learned.bible.voiceDescription.isNotBlank())
            assertTrue(learned.proposals.isNotEmpty())
            assertTrue(learned.chapters.all { it.summary.isNotBlank() })
        }
    }

    @Test
    fun voiceDictationAndCommands() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: AppViewModel
            scenario.onActivity { vm = it.viewModel }
            compose.runOnIdle { vm.createProject("Voice test") }
            waitFor { vm.screen == Screen.WRITE && vm.currentChapter != null }

            fun say(text: String) {
                compose.runOnIdle { vm.handleSpeech(text) }
                compose.waitForIdle()
            }
            say("the rain fell comma")
            say("and the sea rose full stop")
            assertEquals("The rain fell, and the sea rose.", vm.editor.text)
            say("new paragraph")
            say("open quote stay comma close quote she said")
            assertEquals("The rain fell, and the sea rose.\n\n“Stay,” she said", vm.editor.text)
            say("scratch that")
            assertEquals("The rain fell, and the sea rose.\n\n", vm.editor.text)
            // With no suggestion on screen, suggestion words are just prose.
            say("Again")
            assertEquals("The rain fell, and the sea rose.\n\nAgain", vm.editor.text)
            say("delete last word")
            assertEquals("The rain fell, and the sea rose.\n\n", vm.editor.text)
            say("bible note Mara has grey eyes")
            assertTrue(vm.project!!.bible.entries.any { it.description.contains("Mara has grey eyes") })
            say("new chapter")
            assertEquals(2, vm.project!!.chapters.size)
            assertEquals("Chapter 2", vm.currentChapter?.title)
            say("previous chapter")
            assertEquals("Chapter 1", vm.currentChapter?.title)
            assertTrue(vm.editor.text.startsWith("The rain fell, and the sea rose."))
        }
    }
}
