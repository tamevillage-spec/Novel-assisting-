package com.novelassist.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaFallbacksParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** Claude models offered in Settings. */
object ClaudeModels {
    val options = listOf(
        "claude-opus-5-5" to "Claude Opus 5.5 (best writing)",
        "claude-sonnet-5-5" to "Claude Sonnet 5.5 (faster, cheaper)",
        "claude-haiku-5-5" to "Claude Haiku 5.5 (fastest, cheapest)",
    )

    fun label(id: String) = options.firstOrNull { it.first == id }?.second ?: id
}

/**
 * Cloud engine using the official Anthropic Java SDK. Only the passages the
 * author asks about are sent, and only when they choose Claude in Settings.
 */
class ClaudeEngine(
    private val apiKey: () -> String,
    private val model: () -> String,
) : AiEngine {
    private var client: AnthropicClient? = null
    private var clientKey: String = ""

    override val displayName: String get() = ClaudeModels.label(model()).substringBefore(" (")
    override val contextBudgetChars: Int = 90_000

    override fun isReady(): Boolean = apiKey().isNotBlank()

    @Synchronized
    private fun client(): AnthropicClient {
        val key = apiKey().trim()
        if (key.isEmpty()) throw AiException("Add your Claude API key in Settings first.")
        val existing = client
        if (existing != null && clientKey == key) return existing
        existing?.close()
        return AnthropicOkHttpClient.builder().apiKey(key).build().also {
            client = it
            clientKey = key
        }
    }

    override fun stream(request: AiRequest): Flow<String> = flow {
        val modelId = model()
        val effort = when (request.task) {
            AiTask.LEARN, AiTask.VOICE -> BetaOutputConfig.Effort.MEDIUM
            else -> BetaOutputConfig.Effort.LOW
        }
        val builder = MessageCreateParams.builder()
            .model(modelId)
            // Generous ceiling: thinking also counts against it, and length is steered by the prompt.
            .maxTokens(16_000L)
            .system(request.system)
            .addUserMessage(request.prompt)
            .outputConfig(BetaOutputConfig.builder().effort(effort).build())
        // If a request is declined, let the API retry it on a recommended fallback model.
        // Haiku has no server-side fallback, so it is left out there.
        if (!modelId.startsWith("claude-haiku")) {
            builder.addBeta("server-side-fallback-2026-07-01")
            builder.fallbacks(BetaFallbacksParam.ofDefault())
        }
        val params = builder.build()

        val text = StringBuilder()
        var refused = false
        try {
            client().beta().messages().createStreaming(params).use { response ->
                for (event in response.stream().iterator()) {
                    currentCoroutineContext().ensureActive()
                    event.contentBlockStart().ifPresent { start ->
                        // A fallback model took over: drop the partial text from the declined attempt.
                        if (start.contentBlock().isFallback()) {
                            text.setLength(0)
                            refused = false
                        }
                    }
                    event.contentBlockDelta().ifPresent { delta ->
                        delta.delta().text().ifPresent { t -> text.append(t.text()) }
                    }
                    event.messageDelta().ifPresent { md ->
                        md.delta().stopReason().ifPresent { reason ->
                            if (reason == BetaStopReason.REFUSAL) refused = true
                        }
                    }
                    if (text.isNotEmpty() && !refused) emit(text.toString())
                }
            }
        } catch (e: UnauthorizedException) {
            throw AiException("Claude didn't accept the API key. Check it in Settings.", e)
        } catch (e: PermissionDeniedException) {
            throw AiException("This API key can't use ${ClaudeModels.label(modelId)}.", e)
        } catch (e: NotFoundException) {
            throw AiException("That Claude model isn't available to this key. Pick another in Settings.", e)
        } catch (e: RateLimitException) {
            throw AiException("Claude is busy or the account limit was reached. Try again in a minute.", e)
        } catch (e: InternalServerException) {
            throw AiException("Claude had a server problem. Try again.", e)
        } catch (e: BadRequestException) {
            throw AiException("Claude couldn't process that request: ${e.message?.take(160)}", e)
        } catch (e: AnthropicServiceException) {
            throw AiException("Claude returned an error (${e.statusCode()}). Try again.", e)
        } catch (e: AnthropicIoException) {
            throw AiException("Couldn't reach Claude. Check the internet connection.", e)
        }
        if (refused) throw AiException("Claude declined to write this one. Try rephrasing, or use the on-device model.")
        if (text.isEmpty()) throw AiException("Claude sent back an empty reply. Try again.")
        emit(text.toString())
    }.flowOn(Dispatchers.IO)
}
