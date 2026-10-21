package com.novelassist.ui.components

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A large, clearly labelled button: easy to hit and easy to name for Voice Access. */
@Composable
fun BigButton(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    val content: @Composable () -> Unit = {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (primary) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 56.dp)) { content() }
    } else {
        FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 56.dp)) { content() }
    }
}

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

/**
 * Launches the phone's own "speak now" box and returns what was said. Used for
 * short fields like names and titles.
 */
@Composable
fun rememberSpeechInput(onBefore: () -> Unit = {}, onText: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(onText)
        }
    }
    return {
        onBefore()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now")
        try {
            launcher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            android.widget.Toast.makeText(context, "No speech recogniser is installed.", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}

/** A text field with a microphone button, so it can be filled by voice. */
@Composable
fun VoiceTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    minLines: Int = 1,
    append: Boolean = !singleLine,
    onBeforeListen: () -> Unit = {},
) {
    val listen = rememberSpeechInput(onBefore = onBeforeListen) { spoken ->
        val next = if (append && value.isNotBlank()) value.trimEnd() + " " + spoken else spoken
        onValueChange(next)
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = minLines,
        modifier = modifier.fillMaxWidth(),
        trailingIcon = {
            IconButton(onClick = listen, modifier = Modifier.size(52.dp)) {
                Icon(AppIcons.Mic, contentDescription = "Speak $label", tint = MaterialTheme.colorScheme.primary)
            }
        },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { Button(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A dialog with one voice-enabled field. */
@Composable
fun VoiceInputDialog(
    title: String,
    label: String,
    initial: String = "",
    confirm: String = "Save",
    singleLine: Boolean = true,
    onBeforeListen: () -> Unit = {},
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            VoiceTextField(
                value = text,
                onValueChange = { text = it },
                label = label,
                singleLine = singleLine,
                minLines = if (singleLine) 1 else 3,
                onBeforeListen = onBeforeListen,
            )
        },
        confirmButton = { Button(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

data class ChartSeries(val name: String, val color: Color, val values: List<Double>)

/** A simple multi-line chart; each series is scaled to its own range. */
@Composable
fun LineChart(series: List<ChartSeries>, labels: List<String>, modifier: Modifier = Modifier) {
    val description = series.joinToString(". ") { s ->
        "${s.name}: " + s.values.joinToString(", ") { "%.1f".format(it) }
    }
    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(160.dp)
                .semantics { contentDescription = "Chart. $description" }
        ) {
            val grid = Color.Gray.copy(alpha = 0.25f)
            for (i in 0..3) {
                val y = size.height * i / 3f
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            series.forEach { s ->
                if (s.values.isEmpty()) return@forEach
                val min = s.values.minOrNull() ?: 0.0
                val max = s.values.maxOrNull() ?: 1.0
                val span = (max - min).takeIf { it > 1e-9 } ?: 1.0
                val stepX = if (s.values.size > 1) size.width / (s.values.size - 1) else 0f
                val path = Path()
                s.values.forEachIndexed { i, v ->
                    val x = if (s.values.size == 1) size.width / 2 else i * stepX
                    val y = size.height - ((v - min) / span).toFloat() * (size.height * 0.85f) - size.height * 0.075f
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    drawCircle(s.color, radius = 5f, center = Offset(x, y))
                }
                drawPath(path, s.color, style = Stroke(width = 4f))
            }
        }
        if (labels.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(labels.first(), style = MaterialTheme.typography.labelSmall)
                if (labels.size > 1) Text(labels.last(), style = MaterialTheme.typography.labelSmall)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            series.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(12.dp)) { drawCircle(s.color, radius = size.minDimension / 2) }
                    Spacer(Modifier.width(4.dp))
                    Text(s.name, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
