package com.cryptochecker.app.ui.features.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.settings.AccentColor
import com.cryptochecker.app.settings.AppSettings
import com.cryptochecker.app.settings.PriceColorChoice
import com.cryptochecker.app.settings.SettingsSearch
import com.cryptochecker.app.settings.SettingsSearchEntry
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.theme.AppColors
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.util.ChangeBasisText
import kotlinx.coroutines.delay

/*
 * Runde 31: Suche in den Einstellungen. Die Hauptseite zeigt oben ein Suchfeld; der Index
 * umfasst ihre Zeilen und die Punkte der Unterseiten (Titel, Hinweise als Synonyme). Ein Treffer
 * öffnet die Unterseite und hebt den Punkt kurz hervor (scrollt dorthin); Zeilen der Hauptseite
 * werden dort hervorgehoben. Abgleich in `SettingsSearch` (rein, getestet).
 */

/** Wohin ein Treffer führt; [anchor] ist der Punkt, der kurz hervorgehoben wird. */
sealed interface SettingsSearchTarget {
    val anchor: String?

    /** Zeile der Hauptseite (Sprache, Widgets, Links). */
    data class Main(override val anchor: String) : SettingsSearchTarget
    data class Page(val page: SettingsPage, override val anchor: String? = null) : SettingsSearchTarget
    data class MarketAlerts(override val anchor: String? = null) : SettingsSearchTarget
    data class Speech(override val anchor: String? = null) : SettingsSearchTarget
}

/** Punkt, der auf der gerade gezeigten Seite hervorgehoben werden soll (oder keiner). */
val LocalSettingsHighlight = staticCompositionLocalOf<String?> { null }

/** Gibt [anchor] an die Punkte der Seite [content] weiter. */
@Composable
fun ProvideSettingsHighlight(anchor: String?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSettingsHighlight provides anchor, content = content)
}

/** Wartezeit, bis die Seite eingeblendet ist (Überblendung der Navigation), ms. */
private const val HIGHLIGHT_DELAY_MILLIS = 250L
private const val HIGHLIGHT_IN_MILLIS = 200
private const val HIGHLIGHT_HOLD_MILLIS = 1_200L
private const val HIGHLIGHT_OUT_MILLIS = 700

/**
 * Ein Punkt einer Einstellungsseite, den die Suche ansteuern kann: Ist er das Ziel
 * ([LocalSettingsHighlight]), scrollt die Seite zu ihm und hinterlegt ihn kurz in der
 * Akzentfarbe. Ohne Bewegung (Animationen aus) erscheint die Hinterlegung ohne Überblendung.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SettingsAnchor(id: String, content: @Composable () -> Unit) {
    val active = LocalSettingsHighlight.current == id
    val requester = remember { BringIntoViewRequester() }
    val alpha = remember { Animatable(0f) }
    val reduceMotion = rememberReduceMotion()
    LaunchedEffect(active) {
        if (!active) {
            alpha.snapTo(0f)
            return@LaunchedEffect
        }
        delay(HIGHLIGHT_DELAY_MILLIS)
        requester.bringIntoView()
        if (reduceMotion) {
            alpha.snapTo(1f)
            delay(HIGHLIGHT_HOLD_MILLIS + HIGHLIGHT_OUT_MILLIS)
            alpha.snapTo(0f)
        } else {
            alpha.animateTo(1f, tween(HIGHLIGHT_IN_MILLIS))
            delay(HIGHLIGHT_HOLD_MILLIS)
            alpha.animateTo(0f, tween(HIGHLIGHT_OUT_MILLIS))
        }
    }
    val tint = AppColors.brand
    // Column, nicht Box: ein Anker kann mehrere Zeilen umfassen (z. B. «Alarm-Signal» mit dem
    // Weg in die Systemeinstellungen) — in einer Box lägen sie übereinander
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester)
            .clip(RoundedCornerShape(Spacing.sm))
            .background(tint.copy(alpha = HIGHLIGHT_ALPHA * alpha.value))
    ) {
        content()
    }
}

/** Stärke der Hinterlegung (Akzentfarbe), dezent auf hellen und dunklen Karten. */
private const val HIGHLIGHT_ALPHA = 0.16f

/** Ein Eintrag der Suche mit seinem Ziel. */
internal data class SettingsSearchItem(val entry: SettingsSearchEntry, val target: SettingsSearchTarget)

