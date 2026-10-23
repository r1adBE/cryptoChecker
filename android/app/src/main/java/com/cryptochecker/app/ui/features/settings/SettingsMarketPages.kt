// Einstellungs-Seiten «Merkliste» und «Märkte» samt Auswahl «Token & TradFi».
// Eigene Datei, weil `SettingsGeneralPages` sonst zu lang würde.
package com.cryptochecker.app.ui.features.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import com.cryptochecker.app.R
import com.cryptochecker.app.settings.AssetScope
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import com.cryptochecker.app.ui.components.SwitchRow
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.util.ChangeBasisText

/** Merkliste: Namen, Mini-Chart, «≈ Umrechnung», ⚡ «Ungewöhnliche Aktivität» und die Basis der %-Änderung. */
@Composable
internal fun WatchlistPage(
    onBack: () -> Unit,
    onOpenPage: (SettingsPage) -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsSubPage(title = stringResource(R.string.settings_row_watchlist), onBack = onBack) {
        GroupCard {
            SettingsAnchor("watchlist.names") {
                SwitchRow(
                    title = stringResource(R.string.settings_watchlist_names),
                    subtitle = stringResource(R.string.settings_watchlist_names_hint),
                    checked = settings.watchlistNames,
                    onCheckedChange = viewModel::setWatchlistNames
                )
            }
            RowDivider()
            SettingsAnchor("watchlist.sparkline") {
                SwitchRow(
                    title = stringResource(R.string.settings_watchlist_sparkline),
                    subtitle = stringResource(R.string.settings_watchlist_sparkline_hint),
                    checked = settings.watchlistSparkline,
                    onCheckedChange = viewModel::setWatchlistSparkline
                )
            }
            RowDivider()
            SettingsAnchor("watchlist.converted") {
                SwitchRow(
                    title = stringResource(R.string.settings_show_converted),
                    subtitle = stringResource(R.string.settings_show_converted_hint, settings.portfolioCurrency),
                    checked = settings.showConverted,
                    onCheckedChange = viewModel::setShowConverted
                )
            }
            RowDivider()
            SettingsAnchor("watchlist.activity") {
                SwitchRow(
                    title = stringResource(R.string.settings_watchlist_activity_card),
                    subtitle = stringResource(R.string.settings_watchlist_activity_card_hint),
                    checked = settings.watchlistActivityCard,
                    onCheckedChange = viewModel::setWatchlistActivityCard
                )
            }
        }
        // Basis der %-Änderung (Review D13: von «Erweitert» hierher — sie bestimmt, was jede Zeile zeigt;
        // gilt auch für Puls und Widgets). Wie iOS
        NavCard {
            SettingsAnchor("watchlist.change_basis") {
                SettingsNavRow(
                    title = stringResource(R.string.settings_change_basis, "%"),
                    icon = R.drawable.ic_settings_change_basis,
                    value = ChangeBasisText.summary(settings.changeBasis),
                    onClick = { onOpenPage(SettingsPage.CHANGE_BASIS) }
                )
            }
        }
    }
}

/**
 * «Token & TradFi» (unter «Erweitert», vorher auf «Merkliste»): was die Paar-Auswahl zeigt,
 * dazu Alpha-Tokens und QTLY (gehören zu Token). Gleiche Anker wie zuvor.
 */
