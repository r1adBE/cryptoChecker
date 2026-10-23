@file:OptIn(ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.settings

import com.cryptochecker.app.ui.components.DialogText
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.navigation.ScreenRoute
import com.cryptochecker.app.ui.navigation.OnTabReselect
import androidx.compose.ui.platform.LocalContext
import com.cryptochecker.app.ui.components.CardPadding
import com.cryptochecker.app.ui.components.AppCard
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import android.app.ActivityManager
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.BuildConfig
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.theme.accentButtonColor
import com.cryptochecker.app.ui.theme.LocalDarkTheme
import com.cryptochecker.app.ui.components.TabTitle
import com.cryptochecker.app.ui.components.readableWidth
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.app.notification.AlarmSoundStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Runde 23f: Hauptseite der Einstellungen als kurze, gruppierte Liste (wie die Einstellungen
 * grosser Börsen-Apps): je Gruppe eine Karte wie im Markt-Tab «Jetzt» ([SettingsSection]) mit der
 * Überschrift oben in der Karte, darunter schlichte Zeilen mit Titel, aktuellem Wert in Grau und
 * Pfeil. Alles Ausführliche steht auf Unterseiten
 * ([SettingsPageScreen], Markt-Meldungen, Sprachausgabe). Schlüssel und Verhalten unverändert.
 *
 * Gruppen: Allgemein (Sprache, Währung, Merkliste, Portfolio, Alarme, Kurs-Aktualisierung)
 * · Darstellung (Hell/Dunkel, Akzent, Kursfarben, Widgets) · Mehr (Sicherung, «Erweitert», Über die App)
 * · rote Karte «App zurücksetzen». Kurs-Aktualisierung und Widgets stehen wieder auf der Hauptseite, weil
 * Alarm-Hinweis und Widget-Hilfe dorthin verweisen und beide oft gesucht werden. Portfolio hat
 * genau einen Ort: die Zeile unter «Allgemein» führt zur Seite mit Schalter, Sperre, Methode, Steuer-Vorlage
 * und Systemsicherung — Einstellungen › Portfolio, wie die Hinweise der App es nennen. «Erweitert»
 * ([AdvancedPage]) trägt Token & TradFi, Markt-Meldungen, Sprachausgabe, Coin-Logos und Entwickler;
 * Datenschutz, Börse wünschen, Quellcode und Lizenzen stehen auf «Über die App».
 * Jede Zeile der Hauptseite trägt links ein schlichtes graues Symbol (Unterseiten ohne);
 * Merkliste wie der Tab, nur «App zurücksetzen» rot.
 */
@Composable
fun SettingsScreen(
    onOpenPage: (SettingsPage) -> Unit = {},
    onOpenSearchTarget: (SettingsSearchTarget) -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val languageTag by viewModel.languageTag.collectAsStateWithLifecycle()
    // Runde 31: Suche oben; bleibt nach dem Öffnen eines Treffers stehen (zurück = weitere Treffer)
    var query by rememberSaveable { mutableStateOf("") }
    // Treffer auf der Hauptseite selbst (Sprache, Widgets, Links): Zeile kurz hervorheben
    var mainHighlight by remember { mutableStateOf<String?>(null) }
    // «App zurücksetzen» — Rückfrage (saveable: übersteht Drehen, Dunkelmodus und Sprachwechsel)
    var askReset by rememberSaveable { mutableStateOf(false) }
    val resetContext = LocalContext.current
    val resetScope = rememberCoroutineScope()
    if (askReset) {
        AlertDialog(
            onDismissRequest = { askReset = false },
            title = { Text(stringResource(R.string.settings_reset_app_title)) },
            text = { DialogText { Text(stringResource(R.string.settings_reset_app_confirm)) } },
            confirmButton = {
                TextButton(onClick = {
                    askReset = false
                    resetScope.launch {
                        // Eigene Alarmtöne liegen in der Mediathek, nicht im App-Speicher: «Speicher leeren» liesse
                        // sie liegen (darum vorher gelöscht, abseits des Hauptthreads)
                        if (AlarmSoundStore.supportsCustomFile) {
                            withContext(Dispatchers.IO) { AlarmSoundStore.deleteAllOwn(resetContext) }
                        }
                        // Löscht alle Daten der App (Datenbank, Einstellungen, Dateien, geplante Arbeiten)
                        // wie «Speicher leeren» in den System-Einstellungen; das System beendet die App
                        // danach, beim nächsten Öffnen startet sie wie neu installiert.
                        resetContext.getSystemService(ActivityManager::class.java)?.clearApplicationUserData()
                    }
                }) {
                    Text(stringResource(R.string.settings_reset_app_action), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { askReset = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
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

    val scrollState = rememberScrollState()
    val reduceMotion = rememberReduceMotion()
    // Erneutes Tippen auf den Tab «Einstellungen»: an den Anfang (wie iOS)
    OnTabReselect(ScreenRoute.Settings) {
        if (reduceMotion) scrollState.scrollTo(0) else scrollState.animateScrollTo(0)
    }
    // Kopfzeile wie Markt und Portfolio: einzeilig, gleiche Farbe beim Scrollen
    Scaffold(
        topBar = { TopAppBar(title = { TabTitle(stringResource(R.string.settings_title), R.drawable.ic_settings) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                // Tablet/Querformat: Inhalt höchstens 640 dp breit, mittig
                .readableWidth()
                .padding(bottom = 16.dp)
        ) {
            SettingsSearchField(query = query, onQueryChange = { query = it })
            if (query.isNotBlank()) {
                SettingsSearchResults(items = settingsSearchItems(settings), query = query, onOpen = openSearchTarget)
            } else {
                ProvideSettingsHighlight(mainHighlight) {
                    // 1 Allgemein: Sprache, Währung, Merkliste, Portfolio, Alarme, Kurs-Aktualisierung
                    SettingsSection(stringResource(R.string.settings_group_general), first = true) {
                        SettingsAnchor("main.language") {
                            LanguageRow(selectedTag = languageTag, onClick = { onOpenPage(SettingsPage.LANGUAGE) })
                        }
                        SettingsNavRow(
                            title = stringResource(R.string.settings_row_currency),
                            icon = R.drawable.ic_settings_currency,
                            value = settings.portfolioCurrency,
                            onClick = { onOpenPage(SettingsPage.CURRENCY) }
                        )
                        SettingsNavRow(
                            title = stringResource(R.string.settings_row_watchlist),
                            // Gleiches Symbol wie der Tab «Merkliste»
                            icon = R.drawable.ic_tab_watchlist,
                            value = watchlistSummary(settings),
                            onClick = { onOpenPage(SettingsPage.WATCHLIST) }
                        )
                        // Alles zum Portfolio an einem Ort (Schalter des Tabs zuoberst auf der Seite); gleich nach
                        // «Merkliste» wie die Reihenfolge der Tabs
                        SettingsNavRow(
                            title = stringResource(R.string.portfolio_title),
                            // Gleiches Symbol wie der Tab «Portfolio»
                            icon = R.drawable.ic_portfolio,
                            value = portfolioSummary(settings),
                            onClick = { onOpenPage(SettingsPage.PORTFOLIO) }
                        )
                        SettingsNavRow(
                            title = stringResource(R.string.settings_row_alarms),
                            // Glocke mit Wellen wie bei den Alarmen der Paare (einfarbig in Textfarbe)
                            icon = R.drawable.ic_notifications_active_outline,
                            value = stringResource(alarmSignalLabel(settings.alarmSignal)),
                            onClick = { onOpenPage(SettingsPage.ALARMS) }
                        )
                        // Direkt nach «Alarme»: bestimmt, wie oft Alarme geprüft werden
                        SettingsNavRow(
                            title = stringResource(R.string.settings_row_updates),
                            icon = R.drawable.ic_settings_updates,
                            value = updatesSummary(settings),
                            onClick = { onOpenPage(SettingsPage.UPDATES) }
                        )
                    }

                    // 2 Darstellung: Hell/Dunkel, Akzentfarbe, Kursfarben, Widgets
                    SettingsSection(stringResource(R.string.settings_section_appearance)) {
                        SettingsNavRow(
                            title = stringResource(R.string.settings_theme_mode),
                            icon = R.drawable.ic_settings_display_mode,
                            value = displayModeSummary(settings),
                            onClick = { onOpenPage(SettingsPage.DISPLAY_MODE) }
                        )
                        val accentName = stringResource(settings.accentColor.labelRes)
                        SettingsNavRow(
                            title = stringResource(R.string.settings_accent),
                            icon = R.drawable.ic_settings_theme,
                            valueDescription = accentName,
                            valueContent = {
                                // Farbpunkt passend zum kleinen Wert unter dem Titel
                                Box(
                                    Modifier
                                        .padding(end = 4.dp)
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        // K3: Farbe der Knöpfe (hell/dunkel), nicht die Grundfarbe
                                        .background(accentButtonColor(settings.accentColor, LocalDarkTheme.current))
                                )
                                SettingsValueText(accentName)
                            },
                            onClick = { onOpenPage(SettingsPage.THEME) }
                        )
                        SettingsNavRow(
                            title = stringResource(R.string.settings_price_colors),
                            // Kerzen wie im Schalter Kerzen | Linie der Charts
                            icon = R.drawable.ic_chart_candles,
                            valueDescription = priceColorsSummary(settings),
                            valueContent = { PriceArrows(settings.priceColorScheme, settings.priceColorsInverted) },
                            onClick = { onOpenPage(SettingsPage.PRICE_COLORS) }
                        )
                        // Widgets direkt aus der App auf den Startbildschirm
                        SettingsNavRow(
                            title = stringResource(R.string.settings_widgets),
                            icon = R.drawable.ic_settings_widgets,
                            onClick = { onOpenPage(SettingsPage.WIDGETS) }
                        )
                    }

                    // 3 Mehr: Sicherung, «Erweitert» (Token & TradFi, Markt-Meldungen, Sprachausgabe, Coin-Logos,
                    // Entwickler), Über die App
                    SettingsSection(stringResource(R.string.settings_group_more)) {
                        SettingsNavRow(
                            title = stringResource(R.string.backup_title),
                            icon = R.drawable.ic_settings_backup,
                            modifier = Modifier.testTag(UiTestTags.SETTINGS_BACKUP),
                            onClick = { onOpenPage(SettingsPage.BACKUP) }
                        )
                        SettingsNavRow(
                            title = stringResource(R.string.settings_row_advanced),
                            icon = R.drawable.ic_settings_advanced,
                            onClick = { onOpenPage(SettingsPage.ADVANCED) }
                        )
                        SettingsNavRow(
                            title = stringResource(R.string.settings_row_about),
                            icon = R.drawable.ic_settings_about,
                            value = BuildConfig.VERSION_NAME,
                            onClick = { onOpenPage(SettingsPage.ABOUT) }
                        )
                    }

                    // 4 «App zurücksetzen» als eigene rote Karte ganz unten (alles löschen wie nach der
                    // Installation, mit Rückfrage)
                    AppCard(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), padding = CardPadding.None) {
                        SettingsAnchor("main.reset") {
                            SettingsDangerRow(
                                title = stringResource(R.string.settings_reset_app),
                                icon = R.drawable.ic_settings_reset,
                                onClick = { askReset = true }
                            )
                        }
                    }
                }
            }
        }
    }

}

/** Dauer der Hervorhebung einer Zeile der Hauptseite nach einem Suchtreffer (ms). */
private const val MAIN_HIGHLIGHT_MILLIS = 2_500L
