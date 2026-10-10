package com.cryptochecker.app.ui.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.settings.PriceColorScheme
import com.cryptochecker.app.ui.components.SwitchRow
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.util.ChangeBasisText
import com.cryptochecker.app.util.LocaleNumbers

// ── Darstellung ──────────────────────────────────────────────────────────────

/** Modus: wie das System, Hell oder Dunkel (App und Widgets); dazu hoher Kontrast. */
@Composable
internal fun DisplayModePage(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsSubPage(title = stringResource(R.string.settings_theme_mode), onBack = onBack) {
        GroupCard {
            Column(Modifier.selectableGroup()) {
                listOf(null, false, true).forEach { dark ->
                    RadioRow(
                        selected = settings.darkMode == dark,
                        onClick = { viewModel.setDarkMode(dark) },
                        title = stringResource(themeModeLabel(dark))
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        GroupCard {
            // Hoher Kontrast: kräftigere Kursfarben, dunklere Nebentexte (auch per System)
            SettingsAnchor("display.contrast") {
                SwitchRow(
                    title = stringResource(R.string.settings_high_contrast),
                    subtitle = stringResource(R.string.settings_high_contrast_hint),
                    checked = settings.highContrast,
                    onCheckedChange = viewModel::setHighContrast
                )
            }
        }
    }
}

/** Theme: Akzentfarbe der App, der Widgets und des App-Symbols. */
@Composable
internal fun ThemePage(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsSubPage(title = stringResource(R.string.settings_accent), onBack = onBack) {
        GroupCard {
            AccentColorList(selected = settings.accentColor, onSelected = viewModel::setAccentColor)
        }
        Text(
            text = stringResource(R.string.settings_accent_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = Spacing.md)
        )
    }
}

/**
 * Kursfarben: drei Stile als Karten — «Frisch» (Standard, Grün/Rot), «Traditionell»
 * (Olivgrün/Himbeer) und «Farbsehschwäche» (Blau/Orange); darunter «Farben tauschen»
 * (Ostasien: Rot = steigend) und hoher Kontrast. Gespeichert als Schema + «getauscht».
 */
@Composable
internal fun PriceColorsPage(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsSubPage(title = stringResource(R.string.settings_price_colors), onBack = onBack) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PriceColorScheme.entries.forEach { scheme ->
                PriceStyleCard(
                    scheme = scheme,
                    selected = scheme == settings.priceColorScheme,
                    inverted = settings.priceColorsInverted,
                    onClick = { if (scheme != settings.priceColorScheme) viewModel.setPriceColorScheme(scheme) }
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        GroupCard {
            SettingsAnchor("price_colors.swap") {
                SwitchRow(
                    title = stringResource(R.string.price_colors_swap),
                    subtitle = stringResource(R.string.price_colors_red_up_hint),
                    checked = settings.priceColorsInverted,
                    onCheckedChange = viewModel::setPriceColorsInverted
                )
            }
            RowDivider()
            SwitchRow(
                title = stringResource(R.string.settings_high_contrast),
                subtitle = stringResource(R.string.settings_high_contrast_hint),
                checked = settings.highContrast,
                onCheckedChange = viewModel::setHighContrast
            )
        }
        Text(
            text = stringResource(R.string.settings_price_colors_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = Spacing.md)
        )
    }
}

/**
 * «Basis der %-Änderung» wie Binance «Change(%) & Chart Timezone»: oben der nummerierte
 * Hinweis, darunter «Letzte 24 Std.» (Standard), «Seit letzter Aktualisierung», die Zone des Geräts («UTC+2, 00:00 (Zeitzone
 * des Geräts)», folgt der Sommerzeit) und feste Zonen UTC+14 … UTC−12 — für Pille, Puls,
 * Aktionsblatt und Widgets; Alarme rechnen unabhängig davon.
 */
@Composable
internal fun ChangeBasisPage(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Zone des Geräts einmal beim Öffnen (Sommerzeit wechselt nicht, während die Seite offen ist)
    val now = remember { System.currentTimeMillis() }
    SettingsSubPage(title = stringResource(R.string.settings_change_basis, "%"), onBack = onBack) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = Spacing.md)) {
            NumberedHint(1, stringResource(R.string.settings_change_basis_hint_1))
            NumberedHint(2, stringResource(R.string.settings_change_basis_hint_2, stringResource(R.string.change_basis_rolling)))
            NumberedHint(3, stringResource(R.string.settings_change_basis_hint_since_last, stringResource(R.string.change_basis_since_last)))
            NumberedHint(4, stringResource(R.string.settings_change_basis_hint_3))
        }
        GroupCard {
            SettingsAnchor("change.period") {
                SwitchRow(
                    title = stringResource(R.string.settings_change_period),
                    subtitle = stringResource(R.string.settings_change_period_hint),
                    checked = settings.showChangePeriod,
                    onCheckedChange = viewModel::setShowChangePeriod
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        GroupCard {
            Column(Modifier.selectableGroup()) {
                ChangeBasis.entries.forEach { basis ->
                    RadioRow(
                        selected = settings.changeBasis == basis,
                        onClick = { viewModel.setChangeBasis(basis) },
                        title = ChangeBasisText.choiceLabel(context, basis, now)
                    )
                }
            }
        }
    }
}

/**
 * «Coin-Logos»: echte Logos (CoinGecko, alle auf einmal geladen und auf dem Gerät gespeichert)
 * statt Initialen — getrennt für App, Portfolio und Widgets; ab Werk nur im Portfolio an.
 */
@Composable
internal fun CoinLogosPage(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsSubPage(title = stringResource(R.string.settings_coin_logos), onBack = onBack) {
        GroupCard {
            SettingsAnchor("logos.app") {
                SwitchRow(
                    title = stringResource(R.string.settings_coin_logos_app),
                    subtitle = stringResource(R.string.settings_coin_logos_app_hint),
                    checked = settings.coinLogos,
                    onCheckedChange = viewModel::setCoinLogos
                )
            }
            RowDivider()
            SettingsAnchor("logos.portfolio") {
                SwitchRow(
                    title = stringResource(R.string.settings_coin_logos_portfolio),
                    subtitle = stringResource(R.string.settings_coin_logos_portfolio_hint),
                    checked = settings.portfolioCoinLogos,
                    onCheckedChange = viewModel::setPortfolioCoinLogos
                )
            }
            RowDivider()
            SettingsAnchor("logos.widgets") {
                SwitchRow(
                    title = stringResource(R.string.settings_coin_logos_widgets),
                    subtitle = stringResource(R.string.settings_coin_logos_widgets_hint),
                    checked = settings.widgetCoinLogos,
                    onCheckedChange = viewModel::setWidgetCoinLogos
                )
            }
        }
        Text(
            text = stringResource(R.string.settings_coin_logos_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = Spacing.md)
        )
    }
}

/** Ein Punkt eines nummerierten Hinweises, Folgezeilen eingerückt («1. …»). */
@Composable
private fun NumberedHint(number: Int, text: String) {
    Row(modifier = Modifier.padding(bottom = Spacing.xs)) {
        Text(
            text = LocaleNumbers.integer(number) + ".",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(20.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}
