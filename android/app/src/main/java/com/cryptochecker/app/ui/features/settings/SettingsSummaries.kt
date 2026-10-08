@file:OptIn(ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.settings

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cryptochecker.app.R
import com.cryptochecker.app.settings.AppSettings
import com.cryptochecker.app.settings.PriceColorChoice
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

/** Name einer Wahl auf der Seite «Kursfarben». */
@androidx.annotation.StringRes
internal fun priceColorChoiceLabel(choice: PriceColorChoice): Int = when (choice) {
    PriceColorChoice.GREEN_UP -> R.string.price_colors_green_up
    PriceColorChoice.RED_UP -> R.string.price_colors_red_up
    PriceColorChoice.BLUE_UP -> R.string.price_colors_blue_up
    PriceColorChoice.ORANGE_UP -> R.string.price_colors_orange_up
}
