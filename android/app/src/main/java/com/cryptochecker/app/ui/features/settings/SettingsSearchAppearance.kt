// Suche in den Einstellungen: Einträge des Bereichs Darstellung (Teil von SettingsSearchCatalog).
package com.cryptochecker.app.ui.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.cryptochecker.app.BuildConfig
import com.cryptochecker.app.domain.settings.AccentColor
import com.cryptochecker.app.domain.settings.AppSettings
import com.cryptochecker.app.domain.settings.PriceColorScheme
import com.cryptochecker.app.domain.settings.SettingsSearchEntry
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.settings.labelRes
import com.cryptochecker.app.util.ChangeBasisText
import com.cryptochecker.resources.R

@Composable
internal fun SearchItemsBuilder.appearanceSection(watchlist: String): SearchItemsBuilder {
    // 2 Darstellung
    val mode = stringResource(R.string.settings_theme_mode)
    addPage(
        SettingsPage.DISPLAY_MODE,
        mode,
        stringResource(R.string.theme_system),
        stringResource(R.string.theme_light),
        stringResource(R.string.theme_dark)
    )
    add(
        "display.scheduled",
        stringResource(R.string.theme_scheduled),
        mode,
        SettingsSearchTarget.Page(SettingsPage.DISPLAY_MODE, "display.scheduled"),
        stringResource(R.string.theme_dark_from),
        stringResource(R.string.theme_light_from)
    )
    add(
        "display.contrast",
        stringResource(R.string.settings_high_contrast),
        mode,
        SettingsSearchTarget.Page(SettingsPage.DISPLAY_MODE, "display.contrast"),
        stringResource(R.string.settings_high_contrast_hint)
    )
    addPage(
        SettingsPage.THEME,
        stringResource(R.string.settings_accent),
        *AccentColor.entries.map { stringResource(it.labelRes) }.toTypedArray()
    )
    addPage(
        SettingsPage.PRICE_COLORS,
        stringResource(R.string.settings_price_colors),
        stringResource(R.string.settings_price_colors_hint),
        *PriceColorScheme.entries.map { stringResource(priceColorSchemeLabel(it)) }.toTypedArray()
    )
    add(
        "price_colors.swap",
        stringResource(R.string.price_colors_swap),
        stringResource(R.string.settings_price_colors),
        SettingsSearchTarget.Page(SettingsPage.PRICE_COLORS, "price_colors.swap"),
        stringResource(R.string.price_colors_red_up_hint)
    )
    // %-Änderung unter «Merkliste», Coin-Logos und Widgets unter «Darstellung»
    val changeBasis = stringResource(R.string.settings_change_basis, "%")
    add(
        "page.change_basis", changeBasis, watchlist, SettingsSearchTarget.Page(SettingsPage.CHANGE_BASIS),
        stringResource(R.string.settings_change_basis_hint_1),
        stringResource(R.string.change_basis_rolling),
        stringResource(R.string.change_basis_since_last),
        stringResource(R.string.change_basis_device, "UTC"),
        // Alle Zonen in einem Text: «UTC+8» findet die Seite einmal, nicht 27 fast gleiche Treffer
        ChangeBasis.entries.filter { it.kind == ChangeBasis.Kind.UTC_DAY }
            .joinToString(" ") { ChangeBasisText.zoneChoice(it) }
    )
    val logos = stringResource(R.string.settings_coin_logos)
    addPage(
        SettingsPage.COIN_LOGOS,
        logos,
        stringResource(R.string.settings_coin_logos_footer),
        "CoinGecko"
    )
    add(
        "logos.app",
        stringResource(R.string.settings_coin_logos_app),
        logos,
        SettingsSearchTarget.Page(SettingsPage.COIN_LOGOS, "logos.app"),
        stringResource(R.string.settings_coin_logos_app_hint)
    )
    add(
        "logos.portfolio",
        stringResource(R.string.settings_coin_logos_portfolio),
        logos,
        SettingsSearchTarget.Page(SettingsPage.COIN_LOGOS, "logos.portfolio"),
        stringResource(R.string.settings_coin_logos_portfolio_hint)
    )
    add(
        "logos.widgets",
        stringResource(R.string.settings_coin_logos_widgets),
        logos,
        SettingsSearchTarget.Page(SettingsPage.COIN_LOGOS, "logos.widgets"),
        stringResource(R.string.settings_coin_logos_widgets_hint)
    )
    addPage(SettingsPage.WIDGETS, stringResource(R.string.settings_widgets))
    add(
        "widgets.watch",
        stringResource(R.string.settings_watch),
        stringResource(R.string.settings_widgets),
        SettingsSearchTarget.Page(SettingsPage.WIDGETS, "widgets.watch"),
        stringResource(R.string.settings_watch_mode_own),
        "Wear OS"
    )
    add(
        "widgets.watch_prices",
        stringResource(R.string.settings_watch_prices),
        stringResource(R.string.settings_widgets),
        SettingsSearchTarget.Page(SettingsPage.WIDGETS, "widgets.watch_prices"),
        stringResource(R.string.settings_watch_prices_phone),
        stringResource(R.string.settings_watch_prices_self)
    )
    return this
}
