package com.novelassist.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novelassist.data.BibleCategory
import com.novelassist.data.BibleEntry
import com.novelassist.data.BibleProposal
import com.novelassist.data.Project
import com.novelassist.ui.AppViewModel
import com.novelassist.ui.components.AppIcons
import com.novelassist.ui.components.BigButton
import com.novelassist.ui.components.ConfirmDialog
import com.novelassist.ui.components.SectionCard
import com.novelassist.ui.components.VoiceTextField

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BibleScreen(vm: AppViewModel, project: Project, modifier: Modifier = Modifier) {
    var filter by remember { mutableStateOf<BibleCategory?>(null) }
    var editing by remember { mutableStateOf<BibleEntry?>(null) }
    var editingProposal by remember { mutableStateOf<BibleProposal?>(null) }
    var question by remember { mutableStateOf("") }
    var premiseDraft by remember(project.id) { mutableStateOf(project.bible.premise) }
    val bible = project.bible
    val entries = bible.entries.filter { filter == null || it.category == filter }
        .sortedWith(compareByDescending<BibleEntry> { it.pinned }.thenByDescending { it.mentionCount }.thenBy { it.name })

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard("Ask your story bible", subtitle = "For example: “What colour are Mara's eyes?” You can also say “ask the bible …” while writing.") {
                VoiceTextField(value = question, onValueChange = { question = it }, label = "Your question", singleLine = true)
                BigButton("Ask", AppIcons.Help, onClick = { vm.askBible(question) }, enabled = question.isNotBlank(), modifier = Modifier.fillMaxWidth())
                vm.bibleAnswer?.let { a ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (a.streaming) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            a.error ?: a.text.ifBlank { "Thinking…" },
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (a.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 6.dp).weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { vm.speak(a.text) }, enabled = a.text.isNotBlank()) { Text("Read aloud") }
                        TextButton(onClick = vm::clearBibleAnswer) { Text("Clear") }
                    }
                }
            }
        }

        if (project.proposals.isNotEmpty()) {
            item {
                SectionCard(
                    "Noticed while learning (${project.proposals.size})",
                    subtitle = "New things found in your chapters. Keep the ones that are right.",
                    container = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    BigButton("Keep all", AppIcons.Check, onClick = vm::acceptAllProposals, modifier = Modifier.fillMaxWidth(), primary = true)
                }
            }
            items(project.proposals, key = { "p" + it.id }) { p ->
                ProposalCard(
                    p,
                    existing = p.existingEntryId?.let { id -> bible.entries.firstOrNull { it.id == id } },
                    onAccept = { vm.acceptProposal(p) },
                    onEdit = { editingProposal = p },
                    onReject = { vm.rejectProposal(p) },
                )
            }
        }

        item {
            SectionCard("Premise", subtitle = "What the book is about, in a few sentences. The AI reads this every time.") {
                VoiceTextField(
                    value = premiseDraft,
                    onValueChange = { premiseDraft = it },
                    label = "Premise",
                    minLines = 2,
                )
                if (premiseDraft != bible.premise) {
                    Button(onClick = { vm.setPremise(premiseDraft) }) { Text("Save premise") }
                }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All (${bible.entries.size})") }, modifier = Modifier.heightIn(min = 48.dp))
                BibleCategory.entries.forEach { cat ->
                    val count = bible.entries.count { it.category == cat }
                    if (count > 0 || cat == BibleCategory.CHARACTER || cat == BibleCategory.PLACE) {
                        FilterChip(
                            selected = filter == cat,
                            onClick = { filter = if (filter == cat) null else cat },
                            label = { Text("${cat.label} ($count)") },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }
        }

        item {
            BigButton(
                "Add ${filter?.singular ?: "an entry"}",
                AppIcons.Add,
                onClick = { editing = BibleEntry(category = filter ?: BibleCategory.CHARACTER, name = "") },
                modifier = Modifier.fillMaxWidth(),
                primary = true,
            )
        }

        if (entries.isEmpty()) {
            item {
                Text(
                    "Nothing here yet. Write a chapter and tap Learn, or add entries yourself. You can also say " +
                        "“bible note” followed by a fact while you write.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(entries, key = { it.id }) { e ->
            Card(
                onClick = { editing = e },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (e.pinned) {
                            Icon(AppIcons.Pin, contentDescription = "Always given to the AI", modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(e.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(
                            e.category.singular + if (e.mentionCount > 0) " · ${e.mentionCount}×" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (e.description.isNotBlank()) {
                        Text(e.description, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    editing?.let { entry ->
        EntryDialog(
            entry = entry,
            isNew = bible.entries.none { it.id == entry.id },
            onSave = { vm.saveEntry(it); editing = null },
            onDelete = { vm.deleteEntry(entry.id); editing = null },
            onDismiss = { editing = null },
        )
    }
    editingProposal?.let { p ->
        EntryDialog(
            entry = BibleEntry(category = p.category, name = p.name, description = p.description),
            isNew = true,
            title = "Edit before keeping",
            onSave = { edited ->
                vm.acceptProposal(p, p.copy(category = edited.category, name = edited.name, description = edited.description))
                editingProposal = null
            },
            onDelete = null,
            onDismiss = { editingProposal = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProposalCard(
    p: BibleProposal,
    existing: BibleEntry?,
    onAccept: () -> Unit,
    onEdit: () -> Unit,
    onReject: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (existing != null) "More about ${existing.name}" else "New ${p.category.singular}: ${p.name}",
                style = MaterialTheme.typography.titleMedium,
            )
            if (p.description.isNotBlank()) Text(p.description, style = MaterialTheme.typography.bodyMedium)
            Text(
                p.reason + if (p.fromAi) " (AI)" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAccept, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep") }
                if (existing == null) FilledTonalButton(onClick = onEdit, modifier = Modifier.heightIn(min = 48.dp)) { Text("Edit") }
                OutlinedButton(onClick = onReject, modifier = Modifier.heightIn(min = 48.dp)) { Text("Not right") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryDialog(
    entry: BibleEntry,
    isNew: Boolean,
    onSave: (BibleEntry) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
    title: String = if (isNew) "New bible entry" else "Edit ${entry.name}",
) {
    var name by remember { mutableStateOf(entry.name) }
    var description by remember { mutableStateOf(entry.description) }
    var aliases by remember { mutableStateOf(entry.aliases.joinToString(", ")) }
    var category by remember { mutableStateOf(entry.category) }
    var pinned by remember { mutableStateOf(entry.pinned) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BibleCategory.entries.forEach { cat ->
                        FilterChip(selected = category == cat, onClick = { category = cat }, label = { Text(cat.singular) })
                    }
                }
                VoiceTextField(value = name, onValueChange = { name = it }, label = "Name", singleLine = true)
                VoiceTextField(value = description, onValueChange = { description = it }, label = "Description", minLines = 3)
                VoiceTextField(value = aliases, onValueChange = { aliases = it }, label = "Other names (comma separated)", singleLine = true, append = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Always give this to the AI", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = pinned, onCheckedChange = { pinned = it })
                }
                if (onDelete != null && !isNew) {
                    OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete entry") }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        entry.copy(
                            name = name.trim(),
                            description = description.trim(),
                            aliases = aliases.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                            category = category,
                            pinned = pinned,
                        )
                    )
                },
                enabled = name.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (confirmDelete && onDelete != null) {
        ConfirmDialog(
            title = "Delete ${entry.name}?",
            text = "This removes it from the story bible.",
            confirm = "Delete",
            onConfirm = { confirmDelete = false; onDelete() },
            onDismiss = { confirmDelete = false },
        )
    }
}
