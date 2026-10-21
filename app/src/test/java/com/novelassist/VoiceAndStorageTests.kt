package com.novelassist

import com.novelassist.data.Chapter
import com.novelassist.data.Project
import com.novelassist.data.ProjectRepository
import com.novelassist.learning.TextTools
import com.novelassist.voice.DictationFormatter
import com.novelassist.voice.VoiceCommand
import com.novelassist.voice.VoiceCommandParser
import com.novelassist.voice.VoiceInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DictationFormatterTest {
    @Test
    fun spokenPunctuation() {
        assertEquals(
            "hello, she said. Where are you going?",
            DictationFormatter.applySpokenPunctuation("hello comma she said period where are you going question mark"),
        )
        assertEquals("wait!\n\nThe door opened.", DictationFormatter.applySpokenPunctuation("wait exclamation mark new paragraph the door opened full stop"))
        assertEquals("“Run,” he said.", DictationFormatter.applySpokenPunctuation("open quote run comma close quote he said full stop"))
    }

    @Test
    fun keepsNounUses() {
        assertEquals("a period of calm", DictationFormatter.applySpokenPunctuation("a period of calm"))
        assertEquals("she studied the colon", DictationFormatter.applySpokenPunctuation("she studied the colon"))
    }

    @Test
    fun joinsChunksWithSpacingAndCapitals() {
        assertEquals(" walked to the door", DictationFormatter.prepareInsert("She", "walked to the door", true))
        assertEquals(" The rain stopped", DictationFormatter.prepareInsert("She left.", "the rain stopped", true))
        assertEquals("It was late", DictationFormatter.prepareInsert("", "it was late", true))
        assertEquals(",", DictationFormatter.prepareInsert("Hello", "comma", true))
        // Recognisers capitalise each phrase; mid-sentence common words go back to lower case.
        assertEquals(" the harbour", DictationFormatter.prepareInsert("She walked down to", "The harbour", true))
        // But names keep their capitals.
        assertEquals(" Mara", DictationFormatter.prepareInsert("She looked at", "Mara", true))
        assertEquals(" I knew", DictationFormatter.prepareInsert("and then", "i knew", true))
    }
}

class VoiceCommandParserTest {
    private fun cmd(text: String, strict: Boolean = false) = (VoiceCommandParser.parse(text, strict) as? VoiceInput.Command)?.command

    @Test
    fun recognisesCommands() {
        assertEquals(VoiceCommand.UNDO, cmd("Scratch that."))
        assertEquals(VoiceCommand.SUGGEST, cmd("continue"))
        assertEquals(VoiceCommand.ACCEPT, cmd("Use that"))
        assertEquals(VoiceCommand.BRAINSTORM, cmd("I'm stuck"))
        assertEquals(VoiceCommand.NEW_CHAPTER, cmd("New chapter"))
        assertEquals(VoiceCommand.READ_BACK, cmd("read that back"))
        assertEquals(VoiceCommand.STOP_LISTENING, cmd("stop listening"))
    }

    @Test
    fun proseIsNotACommand() {
        assertTrue(VoiceCommandParser.parse("She told him to continue walking") is VoiceInput.Dictation)
        assertTrue(VoiceCommandParser.parse("undo the knot, he said") is VoiceInput.Dictation)
    }

    @Test
    fun commandsWithArguments() {
        val ask = VoiceCommandParser.parse("Ask the bible what colour are Mara's eyes") as VoiceInput.Command
        assertEquals(VoiceCommand.ASK_BIBLE, ask.command)
        assertEquals("what colour are Mara's eyes", ask.argument)
        val note = VoiceCommandParser.parse("Bible note Tobias is afraid of the sea") as VoiceInput.Command
        assertEquals(VoiceCommand.BIBLE_NOTE, note.command)
        assertEquals("Tobias is afraid of the sea", note.argument)
        val pick = VoiceCommandParser.parse("pick two") as VoiceInput.Command
        assertEquals("2", pick.argument)
    }

    @Test
    fun strictModeNeedsPrefix() {
        assertTrue(VoiceCommandParser.parse("undo", strict = true) is VoiceInput.Dictation)
        assertEquals(VoiceCommand.UNDO, cmd("command undo", strict = true))
        val ask = VoiceCommandParser.parse("command ask the bible who is Hale", strict = true) as VoiceInput.Command
        assertEquals("who is Hale", ask.argument)
    }
}

class TextToolsTest {
    @Test
    fun countsAndSplits() {
        assertEquals(5, TextTools.wordCount("It wasn't over, not yet."))
        assertEquals(3, TextTools.sentences("One. Two! \"Three?\"").size)
        val text = "First para.\n\nSecond para here."
        val r = TextTools.paragraphRangeAt(text, text.length)
        assertEquals("Second para here.", text.substring(r.first, r.last + 1))
        val r2 = TextTools.paragraphRangeAt("First para.\n\n", 13)
        assertEquals("First para.", "First para.\n\n".substring(r2.first, r2.last + 1))
    }
}

class ProjectRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun savesLoadsAndSurvivesCorruption() {
        val repo = ProjectRepository(tmp.root)
        val p = Project(title = "Book", chapters = listOf(Chapter(title = "One", text = SampleText.chapterOne)))
        repo.save(p)
        assertEquals(p, repo.load(p.id))
        assertEquals(1, repo.listProjects().size)

        // Make a backup exist, then corrupt the main file: loading falls back to the backup.
        val backups = java.io.File(tmp.root, "backups")
        java.io.File(tmp.root, "projects/${p.id}.json").copyTo(java.io.File(backups, "${p.id}-1.json"))
        java.io.File(tmp.root, "projects/${p.id}.json").writeText("{ broken")
        assertEquals(p.title, repo.load(p.id)?.title)
    }

    @Test
    fun importMakesCopyWhenIdExists() {
        val repo = ProjectRepository(tmp.root)
        val p = Project(title = "Book")
        repo.save(p)
        val imported = repo.importJson(repo.exportJson(p))
        assertNotEquals(p.id, imported.id)
        assertTrue(imported.title.endsWith("(imported)"))
        assertEquals(2, repo.listProjects().size)
    }

    @Test
    fun oldFilesWithMissingFieldsStillLoad() {
        val repo = ProjectRepository(tmp.root)
        val json = """{"id":"abc","title":"Old","chapters":[{"id":"c1","title":"One","text":"Hi"}]}"""
        java.io.File(tmp.root, "projects/abc.json").writeText(json)
        val p = repo.load("abc")
        assertNotNull(p)
        assertEquals("Hi", p!!.chapters.single().text)
        assertEquals("three_act", p.structureTemplateId)
    }

    @Test
    fun deleteKeepsACopy() {
        val repo = ProjectRepository(tmp.root)
        val p = Project(title = "Gone")
        repo.save(p)
        repo.delete(p.id)
        assertTrue(repo.listProjects().isEmpty())
        assertTrue(java.io.File(tmp.root, "backups").listFiles()!!.any { it.name.startsWith("deleted-${p.id}") })
    }

    @Test
    fun manuscriptAndBibleExports() {
        val p = Project(title = "Book", chapters = listOf(Chapter(title = "One", text = "Text here.")))
        assertTrue(ProjectRepository.manuscript(p).contains("One\n\nText here."))
        assertTrue(ProjectRepository.bibleText(p).startsWith("STORY BIBLE — Book"))
    }
}
