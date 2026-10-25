// Inhalt der Seite «Steuer-Arbeitsunterlage» (Teil von `TaxWorksheetScreen`): Vorlage samt Hinweisen, Steuerjahr,
// Regelstand, die vier Exporte mit Fortschritt/«Abbrechen» bzw. Fehlermeldung und die Jahresübersicht.
// Eigene Datei, weil `TaxWorksheetScreen` sonst zu lang würde; Zustand und Exporte bleiben dort.
package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.CostMethod
import com.cryptochecker.app.domain.portfolio.TaxPeriod
import com.cryptochecker.app.domain.portfolio.TaxSummaryTexts
import com.cryptochecker.app.domain.portfolio.TaxTemplate
import com.cryptochecker.app.domain.portfolio.TaxYearSummary
import com.cryptochecker.app.settings.deviceRegionLocale
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.util.LocaleNumbers
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Kleiner Hinweis in der Farbe der Nebentexte. */
@Composable
internal fun TaxWorksheetHint(text: String, top: Dp = Spacing.xs) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = top)
    )
}

/**
 * Steuer-Vorlage: Wahl (Liste aller Vorlagen, dieselbe Einstellung wie Einstellungen → Portfolio), Methode und Währung,
 * Länder-Vorlagen als «Beta» markiert, Hinweise und — wenn die Region des Geräts eine andere Vorlage nahelegt — ein
 * Vorschlag mit Knopf (nie still übernommen).
 */
@Composable
internal fun TaxWorksheetTemplateSection(
    template: TaxTemplate,
    costMethod: CostMethod,
    taxYear: Int?,
    workCurrency: String,
    methodNote: String?,
    running: Boolean,
    onSelect: (TaxTemplate) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val templateName = stringResource(taxTemplateLabel(template))
    Text(
        stringResource(R.string.tax_template_title),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { heading() }
    )
    Box {
        Text(
            templateName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (running) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !running) { open = true }
                .testTag(UiTestTags.TAX_WORKSHEET_TEMPLATE)
                .padding(vertical = 8.dp)
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            TaxTemplate.entries.forEach { t ->
                DropdownMenuItem(
                    text = { Text(stringResource(taxTemplateLabel(t))) },
                    onClick = { onSelect(t); open = false },
                    modifier = Modifier.testTag(UiTestTags.taxTemplateOption(t.name)),
                )
            }
        }
    }
    // Länder-Vorlagen als «Beta» markiert (Neutral und Stichtag-Export nicht)
    val beta = template != TaxTemplate.NEUTRAL
    val method = template.methodFor(costMethod, taxYear)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.tax_template_current, templateName, taxMethodName(method), workCurrency),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (beta) TaxBetaBadge()
    }
    if (beta) TaxWorksheetHint(stringResource(R.string.tax_template_beta_note))
    if (methodNote != null) TaxWorksheetHint(methodNote)
    // Methode/Vorlage gewechselt: abgegebene Jahre werden still neu gerechnet — fester Hinweis
    TaxWorksheetHint(stringResource(R.string.tax_export_method_change_warning))
    val suggestion = TaxTemplate.suggest(deviceRegionLocale().country)
        .takeIf { it != TaxTemplate.NEUTRAL && it != template }
    if (suggestion != null) {
        val suggestionName = stringResource(taxTemplateLabel(suggestion))
        TaxWorksheetHint(stringResource(R.string.tax_template_suggestion, suggestionName))
        TextButton(enabled = !running, onClick = { onSelect(suggestion) }) {
            Text(stringResource(R.string.tax_template_use, suggestionName))
        }
    }
}

/**
 * Steuerjahr (Liste der Jahre mit Transaktionen), Zeitraum in Ortszeit, Regelstand ([TaxRulesNotice]), Hinweise der
 * Vorlage, die vier Exporte, darunter Fortschritt mit «Abbrechen» bzw. die Fehlermeldung, und die Jahresübersicht.
 */
