package com.novelassist.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novelassist.ui.components.AppIcons
import com.novelassist.ui.screens.BibleScreen
import com.novelassist.ui.screens.LibraryScreen
import com.novelassist.ui.screens.SettingsScreen
import com.novelassist.ui.screens.StructureScreen
import com.novelassist.ui.screens.VoiceProfileScreen
import com.novelassist.ui.screens.WriteScreen
import com.novelassist.ui.theme.NovelAssistTheme
import com.novelassist.voice.VoiceCommandParser

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelAssistRoot(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    NovelAssistTheme(textScale = settings.textScale) {
        val snackbar = remember { SnackbarHostState() }
        var settingsReturn by rememberSaveable { mutableStateOf(Screen.LIBRARY) }

        LaunchedEffect(vm.message) {
            val text = vm.message ?: return@LaunchedEffect
            vm.message = null
            snackbar.showSnackbar(text, withDismissAction = true)
        }

        fun openSettings() {
            settingsReturn = if (vm.screen == Screen.SETTINGS) settingsReturn else vm.screen
            vm.screen = Screen.SETTINGS
        }

        BackHandler(enabled = vm.screen != Screen.LIBRARY) {
            when (vm.screen) {
                Screen.SETTINGS -> vm.screen = if (vm.project == null) Screen.LIBRARY else settingsReturn
                Screen.WRITE -> vm.closeProject()
                else -> vm.screen = Screen.WRITE
            }
        }

        val project = vm.project
        when {
            vm.screen == Screen.SETTINGS -> SettingsScreen(
                vm = vm,
                settings = settings,
                snackbar = snackbar,
                onBack = { vm.screen = if (project == null) Screen.LIBRARY else settingsReturn },
            )
            project == null || vm.screen == Screen.LIBRARY -> LibraryScreen(vm = vm, snackbar = snackbar, onSettings = ::openSettings)
            else -> Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        navigationIcon = {
                            IconButton(onClick = { vm.closeProject() }) {
                                Icon(AppIcons.Back, contentDescription = "Back to my novels")
                            }
                        },
                        title = { Text(project.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        actions = {
                            IconButton(onClick = { vm.showHelp = true }) {
                                Icon(AppIcons.Help, contentDescription = "Voice commands")
                            }
                            IconButton(onClick = ::openSettings) {
                                Icon(AppIcons.Settings, contentDescription = "Settings")
                            }
                        },
                    )
                },
                bottomBar = {
                    NavigationBar {
                        listOf(
                            Triple(Screen.WRITE, "Write", AppIcons.Pen),
                            Triple(Screen.BIBLE, "Bible", AppIcons.Book),
                            Triple(Screen.VOICE, "Voice", AppIcons.Voice),
                            Triple(Screen.STRUCTURE, "Structure", AppIcons.Timeline),
                        ).forEach { (screen, label, icon) ->
                            val pending = if (screen == Screen.BIBLE) project.proposals.size else 0
                            NavigationBarItem(
                                selected = vm.screen == screen,
                                onClick = { vm.screen = screen },
                                icon = { Icon(icon, contentDescription = null) },
                                label = { Text(if (pending > 0) "$label ($pending)" else label, maxLines = 1) },
                            )
                        }
                    }
                },
            ) { padding ->
                val modifier = Modifier.padding(padding)
                when (vm.screen) {
                    Screen.BIBLE -> BibleScreen(vm, project, modifier)
                    Screen.VOICE -> VoiceProfileScreen(vm, project, modifier)
                    Screen.STRUCTURE -> StructureScreen(vm, project, modifier)
                    else -> WriteScreen(vm, settings, modifier)
                }
            }
        }

        if (vm.showHelp) {
            AlertDialog(
                onDismissRequest = { vm.showHelp = false },
                title = { Text("What you can say") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            "Just talk to write. Say punctuation out loud: “comma”, “full stop”, “question mark”, " +
                                "“new paragraph”, “open quote” and “close quote”.\n",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(VoiceCommandParser.helpText, style = MaterialTheme.typography.bodyMedium)
                        if (settings.strictCommands) {
                            Text("\nStrict mode is on: say “command” first, e.g. “command undo”.", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                },
                confirmButton = { Button(onClick = { vm.showHelp = false }, modifier = Modifier.padding(4.dp)) { Text("Got it") } },
            )
        }
    }
}
