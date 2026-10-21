package com.novelassist.ui

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.novelassist.NovelAssistApp
import com.novelassist.ai.AiEngine
import com.novelassist.ai.AiException
import com.novelassist.ai.DownloadProgress
import com.novelassist.ai.ModelCatalog
import com.novelassist.ai.ModelOption
import com.novelassist.ai.WritingAssistant
import com.novelassist.data.AppSettings
import com.novelassist.data.BibleCategory
import com.novelassist.data.BibleEntry
import com.novelassist.data.BibleProposal
import com.novelassist.data.Chapter
import com.novelassist.data.EngineChoice
import com.novelassist.data.Project
import com.novelassist.data.ProjectRepository
import com.novelassist.data.ProjectSummary
import com.novelassist.data.now
import com.novelassist.learning.EntityExtractor
import com.novelassist.learning.LearningEngine
import com.novelassist.learning.PersonalLanguageModel
import com.novelassist.learning.TextTools
import com.novelassist.voice.DictationController
import com.novelassist.voice.DictationFormatter
import com.novelassist.voice.MicState
import com.novelassist.voice.Speaker
import com.novelassist.voice.VoiceCommand
import com.novelassist.voice.VoiceCommandParser
import com.novelassist.voice.VoiceInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

enum class Screen { LIBRARY, WRITE, BIBLE, VOICE, STRUCTURE, SETTINGS }

enum class SuggestionKind(val title: String) {
    CONTINUE("Suggested next lines"),
    FIX("Corrected paragraph"),
    IDEAS("Ideas for what happens next"),
    ANSWER("From your story bible"),
}

