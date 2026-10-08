@file:OptIn(ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.BuildConfig
import com.cryptochecker.app.R
import com.cryptochecker.app.settings.PriceColorChoice
import com.cryptochecker.app.ui.components.AddWidgetsSheet
import com.cryptochecker.app.ui.components.readableWidth
import com.cryptochecker.app.ui.features.about.EXCHANGE_REQUEST_URL
import com.cryptochecker.app.ui.features.about.LicensesSheet
import com.cryptochecker.app.ui.features.about.PRIVACY_POLICY_URL
import com.cryptochecker.app.ui.features.about.SOURCE_CODE_URL
import com.cryptochecker.app.util.ChangeBasisText
import kotlinx.coroutines.delay

/*
 * Runde 23f: Hauptseite der Einstellungen als kurze, gruppierte Liste (wie die Einstellungen
 * grosser Börsen-Apps): kleine graue Gruppenüberschrift, darunter schlichte Zeilen mit Titel,
 * aktuellem Wert in Grau und Pfeil. Alles Ausführliche steht auf Unterseiten
 * ([SettingsPageScreen], Markt-Meldungen, Sprachausgabe). Schlüssel und Verhalten unverändert.
 *
 * Gruppen: Allgemein · Darstellung · Alarme & Mitteilungen · Portfolio · Daten · Über.
 */
@Composable
fun SettingsScreen(
    onOpenMarketAlerts: () -> Unit = {},
    onOpenSpeech: () -> Unit = {},
    onOpenPage: (SettingsPage) -> Unit = {},
    onOpenSearchTarget: (SettingsSearchTarget) -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val languageTag by viewModel.languageTag.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    var widgetsOpen by rememberSaveable { mutableStateOf(false) }
    var licensesOpen by rememberSaveable { mutableStateOf(false) }
    // Runde 31: Suche oben; bleibt nach dem Öffnen eines Treffers stehen (zurück = weitere Treffer)
    var query by rememberSaveable { mutableStateOf("") }
    // Treffer auf der Hauptseite selbst (Sprache, Widgets, Links): Zeile kurz hervorheben
    var mainHighlight by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(mainHighlight) {
        if (mainHighlight != null) {
            delay(MAIN_HIGHLIGHT_MILLIS)
            mainHighlight = null
        }
    }
    val openSearchTarget: (SettingsSearchTarget) -> Unit = { target ->
        focusManager.clearFocus()
        if (target is SettingsSearchTarget.Main) {
            query = ""
            mainHighlight = target.anchor
        } else {
            onOpenSearchTarget(target)
        }
    }

    // Kopfzeile wie Markt und Portfolio: einzeilig, gleiche Farbe beim Scrollen
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                // Tablet/Querformat: Inhalt höchstens 640 dp breit, mittig
                .readableWidth()
                .padding(bottom = 16.dp)
        ) {
            SettingsSearchField(query = query, onQueryChange = { query = it })
            if (query.isNotBlank()) {
                SettingsSearchResults(items = settingsSearchItems(settings), query = query, onOpen = openSearchTarget)
            } else {
                ProvideSettingsHighlight(mainHighlight) {
                    // 1 Allgemein: Sprache, Währung, Aktualisierung, Merkliste
                    SettingsSectionHeader(stringResource(R.string.settings_group_general), first = true)
                    SettingsAnchor("main.language") {
                        LanguageRow(selectedTag = languageTag, onSelected = viewModel::setLanguage)
                    }
                    SettingsNavRow(
                        title = stringResource(R.string.settings_row_currency),
                        value = settings.portfolioCurrency,
                        onClick = { onOpenPage(SettingsPage.CURRENCY) }
                    )
                    SettingsNavRow(
                        title = stringResource(R.string.settings_row_updates),
                        value = updatesSummary(settings),
                        onClick = { onOpenPage(SettingsPage.UPDATES) }
                    )
                    SettingsNavRow(
                        title = stringResource(R.string.settings_row_watchlist),
                        value = watchlistSummary(settings),
                        onClick = { onOpenPage(SettingsPage.WATCHLIST) }
                    )

                    // 2 Darstellung: Modus, Theme, Kursfarben, Widgets
                    SettingsSectionHeader(stringResource(R.string.settings_section_appearance))
                    SettingsNavRow(
                        title = stringResource(R.string.settings_theme_mode),
                        value = displayModeSummary(settings),
                        onClick = { onOpenPage(SettingsPage.DISPLAY_MODE) }
                    )
                    val accentName = stringResource(settings.accentColor.labelRes)
                    SettingsNavRow(
                        title = stringResource(R.string.settings_accent),
                        valueDescription = accentName,
                        valueContent = {
                            Box(
                                Modifier
                                    .padding(end = 8.dp)
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(Color(settings.accentColor.seed))
                            )
                            SettingsValueText(accentName)
                        },
                        onClick = { onOpenPage(SettingsPage.THEME) }
                    )
                    val priceChoice = PriceColorChoice.of(settings.priceColorScheme, settings.priceColorsInverted)
                    val priceChoiceLabel = stringResource(priceColorChoiceLabel(priceChoice))
                    SettingsNavRow(
                        title = stringResource(R.string.settings_price_colors),
                        valueDescription = priceChoiceLabel,
                        valueContent = { PriceArrows(priceChoice) },
                        onClick = { onOpenPage(SettingsPage.PRICE_COLORS) }
                    )
                    // Basis der %-Änderung (Pille, Puls, Widgets): «Letzte 24 Std.», «Seit 00:00 UTC» …
                    SettingsNavRow(
                        title = stringResource(R.string.settings_change_basis, "%"),
                        value = ChangeBasisText.summary(settings.changeBasis),
                        onClick = { onOpenPage(SettingsPage.CHANGE_BASIS) }
                    )
                    // Coin-Logos: je ein Schalter für App, Portfolio und Widgets
                    SettingsNavRow(
                        title = stringResource(R.string.settings_coin_logos),
                        value = coinLogosSummary(settings),
                        onClick = { onOpenPage(SettingsPage.COIN_LOGOS) }
                    )
                    // Widgets direkt aus der App auf den Startbildschirm (Runde 13b)
                    SettingsAnchor("main.widgets") {
                        SettingsNavRow(
                            title = stringResource(R.string.settings_widgets),
                            onClick = { widgetsOpen = true }
                        )
                    }

                    // 3 Alarme & Mitteilungen
                    SettingsSectionHeader(stringResource(R.string.settings_group_alerts))
                    SettingsNavRow(
                        title = stringResource(R.string.settings_row_alarms),
                        value = stringResource(alarmSignalLabel(settings.alarmSignal)),
                        onClick = { onOpenPage(SettingsPage.ALARMS) }
                    )
                    SettingsNavRow(
                        title = stringResource(R.string.settings_market_alerts),
                        value = marketAlertsSummary(settings),
                        onClick = onOpenMarketAlerts
                    )
                    SettingsNavRow(
                        title = stringResource(R.string.settings_tts),
                        value = speechSummary(settings),
                        onClick = onOpenSpeech
                    )

                    // 4 Portfolio: Tab und Sperre
                    SettingsSectionHeader(stringResource(R.string.portfolio_title))
                    SettingsNavRow(
                        title = stringResource(R.string.portfolio_title),
                        value = portfolioSummary(settings),
                        onClick = { onOpenPage(SettingsPage.PORTFOLIO) }
                    )

                    // 5 Daten: Sichern & Wiederherstellen (Laufzeit-Futures und TradFi: unter «Merkliste»)
                    SettingsSectionHeader(stringResource(R.string.settings_group_data_only))
                    SettingsNavRow(
                        title = stringResource(R.string.backup_title),
                        onClick = { onOpenPage(SettingsPage.BACKUP) }
                    )

                    // 6 Über: App, Datenschutz, Börse wünschen, Quellcode, Lizenzen, Entwickler
                    SettingsSectionHeader(stringResource(R.string.settings_section_about))
                    SettingsNavRow(
                        title = stringResource(R.string.settings_row_about),
                        value = BuildConfig.VERSION_NAME,
                        onClick = { onOpenPage(SettingsPage.ABOUT) }
                    )
                    // Google Play verlangt den Link zur Datenschutzerklärung auch in der App.
                    // Ohne Browser wirft der Standard-Handler — nie zum Absturz werden lassen.
                    SettingsAnchor("main.privacy") {
                        SettingsNavRow(
                            title = stringResource(R.string.about_privacy_policy),
                            onClick = { runCatching { uriHandler.openUri(PRIVACY_POLICY_URL) } }
                        )
                    }
                    SettingsAnchor("main.exchange") {
                        SettingsNavRow(
                            title = stringResource(R.string.about_request_exchange),
                            onClick = { runCatching { uriHandler.openUri(EXCHANGE_REQUEST_URL) } }
                        )
                    }
                    SettingsAnchor("main.source") {
                        SettingsNavRow(
                            title = stringResource(R.string.about_source_code),
                            onClick = { runCatching { uriHandler.openUri(SOURCE_CODE_URL) } }
                        )
                    }
                    SettingsAnchor("main.licenses") {
                        SettingsNavRow(
                            title = stringResource(R.string.about_licenses),
                            onClick = { licensesOpen = true }
                        )
                    }
                    // Entwickler erst nach sieben Tipps auf die Version (Seite «Über die App»)
                    if (settings.developerUnlocked) {
                        SettingsNavRow(
                            title = stringResource(R.string.settings_section_developer),
                            value = onOff(settings.showHttpLog),
                            onClick = { onOpenPage(SettingsPage.DEVELOPER) }
                        )
                    }
                }
            }
        }
    }

    if (widgetsOpen) {
        AddWidgetsSheet(portfolioEnabled = settings.portfolioEnabled, onDismiss = { widgetsOpen = false })
    }
    if (licensesOpen) LicensesSheet(onDismiss = { licensesOpen = false })
}

/** Dauer der Hervorhebung einer Zeile der Hauptseite nach einem Suchtreffer (ms). */
private const val MAIN_HIGHLIGHT_MILLIS = 2_500L
