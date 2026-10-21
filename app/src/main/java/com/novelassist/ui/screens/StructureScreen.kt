package com.novelassist.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.novelassist.data.Chapter
import com.novelassist.data.Project
import com.novelassist.learning.StoryStructures
import com.novelassist.learning.TextTools
import com.novelassist.ui.AppViewModel
import com.novelassist.ui.components.AppIcons
import com.novelassist.ui.components.BigButton
import com.novelassist.ui.components.ConfirmDialog
import com.novelassist.ui.components.SectionCard
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StructureScreen(vm: AppViewModel, project: Project, modifier: Modifier = Modifier) {
    val template = StoryStructures.byId(project.structureTemplateId)
    val words = project.wordCount
    val position = StoryStructures.position(template, words, project.targetWordCount)
    val chapterPositions = StoryStructures.chapterPositions(project.chapters, project.targetWordCount)
    val insights = StoryStructures.insights(project)
    var target by remember(project.targetWordCount) { mutableFloatStateOf(project.targetWordCount.toFloat()) }
    var beatFor by remember { mutableStateOf<Chapter?>(null) }
    var deleting by remember { mutableStateOf<Chapter?>(null) }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(
                "You're ${(position.progress * 100).roundToInt()}% of the way through",
                subtitle = "${TextTools.formatCount(words)} of ${TextTools.formatCount(project.targetWordCount)} planned words",
                container = MaterialTheme.colorScheme.primaryContainer,
            ) {
                StoryBar(project, template.beats.map { it.position }, chapterPositions, position.progress)
                Text("Now: ${position.current.name}", style = MaterialTheme.typography.titleMedium)
                Text(position.current.description, style = MaterialTheme.typography.bodyMedium)
                position.next?.let {
                    Text(
                        "Next: ${it.name}, in about ${TextTools.formatCount(position.wordsToNext)} words",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(it.description, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (insights.isNotEmpty()) {
            item {
                SectionCard("What I've learned about your structure") {
                    insights.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    FilledTonalButton(onClick = { vm.speak(insights.joinToString(" ")) }) { Text("Read aloud") }
                }
            }
        }

        item {
            SectionCard("Story shape", subtitle = template.description) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StoryStructures.all.forEach { t ->
                        FilterChip(
                            selected = t.id == template.id,
                            onClick = { vm.setStructure(t.id) },
                            label = { Text(t.name) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
                Text("Planned length: ${TextTools.formatCount(target.roundToInt())} words", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = target,
                    onValueChange = { target = (it / 5000).roundToInt() * 5000f },
                    onValueChangeFinished = { vm.setTargetWords(target.roundToInt()) },
                    valueRange = 20_000f..200_000f,
                )
            }
        }

        item {
            BigButton(
                "Learn all new writing",
                AppIcons.School,
                onClick = vm::learnAll,
                enabled = vm.learningStatus == null,
                modifier = Modifier.fillMaxWidth(),
                primary = true,
            )
            vm.learningStatus?.let { Text(it, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp)) }
        }

        items(project.chapters, key = { it.id }) { ch ->
            val index = project.chapters.indexOf(ch)
            val pos = chapterPositions.getOrElse(index) { 0.0 }
            val expected = StoryStructures.expectedBeat(template, pos)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth(),
                onClick = { vm.selectChapter(ch.id); vm.screen = com.novelassist.ui.Screen.WRITE },
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row {
                        Text(ch.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(
                            "${TextTools.formatCount(TextTools.wordCount(ch.text))} words · ${(pos * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    Text(
                        "Beat: " + ch.beat.ifBlank { "${expected.name} (expected here)" } + if (ch.beatIsManual) " (set by you)" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (ch.summary.isNotBlank()) Text(ch.summary, style = MaterialTheme.typography.bodyMedium)
                    else if (ch.text.isNotBlank()) Text("Not learned yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { vm.learnChapter(ch.id) }, enabled = vm.learningStatus == null && ch.text.isNotBlank()) {
                            Text(if (ch.needsLearning) "Learn" else "Learn again")
                        }
                        OutlinedButton(onClick = { beatFor = ch }) { Text("Set beat") }
                        IconButton(onClick = { vm.moveChapter(ch.id, -1) }, enabled = index > 0) {
                            Icon(AppIcons.ArrowUp, contentDescription = "Move ${ch.title} earlier")
                        }
                        IconButton(onClick = { vm.moveChapter(ch.id, 1) }, enabled = index < project.chapters.lastIndex) {
                            Icon(AppIcons.ArrowDown, contentDescription = "Move ${ch.title} later")
                        }
                        IconButton(onClick = { deleting = ch }) {
                            Icon(AppIcons.Delete, contentDescription = "Delete ${ch.title}")
                        }
                    }
                }
            }
        }
    }

    beatFor?.let { ch ->
        AlertDialog(
            onDismissRequest = { beatFor = null },
            title = { Text("Which beat is ${ch.title}?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    template.beats.forEach { b ->
                        OutlinedButton(
                            onClick = { vm.setChapterBeat(ch.id, b.name); beatFor = null },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text(b.name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.setChapterBeat(ch.id, ""); beatFor = null }) { Text("Let the app decide") } },
            dismissButton = { TextButton(onClick = { beatFor = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { ch ->
        ConfirmDialog(
            title = "Delete ${ch.title}?",
            text = "Its text will be removed. (An older copy stays in the app's backups.)",
            confirm = "Delete",
            onConfirm = { vm.deleteChapter(ch.id); deleting = null },
            onDismiss = { deleting = null },
        )
    }
}

/** A bar for the whole book: chapters as blocks, beats as ticks, and a marker for "you are here". */
@Composable
private fun StoryBar(project: Project, beats: List<Double>, chapters: List<Double>, progress: Double) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val tick = MaterialTheme.colorScheme.onSurfaceVariant
    val chapterColor = MaterialTheme.colorScheme.secondary
    val total = maxOf(project.targetWordCount, project.wordCount, 1).toFloat()
    val counts = project.chapters.map { TextTools.wordCount(it.text) }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .semantics { contentDescription = "Story progress ${(progress * 100).roundToInt()} percent, ${chapters.size} chapters" }
    ) {
        val barTop = size.height * 0.35f
        val barHeight = size.height * 0.4f
        drawRoundRect(track, Offset(0f, barTop), Size(size.width, barHeight), CornerRadius(12f))
        var x = 0f
        counts.forEach { c ->
            val w = size.width * c / total
            if (w > 1f) {
                drawRoundRect(chapterColor.copy(alpha = 0.55f), Offset(x + 1f, barTop), Size((w - 2f).coerceAtLeast(1f), barHeight), CornerRadius(8f))
            }
            x += w
        }
        beats.forEach { b ->
            val bx = size.width * b.toFloat()
            drawLine(tick, Offset(bx, barTop - 8f), Offset(bx, barTop + barHeight + 8f), strokeWidth = 3f)
        }
        val px = (size.width * progress.toFloat()).coerceIn(4f, size.width - 4f)
        drawCircle(primary, radius = size.height * 0.2f, center = Offset(px, barTop + barHeight / 2))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Start", style = MaterialTheme.typography.labelSmall)
        Text("Middle", style = MaterialTheme.typography.labelSmall)
        Text("End", style = MaterialTheme.typography.labelSmall)
    }
}
