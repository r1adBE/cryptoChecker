package com.cryptochecker.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.cryptochecker.app.R

/** Höchstlänge eines Gruppennamens (wie in der Gruppen-Auswahl der Aktionen). */
const val GROUP_NAME_MAX = 24

/**
 * Name einer Gruppe eingeben (neue Gruppe oder Umbenennen).
 * Leere Namen sind nicht möglich, die Länge ist auf [GROUP_NAME_MAX] begrenzt.
 */
@Composable
fun GroupNameDialog(
    title: String,
    initial: String,
    confirmText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial.take(GROUP_NAME_MAX)) }
    val trimmed = name.trim()
    val focusRequester = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(GROUP_NAME_MAX) },
                label = { Text(stringResource(R.string.group_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = {
                    if (trimmed.isNotEmpty()) onConfirm(trimmed)
                }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
            )
            // Gleich tippen können
            LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
        },
        confirmButton = {
            TextButton(enabled = trimmed.isNotEmpty(), onClick = { onConfirm(trimmed) }) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/**
 * Gibt es die Gruppe schon (Gross-/Kleinschreibung egal), gilt deren
 * Schreibweise — so entstehen keine Fast-Doppel wie «Defi» und «DeFi».
 */
fun canonicalGroupName(name: String, groups: List<String>): String {
    val trimmed = name.trim()
    return groups.firstOrNull { it.equals(trimmed, ignoreCase = true) } ?: trimmed
}
