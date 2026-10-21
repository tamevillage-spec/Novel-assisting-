package com.novelassist.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class EngineChoice { BUILT_IN, ON_DEVICE, CLAUDE }

data class AppSettings(
    val engine: EngineChoice = EngineChoice.BUILT_IN,
    val claudeApiKey: String = "",
    val claudeModel: String = "claude-opus-5-5",
    val onDeviceModelPath: String = "",
    val onDeviceUseGpu: Boolean = false,
    val allowMobileDataDownload: Boolean = false,
    val activeDownloadId: Long = -1L,
    val activeDownloadModelId: String = "",
    /** Keep the microphone listening between sentences. */
    val handsFree: Boolean = true,
    /** Start listening as soon as a chapter is opened. */
    val autoStartListening: Boolean = false,
    val spokenPunctuation: Boolean = true,
    /** Require the word "command" before voice commands. */
    val strictCommands: Boolean = false,
    /** Let Android add punctuation automatically (Android 13+). */
    val autoPunctuation: Boolean = false,
    val preferOfflineSpeech: Boolean = false,
    val autoReadSuggestions: Boolean = true,
    val speechRate: Float = 1.0f,
    val textScale: Float = 1.15f,
    val suggestionWords: Int = 120,
    val autoLearn: Boolean = true,
    val lastProjectId: String = "",
)

/** Settings live in private app storage; they are excluded from cloud backup. */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = state.asStateFlow()
    val current: AppSettings get() = state.value

    fun update(change: (AppSettings) -> AppSettings) {
        val next = change(state.value)
        write(next)
        state.value = next
    }

    private fun read(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            engine = runCatching { EngineChoice.valueOf(prefs.getString("engine", d.engine.name)!!) }.getOrDefault(d.engine),
            claudeApiKey = prefs.getString("claudeApiKey", d.claudeApiKey)!!,
            claudeModel = prefs.getString("claudeModel", d.claudeModel)!!,
            onDeviceModelPath = prefs.getString("onDeviceModelPath", d.onDeviceModelPath)!!,
            onDeviceUseGpu = prefs.getBoolean("onDeviceUseGpu", d.onDeviceUseGpu),
            allowMobileDataDownload = prefs.getBoolean("allowMobileDataDownload", d.allowMobileDataDownload),
            activeDownloadId = prefs.getLong("activeDownloadId", d.activeDownloadId),
            activeDownloadModelId = prefs.getString("activeDownloadModelId", d.activeDownloadModelId)!!,
            handsFree = prefs.getBoolean("handsFree", d.handsFree),
            autoStartListening = prefs.getBoolean("autoStartListening", d.autoStartListening),
            spokenPunctuation = prefs.getBoolean("spokenPunctuation", d.spokenPunctuation),
            strictCommands = prefs.getBoolean("strictCommands", d.strictCommands),
            autoPunctuation = prefs.getBoolean("autoPunctuation", d.autoPunctuation),
            preferOfflineSpeech = prefs.getBoolean("preferOfflineSpeech", d.preferOfflineSpeech),
            autoReadSuggestions = prefs.getBoolean("autoReadSuggestions", d.autoReadSuggestions),
            speechRate = prefs.getFloat("speechRate", d.speechRate),
            textScale = prefs.getFloat("textScale", d.textScale),
            suggestionWords = prefs.getInt("suggestionWords", d.suggestionWords),
            autoLearn = prefs.getBoolean("autoLearn", d.autoLearn),
            lastProjectId = prefs.getString("lastProjectId", d.lastProjectId)!!,
        )
    }

    private fun write(s: AppSettings) {
        prefs.edit()
            .putString("engine", s.engine.name)
            .putString("claudeApiKey", s.claudeApiKey)
            .putString("claudeModel", s.claudeModel)
            .putString("onDeviceModelPath", s.onDeviceModelPath)
            .putBoolean("onDeviceUseGpu", s.onDeviceUseGpu)
            .putBoolean("allowMobileDataDownload", s.allowMobileDataDownload)
            .putLong("activeDownloadId", s.activeDownloadId)
            .putString("activeDownloadModelId", s.activeDownloadModelId)
            .putBoolean("handsFree", s.handsFree)
            .putBoolean("autoStartListening", s.autoStartListening)
            .putBoolean("spokenPunctuation", s.spokenPunctuation)
            .putBoolean("strictCommands", s.strictCommands)
            .putBoolean("autoPunctuation", s.autoPunctuation)
            .putBoolean("preferOfflineSpeech", s.preferOfflineSpeech)
            .putBoolean("autoReadSuggestions", s.autoReadSuggestions)
            .putFloat("speechRate", s.speechRate)
            .putFloat("textScale", s.textScale)
            .putInt("suggestionWords", s.suggestionWords)
            .putBoolean("autoLearn", s.autoLearn)
            .putString("lastProjectId", s.lastProjectId)
            .apply()
    }
}