/**
 * Alle durchsuchbaren Punkte in der Sprache der App — Hauptseite und Unterseiten, in der
 * Reihenfolge der Seiten. Ids sind zugleich die Anker ([SettingsAnchor]).
 */
@Composable
internal fun settingsSearchItems(settings: AppSettings): List<SettingsSearchItem> {
    val items = ArrayList<SettingsSearchItem>()
    fun add(id: String, title: String, path: String, target: SettingsSearchTarget, vararg synonyms: String) {
        items += SettingsSearchItem(SettingsSearchEntry(id, title, path, synonyms.toList()), target)
    }

    // 1 Allgemein
    val general = stringResource(R.string.settings_group_general)
    add("main.language", stringResource(R.string.settings_section_language), general,
        SettingsSearchTarget.Page(SettingsPage.LANGUAGE), stringResource(R.string.settings_language_choose),
        stringResource(R.string.language_search))
    val currency = stringResource(R.string.settings_row_currency)
    add("page.currency", currency, general, SettingsSearchTarget.Page(SettingsPage.CURRENCY), stringResource(R.string.currency_search))
    add("currency.conversion", stringResource(R.string.settings_conversion_currency), currency,
        SettingsSearchTarget.Page(SettingsPage.CURRENCY, "currency.conversion"),
        stringResource(R.string.settings_conversion_currency_hint))
    val updates = stringResource(R.string.settings_row_updates)
    add("page.updates", updates, general, SettingsSearchTarget.Page(SettingsPage.UPDATES))
    add("updates.background", stringResource(R.string.settings_background_updates), updates,
        SettingsSearchTarget.Page(SettingsPage.UPDATES, "updates.background"),
        stringResource(R.string.settings_background_updates_hint))
    // Akkunutzung steht ganz unten auf der Seite: eigener Treffer, damit die Suche dorthin springt
    add("updates.battery", stringResource(R.string.settings_battery_restricted), updates,
        SettingsSearchTarget.Page(SettingsPage.UPDATES, "updates.battery"),
        stringResource(R.string.settings_battery_ok), stringResource(R.string.settings_battery_hint))
    add("updates.background_interval", stringResource(R.string.settings_background_interval), updates,
        SettingsSearchTarget.Page(SettingsPage.UPDATES, "updates.background_interval"))
    add("updates.live", stringResource(R.string.settings_live_service), updates,
        SettingsSearchTarget.Page(SettingsPage.UPDATES, "updates.live"),
        stringResource(R.string.settings_live_service_hint))
    add("updates.live_interval", stringResource(R.string.settings_live_interval), updates,
        SettingsSearchTarget.Page(SettingsPage.UPDATES, "updates.live_interval"),
        stringResource(R.string.settings_live_interval_hidden_hint))
    add("updates.live_websocket", stringResource(R.string.settings_live_websocket), updates,
        SettingsSearchTarget.Page(SettingsPage.UPDATES, "updates.live_websocket"),
        stringResource(R.string.settings_live_websocket_hint))
    add("updates.ongoing", stringResource(R.string.settings_ongoing_notifications), updates,
        SettingsSearchTarget.Page(SettingsPage.UPDATES, "updates.ongoing"),
        stringResource(R.string.settings_ongoing_notifications_hint))
    val watchlist = stringResource(R.string.settings_row_watchlist)
    add("page.watchlist", watchlist, general, SettingsSearchTarget.Page(SettingsPage.WATCHLIST))
    add("watchlist.names", stringResource(R.string.settings_watchlist_names), watchlist,
        SettingsSearchTarget.Page(SettingsPage.WATCHLIST, "watchlist.names"),
        stringResource(R.string.settings_watchlist_names_hint))
    add("watchlist.sparkline", stringResource(R.string.settings_watchlist_sparkline), watchlist,
        SettingsSearchTarget.Page(SettingsPage.WATCHLIST, "watchlist.sparkline"),
        stringResource(R.string.settings_watchlist_sparkline_hint))
    add("watchlist.converted", stringResource(R.string.settings_show_converted), watchlist,
        SettingsSearchTarget.Page(SettingsPage.WATCHLIST, "watchlist.converted"),
        stringResource(R.string.settings_show_converted_hint, settings.portfolioCurrency))
    add("watchlist.activity", stringResource(R.string.settings_watchlist_activity_card), watchlist,
        SettingsSearchTarget.Page(SettingsPage.WATCHLIST, "watchlist.activity"),
        stringResource(R.string.settings_watchlist_activity_card_hint), "⚡")

    // 2 Darstellung
    val appearance = stringResource(R.string.settings_section_appearance)
    val mode = stringResource(R.string.settings_theme_mode)
    add("page.display_mode", mode, appearance, SettingsSearchTarget.Page(SettingsPage.DISPLAY_MODE),
        stringResource(R.string.theme_system), stringResource(R.string.theme_light), stringResource(R.string.theme_dark))
    add("display.contrast", stringResource(R.string.settings_high_contrast), mode,
        SettingsSearchTarget.Page(SettingsPage.DISPLAY_MODE, "display.contrast"),
        stringResource(R.string.settings_high_contrast_hint))
    add("page.theme", stringResource(R.string.settings_accent), appearance, SettingsSearchTarget.Page(SettingsPage.THEME),
        *AccentColor.entries.map { stringResource(it.labelRes) }.toTypedArray())
    add("page.price_colors", stringResource(R.string.settings_price_colors), appearance,
        SettingsSearchTarget.Page(SettingsPage.PRICE_COLORS),
        stringResource(R.string.settings_price_colors_hint),
        *PriceColorChoice.entries.map { stringResource(priceColorChoiceLabel(it)) }.toTypedArray())
    add("page.change_basis", stringResource(R.string.settings_change_basis, "%"), appearance,
        SettingsSearchTarget.Page(SettingsPage.CHANGE_BASIS),
        stringResource(R.string.settings_change_basis_hint_1),
        stringResource(R.string.change_basis_rolling),
        stringResource(R.string.change_basis_since_last),
        stringResource(R.string.change_basis_device, "UTC"),
        // Alle Zonen in einem Text: «UTC+8» findet die Seite einmal, nicht 27 fast gleiche Treffer
        ChangeBasis.entries.filter { it.kind == ChangeBasis.Kind.UTC_DAY }
            .joinToString(" ") { ChangeBasisText.zoneChoice(it) })
    add("change.period", stringResource(R.string.settings_change_period), stringResource(R.string.settings_change_basis, "%"),
        SettingsSearchTarget.Page(SettingsPage.CHANGE_BASIS, "change.period"),
        stringResource(R.string.settings_change_period_hint))
    val logos = stringResource(R.string.settings_coin_logos)
    add("page.coin_logos", logos, appearance, SettingsSearchTarget.Page(SettingsPage.COIN_LOGOS),
        stringResource(R.string.settings_coin_logos_footer), "CoinGecko")
    add("logos.app", stringResource(R.string.settings_coin_logos_app), logos,
        SettingsSearchTarget.Page(SettingsPage.COIN_LOGOS, "logos.app"),
        stringResource(R.string.settings_coin_logos_app_hint))
    add("logos.portfolio", stringResource(R.string.settings_coin_logos_portfolio), logos,
        SettingsSearchTarget.Page(SettingsPage.COIN_LOGOS, "logos.portfolio"),
        stringResource(R.string.settings_coin_logos_portfolio_hint))
    add("logos.widgets", stringResource(R.string.settings_coin_logos_widgets), logos,
        SettingsSearchTarget.Page(SettingsPage.COIN_LOGOS, "logos.widgets"),
        stringResource(R.string.settings_coin_logos_widgets_hint))
    add("main.widgets", stringResource(R.string.settings_widgets), appearance, SettingsSearchTarget.Page(SettingsPage.WIDGETS))

    // 3 Alarme & Mitteilungen
    val alerts = stringResource(R.string.settings_group_alerts)
    val alarms = stringResource(R.string.settings_row_alarms)
    add("page.alarms", alarms, alerts, SettingsSearchTarget.Page(SettingsPage.ALARMS))
    add("alarms.price", stringResource(R.string.settings_price_notifications), alarms,
        SettingsSearchTarget.Page(SettingsPage.ALARMS, "alarms.price"),
        stringResource(R.string.settings_price_notifications_hint))
    add("alarms.change", stringResource(R.string.settings_notification_change), alarms,
        SettingsSearchTarget.Page(SettingsPage.ALARMS, "alarms.change"),
        stringResource(R.string.settings_notification_change_hint))
    add("alarms.cooldown", stringResource(R.string.settings_alarm_cooldown), alarms,
        SettingsSearchTarget.Page(SettingsPage.ALARMS, "alarms.cooldown"))
    add("alarms.sound", stringResource(R.string.settings_alarm_sound), alarms,
        SettingsSearchTarget.Page(SettingsPage.ALARMS, "alarms.sound"))
    add("alarms.signal", stringResource(R.string.settings_alarm_signal), alarms,
        SettingsSearchTarget.Page(SettingsPage.ALARMS, "alarms.signal"),
        stringResource(R.string.settings_alarm_signal_system_hint))
    add("alarms.quiet", stringResource(R.string.settings_quiet_hours), alarms,
        SettingsSearchTarget.Page(SettingsPage.ALARMS, "alarms.quiet"),
        stringResource(R.string.settings_quiet_hours_from), stringResource(R.string.settings_quiet_hours_to))
    add("alarms.badge", stringResource(R.string.settings_app_badge), alarms,
        SettingsSearchTarget.Page(SettingsPage.ALARMS, "alarms.badge"),
        stringResource(R.string.settings_app_badge_hint))
    add("alarms.test", stringResource(R.string.alarm_test), alarms,
        SettingsSearchTarget.Page(SettingsPage.ALARMS, "alarms.test"),
        stringResource(R.string.alarm_test_hint))
    val market = stringResource(R.string.settings_market_alerts)
    add("page.market_alerts", market, alerts, SettingsSearchTarget.MarketAlerts())
    add("market.zone", stringResource(R.string.settings_zone_alerts), market,
        SettingsSearchTarget.MarketAlerts("market.zone"), stringResource(R.string.settings_zone_alerts_hint))
    add("market.fng_below", stringResource(R.string.settings_fng_below), market,
        SettingsSearchTarget.MarketAlerts("market.fng_below"))
    add("market.fng_above", stringResource(R.string.settings_fng_above), market,
        SettingsSearchTarget.MarketAlerts("market.fng_above"))
    add("market.gas_eth", stringResource(R.string.settings_gas_eth_below), market,
        SettingsSearchTarget.MarketAlerts("market.gas_eth"), stringResource(R.string.settings_gas_alert_hint))
    add("market.gas_btc", stringResource(R.string.settings_gas_btc_below), market,
        SettingsSearchTarget.MarketAlerts("market.gas_btc"), stringResource(R.string.settings_gas_alert_hint))
    add("market.activity", stringResource(R.string.settings_activity_alerts), market,
        SettingsSearchTarget.MarketAlerts("market.activity"), stringResource(R.string.settings_activity_alerts_hint))
    add("market.sensitivity", stringResource(R.string.settings_activity_sensitivity), market,
        SettingsSearchTarget.MarketAlerts("market.sensitivity"),
        stringResource(R.string.settings_activity_sensitivity_hint))
    add("market.macro", stringResource(R.string.settings_macro_notifications), market,
        SettingsSearchTarget.MarketAlerts("market.macro"), stringResource(R.string.settings_macro_notifications_hint))
    val speech = stringResource(R.string.settings_tts)
    add("page.speech", speech, alerts, SettingsSearchTarget.Speech("speech.enabled"),
        stringResource(R.string.settings_tts_hint_silent))
    add("speech.alarms_only", stringResource(R.string.settings_tts_alarms_only), speech,
        SettingsSearchTarget.Speech("speech.alarms_only"), stringResource(R.string.settings_tts_alarms_only_hint))
    add("speech.test", stringResource(R.string.settings_tts_test), speech, SettingsSearchTarget.Speech("speech.test"))

    // 4 Portfolio
    val portfolio = stringResource(R.string.portfolio_title)
    add("page.portfolio", portfolio, portfolio, SettingsSearchTarget.Page(SettingsPage.PORTFOLIO))
    add("portfolio.tab", stringResource(R.string.settings_portfolio_tab), portfolio,
        SettingsSearchTarget.Page(SettingsPage.PORTFOLIO, "portfolio.tab"), stringResource(R.string.portfolio_setting_hint))
    add("portfolio.lock", stringResource(R.string.settings_portfolio_lock), portfolio,
        SettingsSearchTarget.Page(SettingsPage.PORTFOLIO, "portfolio.lock"),
        stringResource(R.string.settings_portfolio_lock_hint))
    add("portfolio.hide", stringResource(R.string.portfolio_hide_amounts), portfolio,
        SettingsSearchTarget.Page(SettingsPage.PORTFOLIO, "portfolio.hide"),
        stringResource(R.string.portfolio_hide_amounts_hint))
    add("portfolio.system_backup", stringResource(R.string.settings_portfolio_system_backup), portfolio,
        SettingsSearchTarget.Page(SettingsPage.PORTFOLIO, "portfolio.system_backup"),
        stringResource(R.string.settings_portfolio_system_backup_hint))

    // 5 Daten
    val dataGroup = stringResource(R.string.settings_group_data_only)
    val backup = stringResource(R.string.backup_title)
    add("page.backup", backup, dataGroup, SettingsSearchTarget.Page(SettingsPage.BACKUP),
        stringResource(R.string.backup_hint))
    add("backup.export", stringResource(R.string.backup_export), backup,
        SettingsSearchTarget.Page(SettingsPage.BACKUP, "backup.actions"))
    add("backup.import", stringResource(R.string.backup_import), backup,
        SettingsSearchTarget.Page(SettingsPage.BACKUP, "backup.actions"))
    add("main.reset", stringResource(R.string.settings_reset_app), dataGroup, SettingsSearchTarget.Main("main.reset"),
        stringResource(R.string.settings_reset_app_confirm))
    // Laufzeit-Futures und TradFi liegen auf der Seite «Merkliste»
    val futures = stringResource(R.string.settings_row_watchlist)
    add("futures.rolling", stringResource(R.string.settings_rolling_futures), futures,
        SettingsSearchTarget.Page(SettingsPage.WATCHLIST, "futures.rolling"),
        stringResource(R.string.settings_rolling_futures_hint))
    add("futures.tradfi", stringResource(R.string.settings_tradfi_futures), futures,
        SettingsSearchTarget.Page(SettingsPage.WATCHLIST, "futures.tradfi"),
        stringResource(R.string.settings_tradfi_futures_hint), "TradFi",
        stringResource(R.string.settings_row_dated_futures))

    // 6 Über
    val about = stringResource(R.string.settings_section_about)
    add("page.about", stringResource(R.string.settings_row_about), about, SettingsSearchTarget.Page(SettingsPage.ABOUT))
    add("main.privacy", stringResource(R.string.about_privacy_policy), about, SettingsSearchTarget.Main("main.privacy"))
    add("main.exchange", stringResource(R.string.about_request_exchange), about, SettingsSearchTarget.Main("main.exchange"))
    add("main.source", stringResource(R.string.about_source_code), about, SettingsSearchTarget.Main("main.source"))
    add("main.licenses", stringResource(R.string.about_licenses), about, SettingsSearchTarget.Page(SettingsPage.LICENSES))
    // Entwickler nur, wenn freigeschaltet (wie die Zeile der Hauptseite)
    if (settings.developerUnlocked) {
        val developer = stringResource(R.string.settings_section_developer)
        add("page.developer", developer, about, SettingsSearchTarget.Page(SettingsPage.DEVELOPER))
        add("developer.http_log", stringResource(R.string.settings_http_log), developer,
            SettingsSearchTarget.Page(SettingsPage.DEVELOPER, "developer.http_log"),
            stringResource(R.string.settings_http_log_hint))
    }
    return items
}

