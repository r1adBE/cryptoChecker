package com.cryptochecker.app.ui.features.portfolio

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.BuildConfig
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.CutoffExport
import com.cryptochecker.app.domain.portfolio.EstvKursliste
import com.cryptochecker.app.domain.portfolio.TaxExport
import com.cryptochecker.app.domain.portfolio.TaxMethod
import com.cryptochecker.app.domain.portfolio.TaxTemplate
import com.cryptochecker.app.settings.deviceRegionLocale
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.util.LocaleNumbers
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Stichtag-Export: Datum wählen (Standard 31.12. des Vorjahrs), Währung aus der
 * Einstellung «Umrechnen in» (nur Anzeige), dann CSV-Datei anlegen lassen.
 * Darunter «Arbeitsunterlage»: gewählte Steuer-Vorlage (Einstellung; ein Vorschlag aus der Region wird nur angeboten),
 * Methode und Währung der Vorlage, Steuerjahr nach ihren Grenzen; dann realisierte Veräusserungen, Einkünfte, Bestand per
 * Ende des Steuerjahrs (CH: 31.12. in CHF mit ESTV-Hinweis) und Jahresübersicht (CSV + Karte) ([TaxExport]).
 * Der Dialog bleibt während des Exports offen und zeigt den Fortschritt.
 */