@Composable
internal fun MarketsPage(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsSubPage(title = stringResource(R.string.settings_asset_scope_title), onBack = onBack) {
        GroupCard {
            SettingsAnchor("futures.scope") {
                AssetScopeList(selected = settings.assetScope, onSelected = viewModel::setAssetScope)
            }
            RowDivider()
            SettingsAnchor("futures.alpha") {
                // «inkl. Alpha-Tokens» über QTLY; gehört zu Token, bei «Nur TradFi» gesperrt wie QTLY
                val enabled = settings.assetScope.allowsTokens
                Box(Modifier.alpha(if (enabled) 1f else DISABLED_ALPHA)) {
                    SwitchRow(
                        title = stringResource(R.string.settings_asset_scope_alpha),
                        subtitle = stringResource(
                            if (enabled) R.string.settings_asset_scope_alpha_hint
                            else R.string.settings_asset_scope_alpha_tradfi_hint
                        ),
                        checked = settings.includeAlphaTokens,
                        onCheckedChange = viewModel::setIncludeAlphaTokens,
                        enabled = enabled,
                    )
                }
            }
            RowDivider()
            SettingsAnchor("futures.rolling") {
                // Bei «Nur TradFi» gesperrt und ausgegraut; der Wert bleibt und gilt weiter für
                // gemerkte Laufzeit-Futures (Aktualisierung, PriceFetcher)
                val enabled = settings.assetScope.allowsTokens
                Box(Modifier.alpha(if (enabled) 1f else DISABLED_ALPHA)) {
                    SwitchRow(
                        title = stringResource(R.string.settings_rolling_futures_qtly),
                        subtitle = stringResource(
                            if (enabled) R.string.settings_rolling_futures_hint
                            else R.string.settings_rolling_futures_tradfi_hint
                        ),
                        checked = settings.includeRollingFutures,
                        onCheckedChange = viewModel::setIncludeRollingFutures,
                        enabled = enabled,
                    )
                }
            }
        }
        // Gemerkte Paare bleiben: unter der Karte, eingerückt wie die Gruppenüberschrift
        Box(Modifier.padding(horizontal = 16.dp)) {
            Hint(stringResource(R.string.settings_asset_scope_hint), top = Spacing.sm)
        }
    }
}

/** Deckkraft der gesperrten QTLY-Zeile (wie `SwitchRow(enabled:)` in iOS). */
private const val DISABLED_ALPHA = 0.5f

/**
 * «Nur Token / Nur TradFi / Token und TradFi» als Einfach-Auswahl ([RadioRow]) mit Symbolen wie bei
 * den Gruppen-Chips der Merkliste: Token = Raute, TradFi = Bank, beides = beide Symbole.
 * Die Symbole sind Dekoration; der Screenreader liest Titel, Untertitel und «ausgewählt».
 */
@Composable
private fun AssetScopeList(selected: AssetScope, onSelected: (AssetScope) -> Unit) {
    Column(Modifier.selectableGroup()) {
        AssetScope.entries.forEach { scope ->
            RadioRow(
                selected = scope == selected,
                onClick = { if (scope != selected) onSelected(scope) },
                title = stringResource(scope.titleRes),
                subtitle = stringResource(scope.hintRes),
                leading = { AssetScopeIcons(scope) }
            )
        }
    }
}

/** Feste Breite (zwei Symbole), damit die Titel aller drei Zeilen bündig stehen. */
@Composable
private fun AssetScopeIcons(scope: AssetScope) {
    Row(
        modifier = Modifier.width(44.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (scope.allowsTokens) AssetScopeIcon(R.drawable.ic_token)
        if (scope.allowsTradFi) AssetScopeIcon(R.drawable.ic_bank)
    }
}

@Composable
private fun AssetScopeIcon(@DrawableRes icon: Int) {
    Icon(
        painterResource(icon),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp)
    )
}

internal val AssetScope.titleRes: Int
    get() = when (this) {
        AssetScope.TOKENS -> R.string.settings_asset_scope_tokens
        AssetScope.TRADFI -> R.string.settings_asset_scope_tradfi
        AssetScope.BOTH -> R.string.settings_asset_scope_both
    }

private val AssetScope.hintRes: Int
    get() = when (this) {
        AssetScope.TOKENS -> R.string.settings_asset_scope_tokens_hint
        AssetScope.TRADFI -> R.string.settings_asset_scope_tradfi_hint
        AssetScope.BOTH -> R.string.settings_asset_scope_both_hint
    }
