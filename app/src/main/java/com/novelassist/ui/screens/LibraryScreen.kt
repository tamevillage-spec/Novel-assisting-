package com.novelassist.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.novelassist.data.ProjectSummary
import com.novelassist.learning.TextTools
import com.novelassist.ui.AppViewModel
import com.novelassist.ui.components.AppIcons
import com.novelassist.ui.components.BigButton
import com.novelassist.ui.components.ConfirmDialog
import com.novelassist.ui.components.VoiceInputDialog
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: AppViewModel, snackbar: SnackbarHostState, onSettings: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ProjectSummary?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importBackup(uri)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("My novels") },
                actions = {
                    IconButton(onClick = onSettings) { Icon(AppIcons.Settings, contentDescription = "Settings") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Write by talking. Novel Assist learns your story and your voice from everything you write, " +
                        "and grows a story bible as the book evolves.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            item {
                BigButton("Start a new novel", AppIcons.Add, onClick = { creating = true }, primary = true, modifier = Modifier.fillMaxWidth())
            }
            items(vm.projects, key = { it.id }) { p ->
                Card(
                    onClick = { vm.openProject(p.id) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.title, style = MaterialTheme.typography.titleLarge)
                            Text(
                                "${TextTools.formatCount(p.wordCount)} words · ${p.chapterCount} chapter${if (p.chapterCount == 1) "" else "s"}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "Last written ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(p.updatedAt))}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { deleting = p }) {
                            Icon(AppIcons.Delete, contentDescription = "Delete ${p.title}")
                        }
                    }
                }
            }
            item {
                BigButton(
                    "Restore a backup",
                    null,
                    onClick = { importer.launch(arrayOf("application/json", "application/octet-stream", "*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (creating) {
        VoiceInputDialog(
            title = "New novel",
            label = "Working title",
            confirm = "Create",
            onConfirm = { creating = false; vm.createProject(it) },
            onDismiss = { creating = false },
        )
    }
    deleting?.let { p ->
        ConfirmDialog(
            title = "Delete “${p.title}”?",
            text = "It disappears from your list. A copy is kept in the app's backup folder.",
            confirm = "Delete",
            onConfirm = { deleting = null; vm.deleteProject(p.id) },
            onDismiss = { deleting = null },
        )
    }
}
