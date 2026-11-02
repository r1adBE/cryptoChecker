@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.BuildConfig
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.CutoffExport
import com.cryptochecker.app.domain.portfolio.EstvKursliste
import com.cryptochecker.app.domain.portfolio.TaxExport
import com.cryptochecker.app.domain.portfolio.TaxMethod
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.app.ui.components.AppSnackbarHost
import com.cryptochecker.app.ui.components.HapticEvent
import com.cryptochecker.app.ui.components.rememberHaptics
import com.cryptochecker.app.ui.features.watchlist.rememberWatchlistBanner
import com.cryptochecker.app.ui.theme.SnackbarKind
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.util.LocaleNumbers
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Steuer-Arbeitsunterlage als eigene Seite (aus Portfolio ⋯ › «Steuer-Arbeitsunterlage…»): Steuer-Vorlage wählen
 * (dieselbe Einstellung wie Einstellungen → Portfolio; ein Vorschlag aus der Region wird nur angeboten), Methode und
 * Währung der Vorlage, Steuerjahr nach ihren Grenzen, Regelstand; dann realisierte Veräusserungen, Einkünfte, Bestand
 * per Ende des Steuerjahrs (CH: 31.12. in CHF mit ESTV-Hinweis) und Jahresübersicht (CSV + Karte) ([TaxExport]).
 * Ein laufender Export lässt sich abbrechen (angefangene Datei wird gelöscht); Fehler nach Art mit «Erneut versuchen».
 * [currency] = Anzeigewährung des Portfolios («Umrechnen in»).
 */
