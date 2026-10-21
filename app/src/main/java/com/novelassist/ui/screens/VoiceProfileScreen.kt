package com.novelassist.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.novelassist.data.Project
import com.novelassist.data.StyleMetrics
import com.novelassist.learning.StyleAnalyzer
import com.novelassist.learning.TextTools
import com.novelassist.ui.AppViewModel
import com.novelassist.ui.components.AppIcons
import com.novelassist.ui.components.BigButton
import com.novelassist.ui.components.ChartSeries
import com.novelassist.ui.components.LineChart
import com.novelassist.ui.components.SectionCard
import com.novelassist.ui.components.VoiceTextField
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceProfileScreen(vm: AppViewModel, project: Project, modifier: Modifier = Modifier) {
    val bible = project.bible
    val snapshots = project.styleHistory.sortedBy { it.chapterIndex }
    val combined = StyleAnalyzer.combine(snapshots.map { it.metrics })
    val trends = StyleAnalyzer.trends(snapshots)
    var showHistory by remember { mutableStateOf(false) }
    var notesDraft by remember(project.id) { mutableStateOf(bible.authorNotes) }
    val df = DateFormat.getDateInstance(DateFormat.MEDIUM)

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(
            "Your voice, as learned so far",
            subtitle = bible.voiceHistory.lastOrNull()?.let {
                "Updated ${df.format(Date(it.createdAt))} from ${it.chapterCount} chapter${if (it.chapterCount == 1) "" else "s"} (${it.source})"
            } ?: "Learn a chapter to build your voice profile.",
            container = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Text(
                bible.voiceDescription.ifBlank { StyleAnalyzer.describe(combined) },
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { vm.speak(bible.voiceDescription.ifBlank { StyleAnalyzer.describe(combined) }) }) { Text("Read aloud") }
                if (bible.voiceHistory.size > 1) {
                    TextButton(onClick = { showHistory = !showHistory }) {
                        Text(if (showHistory) "Hide history" else "How it evolved (${bible.voiceHistory.size})")
                    }
                }
            }
            if (showHistory) {
                bible.voiceHistory.asReversed().forEach { v ->
                    HorizontalDivider()
                    Text(
                        "${df.format(Date(v.createdAt))} · ${v.chapterCount} chapters · ${TextTools.formatCount(v.wordCount)} words",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(v.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (trends.isNotEmpty()) {
            SectionCard("How your prose is changing") {
                trends.forEach { Text("• $it", style = MaterialTheme.typography.bodyLarge) }
            }
        }

        SectionCard(
            "In your own words",
            subtitle = "Describe how you want the book to sound. The AI follows this as closely as your measured style.",
        ) {
            VoiceTextField(value = notesDraft, onValueChange = { notesDraft = it }, label = "My style and intentions", minLines = 3)
            if (notesDraft != bible.authorNotes) {
                Button(onClick = { vm.setAuthorNotes(notesDraft) }) { Text("Save") }
            }
        }

        if (combined.wordCount > 0) {
            SectionCard("Measurements (whole book)") {
                MetricsGrid(combined)
                if (combined.favouriteWords.isNotEmpty()) {
                    Text("Favourite words", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        combined.favouriteWords.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
                    }
                }
                if (combined.signaturePhrases.isNotEmpty()) {
                    Text("Recurring phrases", style = MaterialTheme.typography.titleSmall)
                    combined.signaturePhrases.forEach { Text("“$it”", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }

        if (snapshots.size >= 2) {
            SectionCard("Chapter by chapter", subtitle = "Each line is scaled to its own range, so you can see the shape of change.") {
                LineChart(
                    series = listOf(
                        ChartSeries("Sentence length", MaterialTheme.colorScheme.primary, snapshots.map { it.metrics.avgSentenceLength }),
                        ChartSeries("Dialogue", MaterialTheme.colorScheme.secondary, snapshots.map { it.metrics.dialogueRatio * 100 }),
                        ChartSeries("Vocabulary", Color(0xFF7B5EA7), snapshots.map { it.metrics.lexicalDiversity * 100 }),
                    ),
                    labels = listOf(snapshots.first().chapterTitle, snapshots.last().chapterTitle),
                )
            }
        }

        SectionCard("Your personal word model", subtitle = "Learns only from your writing and powers the word suggestions above the microphone.") {
            Text(vm.modelStats.ifBlank { "Building…" }, style = MaterialTheme.typography.bodyMedium)
        }

        BigButton(
            "Learn all new writing",
            AppIcons.School,
            onClick = vm::learnAll,
            enabled = vm.learningStatus == null,
            modifier = Modifier.fillMaxWidth(),
            primary = true,
        )
        vm.learningStatus?.let { Text(it, style = MaterialTheme.typography.labelLarge) }

        if (project.learningLog.isNotEmpty()) {
            SectionCard("Learning diary") {
                project.learningLog.takeLast(12).asReversed().forEach { e ->
                    Text(
                        "${df.format(Date(e.at))}: ${e.summary}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricsGrid(m: StyleMetrics) {
    val rows = listOf(
        "Point of view" to m.pointOfView.ifBlank { "—" },
        "Tense" to m.tense.ifBlank { "—" },
        "Words per sentence" to "${m.avgSentenceLength.roundToInt()} (varies ±${m.sentenceLengthSpread.roundToInt()})",
        "Dialogue" to StyleAnalyzer.pct(m.dialogueRatio),
        "Vocabulary variety" to "%.2f".format(m.lexicalDiversity),
        "Adverbs per 100 words" to "%.1f".format(m.adverbsPer100),
        "Sentences per paragraph" to "%.1f".format(m.sentencesPerParagraph),
        "Commas per sentence" to "%.1f".format(m.commasPerSentence),
        "Words measured" to TextTools.formatCount(m.wordCount),
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth()) {
                Text(k, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(v, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