/**
 * Suchfeld oben auf der Hauptseite; darunter (bei Eingabe) [SettingsSearchResults] statt der Gruppen.
 */
@Composable
internal fun SettingsSearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.settings_search_hint)) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.action_clear))
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = MaterialTheme.shapes.large,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
    )
}

/** Treffer als Zeilen: Titel, darunter grau die Seite bzw. Gruppe; leer ein Hinweis. */
@Composable
internal fun SettingsSearchResults(
    items: List<SettingsSearchItem>,
    query: String,
    onOpen: (SettingsSearchTarget) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val index = remember(items, locale) { SettingsSearch.index(items.map { it.entry }, locale) }
    val byId = remember(items) { items.associateBy { it.entry.id } }
    val hits = remember(index, query) { SettingsSearch.search(index, query) }
    if (hits.isEmpty()) {
        Text(
            text = stringResource(R.string.watchlist_search_empty, query.trim()),
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.neutral,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.xl)
                .semantics { liveRegion = LiveRegionMode.Polite }
        )
        return
    }
    Column {
        hits.forEach { entry ->
            val target = byId[entry.id]?.target ?: return@forEach
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(role = androidx.compose.ui.semantics.Role.Button) { onOpen(target) }
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(entry.title, style = MaterialTheme.typography.bodyLarge)
                    if (entry.path.isNotEmpty() && entry.path != entry.title) {
                        Text(
                            entry.path,
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.neutral,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Icon(
                    painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = AppColors.neutral,
                    modifier = Modifier.padding(start = Spacing.xs)
                )
            }
        }
    }
}
