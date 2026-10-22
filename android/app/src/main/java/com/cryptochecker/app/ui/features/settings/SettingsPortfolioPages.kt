package com.cryptochecker.app.ui.features.settings

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.cryptochecker.app.ui.theme.AppColors
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.CostMethod
import com.cryptochecker.app.domain.portfolio.TaxMethod
import com.cryptochecker.app.domain.portfolio.TaxTemplate
import com.cryptochecker.app.settings.deviceRegionLocale
import com.cryptochecker.app.ui.features.portfolio.taxMethodName
import com.cryptochecker.app.ui.features.portfolio.TaxTemplateConfirmDialog
import com.cryptochecker.app.ui.features.portfolio.taxTemplateLabel
import com.cryptochecker.app.lock.AppLockAuth
import com.cryptochecker.app.lock.PortfolioLockPolicy
import com.cryptochecker.app.lock.findFragmentActivity
import com.cryptochecker.app.ui.components.SwitchRow
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.components.HapticEvent
import com.cryptochecker.app.ui.components.rememberHaptics

// ── Portfolio ────────────────────────────────────────────────────────────────

/** Portfolio als eigener Tab, die Portfolio-Sperre (nur mit eingeschaltetem Portfolio) und die Einstandsmethode. */
@Composable
internal fun PortfolioPage(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SettingsSubPage(title = stringResource(R.string.portfolio_title), onBack = onBack) {
        GroupCard {
            SettingsAnchor("portfolio.tab") {
                SwitchRow(
                    title = stringResource(R.string.settings_portfolio_tab),
                    subtitle = stringResource(R.string.portfolio_setting_hint),
                    checked = settings.portfolioEnabled,
                    onCheckedChange = viewModel::setPortfolioEnabled
                )
            }
            // Nur mit eingeschaltetem Portfolio; der Wert bleibt beim Ausblenden erhalten
            if (PortfolioLockPolicy.showSetting(settings.portfolioEnabled)) {
                val unavailableText = stringResource(R.string.app_lock_unavailable)
                val haptics = rememberHaptics()
                val reason = stringResource(R.string.portfolio_lock_reason)
                // Build 46: ohne Displaysperre (PIN/Muster/Passwort) hebt sich die Sperre selbst auf
                // (AppLock.requireUnlock) — das hier ehrlich sagen; beim Zurückkehren neu prüfen
                var deviceSecure by remember { mutableStateOf(AppLockAuth.canAuthenticate(context)) }
                LifecycleResumeEffect(Unit) {
                    deviceSecure = AppLockAuth.canAuthenticate(context)
                    onPauseOrDispose { }
                }
                RowDivider()
                SettingsAnchor("portfolio.lock") {
                    Column {
                        SwitchRow(
                            title = stringResource(R.string.settings_portfolio_lock),
                            subtitle = stringResource(R.string.settings_portfolio_lock_hint),
                            checked = settings.appLock,
                            onCheckedChange = { on ->
                                val activity = context.findFragmentActivity()
                                if (!on) {
                                    // Solange gesperrt, erst entsperren — sonst wäre die Sperre hier zu umgehen
                                    viewModel.disableAppLock(activity, reason)
                                } else if (activity == null || !AppLockAuth.canAuthenticate(context)) {
                                    haptics.play(HapticEvent.ERROR)
                                    Toast.makeText(context, unavailableText, Toast.LENGTH_LONG).show()
                                } else {
                                    // Einschalten erst nach einer erfolgreichen Entsperrung
                                    viewModel.enableAppLock(activity, reason)
                                }
                            }
                        )
                        if (!deviceSecure) {
                            Text(
                                stringResource(R.string.settings_portfolio_lock_no_device_lock),
                                style = MaterialTheme.typography.bodySmall,
                                color = AppColors.warningText,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                    }
                }
                RowDivider()
                // «Beträge verbergen»: wie das Auge im Portfolio-Kopf (Portfolio und Widget)
                SettingsAnchor("portfolio.hide") {
                    SwitchRow(
                        title = stringResource(R.string.portfolio_hide_amounts),
                        subtitle = stringResource(R.string.portfolio_hide_amounts_hint),
                        checked = settings.hidePortfolioAmounts,
                        onCheckedChange = viewModel::setHidePortfolioAmounts
                    )
                }
            }
            // Build 46: Methode und Steuer-Vorlage erst mit eingeschaltetem Portfolio (wer nur Alarme will, sieht
            // keine Buchhaltung); die Werte bleiben beim Ausblenden erhalten
            if (settings.portfolioEnabled) {
                RowDivider()
                // Einstandsmethode für Einstand, «Seit Kauf», realisiert und Steuer-Export (Standard FIFO)
                SettingsAnchor("portfolio.cost_method") {
                    // Drei Zeilen untereinander (Titel + Hinweis) statt Segmentknopf: die Namen wurden dort abgeschnitten
                    Column(modifier = Modifier.padding(vertical = 8.dp).selectableGroup()) {
                        Text(
                            stringResource(R.string.settings_cost_method),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 4.dp).semantics { heading() }
                        )
                        CostMethod.entries.forEach { method ->
                            RadioRow(
                                selected = settings.costMethod == method,
                                onClick = { viewModel.setCostMethod(method) },
                                title = stringResource(
                                    when (method) {
                                        CostMethod.FIFO -> R.string.settings_cost_method_fifo
                                        CostMethod.FIFO_WALLET -> R.string.settings_cost_method_fifo_wallet
                                        CostMethod.AVERAGE -> R.string.settings_cost_method_average
                                    }
                                ),
                                subtitle = stringResource(
                                    when (method) {
                                        CostMethod.FIFO -> R.string.settings_cost_method_fifo_hint
                                        CostMethod.FIFO_WALLET -> R.string.settings_cost_method_fifo_wallet_hint
                                        CostMethod.AVERAGE -> R.string.settings_cost_method_average_hint
                                    }
                                ),
                            )
                        }
                    }
                }
                RowDivider()
                // Steuer-Vorlage der Arbeitsunterlage (Standard Neutral); die Region wird nur vorgeschlagen, nie still übernommen
                SettingsAnchor("portfolio.tax_template") {
                    TaxTemplateRow(settings.taxTemplate, settings.costMethod, viewModel::setTaxTemplate)
                    // Länder-Vorlage zum ersten Mal: Hinweis einmal bestätigen
                    TaxTemplateConfirmDialog(settings.taxTemplate, settings.taxTemplateConfirmed, viewModel::confirmTaxTemplate)
                }
            }
            RowDivider()
            // Immer sichtbar: Die Daten bleiben auch bei ausgeblendetem Portfolio gespeichert
            val backupReason = stringResource(R.string.portfolio_lock_reason)
            // Android 8/8.1: aus und gesperrt, mit Begründung (Systemsicherung dort unverschlüsselt)
            val systemBackupSupported = PortfolioLockPolicy.systemBackupSupported(Build.VERSION.SDK_INT)
            SettingsAnchor("portfolio.system_backup") {
                SwitchRow(
                    title = stringResource(R.string.settings_portfolio_system_backup),
                    subtitle = stringResource(
                        if (systemBackupSupported) R.string.settings_portfolio_system_backup_hint
                        else R.string.settings_portfolio_system_backup_unavailable
                    ),
                    checked = settings.portfolioSystemBackup && systemBackupSupported,
                    enabled = systemBackupSupported,
                    onCheckedChange = { on ->
                        viewModel.setPortfolioSystemBackup(context.findFragmentActivity(), backupReason, on)
                    }
                )
            }
        }
        // Ehrlich: Die Sperre schützt die Anzeige; die Daten schützt die Geräteverschlüsselung
        if (PortfolioLockPolicy.showSetting(settings.portfolioEnabled)) {
            Text(
                text = stringResource(R.string.settings_portfolio_lock_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = Spacing.md)
            )
        }
        // Eigener Server für Adressabfragen (nur Bitcoin); immer sichtbar wie die Adressen selbst
        AddressServerCard()
    }
}

/**
 * Steuer-Vorlage: aktuelle Wahl (antippen → Liste aller Vorlagen), Hinweis «nur Orientierung» und — wenn die Region des
 * Geräts eine andere Vorlage nahelegt — ein Vorschlag mit Knopf (nie still übernommen).
 */
@Composable
private fun TaxTemplateRow(selected: TaxTemplate, costMethod: CostMethod, onSelect: (TaxTemplate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val suggestion = TaxTemplate.suggest(deviceRegionLocale().country).takeIf { it != TaxTemplate.NEUTRAL && it != selected }
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            stringResource(R.string.tax_template_title),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { heading() }
        )
        Box {
            Text(
                stringResource(taxTemplateLabel(selected)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 8.dp)
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                TaxTemplate.entries.forEach { t ->
                    DropdownMenuItem(
                        text = { Text(stringResource(taxTemplateLabel(t))) },
                        onClick = { onSelect(t); open = false },
                    )
                }
            }
        }
        Hint(stringResource(R.string.tax_template_hint))
        // Vorlage verlangt eine andere Methode als die App-Einstellung: der Export rechnet mit der der Vorlage
        if (selected.methodOverridden(costMethod)) {
            Hint(
                stringResource(
                    R.string.tax_export_method_overridden,
                    taxMethodName(selected.methodFor(costMethod)),
                    taxMethodName(TaxMethod.of(costMethod)),
                ),
                top = 4.dp,
            )
        }
        if (suggestion != null) {
            val name = stringResource(taxTemplateLabel(suggestion))
            Hint(stringResource(R.string.tax_template_suggestion, name), top = 4.dp)
            TextButton(onClick = { onSelect(suggestion) }) { Text(stringResource(R.string.tax_template_use, name)) }
        }
    }
}
