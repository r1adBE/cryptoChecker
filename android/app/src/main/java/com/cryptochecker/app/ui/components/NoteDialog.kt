package com.cryptochecker.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.NOTE_MAX
import com.cryptochecker.app.util.LocaleNumbers

/**
 * Notiz zu einem Paar bearbeiten. Leer speichern oder «Entfernen» löscht sie.
 * Höchstens [NOTE_MAX] Zeichen.
 */
@Composable
fun NoteDialog(
    title: String,
    initial: String?,
    onSave: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial.orEmpty().take(NOTE_MAX)) }
    val focusRequester = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(NOTE_MAX) },
                    placeholder = { Text(stringResource(R.string.note_hint)) },
                    minLines = 2,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                )
                Text(
                    "${LocaleNumbers.integer(text.length)} / ${LocaleNumbers.integer(NOTE_MAX)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(androidx.compose.ui.Alignment.End).padding(top = 4.dp)
                )
            }
            LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim().takeIf { it.isNotEmpty() }) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            Row {
                if (!initial.isNullOrBlank()) {
                    TextButton(onClick = { onSave(null) }) {
                        Text(stringResource(R.string.note_remove), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    )
}
