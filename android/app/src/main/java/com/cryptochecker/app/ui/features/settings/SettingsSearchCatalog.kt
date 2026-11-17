// Index der Suche in den Einstellungen (Oberfläche in `SettingsSearchUi`): alle durchsuchbaren Punkte und das Ziel
// je Treffer — wie `SettingsSearchCatalog.swift`. Eigene Datei, weil der Index den Grossteil der Suche ausmacht.
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

/** Ein Eintrag der Suche mit seinem Ziel. */
internal data class SettingsSearchItem(val entry: SettingsSearchEntry, val target: SettingsSearchTarget)

/**
 * Alle durchsuchbaren Punkte in der Sprache der App — Hauptseite und Unterseiten, in der
 * Reihenfolge der Seiten. Ids sind zugleich die Anker ([SettingsAnchor]).
 */
@Composable
internal fun settingsSearchItems(settings: AppSettings): List<SettingsSearchItem> {
    // Hauptseite Allgemein · Darstellung · Mehr. Der Pfad einer Seite kommt aus ihrem Ort
    // (SettingsPage.home), damit er zur Anordnung passt
    val index = SearchItemsBuilder(
        general = stringResource(R.string.settings_group_general),
        appearance = stringResource(R.string.settings_section_appearance),
        more = stringResource(R.string.settings_group_more),
    )
    // Seite «Portfolio» (Zeile unter «Allgemein», einziger Ort für alles zum Portfolio): früh im Index, damit sie
    // bei «Portfolio» vor «Portfolio-Sperre» und «Portfolio in Systemsicherung» steht (gleich viele Punkte →
    // frühere Stelle zuerst)
    val portfolio = stringResource(R.string.portfolio_title)
    val watchlist = stringResource(R.string.settings_row_watchlist)
    return index
        .generalSection(settings, portfolio, watchlist)
        .appearanceSection(watchlist)
        .alarmSection()
        .portfolioSection(portfolio)
        .moreSection(settings, watchlist)
        .items
}

/** Sammelt die Treffer in Index-Reihenfolge; [homePath] = Gruppe der Hauptseite, unter der eine Seite steht. */
internal class SearchItemsBuilder(private val general: String, private val appearance: String, private val more: String) {
    val items = ArrayList<SettingsSearchItem>()

    fun add(id: String, title: String, path: String, target: SettingsSearchTarget, vararg synonyms: String) {
        items += SettingsSearchItem(SettingsSearchEntry(id, title, path, synonyms.toList()), target)
    }

    fun homePath(page: SettingsPage): String = when (page.home) {
        SettingsHome.GENERAL -> general
        SettingsHome.APPEARANCE -> appearance
        SettingsHome.MORE -> more
        null -> ""
    }

    fun addPage(page: SettingsPage, title: String, vararg synonyms: String) =
        add("page." + page.name.lowercase(), title, homePath(page), SettingsSearchTarget.Page(page), *synonyms)
}

