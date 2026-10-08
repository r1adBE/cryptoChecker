@file:OptIn(ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.BuildConfig
import com.cryptochecker.app.R
import com.cryptochecker.app.settings.AccentColor
import com.cryptochecker.app.settings.AppLanguages
import com.cryptochecker.app.settings.AppSettings
import com.cryptochecker.app.settings.PriceColorChoice
import com.cryptochecker.app.settings.SettingsSummary
import com.cryptochecker.app.data.portfolio.FxRateSource
import com.cryptochecker.app.domain.alarm.AlarmSignal
import com.cryptochecker.app.domain.alarm.ThresholdParser
import com.cryptochecker.app.ui.components.AddWidgetsSheet
import com.cryptochecker.app.ui.components.ComboBox
import com.cryptochecker.app.ui.components.readableWidth
import com.cryptochecker.app.ui.features.about.EXCHANGE_REQUEST_URL
import com.cryptochecker.app.ui.features.about.LicensesSheet
import com.cryptochecker.app.ui.features.about.PRIVACY_POLICY_URL
import com.cryptochecker.app.ui.features.about.SOURCE_CODE_URL
import com.cryptochecker.app.ui.theme.AppColors
import com.cryptochecker.app.ui.theme.LocalDarkTheme
import com.cryptochecker.app.ui.theme.LocalHighContrast
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.util.ChangeBasisText
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.rememberSaveable
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

/**
 * Kleine graue Gruppenüberschrift (für den Screenreader eine Überschrift); ab der
 * zweiten Gruppe mit einer dünnen Trennlinie darüber.
 */
@Composable
internal fun SettingsSectionHeader(title: String, first: Boolean = false) {
    if (!first) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 8.dp)
        )
    }
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
            .semantics { heading() }
    )
}

/** «Ein» / «Aus» als Kurzwert. */
@Composable
internal fun onOff(on: Boolean): String =
    stringResource(if (on) R.string.settings_summary_on else R.string.option_off)

/** Kurzwert «Aktualisierung»: «Live · 15 s», «Alle 15 min» oder «Aus». */
@Composable
private fun updatesSummary(settings: AppSettings): String =
    when (SettingsSummary.updates(settings.liveService, settings.backgroundUpdates)) {
        SettingsSummary.Updates.LIVE ->
            stringResource(R.string.settings_updates_live, liveIntervalLabel(settings.liveIntervalSeconds))
        SettingsSummary.Updates.BACKGROUND ->
            stringResource(
                R.string.settings_updates_every,
                stringResource(R.string.settings_minutes, settings.backgroundIntervalMinutes)
            )
        SettingsSummary.Updates.OFF -> stringResource(R.string.option_off)
    }

/** Live-Intervall kurz: «15 s», «5 min» statt «300 Sekunden». */
@Composable
internal fun liveIntervalLabel(seconds: Int): String =
    if (seconds >= 60 && seconds % 60 == 0) stringResource(R.string.settings_minutes, seconds / 60)
    else stringResource(R.string.settings_seconds, seconds)

/** Kurzwert «Merkliste»: «Mini-Chart · ≈ CHF» oder «Nur Kurs». */
@Composable
private fun watchlistSummary(settings: AppSettings): String {
    val parts = SettingsSummary.watchlist(settings.watchlistSparkline, settings.showConverted)
    if (parts.isEmpty()) return stringResource(R.string.settings_watchlist_value_price_only)
    val sparkline = stringResource(R.string.settings_watchlist_value_sparkline)
    return parts.joinToString(" · ") {
        when (it) {
            SettingsSummary.WatchlistPart.SPARKLINE -> sparkline
            SettingsSummary.WatchlistPart.CONVERTED -> "≈ " + settings.portfolioCurrency
        }
    }
}

/** Kurzwert «Modus»: «System», «Hell» oder «Dunkel», bei hohem Kontrast mit Zusatz. */
@Composable
private fun displayModeSummary(settings: AppSettings): String {
    val mode = stringResource(themeModeLabel(settings.darkMode))
    return if (settings.highContrast) mode + " · " + stringResource(R.string.settings_high_contrast) else mode
}

@androidx.annotation.StringRes
internal fun themeModeLabel(dark: Boolean?): Int = when (dark) {
    null -> R.string.theme_system
    false -> R.string.theme_light
    true -> R.string.theme_dark
}

/** Kurzwert «Portfolio»: «Aus», «Ein» oder «Ein, mit Sperre». */
@Composable
private fun portfolioSummary(settings: AppSettings): String =
    when (SettingsSummary.portfolio(settings.portfolioEnabled, settings.appLock)) {
        SettingsSummary.Portfolio.OFF -> stringResource(R.string.option_off)
        SettingsSummary.Portfolio.ON -> stringResource(R.string.settings_summary_on)
        SettingsSummary.Portfolio.LOCKED -> stringResource(R.string.settings_portfolio_value_locked)
    }