data class Suggestion(
    val kind: SuggestionKind,
    val text: String,
    val streaming: Boolean,
    val engine: String,
    val error: String? = null,
    /** For FIX: the paragraph being replaced and what it said. */
    val replaceRange: IntRange? = null,
    val original: String = "",
    val question: String = "",
    /** Shown until the first words arrive. */
    val waitingText: String = "Writing…",
) {
    val canInsert: Boolean get() = kind == SuggestionKind.CONTINUE || kind == SuggestionKind.FIX
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app as NovelAssistApp
    private val repo = container.projects
    private val settingsRepo = container.settings
    val settings: StateFlow<AppSettings> = settingsRepo.settings

    private val learningEngine = LearningEngine()
    private val assistant = WritingAssistant(engine = { activeEngine() }, personalModel = { personalModel })
    private val main = Handler(Looper.getMainLooper())

    // ---- UI state ----
    var screen by mutableStateOf(Screen.LIBRARY)
    var projects by mutableStateOf<List<ProjectSummary>>(emptyList())
        private set
    var project by mutableStateOf<Project?>(null)
        private set
    var chapterId by mutableStateOf<String?>(null)
        private set
    var editor by mutableStateOf(TextFieldValue(""))
        private set
    var predictions by mutableStateOf<List<String>>(emptyList())
        private set
    var suggestion by mutableStateOf<Suggestion?>(null)
        private set
    var bibleAnswer by mutableStateOf<Suggestion?>(null)
        private set
    var micState by mutableStateOf(MicState.OFF)
        private set
    var partialSpeech by mutableStateOf("")
        private set
    var speaking by mutableStateOf(false)
        private set
    var learningStatus by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    var showHelp by mutableStateOf(false)
    var downloadProgress by mutableStateOf<DownloadProgress?>(null)
        private set
    var importStatus by mutableStateOf<String?>(null)
        private set
    var modelStats by mutableStateOf("")
        private set

    @Volatile
    private var personalModel: PersonalLanguageModel? = null
    private var modelBuiltFromHash = 0
    private var saveJob: Job? = null
    private var suggestJob: Job? = null
    private var predictJob: Job? = null
    private var modelJob: Job? = null
    private var downloadJob: Job? = null
    private val learnLock = Mutex()
    private val undoStack = ArrayDeque<TextFieldValue>()
    private var lastUndoPushAt = 0L
    private var listeningBeforeBackground = false

    private val speaker = Speaker(app) { isSpeaking ->
        main.post {
            speaking = isSpeaking
            if (isSpeaking) dictation.pause() else dictation.resume()
        }
    }

    private val dictation = DictationController(
        context = app,
        onResult = { text -> handleSpeech(text) },
        onPartial = { partialSpeech = it },
        onState = { micState = it },
        onError = { showMessage(it, speak = true) },
    )

    init {
        refreshProjects()
        val last = settingsRepo.current.lastProjectId
        if (last.isNotBlank()) openProject(last, goToWrite = false)
        resumeDownloadTracking()
    }

    // ---- engines ----

    fun activeEngine(): AiEngine? {
        val s = settingsRepo.current
        return when (s.engine) {
            EngineChoice.CLAUDE -> container.claude.takeIf { it.isReady() }
            EngineChoice.ON_DEVICE -> container.onDevice.takeIf { it.isReady() }
            EngineChoice.BUILT_IN -> null
        }
    }

    val engineLabel: String
        get() {
            val s = settingsRepo.current
            val engine = activeEngine()
            return when {
                engine != null -> engine.displayName
                s.engine == EngineChoice.ON_DEVICE -> "Built-in (on-device model not downloaded)"
                s.engine == EngineChoice.CLAUDE -> "Built-in (no Claude API key)"
                else -> "Built-in model"
            }
        }

    fun updateSettings(change: (AppSettings) -> AppSettings) {
        val before = settingsRepo.current
        settingsRepo.update(change)
        val after = settingsRepo.current
        if (before.onDeviceUseGpu != after.onDeviceUseGpu || before.onDeviceModelPath != after.onDeviceModelPath) {
            container.onDevice.releaseIfIdle()
        }
        if (after.engine == EngineChoice.ON_DEVICE && before.engine != EngineChoice.ON_DEVICE && container.onDevice.isReady()) {
            viewModelScope.launch(Dispatchers.Default) { container.onDevice.warmUp() }
        }
    }

    // ---- library ----

    fun refreshProjects() {
        viewModelScope.launch {
            projects = withContext(Dispatchers.IO) { repo.listProjects() }
        }
    }

    fun createProject(title: String) {
        val p = Project(title = title.trim().ifBlank { "Untitled novel" })
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.save(p) }
            refreshProjects()
            openProject(p.id, goToWrite = true)
        }
    }

    fun openProject(id: String, goToWrite: Boolean = true) {
        viewModelScope.launch {
            flushSave()
            val loaded = withContext(Dispatchers.IO) { repo.load(id) }
            if (loaded == null) {
                if (goToWrite) showMessage("Couldn't open that novel.")
                return@launch
            }
            project = loaded
            settingsRepo.update { it.copy(lastProjectId = loaded.id) }
            val chapter = loaded.chapters.maxByOrNull { it.updatedAt } ?: loaded.chapters.first()
            loadChapter(chapter.id)
            rebuildModel(force = true)
            if (goToWrite) screen = Screen.WRITE
        }
    }

    fun closeProject() {
        stopDictation()
        viewModelScope.launch {
            flushSave()
            project = null
            chapterId = null
            suggestion = null
            screen = Screen.LIBRARY
            refreshProjects()
        }
    }

    fun deleteProject(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.delete(id) }
            if (project?.id == id) {
                project = null
                chapterId = null
            }
            refreshProjects()
            showMessage("Novel deleted. A copy is kept in the app's backup folder.")
        }
    }

    fun renameProject(title: String) = mutateProject { it.copy(title = title.trim().ifBlank { it.title }) }

    // ---- chapters ----

    val currentChapter: Chapter? get() = project?.chapter(chapterId)

    private fun loadChapter(id: String) {
        val ch = project?.chapter(id) ?: return
        chapterId = id
        editor = TextFieldValue(ch.text, TextRange(ch.text.length))
        undoStack.clear()
        suggestion = null
        updatePredictions()
    }

    fun selectChapter(id: String) {
        if (id == chapterId) return
        val leaving = chapterId
        syncEditorIntoProject()
        scheduleSave(immediate = true)
        loadChapter(id)
        leaving?.let { maybeAutoLearn(it) }
    }

    fun addChapter(title: String? = null) {
        val p = syncEditorIntoProject() ?: return
        val leaving = chapterId
        val ch = Chapter(title = title?.takeIf { it.isNotBlank() } ?: "Chapter ${p.chapters.size + 1}")
        project = p.copy(chapters = p.chapters + ch, updatedAt = now())
        scheduleSave(immediate = true)
        loadChapter(ch.id)
        leaving?.let { maybeAutoLearn(it) }
        showMessage("Started ${ch.title}.")
    }

    fun stepChapter(delta: Int) {
        val p = project ?: return
        val i = p.chapters.indexOfFirst { it.id == chapterId }
        val next = p.chapters.getOrNull(i + delta) ?: run {
            showMessage(if (delta > 0) "This is the last chapter." else "This is the first chapter.", speak = true)
            return
        }
        selectChapter(next.id)
        showMessage(next.title, speak = dictation.isActive)
    }

    fun renameChapter(id: String, title: String) = mutateProject { p ->
        p.updateChapter(id) { it.copy(title = title.trim().ifBlank { it.title }) }
    }

    fun setChapterBeat(id: String, beat: String) = mutateProject { p ->
        p.updateChapter(id) { it.copy(beat = beat, beatIsManual = beat.isNotBlank()) }
    }

    fun deleteChapter(id: String) {
        val p = syncEditorIntoProject() ?: return
        if (p.chapters.size <= 1) {
            showMessage("A novel needs at least one chapter.")
            return
        }
        val removed = p.chapter(id) ?: return
        val remaining = p.chapters.filter { it.id != id }
        project = p.copy(
            chapters = remaining,
            styleHistory = p.styleHistory.filter { it.chapterId != id },
            updatedAt = now(),
        )
        if (chapterId == id) loadChapter(remaining.last().id)
        scheduleSave(immediate = true)
        rebuildModel(force = true)
        showMessage("Deleted ${removed.title}.")
    }

    fun moveChapter(id: String, delta: Int) = mutateProject { p ->
        val list = p.chapters.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in list.indices) return@mutateProject p
        val tmp = list[i]; list[i] = list[j]; list[j] = tmp
        p.copy(chapters = list, updatedAt = now())
    }

    fun setStructure(templateId: String) = mutateProject { it.copy(structureTemplateId = templateId) }
    fun setTargetWords(words: Int) = mutateProject { it.copy(targetWordCount = words.coerceIn(5_000, 500_000)) }

    // ---- editor ----

    fun onEditorChange(value: TextFieldValue) {
        val changed = value.text != editor.text
        if (changed && now() - lastUndoPushAt > 1500) pushUndo()
        editor = value
        if (changed) {
            scheduleSave()
            maybeRebuildModel()
        }
        updatePredictions()
    }

    private fun pushUndo() {
        undoStack.addLast(editor)
        if (undoStack.size > 100) undoStack.removeFirst()
        lastUndoPushAt = now()
    }

    private fun replaceEditor(text: String, cursor: Int) {
        editor = TextFieldValue(text, TextRange(cursor.coerceIn(0, text.length)))
        scheduleSave()
        maybeRebuildModel()
        updatePredictions()
    }

    fun insertText(chunk: String, formatSpoken: Boolean) {
        val value = editor
        val start = value.selection.min
        val end = value.selection.max
        val before = value.text.substring(0, start)
        val after = value.text.substring(end)
        val insert = DictationFormatter.prepareInsert(before, chunk, formatSpoken)
        if (insert.isEmpty()) return
        val spacer = if (after.isNotEmpty() && !after.first().isWhitespace() && after.first() !in ",.;:!?…)”" &&
            !insert.last().isWhitespace()
        ) " " else ""
        pushUndo()
        replaceEditor(before + insert + spacer + after, start + insert.length)
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull()
        if (previous == null) {
            showMessage("Nothing to undo.")
            return
        }
        editor = previous
        scheduleSave()
        updatePredictions()
    }

    fun deleteLastWord() {
        val v = editor
        val cursor = v.selection.min
        val before = v.text.substring(0, cursor).trimEnd()
        val cut = before.dropLastWhile { !it.isWhitespace() }.trimEnd(' ')
        pushUndo()
        replaceEditor(cut + v.text.substring(cursor), cut.length)
    }

    fun deleteLastSentence() {
        val v = editor
        val cursor = v.selection.min
        val before = v.text.substring(0, cursor).trimEnd()
        if (before.isEmpty()) return
        val body = before.dropLast(1)
        val idx = body.lastIndexOfAny(charArrayOf('.', '!', '?', '…', '\n'))
        val cut = if (idx < 0) "" else before.substring(0, idx + 1)
        pushUndo()
        replaceEditor(cut + v.text.substring(cursor), cut.length)
    }

    fun newParagraph() {
        val v = editor
        val cursor = v.selection.min
        val before = v.text.substring(0, cursor).trimEnd(' ')
        if (before.isEmpty() || before.endsWith("\n\n")) return
        val add = if (before.endsWith("\n")) "\n" else "\n\n"
        pushUndo()
        val newText = before + add + v.text.substring(cursor).trimStart(' ')
        replaceEditor(newText, before.length + add.length)
    }

    fun pickPrediction(index: Int) {
        val word = predictions.getOrNull(index) ?: run {
            showMessage("No word ${index + 1} to pick.")
            return
        }
        val v = editor
        val cursor = v.selection.min
        val before = v.text.substring(0, cursor)
        val partial = if (before.isNotEmpty() && before.last().isLetter()) before.takeLastWhile { it.isLetter() || it == '\'' || it == '’' } else ""
        val base = before.dropLast(partial.length)
        val spacer = if (base.isNotEmpty() && !base.last().isWhitespace() && base.last() !in "“(—") " " else ""
        val insert = "$spacer$word "
        pushUndo()
        replaceEditor(base + insert + v.text.substring(v.selection.max).trimStart(' '), base.length + insert.length)
    }

    private fun updatePredictions() {
        val model = personalModel ?: run { predictions = emptyList(); return }
        val v = editor
        val before = v.text.substring(0, v.selection.min.coerceAtMost(v.text.length)).takeLast(300)
        predictJob?.cancel()
        predictJob = viewModelScope.launch {
            val words = withContext(Dispatchers.Default) { model.suggest(before, 5) }
            predictions = words
        }
    }

    // ---- saving ----

    private fun syncEditorIntoProject(): Project? {
        val p = project ?: return null
        val id = chapterId ?: return p
        val current = p.chapter(id) ?: return p
        if (current.text == editor.text) return p
        val updated = p.updateChapter(id) { it.copy(text = editor.text, updatedAt = now()) }
        project = updated
        return updated
    }

    private fun scheduleSave(immediate: Boolean = false) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            if (!immediate) delay(1200)
            val p = syncEditorIntoProject() ?: return@launch
            withContext(Dispatchers.IO) { repo.save(p) }
        }
    }

    suspend fun flushSave() {
        saveJob?.cancel()
        val p = syncEditorIntoProject() ?: return
        withContext(Dispatchers.IO) { repo.save(p) }
    }

    fun saveNow() {
        viewModelScope.launch { flushSave() }
    }

    private fun mutateProject(change: (Project) -> Project) {
        val p = syncEditorIntoProject() ?: return
        project = change(p)
        scheduleSave(immediate = true)
    }

    // ---- personal model ----

    private fun maybeRebuildModel() {
        modelJob?.let { if (it.isActive) return }
        val hash = project?.chapters?.sumOf { it.text.length }?.plus(editor.text.length) ?: 0
        if (kotlin.math.abs(hash - modelBuiltFromHash) < 150) return
        rebuildModel(force = false)
    }

    private fun rebuildModel(force: Boolean) {
        if (!force && modelJob?.isActive == true) return
        modelJob = viewModelScope.launch {
            if (!force) delay(3000)
            val p = syncEditorIntoProject() ?: return@launch
            val texts = p.chapters.map { it.text }
            val extra = listOfNotNull(p.bible.premise.takeIf { it.isNotBlank() }) + p.bible.entries.map { it.description }
            val model = withContext(Dispatchers.Default) { PersonalLanguageModel.fromChapters(texts, extra) }
            personalModel = model
            modelBuiltFromHash = p.chapters.sumOf { it.text.length } + editor.text.length
            modelStats = "Trained on ${TextTools.formatCount(model.trainedTokens)} words and marks from your writing, " +
                "${TextTools.formatCount(model.vocabularySize)} different words. Newer chapters count up to twice as much."
            updatePredictions()
        }
    }

    // ---- AI suggestions ----

    fun requestContinue() = startSuggestion(SuggestionKind.CONTINUE)
    fun requestIdeas() = startSuggestion(SuggestionKind.IDEAS)
    fun requestFix() = startSuggestion(SuggestionKind.FIX)

    private fun startSuggestion(kind: SuggestionKind, question: String = "") {
        val p = syncEditorIntoProject() ?: return
        val chapter = currentChapter ?: return
        val cursor = editor.selection.min
        val textBefore = editor.text.substring(0, cursor)
        var range: IntRange? = null
        var original = ""
        val flow = when (kind) {
            SuggestionKind.CONTINUE -> assistant.continueStory(p, chapter, textBefore, settingsRepo.current.suggestionWords)
            SuggestionKind.IDEAS -> assistant.brainstorm(p, chapter, textBefore)
            SuggestionKind.ANSWER -> assistant.ask(p, chapter, question)
            SuggestionKind.FIX -> {
                val r = TextTools.paragraphRangeAt(editor.text, cursor)
                if (r.isEmpty()) {
                    showMessage("There's no paragraph here to fix yet.", speak = true)
                    return
                }
                range = r
                original = editor.text.substring(r.first, r.last + 1)
                assistant.fixDictation(p, chapter, original)
            }
        }
        val engineName = assistant.engineName
        val waiting = when {
            learningStatus != null && activeEngine() != null -> "Waiting while I finish learning…"
            activeEngine() === container.onDevice && !container.onDevice.isLoaded ->
                "Loading the on-device model. The first time can take up to a minute…"
            else -> "Writing…"
        }
        suggestJob?.cancel()
        speaker.stop()
        suggestion = Suggestion(
            kind, "", streaming = true, engine = engineName,
            replaceRange = range, original = original, question = question, waitingText = waiting,
        )
        suggestJob = viewModelScope.launch {
            try {
                flow.collect { text -> suggestion = suggestion?.copy(text = text) }
                val done = suggestion?.copy(streaming = false) ?: return@launch
                suggestion = done
                if (settingsRepo.current.autoReadSuggestions && done.text.isNotBlank()) {
                    val prompt = when (kind) {
                        SuggestionKind.CONTINUE -> " … Say accept, try again, or discard."
                        SuggestionKind.FIX -> " … Say accept to use the corrected version."
                        else -> ""
                    }
                    speaker.speak(done.text + prompt, settingsRepo.current.speechRate)
                }
            } catch (e: AiException) {
                suggestion = suggestion?.copy(streaming = false, error = e.message)
                speaker.speak(e.message ?: "Something went wrong.", settingsRepo.current.speechRate)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                suggestion = suggestion?.copy(streaming = false, error = "Something went wrong: ${e.message}")
            }
        }
    }

    fun retrySuggestion() {
        val s = suggestion ?: return
        startSuggestion(s.kind, s.question)
    }

    fun dismissSuggestion() {
        suggestJob?.cancel()
        suggestion = null
        speaker.stop()
    }

    fun acceptSuggestion() {
        val s = suggestion ?: return
        if (s.streaming || s.text.isBlank() || !s.canInsert) return
        when (s.kind) {
            SuggestionKind.FIX -> {
                val r = s.replaceRange
                val text = editor.text
                if (r != null && r.last < text.length && text.substring(r.first, r.last + 1) == s.original) {
                    pushUndo()
                    val newText = text.substring(0, r.first) + s.text.trim() + text.substring(r.last + 1)
                    replaceEditor(newText, r.first + s.text.trim().length)
                } else {
                    showMessage("The paragraph changed while I was fixing it, so I left it alone.")
                }
            }
            else -> insertText(s.text, formatSpoken = false)
        }
        suggestion = null
        speaker.stop()
    }

    fun askBible(question: String) {
        val p = syncEditorIntoProject() ?: return
        if (question.isBlank()) return
        val engineName = assistant.engineName
        bibleAnswer = Suggestion(SuggestionKind.ANSWER, "", streaming = true, engine = engineName, question = question)
        viewModelScope.launch {
            try {
                assistant.ask(p, currentChapter, question).collect { bibleAnswer = bibleAnswer?.copy(text = it) }
                bibleAnswer = bibleAnswer?.copy(streaming = false)
                bibleAnswer?.text?.let { speaker.speak(it, settingsRepo.current.speechRate) }
            } catch (e: AiException) {
                bibleAnswer = bibleAnswer?.copy(streaming = false, error = e.message)
            }
        }
    }

    fun clearBibleAnswer() {
        bibleAnswer = null
    }

    // ---- reading aloud ----

    fun readBack() {
        val v = editor
        val cursor = v.selection.min
        val r = TextTools.paragraphRangeAt(v.text, cursor)
        val text = if (r.isEmpty()) "" else v.text.substring(r.first, r.last + 1)
        if (text.isBlank()) showMessage("There's nothing here to read yet.", speak = true)
        else speak(text)
    }

    fun readChapter() {
        val text = editor.text
        if (text.isBlank()) showMessage("This chapter is empty.", speak = true) else speak(text)
    }

    fun readSuggestion() {
        suggestion?.text?.takeIf { it.isNotBlank() }?.let { speak(it) }
    }

    fun speak(text: String) = speaker.speak(text, settingsRepo.current.speechRate)
    fun stopSpeaking() = speaker.stop()

    // ---- dictation ----

    fun toggleMic() {
        if (dictation.isActive) stopDictation() else startDictation()
    }

    fun startDictation() {
        if (screen != Screen.WRITE) screen = Screen.WRITE
        val s = settingsRepo.current
        dictation.start(continuous = s.handsFree, preferOffline = s.preferOfflineSpeech, autoPunctuation = s.autoPunctuation)
    }

    fun stopDictation() {
        dictation.stop()
    }

    /** Called by voice fields in dialogs, so the two microphones don't clash. */
    fun suspendDictationForField(): Boolean {
        val was = dictation.isActive
        if (was) dictation.stop()
        return was
    }

    fun onAppBackground() {
        listeningBeforeBackground = dictation.isActive
        dictation.stop()
        speaker.stop()
        viewModelScope.launch { flushSave() }
    }

    fun onAppForeground() {
        if (listeningBeforeBackground && screen == Screen.WRITE) startDictation()
        listeningBeforeBackground = false
    }

    fun onEnterWriteScreen() {
        if (settingsRepo.current.autoStartListening && !dictation.isActive) startDictation()
    }

    fun onLeaveWriteScreen() {
        if (dictation.isActive) dictation.stop()
    }

    /** Everything the microphone hears comes through here: prose or a command. */
    internal fun handleSpeech(text: String) {
        when (val input = VoiceCommandParser.parse(text, settingsRepo.current.strictCommands)) {
            is VoiceInput.Dictation -> insertText(input.text, settingsRepo.current.spokenPunctuation)
            is VoiceInput.Command -> runCommand(input, text)
        }
    }

    private fun runCommand(input: VoiceInput.Command, raw: String) {
        val s = suggestion
        // Suggestion words with nothing to act on are just prose ("Again," she said).
        fun asDictation() = insertText(raw, settingsRepo.current.spokenPunctuation)
        when (input.command) {
            VoiceCommand.UNDO -> undo()
            VoiceCommand.DELETE_LAST_WORD -> deleteLastWord()
            VoiceCommand.DELETE_LAST_SENTENCE -> deleteLastSentence()
            VoiceCommand.NEW_PARAGRAPH -> newParagraph()
            VoiceCommand.NEW_CHAPTER -> addChapter()
            VoiceCommand.NEXT_CHAPTER -> stepChapter(1)
            VoiceCommand.PREVIOUS_CHAPTER -> stepChapter(-1)
            VoiceCommand.READ_BACK -> readBack()
            VoiceCommand.READ_CHAPTER -> readChapter()
            VoiceCommand.STOP_READING -> if (speaking) stopSpeaking() else asDictation()
            VoiceCommand.SUGGEST -> requestContinue()
            VoiceCommand.ACCEPT -> if (s != null && s.canInsert) acceptSuggestion() else asDictation()
            VoiceCommand.REJECT -> if (s != null) dismissSuggestion() else asDictation()
            VoiceCommand.RETRY -> if (s != null) retrySuggestion() else asDictation()
            VoiceCommand.READ_SUGGESTION -> if (s != null) readSuggestion() else asDictation()
            VoiceCommand.FIX -> requestFix()
            VoiceCommand.BRAINSTORM -> requestIdeas()
            VoiceCommand.ASK_BIBLE -> startSuggestion(SuggestionKind.ANSWER, input.argument)
            VoiceCommand.BIBLE_NOTE -> addBibleNote(input.argument)
            VoiceCommand.LEARN -> chapterId?.let { learnChapter(it) }
            VoiceCommand.SAVE -> {
                saveNow(); showMessage("Saved.", speak = true)
            }
            VoiceCommand.STOP_LISTENING -> {
                stopDictation(); showMessage("Microphone off.")
            }
            VoiceCommand.PICK_WORD -> pickPrediction((input.argument.toIntOrNull() ?: 1) - 1)
            VoiceCommand.HELP -> {
                showHelp = true
                speak("You can say: continue, accept, try again, discard, fix that, I'm stuck, read that back, undo, new paragraph, new chapter, learn this chapter, ask the bible, bible note, and stop listening.")
            }
        }
    }

    // ---- story bible ----

    fun addBibleNote(note: String) {
        val p = syncEditorIntoProject() ?: return
        val text = note.trim().replaceFirstChar { it.uppercase() }
        if (text.isBlank()) return
        val known = p.bible.entries.firstOrNull { e ->
            (listOf(e.name) + e.aliases).any { a -> a.isNotBlank() && Regex("\\b${Regex.escape(a)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) }
        }
        project = if (known != null) {
            val desc = listOf(known.description, text.trimEnd('.') + ".").filter { it.isNotBlank() }.joinToString(" ")
            p.copy(bible = p.bible.copy(entries = p.bible.entries.map {
                if (it.id == known.id) it.copy(description = desc, updatedAt = now()) else it
            }))
        } else {
            val name = TextTools.clip(text.trimEnd('.'), 40)
            p.copy(bible = p.bible.copy(entries = p.bible.entries + BibleEntry(category = BibleCategory.OTHER, name = name, description = text)))
        }
        scheduleSave(immediate = true)
        showMessage(if (known != null) "Added to ${known.name}." else "Saved to the story bible.", speak = dictation.isActive)
    }

    fun saveEntry(entry: BibleEntry) = mutateProject { p ->
        val exists = p.bible.entries.any { it.id == entry.id }
        val entries = if (exists) p.bible.entries.map { if (it.id == entry.id) entry.copy(updatedAt = now()) else it }
        else p.bible.entries + entry
        p.copy(bible = p.bible.copy(entries = entries))
    }

    fun deleteEntry(id: String) = mutateProject { p ->
        p.copy(bible = p.bible.copy(entries = p.bible.entries.filter { it.id != id }))
    }

    fun setPremise(text: String) = mutateProject { it.copy(bible = it.bible.copy(premise = text)) }
    fun setAuthorNotes(text: String) = mutateProject { it.copy(bible = it.bible.copy(authorNotes = text)) }

    fun acceptProposal(proposal: BibleProposal, edited: BibleProposal = proposal) = mutateProject { p ->
        val entries = if (edited.existingEntryId != null) {
            p.bible.entries.map { e ->
                if (e.id == edited.existingEntryId) e.copy(
                    description = listOf(e.description, edited.description).filter { it.isNotBlank() }.joinToString(" "),
                    updatedAt = now(),
                ) else e
            }
        } else {
            val mentions = p.chapters.sumOf { ch ->
                EntityExtractor.countMentions(ch.text, BibleEntry(category = edited.category, name = edited.name))
            }
            p.bible.entries + BibleEntry(
                category = edited.category,
                name = edited.name,
                description = edited.description,
                firstChapterId = edited.sourceChapterId,
                mentionCount = mentions,
            )
        }
        p.copy(bible = p.bible.copy(entries = entries), proposals = p.proposals.filter { it.id != proposal.id })
    }

    fun rejectProposal(proposal: BibleProposal) = mutateProject { p ->
        p.copy(
            proposals = p.proposals.filter { it.id != proposal.id },
            rejectedNames = if (proposal.existingEntryId == null) (p.rejectedNames + proposal.name).distinct() else p.rejectedNames,
        )
    }

    fun acceptAllProposals() {
        val list = project?.proposals.orEmpty()
        list.forEach { acceptProposal(it) }
        if (list.isNotEmpty()) showMessage("Added ${list.size} items to the story bible.")
    }

    // ---- learning ----

    private fun maybeAutoLearn(id: String) {
        if (!settingsRepo.current.autoLearn) return
        val ch = project?.chapter(id) ?: return
        if (ch.needsLearning && TextTools.wordCount(ch.text) >= 300) learnChapter(id, quiet = true)
    }

    fun learnChapter(id: String, quiet: Boolean = false) {
        viewModelScope.launch {
            learnLock.withLock { learnOne(id, quiet) }
        }
    }

    fun learnAll() {
        viewModelScope.launch {
            learnLock.withLock {
                val ids = project?.chapters?.filter { it.needsLearning }?.map { it.id }.orEmpty()
                if (ids.isEmpty()) {
                    showMessage("Everything has already been learned.")
                    return@withLock
                }
                ids.forEach { learnOne(it, quiet = true) }
                showMessage("Finished learning ${ids.size} chapter${if (ids.size == 1) "" else "s"}.", speak = dictation.isActive)
            }
        }
    }

    private suspend fun learnOne(id: String, quiet: Boolean) {
        val snapshot = syncEditorIntoProject() ?: return
        val title = snapshot.chapter(id)?.title ?: return
        learningStatus = "Learning from $title…"
        try {
            val result = withContext(Dispatchers.Default) { learningEngine.learn(snapshot, id, activeEngine()) }
            val current = syncEditorIntoProject() ?: return
            project = mergeLearned(snapshot, current, result.project)
            scheduleSave(immediate = true)
            rebuildModel(force = true)
            if (!quiet || result.newProposals > 0) {
                showMessage(result.message, speak = !quiet && dictation.isActive)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            showMessage("Learning stopped: ${e.message}")
        } finally {
            learningStatus = null
        }
    }

    /** Applies what was learned without losing edits made while learning ran. */
    private fun mergeLearned(snapshot: Project, current: Project, learned: Project): Project {
        val oldProposalIds = snapshot.proposals.map { it.id }.toSet()
        val newProposals = learned.proposals.filter { it.id !in oldProposalIds }
            .filter { np -> current.proposals.none { it.name.equals(np.name, true) && it.existingEntryId == np.existingEntryId } }
        val learnedChapters = learned.chapters.associateBy { it.id }
        val mentions = learned.bible.entries.associate { it.id to it.mentionCount }
        return current.copy(
            chapters = current.chapters.map { ch ->
                val l = learnedChapters[ch.id] ?: return@map ch
                if (l.learnedAt == snapshot.chapter(ch.id)?.learnedAt) ch
                else ch.copy(
                    summary = l.summary,
                    beat = if (ch.beatIsManual) ch.beat else l.beat,
                    learnedAt = l.learnedAt,
                    learnedTextHash = l.learnedTextHash,
                )
            },
            bible = current.bible.copy(
                entries = current.bible.entries.map { e -> mentions[e.id]?.let { e.copy(mentionCount = it) } ?: e },
                voiceDescription = learned.bible.voiceDescription,
                voiceHistory = learned.bible.voiceHistory,
            ),
            styleHistory = learned.styleHistory.filter { s -> current.chapters.any { it.id == s.chapterId } },
            proposals = current.proposals + newProposals,
            learningLog = learned.learningLog,
        )
    }

    // ---- model download ----

    fun startDownload(option: ModelOption) {
        val s = settingsRepo.current
        if (s.activeDownloadId >= 0) container.downloader.cancel(s.activeDownloadId)
        // Downloading over the model that's in use: stop using it first.
        val target = ModelCatalog.fileFor(getApplication(), option)
        if (s.onDeviceModelPath == target.absolutePath) {
            container.onDevice.releaseIfIdle()
            if (container.onDevice.isLoaded) {
                showMessage("The model is busy writing. Try again in a moment.")
                return
            }
            updateSettings { it.copy(onDeviceModelPath = "") }
        }
        val id = try {
            container.downloader.start(option, s.allowMobileDataDownload)
        } catch (e: Exception) {
            showMessage("Couldn't start the download: ${e.message}")
            return
        }
        settingsRepo.update { it.copy(activeDownloadId = id, activeDownloadModelId = option.id) }
        trackDownload()
    }

    fun cancelDownload() {
        val s = settingsRepo.current
        container.downloader.cancel(s.activeDownloadId)
        settingsRepo.update { it.copy(activeDownloadId = -1, activeDownloadModelId = "") }
        downloadJob?.cancel()
        downloadProgress = null
    }

    private fun resumeDownloadTracking() {
        if (settingsRepo.current.activeDownloadId >= 0) trackDownload()
    }

    private fun trackDownload() {
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            while (isActive) {
                val s = settingsRepo.current
                val progress = withContext(Dispatchers.IO) { container.downloader.progress(s.activeDownloadId) }
                downloadProgress = progress
                when (progress.status) {
                    DownloadProgress.Status.DONE -> {
                        val option = ModelCatalog.byId(s.activeDownloadModelId)
                        val file = option?.let { ModelCatalog.fileFor(getApplication(), it) }
                        if (file != null && file.exists()) {
                            updateSettings {
                                it.copy(
                                    onDeviceModelPath = file.absolutePath,
                                    engine = EngineChoice.ON_DEVICE,
                                    activeDownloadId = -1,
                                    activeDownloadModelId = "",
                                )
                            }
                            showMessage("${option.shortName} is ready. Suggestions now run on your phone.", speak = true)
                        } else {
                            settingsRepo.update { it.copy(activeDownloadId = -1, activeDownloadModelId = "") }
                            showMessage("The download finished but the file is missing. Try again.")
                        }
                        downloadProgress = null
                        return@launch
                    }
                    DownloadProgress.Status.FAILED, DownloadProgress.Status.MISSING -> {
                        settingsRepo.update { it.copy(activeDownloadId = -1, activeDownloadModelId = "") }
                        if (progress.status == DownloadProgress.Status.FAILED) showMessage(progress.reason)
                        downloadProgress = if (progress.status == DownloadProgress.Status.FAILED) progress else null
                        return@launch
                    }
                    else -> delay(1000)
                }
            }
        }
    }

    /** Copies a .litertlm file the author already has (e.g. in Downloads) into the app. */
    fun importModelFile(uri: Uri) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val name = queryName(uri) ?: "imported-model.litertlm"
            if (!name.endsWith(".litertlm")) {
                showMessage("That isn't a .litertlm model file.")
                return@launch
            }
            val target = File(ModelCatalog.modelsDir(ctx), name)
            importStatus = "Copying model…"
            try {
                withContext(Dispatchers.IO) {
                    val total = runCatching { ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: -1L
                    ctx.contentResolver.openInputStream(uri)!!.use { input ->
                        target.outputStream().use { out ->
                            val buf = ByteArray(1 shl 20)
                            var copied = 0L
                            var lastReport = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                copied += n
                                if (copied - lastReport > 50_000_000) {
                                    lastReport = copied
                                    val pct = if (total > 0) " ${(copied * 100 / total)}%" else ""
                                    withContext(Dispatchers.Main) { importStatus = "Copying model…$pct" }
                                }
                            }
                        }
                    }
                }
                updateSettings { it.copy(onDeviceModelPath = target.absolutePath, engine = EngineChoice.ON_DEVICE) }
                showMessage("Model imported. Suggestions now run on your phone.")
            } catch (e: Exception) {
                target.delete()
                showMessage("Couldn't import the model: ${e.message}")
            } finally {
                importStatus = null
            }
        }
    }

    fun deleteModel() {
        val path = settingsRepo.current.onDeviceModelPath
        container.onDevice.releaseIfIdle()
        if (path.isNotBlank()) File(path).delete()
        updateSettings { it.copy(onDeviceModelPath = "", engine = if (it.engine == EngineChoice.ON_DEVICE) EngineChoice.BUILT_IN else it.engine) }
        showMessage("Model deleted to free up space.")
    }

    private fun queryName(uri: Uri): String? =
        getApplication<Application>().contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    // ---- export / import ----

    fun exportTo(uri: Uri, kind: ExportKind) {
        viewModelScope.launch {
            flushSave()
            val p = project ?: return@launch
            val text = when (kind) {
                ExportKind.BACKUP -> repo.exportJson(p)
                ExportKind.MANUSCRIPT -> ProjectRepository.manuscript(p)
                ExportKind.BIBLE -> ProjectRepository.bibleText(p)
            }
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
                }.isSuccess
            }
            showMessage(if (ok) "Saved ${kind.label}." else "Couldn't save the file.")
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                    repo.importJson(text)
                }
            }
            result.onSuccess {
                refreshProjects()
                showMessage("Imported “${it.title}”.")
            }.onFailure { showMessage("That file isn't a Novel Assist backup.") }
        }
    }

    // ---- messages ----

    fun showMessage(text: String, speak: Boolean = false) {
        message = text
        if (speak) speaker.speak(text, settingsRepo.current.speechRate)
    }

    override fun onCleared() {
        dictation.destroy()
        speaker.shutdown()
        super.onCleared()
    }
}

enum class ExportKind(val label: String, val mime: String, val extension: String) {
    BACKUP("the backup", "application/json", "json"),
    MANUSCRIPT("the manuscript", "text/plain", "txt"),
    BIBLE("the story bible", "text/plain", "txt"),
}
