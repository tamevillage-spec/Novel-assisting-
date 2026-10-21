package com.novelassist.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.novelassist.data.AppSettings
import com.novelassist.learning.TextTools
import com.novelassist.ui.AppViewModel
import com.novelassist.ui.Suggestion
import com.novelassist.ui.components.AppIcons
import com.novelassist.ui.components.ConfirmDialog
import com.novelassist.ui.components.VoiceInputDialog
import com.novelassist.ui.theme.ManuscriptStyle
import com.novelassist.voice.MicState

@Composable
fun WriteScreen(vm: AppViewModel, settings: AppSettings, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startDictation()
        else vm.showMessage("Novel Assist needs the microphone to take dictation. You can allow it in the phone's Settings.")
    }
    fun mic() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted || vm.micState != MicState.OFF) vm.toggleMic() else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    DisposableEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) vm.onEnterWriteScreen()
        onDispose { vm.onLeaveWriteScreen() }
    }

    Column(modifier.fillMaxSize()) {
        ChapterBar(vm)
        vm.learningStatus?.let { status ->
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(status, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        }

        Editor(vm, Modifier.weight(1f))

        if (vm.partialSpeech.isNotBlank()) {
            Text(
                "“${vm.partialSpeech}”",
                style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        vm.suggestion?.let { SuggestionCard(vm, it) }

        if (vm.suggestion == null && vm.predictions.isNotEmpty()) {
            PredictionRow(vm)
        }

        HorizontalDivider()
        ControlPanel(vm, settings, onMic = ::mic)
    }
}

@Composable
private fun ChapterBar(vm: AppViewModel) {
    val project = vm.project ?: return
    val chapter = vm.currentChapter ?: return
    var picking by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val index = project.chapters.indexOfFirst { it.id == chapter.id }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { vm.stepChapter(-1) }, enabled = index > 0, modifier = Modifier.size(52.dp)) {
            Icon(AppIcons.ChevronLeft, contentDescription = "Previous chapter")
        }
        Column(
            Modifier
                .weight(1f)
                .clickable(role = Role.Button, onClickLabel = "Choose chapter") { picking = true }
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(chapter.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${TextTools.formatCount(TextTools.wordCount(vm.editor.text))} words · ${index + 1} of ${project.chapters.size} · ${vm.engineLabel}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { vm.stepChapter(1) }, enabled = index < project.chapters.lastIndex, modifier = Modifier.size(52.dp)) {
            Icon(AppIcons.ChevronRight, contentDescription = "Next chapter")
        }
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Chapters") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    project.chapters.forEach { ch ->
                        val selected = ch.id == chapter.id
                        OutlinedButton(
                            onClick = { picking = false; vm.selectChapter(ch.id) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        ) {
                            Text(
                                (if (selected) "▶ " else "") + ch.title + " · " + TextTools.formatCount(TextTools.wordCount(ch.text)) + " words",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = { picking = false; vm.addChapter() }) { Text("New chapter") } },
            dismissButton = {
                TextButton(onClick = { picking = false; renaming = true }) { Text("Rename this one") }
            },
        )
    }
    if (renaming) {
        VoiceInputDialog(
            title = "Rename chapter",
            label = "Chapter title",
            initial = chapter.title,
            onBeforeListen = { vm.suspendDictationForField() },
            onConfirm = { renaming = false; vm.renameChapter(chapter.id, it) },
            onDismiss = { renaming = false },
        )
    }
}

@Composable
private fun Editor(vm: AppViewModel, modifier: Modifier) {
    val scroll = rememberScrollState()
    val value = vm.editor
    // Follow the writing as dictation adds to the end.
    LaunchedEffect(value.text.length, scroll.maxValue) {
        if (value.selection.end >= value.text.length - 1) scroll.animateScrollTo(scroll.maxValue)
    }
    Box(modifier.fillMaxWidth().verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 8.dp)) {
        BasicTextField(
            value = value,
            onValueChange = vm::onEditorChange,
            textStyle = ManuscriptStyle.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Chapter text" },
            decorationBox = { inner ->
                if (value.text.isEmpty()) {
                    Text(
                        "Tap the microphone and start talking.\n\nSay “comma” or “full stop” for punctuation, " +
                            "“new paragraph” to break, “continue” for the AI to write the next lines in your voice, " +
                            "and “what can I say” for everything else.",
                        style = ManuscriptStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                inner()
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionCard(vm: AppViewModel, s: Suggestion) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Sparkle, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "${s.kind.title} · ${s.engine}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (s.streaming) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Box(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                when {
                    s.error != null -> Text(s.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
                    s.text.isBlank() -> Text(s.waitingText, style = MaterialTheme.typography.bodyLarge)
                    else -> Text(s.text, style = ManuscriptStyle.copy(color = MaterialTheme.colorScheme.onSecondaryContainer))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.canInsert && s.error == null) {
                    Button(onClick = vm::acceptSuggestion, enabled = !s.streaming && s.text.isNotBlank(), modifier = Modifier.heightIn(min = 52.dp)) {
                        Icon(AppIcons.Check, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("Accept")
                    }
                }
                val onCard = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                )
                FilledTonalButton(onClick = vm::retrySuggestion, enabled = !s.streaming, colors = onCard, modifier = Modifier.heightIn(min = 52.dp)) {
                    Icon(AppIcons.Refresh, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("Try again")
                }
                if (s.error == null) {
                    FilledTonalButton(onClick = vm::readSuggestion, enabled = s.text.isNotBlank(), colors = onCard, modifier = Modifier.heightIn(min = 52.dp)) {
                        Icon(AppIcons.VolumeUp, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("Read")
                    }
                }
                OutlinedButton(onClick = vm::dismissSuggestion, modifier = Modifier.heightIn(min = 52.dp)) {
                    Icon(AppIcons.Close, contentDescription = null); Spacer(Modifier.width(4.dp)); Text(if (s.canInsert) "Discard" else "Close")
                }
            }
        }
    }
}

@Composable
private fun PredictionRow(vm: AppViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        vm.predictions.forEachIndexed { i, word ->
            OutlinedButton(
                onClick = { vm.pickPrediction(i) },
                modifier = Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(24.dp),
            ) {
                Text("${i + 1}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(6.dp))
                Text(word, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun ControlPanel(vm: AppViewModel, settings: AppSettings, onMic: () -> Unit) {
    var confirmLearn by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ActionTile("Undo", AppIcons.Undo, vm::undo, Modifier.weight(1f))
            MicButton(vm.micState, settings.handsFree, onMic, Modifier.weight(1.4f))
            ActionTile("Continue", AppIcons.Sparkle, vm::requestContinue, Modifier.weight(1f), highlight = true)
        }
        Spacer(Modifier.size(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ActionTile("Fix", AppIcons.Spellcheck, vm::requestFix, Modifier.weight(1f))
            ActionTile("Ideas", AppIcons.Lightbulb, vm::requestIdeas, Modifier.weight(1f))
            if (vm.speaking) ActionTile("Stop", AppIcons.Stop, vm::stopSpeaking, Modifier.weight(1f))
            else ActionTile("Read", AppIcons.VolumeUp, vm::readBack, Modifier.weight(1f))
            ActionTile("Learn", AppIcons.School, { confirmLearn = true }, Modifier.weight(1f), enabled = vm.learningStatus == null)
        }
    }
    if (confirmLearn) {
        val chapter = vm.currentChapter
        ConfirmDialog(
            title = "Learn from this chapter?",
            text = "I'll study ${chapter?.title ?: "this chapter"} to update the story bible, the chapter summary and your voice profile. " +
                "You'll get to approve anything new before it goes in the bible.",
            confirm = "Learn",
            onConfirm = { confirmLearn = false; chapter?.let { vm.learnChapter(it.id) } },
            onDismiss = { confirmLearn = false },
        )
    }
}

@Composable
private fun ActionTile(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.padding(2.dp).heightIn(min = 64.dp),
    ) {
        Column(
            Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MicButton(state: MicState, handsFree: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val on = state != MicState.OFF
    val label = when (state) {
        MicState.OFF -> "Tap to talk"
        MicState.STARTING -> "Starting…"
        MicState.LISTENING -> if (handsFree) "Listening" else "Listening…"
        MicState.HEARING -> "Hearing you…"
        MicState.PAUSED -> "Paused while reading"
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = when (state) {
                MicState.OFF -> MaterialTheme.colorScheme.primaryContainer
                MicState.PAUSED -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.primary
            },
            modifier = Modifier
                .size(84.dp)
                .semantics { contentDescription = if (on) "Microphone on. Tap to stop." else "Microphone off. Tap to talk." },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (on) AppIcons.Mic else AppIcons.MicOff,
                    contentDescription = null,
                    modifier = Modifier.size(42.dp),
                    tint = if (on && state != MicState.PAUSED) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 1)
    }
}