/** Name einer Wahl auf der Seite «Kursfarben». */
@androidx.annotation.StringRes
internal fun priceColorChoiceLabel(choice: PriceColorChoice): Int = when (choice) {
    PriceColorChoice.GREEN_UP -> R.string.price_colors_green_up
    PriceColorChoice.RED_UP -> R.string.price_colors_red_up
    PriceColorChoice.BLUE_UP -> R.string.price_colors_blue_up
    PriceColorChoice.ORANGE_UP -> R.string.price_colors_orange_up
}

/**
 * «▲▼» in den Farben einer Wahl — Pfeile bleiben richtungsgebunden (▲ = steigend),
 * nur die Farben wechseln. Für den Screenreader nur dekorativ; den Namen trägt die Zeile.
 */
@Composable
internal fun PriceArrows(choice: PriceColorChoice, modifier: Modifier = Modifier) {
    val dark = LocalDarkTheme.current
    val highContrast = LocalHighContrast.current
    Row(modifier = modifier.clearAndSetSemantics { }) {
        Text(
            "▲",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = Color(choice.scheme.up(dark, highContrast, choice.inverted))
        )
        Text(
            "▼",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = Color(choice.scheme.down(dark, highContrast, choice.inverted))
        )
    }
}

/** Grauer Wert rechts in einer Zeile, einzeilig mit «…». */
@Composable
internal fun SettingsValueText(value: String) {
    Text(
        value,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
    )
}

/**
 * Zeile der Hauptseite: Titel links, aktueller Wert grau rechts (gekürzt), Pfeil.
 * Mindestens 56 dp hoch, über die ganze Breite tippbar. Für den Screenreader eine
 * Schaltfläche «Titel, Wert» (z. B. «Theme, Orange, Schaltfläche»).
 *
 * [valueContent] ersetzt den Text-Wert (Farbpunkt, farbige Pfeile); dann beschreibt
 * [valueDescription] den Wert.
 */
@Composable
internal fun SettingsNavRow(
    title: String,
    value: String? = null,
    valueDescription: String? = value,
    valueContent: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = listOfNotNull(title, valueDescription?.takeIf { it.isNotEmpty() })
                    .joinToString(", ")
            }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(end = 16.dp)
        )
        // Wert höchstens gut halb so breit wie die Zeile, Rest mit «…»
        if (valueContent != null || !value.isNullOrEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.widthIn(max = 200.dp)
            ) {
                if (valueContent != null) valueContent() else SettingsValueText(value.orEmpty())
            }
        }
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

/** Karte innerhalb einer Gruppe — gleiche Fläche wie `SectionCard`, ohne eigene Überschrift. */
@Composable
internal fun GroupCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), content = content)
    }
}

@Composable
internal fun Hint(text: String, top: androidx.compose.ui.unit.Dp = 0.dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = top, bottom = Spacing.sm)
    )
}

/** Minuten seit Mitternacht im kurzen Zeitformat des Geräts (12/24 h wie eingestellt). */
internal fun formatMinuteOfDay(context: android.content.Context, minute: Int): String {
    val calendar = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, minute / 60)
        set(java.util.Calendar.MINUTE, minute % 60)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    return android.text.format.DateFormat.getTimeFormat(context).format(calendar.time)
}

/** «Von»/«Bis» der Nachtruhe; ein Tipp öffnet die Zeitauswahl des Systems. */
@Composable
internal fun QuietTimeRow(label: String, minute: Int, onPicked: (Int) -> Unit) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                android.app.TimePickerDialog(
                    context,
                    { _, hour, min -> onPicked(hour * 60 + min) },
                    minute / 60,
                    minute % 60,
                    android.text.format.DateFormat.is24HourFormat(context)
                ).show()
            }
            .padding(vertical = 12.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            formatMinuteOfDay(context, minute),
            style = MaterialTheme.typography.bodyLarge.tabularNumbers(),
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
internal fun RowDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}


/** Punkt als Status: «in Ordnung» bzw. Bernstein für «Achtung». */
@Composable
internal fun StatusDot(ok: Boolean) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(if (ok) AppColors.positive else AppColors.warning)
    )
}

