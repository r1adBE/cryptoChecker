package com.cryptochecker.app.ui.features.settings

import androidx.compose.foundation.layout.Column
import com.cryptochecker.app.ui.components.CardPadding
import com.cryptochecker.app.ui.components.AppCard
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.BuildConfig
import com.cryptochecker.app.R

// ── Erweitert ────────────────────────────────────────────────────────────────

/**
 * Die Hauptseite zeigt nur die häufigen Einstellungen; alles für
 * Fortgeschrittene steht hier, in Karten ohne Überschrift — Märkte (Token & TradFi), Meldungen
 * (Markt-Meldungen, Sprachausgabe), Anzeige (Coin-Logos) und, falls freigeschaltet, «Entwickler».
 * Kurs-Aktualisierung und Widgets stehen auf der Hauptseite, ebenso Portfolio
 * (nur dort, unter «Allgemein»). Zurück führt von jeder Unterseite wieder hierher. Wie iOS (`AdvancedSettingsPage`).
 */
@Composable
internal fun AdvancedPage(
    onBack: () -> Unit,
    onOpenPage: (SettingsPage) -> Unit,
    onOpenMarketAlerts: () -> Unit,
    onOpenSpeech: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsSubPage(title = stringResource(R.string.settings_row_advanced), onBack = onBack) {
        NavCard(first = true) {
            SettingsAnchor("advanced.markets") {
                SettingsNavRow(
                    title = stringResource(R.string.settings_asset_scope_title),
                    icon = R.drawable.ic_settings_exchange,
                    value = stringResource(settings.assetScope.titleRes),
                    onClick = { onOpenPage(SettingsPage.MARKETS) }
                )
            }
        }
        NavCard {
            SettingsAnchor("advanced.market_alerts") {
                SettingsNavRow(
                    title = stringResource(R.string.settings_market_alerts),
                    // Blitz wie «Ungewöhnliche Aktivität»
                    icon = R.drawable.ic_bolt,
                    value = marketAlertsSummary(settings),
                    onClick = onOpenMarketAlerts
                )
            }
            SettingsAnchor("advanced.speech") {
                SettingsNavRow(
                    title = stringResource(R.string.settings_tts),
                    icon = R.drawable.ic_settings_speech,
                    value = speechSummary(settings),
                    onClick = onOpenSpeech
                )
            }
        }
        NavCard {
            // Basis der %-Änderung steht seit Review D13 auf «Merkliste» ([WatchlistPage])
            SettingsAnchor("advanced.coin_logos") {
                SettingsNavRow(
                    title = stringResource(R.string.settings_coin_logos),
                    icon = R.drawable.ic_settings_coin_logos,
                    value = coinLogosSummary(settings),
                    onClick = { onOpenPage(SettingsPage.COIN_LOGOS) }
                )
            }
        }
        // «Entwickler» erst nach 7 Tipps auf die Version (Seite «Über die App»); nur im Debug-Build,
        // der Release enthält keinen HTTP-Mitschnitt
        if (settings.developerUnlocked && BuildConfig.DEBUG) {
            NavCard {
                SettingsNavRow(
                    title = stringResource(R.string.settings_section_developer),
                    icon = R.drawable.ic_settings_developer,
                    value = onOff(settings.showHttpLog),
                    onClick = { onOpenPage(SettingsPage.DEVELOPER) }
                )
            }
        }
    }
}

/**
 * Karte mit Navigationszeilen ohne Überschrift (wie «Wie aktuell?» auf der Seite «Alarme»); ausser der
 * ersten ([first]) mit 12 dp Abstand wie zwischen den Karten der übrigen Seiten.
 */
@Composable
internal fun NavCard(first: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    AppCard(modifier = Modifier.padding(top = if (first) 0.dp else 12.dp), padding = CardPadding.None) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), content = content)
    }
}
