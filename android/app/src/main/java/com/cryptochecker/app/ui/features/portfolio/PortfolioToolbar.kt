// Werkzeugleiste des Portfolio-Tabs (wie `PortfolioToolbar.swift`): «Beträge verbergen» und das Menü ⋯.
// Eigene Datei, weil `PortfolioScreen` sonst zu lang würde; Zustand und Aktionen bleiben dort, hier wird nur gezeichnet.
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.PortfolioImportBatchInfo
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.app.ui.components.AppMenuHead

/**
 * Knöpfe oben rechts im Portfolio-Tab: «Beträge verbergen» und das Menü ⋯ (App, Aktualisieren, Umrechnen,
 * Adressen, CSV-Import, Importe, Export, Alarm, Portfolio leeren). Der Zustand des Menüs bleibt im Bildschirm.
 */
@Composable
internal fun PortfolioToolbarActions(
    hasData: Boolean,
    hasTransactions: Boolean,
    hideAmounts: Boolean,
    refreshing: Boolean,
    currency: String,
    importBatches: List<PortfolioImportBatchInfo>,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    viewModel: PortfolioViewModel,
    onOpenAbout: () -> Unit,
    onPickCurrency: () -> Unit,
    onOpenAddresses: () -> Unit,
    onOpenCsvImport: () -> Unit,
    onOpenImports: () -> Unit,
    onOpenExport: () -> Unit,
    onOpenAlarm: () -> Unit,
    onAskClear: () -> Unit,
) {
    // «Beträge verbergen»: alle Beträge als «•••» (Prozente bleiben), auch im Widget
    if (hasData) {
        IconButton(onClick = { viewModel.setHideAmounts(!hideAmounts) }) {
            Icon(
                painterResource(if (hideAmounts) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                contentDescription = stringResource(
                    if (hideAmounts) R.string.a11y_portfolio_show_amounts else R.string.portfolio_hide_amounts
                )
            )
        }
    }
    Box {
        IconButton(onClick = { onMenuOpenChange(true) }, modifier = Modifier.testTag(UiTestTags.PORTFOLIO_MENU)) {
            Icon(
                painterResource(R.drawable.ic_more_vert),
                contentDescription = stringResource(R.string.action_more)
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
            // Gleicher Anfang wie Merkliste und Markt: App (→ «Über»), Aktualisieren
            AppMenuHead(
                refreshing = refreshing,
                onClose = { onMenuOpenChange(false) },
                onOpenAbout = onOpenAbout,
                onRefresh = { viewModel.refresh(force = true) }
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.portfolio_convert_to_value, currency)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_swap_horiz), null) },
                onClick = { onMenuOpenChange(false); onPickCurrency() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.portfolio_addresses_title)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_portfolio), null) },
                onClick = { onMenuOpenChange(false); onOpenAddresses() }
            )
            // Import = in die App hinein (Pfeil nach unten); darunter die unterstützten Quellen
            DropdownMenuItem(
                text = {
                    Column {
                        Text(stringResource(R.string.portfolio_csv_import_action))
                        Text(
                            CSV_IMPORT_SOURCES,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                leadingIcon = { Icon(painterResource(R.drawable.ic_download), null) },
                onClick = { onMenuOpenChange(false); onOpenCsvImport() }
            )
            // Importläufe (nur mit importierten Transaktionen): «Rückgängig» je Lauf
            if (importBatches.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.portfolio_imports_title)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_list), null) },
                    onClick = { onMenuOpenChange(false); onOpenImports() },
                    modifier = Modifier.testTag(UiTestTags.PORTFOLIO_IMPORTS)
                )
            }
            if (hasTransactions) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.portfolio_export_action)) },
                    // Export = aus der App hinaus (Pfeil nach oben)
                    leadingIcon = { Icon(painterResource(R.drawable.ic_upload), null) },
                    onClick = { onMenuOpenChange(false); onOpenExport() }
                )
            }
            if (hasData) {
                // Alarm «Portfolio-Wert» (erscheint in der Alarm-Übersicht)
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.portfolio_alarm_action)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_notifications_active), null) },
                    onClick = { onMenuOpenChange(false); onOpenAlarm() }
                )
                HorizontalDivider()
                // Wie «Merkliste leeren»: zuunterst, rot, mit Rückfrage
                DropdownMenuItem(
                    text = {
                        Text(stringResource(R.string.portfolio_clear), color = MaterialTheme.colorScheme.error)
                    },
                    leadingIcon = {
                        Icon(painterResource(R.drawable.ic_delete), null, tint = MaterialTheme.colorScheme.error)
                    },
                    onClick = { onMenuOpenChange(false); onAskClear() }
                )
            }
        }
    }
}
