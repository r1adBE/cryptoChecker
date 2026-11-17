// Suche in den Einstellungen: Einträge der Bereiche Portfolio und Mehr (Teil von SettingsSearchCatalog).
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
internal fun SearchItemsBuilder.portfolioSection(portfolio: String): SearchItemsBuilder {
    // 4 Portfolio (unter «Allgemein»; die Seite selbst steht oben im Index)
    add(
        "portfolio.tab",
        stringResource(R.string.settings_portfolio_tab),
        portfolio,
        SettingsSearchTarget.Page(SettingsPage.PORTFOLIO, "portfolio.tab"),
        stringResource(R.string.portfolio_setting_hint)
    )
    add(
        "portfolio.lock",
        stringResource(R.string.settings_portfolio_lock),
        portfolio,
        SettingsSearchTarget.Page(SettingsPage.PORTFOLIO, "portfolio.lock"),
        stringResource(R.string.settings_portfolio_lock_hint)
    )
    add(
        "portfolio.hide",
        stringResource(R.string.portfolio_hide_amounts),
        portfolio,
        SettingsSearchTarget.Page(SettingsPage.PORTFOLIO, "portfolio.hide"),
        stringResource(R.string.portfolio_hide_amounts_hint)
    )
    add(
        "portfolio.watch",
        stringResource(R.string.settings_watch_portfolio),
        portfolio,
        SettingsSearchTarget.Page(SettingsPage.PORTFOLIO, "portfolio.watch"),
        stringResource(R.string.settings_watch_portfolio_hint),
        "Wear OS"
    )
    add(
        "portfolio.cost_method",
        stringResource(R.string.settings_cost_method),
        portfolio,
        SettingsSearchTarget.Page(SettingsPage.PORTFOLIO, "portfolio.cost_method"),
        stringResource(R.string.settings_cost_method_fifo),
        stringResource(R.string.settings_cost_method_average)
    )
    add(
        "portfolio.tax_template",
        stringResource(R.string.tax_template_title),
        portfolio,
        SettingsSearchTarget.Page(SettingsPage.PORTFOLIO, "portfolio.tax_template"),
        stringResource(R.string.tax_template_hint)
    )
    add(
        "portfolio.system_backup",
        stringResource(R.string.settings_portfolio_system_backup),
        portfolio,
        SettingsSearchTarget.Page(SettingsPage.PORTFOLIO, "portfolio.system_backup"),
        stringResource(R.string.settings_portfolio_system_backup_hint)
    )
    add(
        "portfolio.address_server",
        stringResource(R.string.settings_address_server_title),
        portfolio,
        SettingsSearchTarget.Page(SettingsPage.PORTFOLIO, "portfolio.address_server"),
        stringResource(R.string.settings_address_server_hint)
    )
    return this
}

