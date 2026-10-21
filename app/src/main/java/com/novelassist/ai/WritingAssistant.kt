package com.novelassist.ai

import com.novelassist.data.Chapter
import com.novelassist.data.Project
import com.novelassist.learning.OfflineTools
import com.novelassist.learning.PersonalLanguageModel
import com.novelassist.learning.PromptBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Turns the author's requests into AI calls. When no AI engine is set up, the
 * built-in personal model and offline tools answer instead.
 */
class WritingAssistant(
    private val engine: () -> AiEngine?,
    private val personalModel: () -> PersonalLanguageModel?,
) {
    val engineName: String get() = engine()?.displayName ?: "Built-in model"

    fun continueStory(project: Project, chapter: Chapter, textBefore: String, words: Int): Flow<String> {
        val ai = engine() ?: return flow {
            val model = personalModel()
            val text = model?.continueText(textBefore, maxWords = words.coerceAtMost(60)).orEmpty()
            if (text.isBlank()) {
                throw AiException(
                    "The built-in model needs more of your writing before it can continue. " +
                        "Keep dictating, or set up the on-device AI in Settings."
                )
            }
            emit(text)
        }
        val builder = PromptBuilder(project, ai.contextBudgetChars)
        return ai.stream(
            AiRequest(
                task = AiTask.CONTINUE,
                system = builder.system(chapter.id, textBefore),
                prompt = builder.continuation(chapter, textBefore, words),
                maxOutputTokens = (words * 2.2).toInt().coerceIn(120, 1200),
                temperature = 0.85,
            )
        )
    }

    fun fixDictation(project: Project, chapter: Chapter, passage: String): Flow<String> {
        val names = project.bible.entries.flatMap { listOf(it.name) + it.aliases }
        val ai = engine() ?: return flow { emit(OfflineTools.cleanDictation(passage, names)) }
        val builder = PromptBuilder(project, ai.contextBudgetChars)
        return ai.stream(
            AiRequest(
                task = AiTask.FIX,
                system = builder.system(chapter.id, passage),
                prompt = builder.fixDictation(passage),
                maxOutputTokens = (passage.length / 2.5).toInt().coerceIn(120, 2000),
                temperature = 0.2,
            )
        )
    }

    fun brainstorm(project: Project, chapter: Chapter, textBefore: String): Flow<String> {
        val ai = engine() ?: return flow { emit(OfflineTools.brainstorm(project)) }
        val builder = PromptBuilder(project, ai.contextBudgetChars)
        return ai.stream(
            AiRequest(
                task = AiTask.BRAINSTORM,
                system = builder.system(chapter.id, textBefore),
                prompt = builder.brainstorm(chapter, textBefore),
                maxOutputTokens = 400,
                temperature = 0.95,
            )
        )
    }

    fun ask(project: Project, chapter: Chapter?, question: String): Flow<String> {
        val ai = engine() ?: return flow { emit(OfflineTools.answer(project, question)) }
        val builder = PromptBuilder(project, ai.contextBudgetChars)
        return ai.stream(
            AiRequest(
                task = AiTask.ASK,
                system = builder.system(chapter?.id, question),
                prompt = builder.askBible(question),
                maxOutputTokens = 300,
                temperature = 0.3,
            )
        )
    }
}
