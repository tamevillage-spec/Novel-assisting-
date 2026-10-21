package com.novelassist.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.novelassist.ai.ClaudeModels
import com.novelassist.ai.DownloadProgress
import com.novelassist.ai.ModelCatalog
import com.novelassist.data.AppSettings
import com.novelassist.data.EngineChoice
import com.novelassist.ui.AppViewModel
import com.novelassist.ui.ExportKind
import com.novelassist.ui.components.AppIcons
import com.novelassist.ui.components.SectionCard
import java.io.File
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: AppViewModel, settings: AppSettings, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val context = LocalContext.current
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModelFile(uri)
    }
    var exportKind by remember { mutableStateOf(ExportKind.BACKUP) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri != null) vm.exportTo(uri, exportKind)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(AppIcons.Back, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard("Writing AI", subtitle = "Now using: ${vm.engineLabel}") {
                EngineOption(
                    "Built-in (offline, instant)",
                    "Learns your own words and phrasing. Simple suggestions; no download, nothing leaves the phone.",
                    selected = settings.engine == EngineChoice.BUILT_IN,
                ) { vm.updateSettings { it.copy(engine = EngineChoice.BUILT_IN) } }
                EngineOption(
                    "On-device AI (private, offline)",
                    "A full AI model running on your phone. Much better writing; one-time download below.",
                    selected = settings.engine == EngineChoice.ON_DEVICE,
                ) { vm.updateSettings { it.copy(engine = EngineChoice.ON_DEVICE) } }
                EngineOption(
                    "Claude (best quality, online)",
                    "Anthropic's Claude. Needs internet and your own API key. Passages are sent only when you ask for help.",
                    selected = settings.engine == EngineChoice.CLAUDE,
                ) { vm.updateSettings { it.copy(engine = EngineChoice.CLAUDE) } }
            }

            SectionCard("On-device model") {
                val installed = settings.onDeviceModelPath.takeIf { it.isNotBlank() && File(it).exists() }
                if (installed != null) {
                    val option = ModelCatalog.byPath(installed)
                    Text(
                        "Installed: ${option?.name ?: File(installed).name} (${gb(File(installed).length())})",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    ToggleRow("Use the phone's graphics chip (faster on many phones)", settings.onDeviceUseGpu) { on ->
                        vm.updateSettings { it.copy(onDeviceUseGpu = on) }
                    }
                    OutlinedButton(onClick = vm::deleteModel) { Text("Delete model to free space") }
                    HorizontalDivider()
                }
                val progress = vm.downloadProgress
                if (progress != null && settings.activeDownloadId >= 0) {
                    val name = ModelCatalog.byId(settings.activeDownloadModelId)?.shortName ?: "model"
                    Text("Downloading $name: ${(progress.fraction * 100).roundToInt()}% (${gb(progress.downloadedBytes)} of ${gb(progress.totalBytes)})")
                    LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                    if (progress.reason.isNotBlank()) Text(progress.reason, style = MaterialTheme.typography.bodyMedium)
                    Text("You can keep writing; it continues in the background.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = vm::cancelDownload) { Text("Cancel download") }
                } else {
                    if (progress?.status == DownloadProgress.Status.FAILED) {
                        Text(progress.reason, color = MaterialTheme.colorScheme.error)
                    }
                    ModelCatalog.options.forEach { option ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(option.name, style = MaterialTheme.typography.titleMedium)
                            Text(option.description, style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = { vm.startDownload(option) }, modifier = Modifier.heightIn(min = 52.dp)) {
                                Text("Download (${gb(option.sizeBytes)})")
                            }
                        }
                    }
                    ToggleRow("Allow download over mobile data", settings.allowMobileDataDownload) { on ->
                        vm.updateSettings { it.copy(allowMobileDataDownload = on) }
                    }
                }
                vm.importStatus?.let { Text(it) }
                TextButton(onClick = { modelPicker.launch(arrayOf("*/*")) }) { Text("Use a .litertlm model file already on my phone") }
            }

            SectionCard("Claude", subtitle = "Get a key at console.anthropic.com. It's stored only on this phone.") {
                var showKey by remember { mutableStateOf(false) }
                val clipboard = LocalClipboard.current
                val scope = rememberCoroutineScope()
                OutlinedTextField(
                    value = settings.claudeApiKey,
                    onValueChange = { key -> vm.updateSettings { it.copy(claudeApiKey = key.trim()) } },
                    label = { Text("API key") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        scope.launch {
                            val pasted = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text
                            pasted?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { key -> vm.updateSettings { it.copy(claudeApiKey = key) } }
                        }
                    }) { Text("Paste key") }
                    TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") }
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.anthropic.com/settings/keys")))
                    }) { Text("Get a key") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ClaudeModels.options.forEach { (id, label) ->
                        FilterChip(
                            selected = settings.claudeModel == id,
                            onClick = { vm.updateSettings { it.copy(claudeModel = id) } },
                            label = { Text(label) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }

            SectionCard("Voice") {
                ToggleRow("Hands-free: keep listening between sentences", settings.handsFree) { on ->
                    vm.updateSettings { it.copy(handsFree = on) }
                }
                ToggleRow("Start listening when I open a chapter", settings.autoStartListening) { on ->
                    vm.updateSettings { it.copy(autoStartListening = on) }
                }
                ToggleRow("Spoken punctuation (“comma”, “full stop”, “new paragraph”)", settings.spokenPunctuation) { on ->
                    vm.updateSettings { it.copy(spokenPunctuation = on) }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ToggleRow("Automatic punctuation from Android (may break sentences at pauses)", settings.autoPunctuation) { on ->
                        vm.updateSettings { it.copy(autoPunctuation = on) }
                    }
                }
                ToggleRow("Prefer offline speech recognition", settings.preferOfflineSpeech) { on ->
                    vm.updateSettings { it.copy(preferOfflineSpeech = on) }
                }
                ToggleRow("Commands need the word “command” first", settings.strictCommands) { on ->
                    vm.updateSettings { it.copy(strictCommands = on) }
                }
                ToggleRow("Read suggestions aloud automatically", settings.autoReadSuggestions) { on ->
                    vm.updateSettings { it.copy(autoReadSuggestions = on) }
                }
                var rate by remember { mutableFloatStateOf(settings.speechRate) }
                Text("Reading speed: ${"%.1f".format(rate)}×")
                Slider(
                    value = rate,
                    onValueChange = { rate = it },
                    onValueChangeFinished = { vm.updateSettings { it.copy(speechRate = rate) }; vm.speak("This is how fast I'll read.") },
                    valueRange = 0.5f..2f,
                )
            }

            SectionCard("Writing") {
                var words by remember { mutableFloatStateOf(settings.suggestionWords.toFloat()) }
                Text("Suggestion length: about ${words.roundToInt()} words")
                Slider(
                    value = words,
                    onValueChange = { words = (it / 10).roundToInt() * 10f },
                    onValueChangeFinished = { vm.updateSettings { it.copy(suggestionWords = words.roundToInt()) } },
                    valueRange = 30f..300f,
                )
                ToggleRow("Learn chapters automatically when I move on", settings.autoLearn) { on ->
                    vm.updateSettings { it.copy(autoLearn = on) }
                }
                var scale by remember { mutableFloatStateOf(settings.textScale) }
                Text("Text size: ${(scale * 100).roundToInt()}%")
                Slider(
                    value = scale,
                    onValueChange = { scale = it },
                    onValueChangeFinished = { vm.updateSettings { it.copy(textScale = scale) } },
                    valueRange = 0.85f..1.8f,
                )
            }

            if (vm.project != null) {
                SectionCard("Save a copy", subtitle = "Keep a backup somewhere safe, like Google Drive.") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val title = vm.project?.title.orEmpty().replace(Regex("[^\\w ]"), "").ifBlank { "novel" }
                        ExportKind.entries.forEach { kind ->
                            FilledTonalButton(onClick = {
                                exportKind = kind
                                val suffix = when (kind) {
                                    ExportKind.BACKUP -> "backup"
                                    ExportKind.MANUSCRIPT -> "manuscript"
                                    ExportKind.BIBLE -> "story bible"
                                }
                                exporter.launch("$title - $suffix.${kind.extension}")
                            }, modifier = Modifier.heightIn(min = 52.dp)) {
                                Text(
                                    when (kind) {
                                        ExportKind.BACKUP -> "Full backup"
                                        ExportKind.MANUSCRIPT -> "Manuscript"
                                        ExportKind.BIBLE -> "Story bible"
                                    }
                                )
                            }
                        }
                    }
                }
            }

            SectionCard("Privacy") {
                Text(
                    "Your novels are stored only on this phone (and in your phone's own backup). The built-in and " +
                        "on-device AI never send anything anywhere. Claude is used only if you choose it, and only " +
                        "for the passages you ask about.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun EngineOption(title: String, detail: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .toggleable(value = checked, onValueChange = onChange, role = Role.Switch),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

private fun gb(bytes: Long): String =
    if (bytes >= 1_000_000_000) "%.1f GB".format(bytes / 1e9) else "${(bytes / 1e6).roundToInt()} MB"
