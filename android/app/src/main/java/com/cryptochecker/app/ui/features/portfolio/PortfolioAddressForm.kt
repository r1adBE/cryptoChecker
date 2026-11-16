package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.domain.portfolio.AddressValidation
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import com.cryptochecker.app.domain.portfolio.WatchedAddressRules
import com.cryptochecker.app.domain.portfolio.WatchedAddressUndo
import com.cryptochecker.app.ui.components.DialogText
import com.cryptochecker.app.ui.theme.DisabledColors
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.resources.R

/**
 * Hinzufügen im Adressen-Blatt ([PortfolioAddressSheet]): Adresse (Netz automatisch erkannt), Bezeichnung (optional),
 * Grenze erreicht, wer die Adresse sieht ([privacyText]) und der Knopf «Hinzufügen». [error] = Fehler des Speicherns
 * oder der Prüfung (Text-Ressource).
 */
@Composable
internal fun AddressAddForm(
    addresses: List<WatchedAddress>,
    input: String,
    onInput: (String) -> Unit,
    label: String,
    onLabel: (String) -> Unit,
    error: Int?,
    check: AddressValidation.Check,
    btcServer: String?,
    canAdd: Boolean,
    onAdd: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = Spacing.sm)) {
        if (addresses.isNotEmpty()) HorizontalDivider(modifier = Modifier.padding(bottom = Spacing.md))
        OutlinedTextField(
            value = input,
            onValueChange = onInput,
            label = { Text(stringResource(R.string.portfolio_address_hint)) },
            singleLine = true,
            isError = error != null,
            supportingText = {
                when {
                    error != null -> Text(stringResource(error), color = MaterialTheme.colorScheme.error)
                    check.chain != null -> Text(check.chain?.coin.orEmpty())
                    else -> {}
                }
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                // Nur ASCII (Adressen), keine Vorschläge/Wörterbuch-Einträge aus der Adresse
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = label,
            onValueChange = { if (it.codePointCount(0, it.length) <= WatchedAddressRules.MAX_LABEL) onLabel(it) },
            label = { Text(stringResource(R.string.portfolio_address_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm)
        )
        if (!WatchedAddressRules.canAdd(addresses)) {
            Text(
                stringResource(R.string.address_limit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = Spacing.sm)
            )
        }
        // Wer die Adresse sieht (Anbieter des erkannten Netzes); xpub: was er verrät und wie viele Anfragen
        Text(
            privacyText(check, btcServer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.md)
        )
        Button(
            onClick = onAdd,
            enabled = canAdd,
            colors = DisabledColors.button(),
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.md, bottom = Spacing.lg)
        ) {
            Text(stringResource(R.string.portfolio_address_add))
        }
    }
}

/** Rückfrage «Adresse entfernen»: nennt, was mitgeht — Eröffnungsposten und Guthaben-Verlauf (nicht still mitlöschen). */
@Composable
internal fun AddressRemoveDialog(target: WatchedAddress, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val removal = remember(target) { WatchedAddressUndo.removal(target) }
    val alsoRemoved = when {
        removal.lots > 0 && removal.history ->
            pluralStringResource(R.plurals.portfolio_address_remove_lots_history, removal.lots, removal.lots)
        removal.lots > 0 -> pluralStringResource(R.plurals.portfolio_address_remove_lots, removal.lots, removal.lots)
        removal.history -> stringResource(R.string.portfolio_address_remove_history)
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.portfolio_address_remove)) },
        text = {
            DialogText {
                val head = target.label?.let { "$it\n${target.address}" } ?: target.address
                Text(alsoRemoved?.let { "$head\n\n$it" } ?: head)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.portfolio_address_remove), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