/** 1 Allgemein: Portfolio-Seite, Sprache, Währung, Aktualisierung, Akku & Aktivität, Merkliste. */
@Composable
private fun SearchItemsBuilder.generalSection(settings: AppSettings, portfolio: String, watchlist: String): SearchItemsBuilder {
    addPage(
        SettingsPage.PORTFOLIO,
        portfolio,
        stringResource(R.string.settings_portfolio_tab),
        stringResource(R.string.portfolio_setting_hint)
    )
    add(
        "main.language",
        stringResource(R.string.settings_section_language),
        homePath(SettingsPage.LANGUAGE),
        SettingsSearchTarget.Page(SettingsPage.LANGUAGE),
        stringResource(R.string.settings_language_choose),
        stringResource(R.string.language_search)
    )
    val currency = stringResource(R.string.settings_row_currency)
    addPage(SettingsPage.CURRENCY, currency, stringResource(R.string.currency_search))
    add(
        "currency.conversion",
        stringResource(R.string.settings_conversion_currency),
        currency,
        SettingsSearchTarget.Page(SettingsPage.CURRENCY, "currency.conversion"),
        stringResource(R.string.settings_conversion_currency_hint)
    )
    val updates = stringResource(R.string.settings_row_updates)
    addPage(SettingsPage.UPDATES, updates)
    val updatesPath = updates
    // «Wie aktuell?»: Suchwörter auch «Im Hintergrund aktualisieren» und «Live-Kurse»
    add(
        "updates.mode",
        stringResource(R.string.update_mode_title),
        updatesPath,
        SettingsSearchTarget.Page(SettingsPage.UPDATES, "updates.mode"),
        stringResource(R.string.update_mode_sparsam),
        stringResource(R.string.update_mode_live),
        stringResource(R.string.settings_background_updates),
        stringResource(R.string.settings_live_websocket)
    )
    // Akkunutzung steht weit unten auf der Seite (vor «Letzte Prüfungen»): eigener Treffer, damit die Suche dorthin springt
    add(
        "updates.battery",
        stringResource(R.string.settings_battery_restricted),
        updatesPath,
        SettingsSearchTarget.Page(SettingsPage.UPDATES, "updates.battery"),
        stringResource(R.string.settings_battery_ok),
        stringResource(R.string.settings_battery_hint)
    )
    add(
        "updates.background_interval",
        stringResource(R.string.settings_background_interval),
        updatesPath,
        SettingsSearchTarget.Page(SettingsPage.UPDATES, "updates.background_interval")
    )
    // «Pünktlich prüfen» (exakter Wecker, nur Android): eigener Treffer
    add(
        "updates.exact",
        stringResource(R.string.settings_exact_checks),
        updatesPath,
        SettingsSearchTarget.Page(SettingsPage.UPDATES, "updates.exact"),
        exactMinutes(settings.backgroundIntervalMinutes).let { pluralStringResource(R.plurals.settings_exact_checks_hint, it, it) },
        stringResource(R.string.settings_exact_checks_grant)
    )
    add(
        "updates.ongoing",
        stringResource(R.string.settings_ongoing_notifications),
        updatesPath,
        SettingsSearchTarget.Page(SettingsPage.UPDATES, "updates.ongoing"),
        stringResource(R.string.settings_ongoing_notifications_hint)
    )
    add(
        "updates.watch",
        stringResource(R.string.settings_watch_notifications),
        updatesPath,
        SettingsSearchTarget.Page(SettingsPage.UPDATES, "updates.watch"),
        stringResource(R.string.settings_watch_notifications_hint)
    )
    // Preis-Overlay (nur Android)
    add(
        "updates.overlay",
        stringResource(R.string.settings_price_overlay),
        updatesPath,
        SettingsSearchTarget.Page(SettingsPage.UPDATES, "updates.overlay"),
        stringResource(R.string.settings_price_overlay_grant)
    )
    // «Letzte Prüfungen»: Karte ganz unten auf der Seite, eigener Treffer
    add(
        "updates.check_runs",
        stringResource(R.string.settings_check_runs_title),
        updatesPath,
        SettingsSearchTarget.Page(SettingsPage.UPDATES, "updates.check_runs"),
        stringResource(R.string.settings_check_runs_honest_android)
    )
    // Alarm-Protokoll: Knopf in der Karte «Letzte Prüfungen» (ganz unten auf der Seite) — dorthin springen
    add(
        "updates.alarm_log",
        stringResource(R.string.alarm_log_title),
        updatesPath,
        SettingsSearchTarget.Page(SettingsPage.UPDATES, "updates.check_runs"),
        stringResource(R.string.alarm_log_open),
        stringResource(R.string.alarm_log_intro),
        stringResource(R.string.settings_check_runs_title)
    )
    // «Akku & Aktivität»: eigene Zähler von Handy und Uhr, Weg in die Akku-Einstellungen
    val usage = stringResource(R.string.settings_row_usage)
    addPage(SettingsPage.USAGE, usage, stringResource(R.string.battery_open_settings))
    add(
        "usage.watch",
        stringResource(R.string.usage_watch),
        usage,
        SettingsSearchTarget.Page(SettingsPage.USAGE, "usage.watch"),
        stringResource(R.string.usage_watch_none)
    )
    addPage(SettingsPage.WATCHLIST, watchlist)
    add(
        "watchlist.names",
        stringResource(R.string.settings_watchlist_names),
        watchlist,
        SettingsSearchTarget.Page(SettingsPage.WATCHLIST, "watchlist.names"),
        stringResource(R.string.settings_watchlist_names_hint)
    )
    add(
        "watchlist.sparkline",
        stringResource(R.string.settings_watchlist_sparkline),
        watchlist,
        SettingsSearchTarget.Page(SettingsPage.WATCHLIST, "watchlist.sparkline"),
        stringResource(R.string.settings_watchlist_sparkline_hint)
    )
    add(
        "watchlist.converted",
        stringResource(R.string.settings_show_converted),
        watchlist,
        SettingsSearchTarget.Page(SettingsPage.WATCHLIST, "watchlist.converted"),
        stringResource(R.string.settings_show_converted_hint, settings.portfolioCurrency)
    )
    add(
        "watchlist.activity",
        stringResource(R.string.settings_watchlist_activity_card),
        watchlist,
        SettingsSearchTarget.Page(SettingsPage.WATCHLIST, "watchlist.activity"),
        stringResource(R.string.settings_watchlist_activity_card_hint),
        "⚡"
    )
    return this
}