/** 5 Sicherung, Zurücksetzen, Token & TradFi, 6 Über die App (Diagnose und Entwickler nur freigeschaltet). */
@Composable
internal fun SearchItemsBuilder.moreSection(settings: AppSettings, watchlist: String): SearchItemsBuilder {
    // 5 Sicherung (unter «Mehr»)
    val backup = stringResource(R.string.backup_title)
    addPage(
        SettingsPage.BACKUP,
        backup,
        stringResource(R.string.backup_hint)
    )
    add(
        "backup.export",
        stringResource(R.string.backup_export),
        backup,
        SettingsSearchTarget.Page(SettingsPage.BACKUP, "backup.actions")
    )
    add(
        "backup.import",
        stringResource(R.string.backup_import),
        backup,
        SettingsSearchTarget.Page(SettingsPage.BACKUP, "backup.actions")
    )
    add(
        "backup.auto",
        stringResource(R.string.auto_backup_title),
        backup,
        SettingsSearchTarget.Page(SettingsPage.BACKUP, "backup.auto"),
        stringResource(R.string.auto_backup_now)
    )
    // «App zurücksetzen»: eigene rote Karte ganz unten auf der Hauptseite
    add(
        "main.reset",
        stringResource(R.string.settings_reset_app),
        "",
        SettingsSearchTarget.Main("main.reset"),
        stringResource(R.string.settings_reset_app_confirm)
    )
    // «Token & TradFi» mit Alpha und QTLY: eigene Seite, Zeile auf «Merkliste»; weitere Namen als Suchwörter
    val futures = stringResource(R.string.settings_asset_scope_title)
    add("page.exchanges", futures, watchlist, SettingsSearchTarget.Page(SettingsPage.EXCHANGES))
    add(
        "futures.scope", stringResource(R.string.settings_asset_scope_title), futures,
        SettingsSearchTarget.Page(SettingsPage.EXCHANGES, "futures.scope"),
        stringResource(R.string.settings_asset_scope_tokens), stringResource(R.string.settings_asset_scope_tokens_hint),
        stringResource(R.string.settings_asset_scope_tradfi), stringResource(R.string.settings_asset_scope_tradfi_hint),
        stringResource(R.string.settings_asset_scope_both), "TradFi", "Token",
        stringResource(R.string.settings_tradfi_futures), stringResource(R.string.settings_tradfi_futures_hint)
    )
    add(
        "futures.alpha",
        stringResource(R.string.settings_asset_scope_alpha),
        futures,
        SettingsSearchTarget.Page(SettingsPage.EXCHANGES, "futures.alpha"),
        stringResource(R.string.settings_asset_scope_alpha_hint),
        stringResource(R.string.group_alpha),
        "Alpha",
        "α"
    )
    add(
        "futures.rolling",
        stringResource(R.string.settings_rolling_futures_qtly),
        futures,
        SettingsSearchTarget.Page(SettingsPage.EXCHANGES, "futures.rolling"),
        stringResource(R.string.settings_rolling_futures_hint),
        "QTLY",
        stringResource(R.string.settings_rolling_futures),
        stringResource(R.string.settings_row_dated_futures)
    )

    // 6 Über die App (unter «Mehr»): Links und Lizenzen auf der Seite selbst
    val about = stringResource(R.string.settings_row_about)
    addPage(SettingsPage.ABOUT, about)
    add(
        "about.privacy",
        stringResource(R.string.about_privacy_policy),
        about,
        SettingsSearchTarget.Page(SettingsPage.ABOUT, "about.privacy")
    )
    add(
        "about.terms",
        stringResource(R.string.about_terms),
        about,
        SettingsSearchTarget.Page(SettingsPage.ABOUT, "about.terms")
    )
    add(
        "about.exchange",
        stringResource(R.string.about_request_exchange),
        about,
        SettingsSearchTarget.Page(SettingsPage.ABOUT, "about.exchange")
    )
    add(
        "about.source",
        stringResource(R.string.about_source_code),
        about,
        SettingsSearchTarget.Page(SettingsPage.ABOUT, "about.source")
    )
    add("about.licenses", stringResource(R.string.about_licenses), about, SettingsSearchTarget.Page(SettingsPage.LICENSES))
    // Diagnose nur, wenn freigeschaltet (wie auf «Über die App»), in jedem Build
    if (settings.developerUnlocked) {
        add(
            "about.diagnostics",
            stringResource(R.string.diagnostics_share),
            about,
            SettingsSearchTarget.Page(SettingsPage.ABOUT, "about.diagnostics"),
            stringResource(R.string.diagnostics_share_hint)
        )
    }
    // Entwickler nur, wenn freigeschaltet und im Debug-Build (wie die Zeile auf «Über die App»)
    if (settings.developerUnlocked && BuildConfig.DEBUG) {
        val developer = stringResource(R.string.settings_section_developer)
        add("page.developer", developer, about, SettingsSearchTarget.Page(SettingsPage.DEVELOPER))
        add(
            "developer.http_log",
            stringResource(R.string.settings_http_log),
            developer,
            SettingsSearchTarget.Page(SettingsPage.DEVELOPER, "developer.http_log"),
            stringResource(R.string.settings_http_log_hint)
        )
    }
    return this
}
