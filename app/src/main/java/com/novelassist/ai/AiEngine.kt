package com.novelassist.ai

import kotlinx.coroutines.flow.Flow

enum class AiTask { CONTINUE, FIX, BRAINSTORM, ASK, LEARN, VOICE }

data class AiRequest(
    val task: AiTask,
    val system: String,
    val prompt: String,
    /** Rough cap on the visible reply. */
    val maxOutputTokens: Int,
    val temperature: Double = 0.8,
)

/** A friendly, speakable error. */
class AiException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface AiEngine {
    val displayName: String

    /** How much context (in characters) this engine can comfortably take. */
    val contextBudgetChars: Int

    fun isReady(): Boolean

    /** Streams the reply. Each emission is the full text so far, not a fragment. */
    fun stream(request: AiRequest): Flow<String>
}
