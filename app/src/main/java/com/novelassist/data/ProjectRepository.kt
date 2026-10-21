package com.novelassist.data

import com.novelassist.learning.TextTools
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
    explicitNulls = false
}

/**
 * Stores each novel as one JSON file. Writes are atomic (write a temp file, then
 * rename) and older copies are kept in a rolling backup folder, so a crash or a
 * bad edit never loses the author's work.
 */
class ProjectRepository(private val root: File) {
    private val projectsDir = File(root, "projects").apply { mkdirs() }
    private val backupsDir = File(root, "backups").apply { mkdirs() }
    private val lastBackupAt = HashMap<String, Long>()

    private fun fileFor(id: String) = File(projectsDir, "$id.json")

    fun listProjects(): List<ProjectSummary> =
        projectsDir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { f -> runCatching { decode(f.readText()) }.getOrNull() }
            .map { p ->
                ProjectSummary(p.id, p.title, p.chapters.size, p.wordCount, p.updatedAt)
            }
            .sortedByDescending { it.updatedAt }

    fun load(id: String): Project? {
        val file = fileFor(id)
        if (file.exists()) {
            runCatching { return decode(file.readText()) }
        }
        // Main file unreadable: fall back to the newest backup.
        return backupsFor(id).firstNotNullOfOrNull { runCatching { decode(it.readText()) }.getOrNull() }
    }

    @Synchronized
    fun save(project: Project) {
        val file = fileFor(project.id)
        maybeBackup(project.id, file)
        val tmp = File(projectsDir, "${project.id}.json.tmp")
        tmp.outputStream().use { out ->
            out.write(AppJson.encodeToString(Project.serializer(), project).toByteArray())
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    /** Deleting moves the novel into the backup folder instead of erasing it. */
    @Synchronized
    fun delete(id: String) {
        val file = fileFor(id)
        if (file.exists()) {
            file.copyTo(File(backupsDir, "deleted-$id-${now()}.json"), overwrite = true)
            file.delete()
        }
    }

    fun exportJson(project: Project): String =
        AppJson.encodeToString(Project.serializer(), project)

    /** Imports a backup. If a novel with the same id exists, the import becomes a copy. */
    fun importJson(text: String): Project {
        val parsed = decode(text)
        val project = if (fileFor(parsed.id).exists()) {
            parsed.copy(id = newId(), title = parsed.title + " (imported)")
        } else parsed
        save(project)
        return project
    }

    private fun decode(text: String): Project = AppJson.decodeFromString(Project.serializer(), text)

    private fun backupsFor(id: String): List<File> =
        backupsDir.listFiles { f -> f.name.startsWith("$id-") && f.extension == "json" }.orEmpty()
            .sortedByDescending { it.lastModified() }

    private fun maybeBackup(id: String, current: File) {
        if (!current.exists()) return
        val last = lastBackupAt[id] ?: backupsFor(id).firstOrNull()?.lastModified() ?: 0L
        if (now() - last < BACKUP_INTERVAL_MS) return
        current.copyTo(File(backupsDir, "$id-${now()}.json"), overwrite = true)
        lastBackupAt[id] = now()
        backupsFor(id).drop(MAX_BACKUPS).forEach { it.delete() }
    }

    companion object {
        private const val BACKUP_INTERVAL_MS = 10 * 60 * 1000L
        private const val MAX_BACKUPS = 12

        fun manuscript(project: Project): String = buildString {
            appendLine(project.title)
            appendLine()
            project.chapters.forEach { ch ->
                appendLine(ch.title)
                appendLine()
                appendLine(ch.text.trim())
                appendLine()
                appendLine()
            }
        }

        fun bibleText(project: Project): String = buildString {
            val df = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
            appendLine("STORY BIBLE — ${project.title}")
            appendLine("${TextTools.formatCount(project.wordCount)} words, ${project.chapters.size} chapters")
            appendLine()
            if (project.bible.premise.isNotBlank()) {
                appendLine("PREMISE"); appendLine(project.bible.premise); appendLine()
            }
            if (project.bible.voiceDescription.isNotBlank()) {
                appendLine("THE AUTHOR'S VOICE"); appendLine(project.bible.voiceDescription); appendLine()
            }
            BibleCategory.entries.forEach { cat ->
                val items = project.bible.entries.filter { it.category == cat }
                if (items.isNotEmpty()) {
                    appendLine(cat.label.uppercase())
                    items.forEach { appendLine("• ${it.name}: ${it.description}") }
                    appendLine()
                }
            }
            if (project.bible.voiceHistory.isNotEmpty()) {
                appendLine("HOW THE VOICE EVOLVED")
                project.bible.voiceHistory.forEach {
                    appendLine("— ${df.format(Date(it.createdAt))} (${it.chapterCount} chapters): ${it.text}")
                }
            }
        }
    }
}