@Composable
fun PortfolioExportDialog(
    currency: String,
    onDismiss: () -> Unit,
    viewModel: PortfolioExportViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val running = state == CutoffExportState.Running

    // Als Epochentag, damit die Wahl einen Neuaufbau (und die Dateiauswahl) übersteht
    var epochDay by rememberSaveable { mutableLongStateOf(CutoffExport.defaultDate(LocalDate.now()).toEpochDay()) }
    val date = LocalDate.ofEpochDay(epochDay)
    var pickDate by remember { mutableStateOf(false) }

    val texts = cutoffTexts(currency)

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> uri?.let { viewModel.export(it, date, currency, texts) } }

    // ---- Arbeitsunterlage (Steuer-Vorlage) ----
    val years by viewModel.years.collectAsStateWithLifecycle()
    val costMethod by viewModel.costMethod.collectAsStateWithLifecycle()
    val template by viewModel.taxTemplate.collectAsStateWithLifecycle()
    val templateConfirmed by viewModel.taxTemplateConfirmed.collectAsStateWithLifecycle()
    // Länder-Vorlage zum ersten Mal (z. B. Vorschlag übernommen): Hinweis einmal bestätigen
    TaxTemplateConfirmDialog(template, templateConfirmed, viewModel::confirmTaxTemplate)
    // Währung der Arbeitsunterlage: die der Vorlage (Neutral: Anzeigewährung)
    val workCurrency = viewModel.workCurrency(template, currency)
    // 0 = noch nicht gewählt → Standard (letztes abgeschlossenes Steuerjahr)
    var chosenYear by rememberSaveable { mutableIntStateOf(0) }
    val taxYear = chosenYear.takeIf { it in years } ?: TaxExport.defaultYear(years, LocalDate.now(), template)
    // Methode je Steuerjahr (US ab 2025 nur FIFO je Wallet)
    val method = template.methodFor(costMethod, taxYear)
    val period = taxYear?.let { template.period(it) }
    var yearMenu by remember { mutableStateOf(false) }
    var taxKind by rememberSaveable { mutableStateOf<TaxExportKind?>(null) }
    // Daten in der Zeitzone der Vorlage (Neutral: Gerät) — in jeder CSV der Arbeitsunterlage genannt
    val taxZone = template.zone()
    val taxTexts = taxCsvTexts(workCurrency, taxZone, template)
    val templateName = stringResource(taxTemplateLabel(template))
    val methodLabel = taxMethodName(method)
    val yearLabel = taxYear?.let { template.label(it) }.orEmpty()
    // Kopfzeile jeder Datei: Vorlage, Methode, Steuerjahr, Währung, Version; Arbeitsunterlage – keine Steuerberatung
    val info = stringResource(R.string.tax_export_info_template, templateName, methodLabel, yearLabel, workCurrency, BuildConfig.VERSION_NAME)
    val disclaimer = stringResource(R.string.tax_export_workpaper_disclaimer)
    // Länder-Vorlagen: Stand der hinterlegten Regeln in jeder Kopfzeile
    val rulesLine = if (template != TaxTemplate.NEUTRAL) stringResource(R.string.tax_export_rules_date, TaxTemplate.RULES_DATE) else null
    // Beträge in USDT, umgerechnet mit USD-Kursen × USDT/USD-Tagesschluss (ohne Schluss 1, je Zeile vermerkt) — in jeder Kopfzeile
    val usdtNote = stringResource(R.string.tax_export_usdt_usd_note)
    val rule = stringResource(R.string.tax_export_rule)
    // AT: Durchschnitt je Coin und Wallet (KryptowährungsVO § 2) — steht in der Kopfzeile
    val atWalletNote = if (method == TaxMethod.AT_MOVING_AVERAGE) stringResource(R.string.tax_export_at_wallet_note) else null
    // Vorlage erlaubt die Einstandsmethode der App nicht: offen sagen, womit der Export rechnet (Kopfzeile und Dialog)
    val methodNote = if (template.methodOverridden(costMethod, taxYear)) {
        stringResource(R.string.tax_export_method_overridden, methodLabel, taxMethodName(TaxMethod.of(costMethod)))
    } else null
    // «Daten in Ortszeit (Zone)» — Verlauf/Heute rechnen in UTC (Hinweis dort)
    val zoneLine = stringResource(R.string.tax_export_year_local_zone, taxZone.id)
    val gainsTitle = stringResource(R.string.tax_export_gains_title, yearLabel)
    val incomeTitle = stringResource(R.string.tax_export_income_title, yearLabel)
    val summaryTexts = taxSummaryTexts(template, yearLabel, workCurrency, taxYear)
    // CH: «Vermögenssteuer nicht gerechnet – Kursliste ESTV» in den ersten Kopfzeilen jeder CSV (TaxExport.withChWealthNote)
    val chWealthNote = taxYear?.let {
        stringResource(
            R.string.tax_export_ch_wealth_not_calculated,
            EstvKursliste.listUrl(it, LocalConfiguration.current.locales[0].language),
        )
    }.orEmpty()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    // Einstandsmethode als Schlüssel: US-Übergang hängt an ihr, nicht nur an der Methode der Vorlage
    LaunchedEffect(taxYear, template, method, workCurrency, costMethod) { taxYear?.let { viewModel.loadSummary(it, currency) } }
    val summaryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null && taxYear != null) {
            val header = listOfNotNull(summaryTexts.title, info, rulesLine, methodNote, atWalletNote, usdtNote, disclaimer)
            viewModel.exportSummary(uri, taxYear, currency, summaryTexts, TaxExport.withChWealthNote(template, header, chWealthNote), zoneLine)
        }
    }
    val taxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        val kind = taxKind
        taxKind = null
        if (uri != null && kind != null && taxYear != null) {
            val header = when (kind) {
                TaxExportKind.GAINS -> listOfNotNull(gainsTitle, info, rulesLine, methodNote, rule, atWalletNote, usdtNote, disclaimer)
                TaxExportKind.INCOME -> listOfNotNull(incomeTitle, info, rulesLine, usdtNote, disclaimer)
            }
            viewModel.exportTax(uri, kind, taxYear, currency, taxTexts, TaxExport.withChWealthNote(template, header, chWealthNote))
        }
    }
    // Bestand per Stichtag = Ende des Steuerjahrs (CH: 31.12. in CHF mit ESTV-Hinweis), mit Kopfzeilen der Arbeitsunterlage
    val holdingsTexts = cutoffTexts(workCurrency)
    val holdingsEnd = period?.end
    val holdingsTitle = holdingsEnd?.let {
        stringResource(R.string.tax_export_holdings_title, it.format(LocaleNumbers.dates(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))))
    }.orEmpty()
    val holdingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null && holdingsEnd != null) {
            viewModel.export(
                uri, holdingsEnd, workCurrency, holdingsTexts,
                TaxExport.withChWealthNote(template, listOfNotNull(holdingsTitle, info, rulesLine, usdtNote, disclaimer, zoneLine), chWealthNote),
                zone = taxZone,
            )
        }
    }
    // Vorschlag aus der Region des Geräts: nur anbieten, nie still übernehmen
    val suggestion = TaxTemplate.suggest(deviceRegionLocale().country).takeIf { it != TaxTemplate.NEUTRAL && it != template }

    val doneText = stringResource(R.string.portfolio_export_done)
    val failedText = stringResource(R.string.portfolio_export_failed)
    LaunchedEffect(state) {
        val current = state
        val text = when (current) {
            CutoffExportState.Done -> doneText
            CutoffExportState.Failed -> failedText
            else -> return@LaunchedEffect
        }
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        viewModel.consume()
        if (current == CutoffExportState.Done) onDismiss()
    }

    if (pickDate) {
        DateDialog(
            selected = date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            onSelect = { epochDay = it.toEpochDay(); pickDate = false },
            onDismiss = { pickDate = false }
        )
    }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text(stringResource(R.string.portfolio_export_title)) },
        text = {
            // Mit Steuer-Export länger: scrollbar
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.portfolio_export_date),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    date.format(LocaleNumbers.dates(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))),
                    style = MaterialTheme.typography.titleMedium.tabularNumbers(),
                    fontWeight = FontWeight.SemiBold,
                    color = if (running) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !running) { pickDate = true }
                        .padding(vertical = 8.dp)
                )
                // Stichtag heute (UTC): Tag noch nicht abgeschlossen, es gilt der aktuelle Kurs
                if (!date.isBefore(LocalDate.now(java.time.ZoneOffset.UTC))) {
                    Text(
                        stringResource(R.string.portfolio_export_today_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                Text(
                    stringResource(R.string.portfolio_export_currency, currency),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    stringResource(R.string.portfolio_export_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
                // Arbeitsunterlage: nur mit Steuerjahren (Transaktionen)
                if (taxYear != null && period != null) {
                    HorizontalDivider(modifier = Modifier.padding(top = 16.dp, bottom = 12.dp))
                    Text(
                        stringResource(R.string.tax_export_workpaper_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    // Gewählte Vorlage immer sichtbar (Einstellungen → Portfolio → Steuer-Vorlage); Länder-Vorlagen als «Beta»
                    // markiert (Neutral und Stichtag-Export nicht)
                    val beta = template != TaxTemplate.NEUTRAL
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Text(
                            stringResource(R.string.tax_template_current, templateName, methodLabel, workCurrency),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (beta) TaxBetaBadge()
                    }
                    if (beta) {
                        Text(
                            stringResource(R.string.tax_template_beta_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    if (methodNote != null) {
                        Text(
                            methodNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    // Methode/Vorlage gewechselt: abgegebene Jahre werden still neu gerechnet — fester Hinweis
                    Text(
                        stringResource(R.string.tax_export_method_change_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    if (suggestion != null) {
                        val suggestionName = stringResource(taxTemplateLabel(suggestion))
                        Text(
                            stringResource(R.string.tax_template_suggestion, suggestionName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(enabled = !running, onClick = { viewModel.setTaxTemplate(suggestion) }) {
                            Text(stringResource(R.string.tax_template_use, suggestionName))
                        }
                    }
                    Text(
                        stringResource(R.string.tax_export_tax_year),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Box {
                        Text(
                            yearLabel,
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
                                DropdownMenuItem(
                                    text = { Text(template.label(y)) },
                                    onClick = { chosenYear = y; yearMenu = false },
                                )
                            }
                        }
                    }
                    val dates = LocaleNumbers.dates(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
                    Text(
                        // Steuerjahr in Ortszeit (Verlauf/Heute in UTC) — Zone nennen
                        stringResource(R.string.tax_export_period, period.start.format(dates), period.end.format(dates)) + "\n" +
                            stringResource(R.string.tax_export_year_local_zone, template.zone().id),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Hinweise der Vorlage (was die App nicht kann bzw. wie sie zählt)
                    taxTemplateNotes(template).forEach { note ->
                        Text(
                            note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    // Steuer-CSV nach den Regeln des Landes: kann von «realisiert» im Portfolio abweichen
                    Text(
                        stringResource(R.string.tax_export_realized_differs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    TextButton(
                        enabled = !running,
                        onClick = { taxKind = TaxExportKind.GAINS; taxLauncher.launch(TaxExport.gainsFileName(taxYear)) },
                    ) { Text(stringResource(R.string.tax_export_gains)) }
                    TextButton(
                        enabled = !running,
                        onClick = { taxKind = TaxExportKind.INCOME; taxLauncher.launch(TaxExport.incomeFileName(taxYear)) },
                    ) { Text(stringResource(R.string.tax_export_income)) }
                    // Bestand am Ende des Steuerjahrs (Stichtag-Export mit Kopfzeilen der Arbeitsunterlage)
                    TextButton(
                        enabled = !running,
                        onClick = { holdingsLauncher.launch(CutoffExport.fileName(period.end)) },
                    ) { Text(stringResource(R.string.tax_export_holdings_cutoff, period.end.format(dates))) }
                    // Jahresübersicht der Vorlage: Orientierung, keine Steuerberatung (CSV + Karte)
                    TextButton(
                        enabled = !running,
                        onClick = { summaryLauncher.launch(TaxExport.summaryFileName(template, taxYear)) },
                    ) { Text(stringResource(R.string.tax_export_summary, templateName)) }
                    TaxSummaryCard(summary?.takeIf { it.period == period && it.template == template }, summaryTexts, methodNote)
                }
                if (running) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(R.string.portfolio_export_running),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !running,
                onClick = { launcher.launch(CutoffExport.fileName(date)) }
            ) { Text(stringResource(R.string.portfolio_export_button)) }
        },
        dismissButton = {
            TextButton(enabled = !running, onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/** Kleine Kapsel «Beta» neben dem Namen einer Länder-Vorlage (Regeln vereinfacht). */
@Composable
private fun TaxBetaBadge() {
    Text(
        stringResource(R.string.tax_template_beta),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        modifier = Modifier
            .padding(start = 6.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    )
}

/**
 * Länder-Vorlage gewählt und der Hinweis «Beta, vereinfachte Regeln, Transaktionen anderer Plattformen fehlen – vor der
 * Steuererklärung prüfen» noch nie bestätigt: Dialog mit OK (einmal; Zurück schliesst ihn nur bis zum nächsten Mal).
 */
@Composable
internal fun TaxTemplateConfirmDialog(template: TaxTemplate, confirmed: Boolean, onConfirm: () -> Unit) {
    var dismissed by remember(template) { mutableStateOf(false) }
    if (template == TaxTemplate.NEUTRAL || confirmed || dismissed) return
    AlertDialog(
        onDismissRequest = { dismissed = true },
        title = { Text(stringResource(R.string.tax_template_confirm_title)) },
        text = { Text(stringResource(R.string.tax_template_confirm_text)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(android.R.string.ok)) } },
    )
}
