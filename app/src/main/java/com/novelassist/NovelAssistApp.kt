package com.novelassist

import android.app.Application
import android.content.ComponentCallbacks2
import com.novelassist.ai.ClaudeEngine
import com.novelassist.ai.ModelDownloader
import com.novelassist.ai.OnDeviceEngine
import com.novelassist.data.ProjectRepository
import com.novelassist.data.SettingsRepository

/** Holds the app-wide services. */
class NovelAssistApp : Application() {
    lateinit var settings: SettingsRepository
        private set
    lateinit var projects: ProjectRepository
        private set
    lateinit var onDevice: OnDeviceEngine
        private set
    lateinit var claude: ClaudeEngine
        private set
    lateinit var downloader: ModelDownloader
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        projects = ProjectRepository(filesDir)
        onDevice = OnDeviceEngine(
            context = this,
            modelPath = { settings.current.onDeviceModelPath },
            useGpu = { settings.current.onDeviceUseGpu },
        )
        claude = ClaudeEngine(
            apiKey = { settings.current.claudeApiKey },
            model = { settings.current.claudeModel },
        )
        downloader = ModelDownloader(this)
    }

    @Deprecated("Deprecated in Java")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The on-device model uses a lot of memory; let it go if Android is running out.
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) onDevice.releaseIfIdle()
    }
}
