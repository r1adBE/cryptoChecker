package com.cryptochecker.app.ui.features.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.BackupCrypto
import com.cryptochecker.app.ui.components.SwitchRow

/*
 * Runde 25: Passwortschutz der Sicherungsdatei (BackupCrypto). Passwörter nur in `remember`
 * (nicht im gespeicherten Zustand der Aktivität) und nie in einer Meldung.
 */

/**
 * Vor dem Sichern: «Mit Passwort schützen» (vorgewählt, wenn Portfolio-Daten dabei sind),
 * Passwort und Wiederholung (mindestens [BackupCrypto.MIN_PASSWORD_LENGTH] Zeichen).
 * [onConfirm] erhält das Passwort oder null (ohne Schutz).
 */
@Composable
internal fun BackupExportDialog(
    defaultProtect: Boolean,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var protect by remember { mutableStateOf(defaultProtect) }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val canExport = BackupCrypto.canExport(protect, password, repeat)
    val confirm: () -> Unit = { if (canExport) onConfirm(if (protect) password else null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_export)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SwitchRow(
                    title = stringResource(R.string.backup_protect),
                    subtitle = stringResource(R.string.backup_protect_hint),
                    checked = protect,
                    onCheckedChange = { protect = it }
                )
                if (protect) {
                    val tooShort = password.isNotEmpty() && !BackupCrypto.isPasswordLongEnough(password)
                    PasswordField(
                        value = password,
                        onValueChange = { password = it },
                        label = stringResource(R.string.backup_password),
                        visible = visible,
                        onToggleVisible = { visible = !visible },
                        supporting = stringResource(R.string.backup_password_min, BackupCrypto.MIN_PASSWORD_LENGTH),
                        isError = tooShort,
                        imeAction = ImeAction.Next,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    val mismatch = repeat.isNotEmpty() && repeat != password
                    PasswordField(
                        value = repeat,
                        onValueChange = { repeat = it },
                        label = stringResource(R.string.backup_password_repeat),
                        visible = visible,
                        onToggleVisible = { visible = !visible },
                        supporting = if (mismatch) stringResource(R.string.backup_password_mismatch) else null,
                        isError = mismatch,
                        imeAction = ImeAction.Done,
                        onDone = confirm,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        text = stringResource(R.string.backup_password_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = confirm, enabled = canExport) { Text(stringResource(R.string.backup_export)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/**
 * Verschlüsselte Sicherung gewählt: Passwort abfragen. Nach einem Fehlversuch ([wrong]) mit
 * Hinweis erneut; während der Prüfung ([checking]) ist «Weiter» gesperrt.
 */
@Composable
internal fun BackupPasswordDialog(
    wrong: Boolean,
    checking: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Nach einem Fehlversuch leeres Feld
    var password by remember(wrong) { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val submit: () -> Unit = { if (password.isNotEmpty() && !checking) onSubmit(password) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_password_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.backup_password_prompt),
                    style = MaterialTheme.typography.bodyMedium,
                )
                PasswordField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(R.string.backup_password),
                    visible = visible,
                    onToggleVisible = { visible = !visible },
                    supporting = if (wrong) stringResource(R.string.backup_password_wrong) else null,
                    isError = wrong,
                    imeAction = ImeAction.Done,
                    onDone = submit,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = password.isNotEmpty() && !checking) {
                Text(stringResource(R.string.backup_password_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/** Passwortfeld mit «Anzeigen»/«Verbergen». */
@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    visible: Boolean,
    onToggleVisible: () -> Unit,
    supporting: String?,
    isError: Boolean,
    imeAction: ImeAction,
    modifier: Modifier = Modifier,
    onDone: () -> Unit = {},
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        // Auch angezeigt Typ «Passwort»: Die Tastatur lernt nichts und schlägt nichts vor
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        trailingIcon = {
            IconButton(onClick = onToggleVisible) {
                Icon(
                    painterResource(if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                    contentDescription = stringResource(
                        if (visible) R.string.backup_password_hide else R.string.backup_password_show
                    )
                )
            }
        },
        supportingText = supporting?.let { { Text(it) } },
        modifier = modifier.fillMaxWidth()
    )
}