@Composable
internal fun TaxWorksheetYearSection(
    template: TaxTemplate,
    years: List<Int>,
    taxYear: Int,
    period: TaxPeriod,
    state: CutoffExportState,
    onYear: (Int) -> Unit,
    onGains: () -> Unit,
    onIncome: () -> Unit,
    onHoldings: () -> Unit,
    onSummary: () -> Unit,
    onCancel: () -> Unit,
    onRetry: (ExportFailureKind) -> Unit,
    summary: TaxYearSummary?,
    summaryTexts: TaxSummaryTexts,
    methodNote: String?,
) {
    val running = state == CutoffExportState.Running
    var yearMenu by remember { mutableStateOf(false) }
    Text(
        stringResource(R.string.tax_export_tax_year),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.md)
    )
    Box {
        Text(
            template.label(taxYear),
            style = MaterialTheme.typography.titleMedium.tabularNumbers(),
            fontWeight = FontWeight.SemiBold,
            color = if (running) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !running) { yearMenu = true }
                .padding(vertical = 8.dp)
        )
        DropdownMenu(expanded = yearMenu, onDismissRequest = { yearMenu = false }) {
            years.forEach { y ->
                DropdownMenuItem(text = { Text(template.label(y)) }, onClick = { onYear(y); yearMenu = false })
            }
        }
    }
    val dates = LocaleNumbers.dates(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    // Steuerjahr in Ortszeit (Verlauf/Heute in UTC) — Zone nennen
    TaxWorksheetHint(
        stringResource(R.string.tax_export_period, period.start.format(dates), period.end.format(dates)) + "\n" +
            stringResource(R.string.tax_export_year_local_zone, template.zone().id),
        top = 0.dp,
    )
    TaxRulesNotice(template, taxYear) // Regelstand; Steuerjahr danach: Warnhinweis (blockiert nicht)
    // Hinweise der Vorlage (was die App nicht kann bzw. wie sie zählt)
    taxTemplateNotes(template).forEach { TaxWorksheetHint(it) }
    // Steuer-CSV nach den Regeln des Landes: kann von «realisiert» im Portfolio abweichen
    TaxWorksheetHint(stringResource(R.string.tax_export_realized_differs))
    TextButton(enabled = !running, onClick = onGains, modifier = Modifier.testTag(UiTestTags.TAX_WORKSHEET_GAINS)) {
        Text(stringResource(R.string.tax_export_gains))
    }
    TextButton(enabled = !running, onClick = onIncome, modifier = Modifier.testTag(UiTestTags.TAX_WORKSHEET_INCOME)) {
        Text(stringResource(R.string.tax_export_income))
    }
    // Bestand am Ende des Steuerjahrs (Stichtag-Export mit Kopfzeilen der Arbeitsunterlage)
    TextButton(
        enabled = !running,
        onClick = onHoldings,
        modifier = Modifier.testTag(UiTestTags.TAX_WORKSHEET_HOLDINGS),
    ) {
        Text(stringResource(R.string.tax_export_holdings_cutoff, period.end.format(dates)))
    }
    // Jahresübersicht der Vorlage: Orientierung, keine Steuerberatung (CSV + Karte)
    TextButton(enabled = !running, onClick = onSummary, modifier = Modifier.testTag(UiTestTags.TAX_WORKSHEET_SUMMARY)) {
        Text(stringResource(R.string.tax_export_summary, stringResource(taxTemplateLabel(template))))
    }
    if (running) {
        // Laufender Export: abbrechen löscht die angefangene Datei
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.sm)) {
            ExportRunningRow(modifier = Modifier.weight(1f))
            TextButton(onClick = onCancel, modifier = Modifier.testTag(UiTestTags.EXPORT_CANCEL)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
    (state as? CutoffExportState.Failed)?.let { failed ->
        ExportFailureRow(failed.kind, onRetry = { onRetry(failed.kind) }, modifier = Modifier.padding(top = Spacing.sm))
    }
    TaxSummaryCard(summary, summaryTexts, methodNote)
}