@Composable
fun TaxWorksheetScreen(
    currency: String,
    onBack: () -> Unit,
    viewModel: PortfolioExportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val years by viewModel.years.collectAsStateWithLifecycle()
    val costMethod by viewModel.costMethod.collectAsStateWithLifecycle()
    val template by viewModel.taxTemplate.collectAsStateWithLifecycle()
    val templateConfirmed by viewModel.taxTemplateConfirmed.collectAsStateWithLifecycle()
    // Länder-Vorlage zum ersten Mal (gewählt oder Vorschlag übernommen): Hinweis einmal bestätigen
    TaxTemplateConfirmDialog(template, templateConfirmed, viewModel::confirmTaxTemplate)
    // Währung der Arbeitsunterlage: die der Vorlage (Neutral: Anzeigewährung)
    val workCurrency = viewModel.workCurrency(template, currency)
    // 0 = noch nicht gewählt → Standard (letztes abgeschlossenes Steuerjahr)
    var chosenYear by rememberSaveable { mutableIntStateOf(0) }
    val taxYear = chosenYear.takeIf { it in years } ?: TaxExport.defaultYear(years, LocalDate.now(), template)
    // Methode je Steuerjahr (US ab 2025 nur FIFO je Wallet)
    val method = template.methodFor(costMethod, taxYear)
    val period = taxYear?.let { template.period(it) }
    var taxKind by rememberSaveable { mutableStateOf<TaxExportKind?>(null) }
    // Zuletzt angebotener Dateiname: «Erneut versuchen» nach «Datei nicht schreibbar» fragt damit nach einer neuen
    // Datei
    var lastFileName by rememberSaveable { mutableStateOf("") }
    // Daten in der Zeitzone der Vorlage (Neutral: Gerät) — in jeder CSV der Arbeitsunterlage genannt
    val taxZone = template.zone()
    val taxTexts = taxCsvTexts(workCurrency, taxZone, template)
    val templateName = stringResource(taxTemplateLabel(template))
    val methodLabel = taxMethodName(method)
    val yearLabel = taxYear?.let { template.label(it) }.orEmpty()
    // Kopfzeile jeder Datei: Vorlage, Methode, Steuerjahr, Währung, Version; Arbeitsunterlage – keine Steuerberatung
    val info = stringResource(
        R.string.tax_export_info_template, templateName, methodLabel, yearLabel, workCurrency, BuildConfig.VERSION_NAME,
    )
    val disclaimer = stringResource(R.string.tax_export_workpaper_disclaimer)
    // Länder-Vorlagen: Regelstand (Monat/Jahr der App-Sprache) in jeder Kopfzeile
    val rulesLine = taxRulesLine(template)
    // Beträge in USDT, umgerechnet mit USD-Kursen × USDT/USD-Tagesschluss (ohne Schluss 1, je Zeile vermerkt)
    val usdtNote = stringResource(R.string.tax_export_usdt_usd_note)
    // Eine Tageskurs-Regel für geschätzte Marktwerte (TaxDayClose): Schluss des UTC-Tags der Transaktion
    val dayCloseNote = stringResource(R.string.tax_export_day_close_rule)
    val rule = stringResource(R.string.tax_export_rule)
    // AT: Durchschnitt je Coin und Wallet (KryptowährungsVO § 2) — steht in der Kopfzeile
    val atWalletNote = if (method == TaxMethod.AT_MOVING_AVERAGE) stringResource(R.string.tax_export_at_wallet_note) else null
    // Vorlage erlaubt die Einstandsmethode der App nicht: offen sagen, womit der Export rechnet (Kopfzeile und Seite)
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
            val header = listOfNotNull(summaryTexts.title, info, rulesLine, methodNote, atWalletNote, usdtNote, dayCloseNote, disclaimer)
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
                TaxExportKind.GAINS -> listOfNotNull(gainsTitle, info, rulesLine, methodNote, rule, atWalletNote, usdtNote, dayCloseNote, disclaimer)
                TaxExportKind.INCOME -> listOfNotNull(incomeTitle, info, rulesLine, usdtNote, dayCloseNote, disclaimer)
            }
            viewModel.exportTax(uri, kind, taxYear, currency, taxTexts, TaxExport.withChWealthNote(template, header, chWealthNote))
        }
    }
    // Bestand per Stichtag = Ende des Steuerjahrs (CH: 31.12. in CHF mit ESTV-Hinweis), mit Kopfzeilen der Arbeitsunterlage;
    // gerechnet nach der Methode der Vorlage (im Kopf genannt, samt «Vorlage ersetzt die Methode»)
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
                TaxExport.withChWealthNote(template, listOfNotNull(holdingsTitle, info, rulesLine, methodNote, usdtNote, disclaimer, zoneLine), chWealthNote),
                zone = taxZone, method = method.holdingsCostMethod, // Methode der Vorlage, wie im Kopf
            )
        }
    }
    // «Erneut versuchen» nach «Datei nicht schreibbar»: neue Datei wählen, dann derselbe Export dorthin
    val retryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> uri?.let { viewModel.retryExport(it) } }

    val scope = rememberCoroutineScope()
    val banner = rememberWatchlistBanner(scope)
    val doneText = stringResource(R.string.portfolio_export_done)
    val haptics = rememberHaptics()
    LaunchedEffect(state) {
        when (state) {
            // Seite bleibt offen (weitere Dateien der Arbeitsunterlage), Bestätigung in der eigenen Snackbar
            CutoffExportState.Done -> {
                viewModel.consume()
                haptics.play(HapticEvent.SUCCESS)
                banner.show(doneText, SnackbarKind.SUCCESS)
            }
            is CutoffExportState.Failed -> haptics.play(HapticEvent.ERROR)
            else -> Unit
        }
    }
    val launch: (String, () -> Unit) -> Unit = { name, start ->
        viewModel.dismissFailure()
        lastFileName = name
        start()
    }

    Scaffold(
        modifier = Modifier.testTag(UiTestTags.TAX_WORKSHEET_SCREEN),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tax_export_workpaper_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    // Zurück während eines Exports: abbrechen, angefangene Datei löschen
                    IconButton(onClick = { viewModel.cancelExport(); onBack() }) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(banner.host) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
        ) {
            TaxWorksheetTemplateSection(
                template = template,
                costMethod = costMethod,
                taxYear = taxYear,
                workCurrency = workCurrency,
                methodNote = methodNote,
                running = state == CutoffExportState.Running,
                onSelect = viewModel::setTaxTemplate,
            )
            if (taxYear != null && period != null) {
                TaxWorksheetYearSection(
                    template = template,
                    years = years,
                    taxYear = taxYear,
                    period = period,
                    state = state,
                    onYear = { chosenYear = it },
                    onGains = {
                        launch(TaxExport.gainsFileName(taxYear)) {
                            taxKind = TaxExportKind.GAINS; taxLauncher.launch(TaxExport.gainsFileName(taxYear))
                        }
                    },
                    onIncome = {
                        launch(TaxExport.incomeFileName(taxYear)) {
                            taxKind = TaxExportKind.INCOME; taxLauncher.launch(TaxExport.incomeFileName(taxYear))
                        }
                    },
                    onHoldings = {
                        val name = CutoffExport.fileName(period.end)
                        launch(name) { holdingsLauncher.launch(name) }
                    },
                    onSummary = {
                        val name = TaxExport.summaryFileName(template, taxYear)
                        launch(name) { summaryLauncher.launch(name) }
                    },
                    onCancel = viewModel::cancelExport,
                    onRetry = { kind ->
                        if (kind == ExportFailureKind.FILE && lastFileName.isNotEmpty()) {
                            retryLauncher.launch(lastFileName)
                        } else {
                            viewModel.retryExport()
                        }
                    },
                    summary = summary?.takeIf { it.period == period && it.template == template },
                    summaryTexts = summaryTexts,
                    methodNote = methodNote,
                )
            }
            // Arbeitsunterlage – keine Steuererklärung und keine Steuerberatung
            TaxWorksheetHint(disclaimer, top = Spacing.md)
        }
    }
}
