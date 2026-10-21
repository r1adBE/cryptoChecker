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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.BackupCrypto
import com.cryptochecker.app.data.BackupPasswordStrength
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.app.ui.components.SwitchRow
import androidx.compose.runtime.LaunchedEffect
import com.cryptochecker.app.ui.components.HapticEvent
import com.cryptochecker.app.ui.components.rememberHaptics

/*
 * Runde 25: Passwortschutz der Sicherungsdatei (BackupCrypto). Passwörter nur in `remember`
 * (nicht im gespeicherten Zustand der Aktivität) und nie in einer Meldung.
 */

/**
 * Vor dem Sichern: «Mit Passwort schützen» (vorgewählt, wenn Portfolio-Daten dabei sind),
 * Passwort und Wiederholung (mindestens [BackupCrypto.MIN_PASSWORD_LENGTH] Zeichen). Ein leicht zu
 * erratendes Passwort ([BackupPasswordStrength]) zeigt einen Fehler statt des Mindestlängen-Hinweises und sperrt.
 * Ohne Schutz folgt ein ausdrücklicher Bestätigungsschritt mit dem Inhalt der Datei ([hasPortfolioData]:
 * Portfolio und Adressen). [onConfirm] erhält das Passwort oder null (ohne Schutz).
 */
@Composable
internal fun BackupExportDialog(
    defaultProtect: Boolean,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
    hasPortfolioData: Boolean = defaultProtect,
) {
    var protect by remember { mutableStateOf(defaultProtect) }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    // Bestätigungsschritt «Ohne Passwort sichern?» offen
    var confirmPlain by remember { mutableStateOf(false) }
    val canExport = BackupCrypto.canExport(protect, password, repeat)
    val confirm: () -> Unit = {
        if (canExport) {
            if (protect) {
                onConfirm(password)
            } else {
                confirmPlain = true
            }
        }
    }

    if (confirmPlain) {
        BackupPlainConfirmDialog(
            hasPortfolioData = hasPortfolioData,
            onConfirm = { onConfirm(null) },
            // Zurück zur Auswahl (Passwort doch noch setzen)
            onBack = { confirmPlain = false },
        )
        return
    }

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
                    val weak = BackupPasswordStrength.showWeakHint(password)
                    PasswordField(
                        value = password,
                        onValueChange = { password = it },
                        label = stringResource(R.string.backup_password),
                        fieldTag = UiTestTags.BACKUP_PASSWORD_FIELD,
                        visible = visible,
                        onToggleVisible = { visible = !visible },
                        supporting = if (weak) {
                            stringResource(R.string.backup_password_too_weak)
                        } else {
                            stringResource(R.string.backup_password_min, BackupCrypto.MIN_PASSWORD_LENGTH)
                        },
                        isError = tooShort || weak,
                        imeAction = ImeAction.Next,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    val mismatch = repeat.isNotEmpty() && repeat != password
                    PasswordField(
                        value = repeat,
                        onValueChange = { repeat = it },
                        label = stringResource(R.string.backup_password_repeat),
                        fieldTag = UiTestTags.BACKUP_PASSWORD_REPEAT,
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
            TextButton(onClick = confirm, enabled = canExport, modifier = Modifier.testTag(UiTestTags.BACKUP_EXPORT_CONFIRM)) {
                Text(stringResource(R.string.backup_export))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/**
 * Sichern ohne Passwort: ausdrücklich bestätigen, mit Aufzählung des Inhalts (immer Merkliste, Alarme,
 * Einstellungen; mit [hasPortfolioData] zusätzlich Portfolio und gespeicherte Adressen). Wie iOS
 * `BackupExportSheet` (Bestätigung).
 */
@Composable
private fun BackupPlainConfirmDialog(
    hasPortfolioData: Boolean,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onBack,
        title = { Text(stringResource(R.string.backup_plain_confirm_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.backup_plain_confirm_text),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "• " + stringResource(R.string.backup_plain_contents_base),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
                if (hasPortfolioData) {
                    Text(
                        text = "• " + stringResource(R.string.backup_plain_contents_portfolio),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        text = "• " + stringResource(R.string.backup_plain_contents_addresses),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.backup_plain_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
        }
    )
}

/**
 * Verschlüsselte Sicherung gewählt: Passwort abfragen. Nach einem Fehlversuch ([wrong]) mit
 * Hinweis erneut; während der Prüfung ([checking]) ist «Weiter» gesperrt. [failedAttempts] zählt die Fehlversuche.
 */
@Composable
internal fun BackupPasswordDialog(
    wrong: Boolean,
    checking: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
    failedAttempts: Int = 0,
) {
    // Nach JEDEM Fehlversuch leeres Feld (an der Zahl der Versuche, nicht an «falsch» — das bliebe ab dem zweiten gleich)
    var password by remember(failedAttempts) { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val submit: () -> Unit = { if (password.isNotEmpty() && !checking) onSubmit(password) }
    // Fehlversuch: Fehler-Haptik wie iOS (einmal je Versuch)
    val haptics = rememberHaptics()
    LaunchedEffect(failedAttempts) { if (failedAttempts > 0) haptics.play(HapticEvent.ERROR) }

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
                    fieldTag = UiTestTags.BACKUP_RESTORE_PASSWORD_FIELD,
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
            TextButton(
                onClick = submit,
                enabled = password.isNotEmpty() && !checking,
                modifier = Modifier.testTag(UiTestTags.BACKUP_RESTORE_SUBMIT),
            ) {
                Text(stringResource(R.string.backup_password_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/** Passwortfeld mit «Anzeigen»/«Verbergen». [fieldTag]: Kennung des Felds (UI-Tests), der Hinweis darunter hat sie mit «_hint». */
@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    fieldTag: String,
    visible: Boolean,
    onToggleVisible: () -> Unit,
    supporting: String?,
    isError: Boolean,
    imeAction: ImeAction,
    modifier: Modifier = Modifier,
    supportingColor: Color? = null,
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
        // Eigene Farbe nur für Warnungen (z. B. schwaches Passwort); sonst die des Feldes
        supportingText = supporting?.let { text ->
            { Text(text, color = supportingColor ?: Color.Unspecified, modifier = Modifier.testTag(UiTestTags.hint(fieldTag))) }
        },
        modifier = modifier.fillMaxWidth().testTag(fieldTag)
    )
}