/** Zeigt die gewählte Sprache; ein Tipp öffnet die Auswahl mit Suchfeld. */
@Composable
private fun LanguageRow(selectedTag: String, onSelected: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val systemLabel = stringResource(R.string.settings_language_system)
    val current = AppLanguages.ALL.firstOrNull { it.tag == selectedTag }?.nativeName ?: systemLabel

    // Erste Zeile von «Allgemein»: nennt «Sprache» selbst, die Wahl grau rechts
    SettingsNavRow(
        title = stringResource(R.string.settings_section_language),
        value = current,
        onClick = { open = true }
    )

    if (open) {
        var query by remember { mutableStateOf("") }
        val options = listOf("" to systemLabel) + AppLanguages.ALL.map { it.tag to it.nativeName }
        val q = query.trim()
        val visible = if (q.isEmpty()) options else options.filter { (tag, name) ->
            name.contains(q, ignoreCase = true) || tag.startsWith(q, ignoreCase = true)
        }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.settings_language_choose)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.language_search)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )
                    LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                        items(visible, key = { it.first }) { (tag, name) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable {
                                        open = false
                                        if (tag != selectedTag) onSelected(tag)
                                    }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = tag == selectedTag,
                                    onClick = {
                                        open = false
                                        if (tag != selectedTag) onSelected(tag)
                                    }
                                )
                                Text(name, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

private fun parsePercent(text: String): Double? =
    ThresholdParser.latinDigits(text).trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.0..100.0 }

private fun formatPercent(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

/**
 * Umrechnungswährung als Aufklappliste (mit Suche). Gilt für die umgerechneten
 * Kurse der Merkliste, Alarme in eigener Währung und das Portfolio.
 */
@Composable
internal fun ConversionCurrencyRow(selected: String, onSelected: (String) -> Unit) {
    // Eine früher gesetzte, nicht mehr gelistete Währung trotzdem anzeigen
    val codes = remember(selected) {
        if (selected in FxRateSource.CURRENCIES) FxRateSource.CURRENCIES else FxRateSource.CURRENCIES + selected
    }
    ComboBox(
        selectedIndex = codes.indexOf(selected),
        itemList = codes,
        onValueChange = { index -> codes.getOrNull(index)?.let { if (it != selected) onSelected(it) } },
        label = stringResource(R.string.settings_conversion_currency),
        searchable = true,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = Spacing.xs)
    )
}

/** Farbkreise zur Auswahl der Akzentfarbe; die gewählte hat einen Ring. */
@Composable
internal fun AccentColorRow(selected: AccentColor, onSelected: (AccentColor) -> Unit) {
    Column(modifier = Modifier.padding(vertical = Spacing.sm)) {
        Text(stringResource(R.string.settings_accent), style = MaterialTheme.typography.bodyMedium)
        // Fünf Farben: gleich breite Spalten über die ganze Zeile, damit sie auch auf
        // schmalen Geräten (360 dp) und bei grosser Schrift nebeneinander passen;
        // lange Namen («Marrs Green») brechen in die zweite Zeile um.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            AccentColor.entries.forEach { accent ->
                val isSelected = accent == selected
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelected(accent) }
                        .padding(4.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(44.dp)
                            .border(
                                width = if (isSelected) 3.dp else 0.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                shape = CircleShape
                            )
                            .padding(Spacing.xs)
                            .clip(CircleShape)
                            .background(Color(accent.seed))
                    ) {}
                    Text(
                        text = stringResource(accent.labelRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * Melde-Schwelle als Segmente: 0 / 3 / 5 / 7 % und als letztes ein eigener
 * Wert, der sich frei eingeben lässt (z. B. 2,5).
 */
@Composable
internal fun PercentChoiceRow(label: String, selected: Double, onSelected: (Double) -> Unit) {
    val isCustom = selected !in PERCENT_CHOICES
    var editing by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            val count = PERCENT_CHOICES.size + 1
            PERCENT_CHOICES.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelected(option) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = count),
                    icon = {}
                ) {
                    Text(formatPercent(option) + "%", maxLines = 1)
                }
            }
            // Letztes Segment: eigener Wert
            SegmentedButton(
                selected = isCustom,
                onClick = { editing = true },
                shape = SegmentedButtonDefaults.itemShape(index = count - 1, count = count),
                icon = {}
            ) {
                Text(
                    if (isCustom) formatPercent(selected) + "%" else stringResource(R.string.settings_custom_value),
                    maxLines = 1
                )
            }
        }
    }

    if (editing) {
        var text by remember { mutableStateOf(if (isCustom) formatPercent(selected) else "") }
        val parsed = parsePercent(text)
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(label) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { input -> text = input.filter { it.isDigit() || it == ',' || it == '.' }.take(6) },
                    singleLine = true,
                    suffix = { Text("%") },
                    isError = text.isNotEmpty() && parsed == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = parsed != null,
                    onClick = {
                        parsed?.let(onSelected)
                        editing = false
                    }
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

private val PERCENT_CHOICES = listOf(0.0, 3.0, 5.0, 7.0)

/** Auswahl aus wenigen Werten als Segmentleiste. */
@Composable
internal fun ChoiceRow(
    label: String,
    options: List<Int>,
    selected: Int,
    optionLabel: @Composable (Int) -> String,
    onSelected: (Int) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelected(option) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    icon = {}
                ) {
                    Text(optionLabel(option), maxLines = 1)
                }
            }
        }
    }
}

