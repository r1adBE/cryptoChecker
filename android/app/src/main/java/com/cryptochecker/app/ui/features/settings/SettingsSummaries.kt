@file:OptIn(ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.settings

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cryptochecker.app.R
import com.cryptochecker.app.settings.AppSettings
import com.cryptochecker.app.settings.PriceColorScheme
import com.cryptochecker.app.settings.SettingsSummary

/** «Ein» / «Aus» als Kurzwert. */
@Composable
internal fun onOff(on: Boolean): String =
    stringResource(if (on) R.string.settings_summary_on else R.string.option_off)

/** Kurzwert «Aktualisierung»: «Live · 15 s», «Alle 15 min» oder «Aus». */
@Composable
internal fun updatesSummary(settings: AppSettings): String =
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
internal fun watchlistSummary(settings: AppSettings): String {
    val parts = SettingsSummary.watchlist(settings.watchlistSparkline, settings.showConverted, settings.watchlistNames)
    if (parts.isEmpty()) return stringResource(R.string.settings_watchlist_value_price_only)
    val sparkline = stringResource(R.string.settings_watchlist_value_sparkline)
    val names = stringResource(R.string.settings_watchlist_value_names)
    return parts.joinToString(" · ") {
        when (it) {
            SettingsSummary.WatchlistPart.NAMES -> names
            SettingsSummary.WatchlistPart.SPARKLINE -> sparkline
            SettingsSummary.WatchlistPart.CONVERTED -> "≈ " + settings.portfolioCurrency
        }
    }
}

/** Kurzwert «Coin-Logos»: «App · Portfolio · Widgets», einzelne davon oder «Aus». */
@Composable
internal fun coinLogosSummary(settings: AppSettings): String {
    val parts = buildList {
        if (settings.coinLogos) add(stringResource(R.string.settings_coin_logos_value_app))
        if (settings.portfolioCoinLogos) add(stringResource(R.string.portfolio_title))
        if (settings.widgetCoinLogos) add(stringResource(R.string.settings_widgets))
    }
    return if (parts.isEmpty()) stringResource(R.string.option_off) else parts.joinToString(" · ")
}

/** Kurzwert «Modus»: «System», «Hell» oder «Dunkel», bei hohem Kontrast mit Zusatz. */
@Composable
internal fun displayModeSummary(settings: AppSettings): String {
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
internal fun portfolioSummary(settings: AppSettings): String =
    when (SettingsSummary.portfolio(settings.portfolioEnabled, settings.appLock)) {
        SettingsSummary.Portfolio.OFF -> stringResource(R.string.option_off)
        SettingsSummary.Portfolio.ON -> stringResource(R.string.settings_summary_on)
        SettingsSummary.Portfolio.LOCKED -> stringResource(R.string.settings_portfolio_value_locked)
    }

/** Name eines Stils auf der Seite «Kursfarben» — wie iOS `PriceColorScheme.labelKey`. */
@androidx.annotation.StringRes
internal fun priceColorSchemeLabel(scheme: PriceColorScheme): Int = when (scheme) {
    PriceColorScheme.GREEN_RED -> R.string.price_style_fresh
    PriceColorScheme.TRADITIONAL -> R.string.price_style_traditional
    PriceColorScheme.BLUE_ORANGE -> R.string.price_style_color_vision
}

/** Wert der Zeile «Kursfarben»: Stil, getauscht mit «· Farben tauschen» (Screenreader). */
@Composable
internal fun priceColorsSummary(settings: AppSettings): String {
    val name = stringResource(priceColorSchemeLabel(settings.priceColorScheme))
    return if (settings.priceColorsInverted) name + " · " + stringResource(R.string.price_colors_swap) else name
}
