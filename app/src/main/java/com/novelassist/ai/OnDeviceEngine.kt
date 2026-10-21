package com.novelassist.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Runs an open model (Gemma or Qwen in LiteRT-LM format) entirely on the phone.
 * Nothing leaves the device. The model is loaded once and kept in memory.
 */
class OnDeviceEngine(
    private val context: Context,
    private val modelPath: () -> String,
    private val useGpu: () -> Boolean,
) : AiEngine {
    private val lock = Mutex()
    private var engine: Engine? = null
    private var loadedKey: String = ""

    override val displayName: String
        get() = ModelCatalog.byPath(modelPath())?.shortName ?: "On-device model"

    // Context is 4,096 tokens; leave room for the reply.
    override val contextBudgetChars: Int = 9_000

    override fun isReady(): Boolean = modelPath().let { it.isNotBlank() && File(it).length() > 50_000_000 }

    val isLoaded: Boolean get() = engine != null

    private fun load(): Engine {
        val path = modelPath()
        if (!isReady()) throw AiException("The on-device model isn't downloaded yet. Get it in Settings.")
        val key = "$path|${useGpu()}"
        engine?.let { if (loadedKey == key) return it }
        release()
        fun create(gpu: Boolean): Engine = Engine(
            EngineConfig(
                modelPath = path,
                backend = if (gpu) Backend.GPU() else Backend.CPU(),
                maxNumTokens = 4096,
                cacheDir = context.cacheDir.absolutePath,
            )
        ).also { it.initialize() }
        val created = try {
            create(useGpu())
        } catch (e: Throwable) {
            if (useGpu()) {
                // Not every phone's GPU is supported: fall back to the processor.
                runCatching { create(false) }.getOrElse { throw loadError(it) }
            } else throw loadError(e)
        }
        engine = created
        loadedKey = key
        return created
    }

    private fun loadError(e: Throwable): AiException {
        val oom = e is OutOfMemoryError || e.message.orEmpty().contains("memory", true)
        return AiException(
            if (oom) "Not enough free memory to load the on-device model. Close other apps or choose the smaller model."
            else "Couldn't start the on-device model (${e.message?.take(120)}). Try re-downloading it, or turn off GPU in Settings.",
            e,
        )
    }

    /** Loads the model ahead of time so the first suggestion is quick. */
    suspend fun warmUp() = lock.withLock { runCatching { load() } }

    /** Frees the model's memory unless it is busy writing. */
    fun releaseIfIdle() {
        if (!lock.isLocked) release()
    }

    fun release() {
        runCatching { engine?.close() }
        engine = null
        loadedKey = ""
    }

    override fun stream(request: AiRequest): Flow<String> = flow {
        lock.withLock {
            val eng = load()
            val config = ConversationConfig(
                systemInstruction = Contents.of(request.system),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = request.temperature, seed = (System.nanoTime() and 0x7fffffff).toInt()),
                // Qwen3 and Gemma can "think" first; for writing help we want the answer straight away.
                extraContext = mapOf("enable_thinking" to false),
                maxOutputToken = request.maxOutputTokens,
            )
            val conversation = eng.createConversation(config)
            val text = StringBuilder()
            try {
                conversation.sendMessageAsync(request.prompt).collect { message ->
                    text.append(textOf(message))
                    val visible = cleanOutput(text.toString())
                    if (visible.isNotEmpty()) emit(visible)
                }
            } catch (e: CancellationException) {
                runCatching { conversation.cancelProcess() }
                throw e
            } catch (e: Throwable) {
                throw AiException("The on-device model stopped unexpectedly (${e.message?.take(120)}).", e)
            } finally {
                runCatching { conversation.close() }
            }
            val final = cleanOutput(text.toString())
            if (final.isEmpty()) throw AiException("The on-device model gave an empty reply. Try again.")
            emit(final)
        }
    }.flowOn(Dispatchers.Default)

    private fun textOf(message: Message): String =
        message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    companion object {
        /** Removes any visible "thinking" and stray chat markers small models sometimes print. */
        fun cleanOutput(raw: String): String {
            var s = raw.replace(Regex("(?s)<think>.*?</think>"), "")
            if (s.contains("<think>")) s = s.substringBefore("<think>")
            s = s.replace(Regex("<\\|?(?:im_end|im_start|end_of_turn|start_of_turn|eot_id)\\|?>"), "")
            return s.trim()
        }
    }
}