/** Anzeigename eines «Alarm-Signals». */
@androidx.annotation.StringRes
internal fun alarmSignalLabel(signal: AlarmSignal): Int = when (signal) {
    AlarmSignal.SYSTEM -> R.string.alarm_signal_system
    AlarmSignal.SOUND_VIBRATE -> R.string.alarm_signal_sound_vibrate
    AlarmSignal.SOUND -> R.string.alarm_signal_sound
    AlarmSignal.VIBRATE -> R.string.alarm_signal_vibrate
    AlarmSignal.SILENT -> R.string.alarm_signal_silent
}

/**
 * «Alarm-Signal» mit Kurzwert («Nur Vibration»); ein Tipp öffnet die Auswahl.
 * Darunter der Weg in die Systemeinstellungen des Kanals, der gerade gilt —
 * bei «System» stellt man Ton und Vibration genau dort ein.
 */
@Composable
internal fun AlarmSignalRow(
    selected: AlarmSignal,
    onSelected: (AlarmSignal) -> Unit,
    channelId: () -> String,
) {
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf(false) }
    SubPageRow(
        title = stringResource(R.string.settings_alarm_signal),
        subtitle = if (selected == AlarmSignal.SYSTEM) stringResource(R.string.settings_alarm_signal_system_hint) else null,
        value = stringResource(alarmSignalLabel(selected)),
        onClick = { open = true }
    )
    TextButton(
        onClick = {
            val intent = android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, channelId())
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        },
        modifier = Modifier.padding(bottom = 4.dp)
    ) {
        Text(stringResource(R.string.settings_alarm_signal_open_system))
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.settings_alarm_signal)) },
            text = {
                Column {
                    AlarmSignal.entries.forEach { signal ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = signal == selected,
                                    role = androidx.compose.ui.semantics.Role.RadioButton
                                ) {
                                    open = false
                                    onSelected(signal)
                                }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(selected = signal == selected, onClick = null)
                            Text(
                                stringResource(alarmSignalLabel(signal)),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

/**
 * Alarmton (#202): Systemton aus der Android-Auswahl, eigene Audiodatei
 * (ab Android 10) oder zurück zum Standardton.
 */
@Composable
internal fun AlarmSoundRow(
    soundName: String?,
    isCustom: Boolean,
    currentUri: String?,
    onPicked: (android.net.Uri?) -> Unit,
    onFile: (android.net.Uri) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val pickRingtone = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri: android.net.Uri? = androidx.core.content.IntentCompat.getParcelableExtra(
            result.data ?: return@rememberLauncherForActivityResult,
            android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
            android.net.Uri::class.java
        )
        // «Standard» in der Auswahl = Standard-Mitteilungston
        val isDefault = uri == null || android.media.RingtoneManager.isDefault(uri)
        onPicked(if (isDefault) null else uri)
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onFile)
    }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { menu = true }
                .padding(vertical = Spacing.lg)
        ) {
            Icon(
                painterResource(R.drawable.ic_music_note),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(stringResource(R.string.settings_alarm_sound), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (isCustom) soundName ?: stringResource(R.string.settings_alarm_sound_custom)
                    else stringResource(R.string.settings_alarm_sound_default),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings_alarm_sound_system)) },
                onClick = {
                    menu = false
                    val intent = android.content.Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                        putExtra(
                            android.media.RingtoneManager.EXTRA_RINGTONE_TYPE,
                            android.media.RingtoneManager.TYPE_NOTIFICATION or android.media.RingtoneManager.TYPE_ALARM
                        )
                        putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        putExtra(
                            android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                            currentUri?.let { android.net.Uri.parse(it) }
                                ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                        )
                    }
                    runCatching { pickRingtone.launch(intent) }
                }
            )
            if (com.cryptochecker.app.notification.AlarmSoundStore.supportsCustomFile) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.settings_alarm_sound_file)) },
                    onClick = {
                        menu = false
                        runCatching { pickFile.launch(arrayOf("audio/*")) }
                    }
                )
            }
            if (isCustom) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.settings_alarm_sound_reset)) },
                    onClick = { menu = false; onPicked(null) }
                )
            }
        }
    }
}
