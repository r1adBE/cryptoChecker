// Suche in den Einstellungen: Einträge des Bereichs Alarme (Teil von SettingsSearchCatalog).
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
internal fun SearchItemsBuilder.alarmSection(): SearchItemsBuilder {
    // 3 Alarme (unter «Allgemein»); Markt-Meldungen und Sprachausgabe ebenfalls unter «Alarme»
    val alarms = stringResource(R.string.settings_row_alarms)
    addPage(SettingsPage.ALARMS, alarms)
    // «Damit Alarme ankommen»: Zeile oben auf der Seite, öffnet die Übersicht (Erlaubnis, Kanal, Akku, letzte Prüfung)
    add(
        "alarms.delivery", stringResource(R.string.delivery_title), alarms,
        SettingsSearchTarget.Page(SettingsPage.ALARMS, "alarms.delivery"),
        stringResource(R.string.delivery_notifications), stringResource(R.string.delivery_channel),
        stringResource(R.string.delivery_battery), stringResource(R.string.delivery_dnd),
        stringResource(R.string.delivery_last_check)
    )
    add(
        "alarms.price",
        stringResource(R.string.settings_price_notifications),
        alarms,
        SettingsSearchTarget.Page(SettingsPage.ALARMS, "alarms.price"),
        stringResource(R.string.settings_price_notifications_hint)
    )
    add(
        "alarms.change",
        stringResource(R.string.settings_notification_change),
        alarms,
        SettingsSearchTarget.Page(SettingsPage.ALARMS, "alarms.change"),
        stringResource(R.string.settings_notification_change_hint)
    )
    add(
        "alarms.cooldown",
        stringResource(R.string.settings_alarm_cooldown),
        alarms,
        SettingsSearchTarget.Page(SettingsPage.ALARMS, "alarms.cooldown")
    )
    add(
        "alarms.sound",
        stringResource(R.string.settings_alarm_sound),
        alarms,
        SettingsSearchTarget.Page(SettingsPage.ALARMS, "alarms.sound")
    )
    add(
        "alarms.signal",
        stringResource(R.string.settings_alarm_signal),
        alarms,
        SettingsSearchTarget.Page(SettingsPage.ALARMS, "alarms.signal"),
        stringResource(R.string.settings_alarm_signal_system_hint)
    )
    add(
        "alarms.quiet",
        stringResource(R.string.settings_quiet_hours),
        alarms,
        SettingsSearchTarget.Page(SettingsPage.ALARMS, "alarms.quiet"),
        stringResource(R.string.settings_quiet_hours_from),
        stringResource(R.string.settings_quiet_hours_to)
    )
    add(
        "alarms.badge",
        stringResource(R.string.settings_app_badge),
        alarms,
        SettingsSearchTarget.Page(SettingsPage.ALARMS, "alarms.badge"),
        stringResource(R.string.settings_app_badge_hint)
    )
    add(
        "alarms.depeg",
        stringResource(R.string.settings_depeg_alarm),
        alarms,
        SettingsSearchTarget.Page(SettingsPage.ALARMS, "alarms.depeg"),
        stringResource(R.string.settings_depeg_alarm_hint),
        "USDT",
        "USDC",
        "Depeg"
    )
    add(
        "alarms.test",
        stringResource(R.string.alarm_test),
        alarms,
        SettingsSearchTarget.Page(SettingsPage.ALARMS, "alarms.test"),
        stringResource(R.string.alarm_test_hint)
    )
    marketAlertItems(alarms)
    val speech = stringResource(R.string.settings_tts)
    add(
        "page.speech",
        speech,
        alarms,
        SettingsSearchTarget.Speech("speech.enabled"),
        stringResource(R.string.settings_tts_hint_silent)
    )
    add(
        "speech.alarms_only",
        stringResource(R.string.settings_tts_alarms_only),
        speech,
        SettingsSearchTarget.Speech("speech.alarms_only"),
        stringResource(R.string.settings_tts_alarms_only_hint)
    )
    add("speech.test", stringResource(R.string.settings_tts_test), speech, SettingsSearchTarget.Speech("speech.test"))
    return this
}

/** Markt-Meldungen unter «Alarme» ([alarmSection]): Seite und ihre Einträge, in dieser Reihenfolge. */
@Composable
private fun SearchItemsBuilder.marketAlertItems(alarms: String) {
    val market = stringResource(R.string.settings_market_alerts)
    add("page.market_alerts", market, alarms, SettingsSearchTarget.MarketAlerts())
    add(
        "market.fng_below",
        stringResource(R.string.settings_fng_below),
        market,
        SettingsSearchTarget.MarketAlerts("market.fng_below")
    )
    add(
        "market.fng_above",
        stringResource(R.string.settings_fng_above),
        market,
        SettingsSearchTarget.MarketAlerts("market.fng_above")
    )
    add(
        "market.gas_eth",
        stringResource(R.string.settings_gas_eth_below),
        market,
        SettingsSearchTarget.MarketAlerts("market.gas_eth"),
        stringResource(R.string.settings_gas_alert_hint)
    )
    add(
        "market.gas_btc",
        stringResource(R.string.settings_gas_btc_below),
        market,
        SettingsSearchTarget.MarketAlerts("market.gas_btc"),
        stringResource(R.string.settings_gas_alert_hint)
    )
    add(
        "market.indicators",
        stringResource(R.string.neutral_btc_indicators_title),
        market,
        SettingsSearchTarget.MarketAlerts("market.indicators"),
        stringResource(R.string.settings_indicator_alerts_hint)
    )
    add(
        "market.activity",
        stringResource(R.string.settings_activity_alerts),
        market,
        SettingsSearchTarget.MarketAlerts("market.activity"),
        stringResource(R.string.settings_activity_alerts_hint)
    )
    add(
        "market.sensitivity",
        stringResource(R.string.settings_activity_sensitivity),
        market,
        SettingsSearchTarget.MarketAlerts("market.sensitivity"),
        stringResource(R.string.settings_activity_sensitivity_hint)
    )
    add(
        "market.macro",
        stringResource(R.string.settings_macro_notifications),
        market,
        SettingsSearchTarget.MarketAlerts("market.macro"),
        stringResource(R.string.settings_macro_notifications_hint)
    )
}
