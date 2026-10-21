package com.novelassist.ai

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File

data class ModelOption(
    val id: String,
    val name: String,
    val shortName: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val description: String,
)

/** Open models that run on the phone and can be downloaded without an account. */
object ModelCatalog {
    val options = listOf(
        ModelOption(
            id = "gemma4-e2b",
            name = "Gemma 4 E2B (recommended)",
            shortName = "Gemma 4",
            fileName = "gemma-4-E2B-it.litertlm",
            url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            sizeBytes = 2_588_147_712L,
            description = "Google's on-device model. Best writing quality. 2.6 GB download; works best on phones with 8 GB of memory.",
        ),
        ModelOption(
            id = "qwen3-0.6b",
            name = "Qwen3 0.6B (small)",
            shortName = "Qwen3 small",
            fileName = "Qwen3-0.6B.litertlm",
            url = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",
            sizeBytes = 613_722_264L,
            description = "Small and quick. 0.6 GB download for older or lower-memory phones. Simpler suggestions.",
        ),
    )

    fun modelsDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply { mkdirs() }

    fun fileFor(context: Context, option: ModelOption): File = File(modelsDir(context), option.fileName)

    fun byPath(path: String): ModelOption? = options.firstOrNull { path.endsWith(it.fileName) }

    fun byId(id: String): ModelOption? = options.firstOrNull { it.id == id }
}

data class DownloadProgress(
    val status: Status,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val reason: String = "",
) {
    enum class Status { RUNNING, PAUSED, DONE, FAILED, MISSING }

    val fraction: Float get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

/**
 * Downloads a model with Android's download manager, which keeps going in the
 * background, survives the app closing and shows progress in notifications.
 */
class ModelDownloader(private val context: Context) {
    private val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    fun start(option: ModelOption, allowMobileData: Boolean): Long {
        val target = ModelCatalog.fileFor(context, option)
        if (target.exists()) target.delete()
        val request = DownloadManager.Request(Uri.parse(option.url))
            .setTitle("Novel Assist: ${option.shortName}")
            .setDescription("Downloading the on-device writing model")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, null, "models/${option.fileName}")
            .setAllowedOverMetered(allowMobileData)
            .setAllowedOverRoaming(false)
        return manager.enqueue(request)
    }

    fun cancel(id: Long) {
        if (id >= 0) manager.remove(id)
    }

    fun progress(id: Long): DownloadProgress {
        if (id < 0) return DownloadProgress(DownloadProgress.Status.MISSING, 0, 0)
        manager.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (c == null || !c.moveToFirst()) return DownloadProgress(DownloadProgress.Status.MISSING, 0, 0)
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> DownloadProgress(DownloadProgress.Status.DONE, done, total)
                DownloadManager.STATUS_FAILED -> DownloadProgress(DownloadProgress.Status.FAILED, done, total, failureText(reason))
                DownloadManager.STATUS_PAUSED -> DownloadProgress(DownloadProgress.Status.PAUSED, done, total, pauseText(reason))
                else -> DownloadProgress(DownloadProgress.Status.RUNNING, done, total)
            }
        }
    }

    private fun pauseText(reason: Int) = when (reason) {
        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Waiting for Wi-Fi (or allow mobile data in Settings)."
        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for an internet connection."
        DownloadManager.PAUSED_WAITING_TO_RETRY -> "Connection dropped; retrying."
        else -> "Paused."
    }

    private fun failureText(reason: Int) = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough storage space on the phone."
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Storage isn't available."
        DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "The connection failed. Try again."
        else -> "The download failed (code $reason). Try again."
    }
}
