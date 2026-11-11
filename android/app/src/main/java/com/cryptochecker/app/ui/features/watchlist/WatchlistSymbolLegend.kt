@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.watchlist

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.watch.WatchlistSymbol
import com.cryptochecker.app.ui.theme.Spacing

/**
 * «Symbole erklärt» (Menü der Merkliste): die kleinen Symbole unter einem Paar ([WatchlistSymbol], Reihenfolge wie in
 * der Zeile) mit je einer Zeile Text. Gleicher Aufbau wie `WatchlistSymbolLegendSheet` (iOS).
 */
@Composable
internal fun WatchlistSymbolLegendSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = Spacing.xl, end = Spacing.md, bottom = Spacing.lg)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.watchlist_symbols_legend),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).semantics { heading() }
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.action_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            WatchlistSymbol.entries.forEach { symbol ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.md, end = Spacing.md),
                ) {
                    // Dekoration: Der Text daneben sagt dasselbe
                    Icon(
                        painterResource(symbol.icon()),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        stringResource(symbol.text()),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = Spacing.lg),
                    )
                }
            }
        }
    }
}

@DrawableRes
private fun WatchlistSymbol.icon(): Int = when (this) {
    WatchlistSymbol.TOKEN -> R.drawable.ic_token
    WatchlistSymbol.TRADFI -> R.drawable.ic_bank
    WatchlistSymbol.ALPHA -> R.drawable.ic_alpha
    WatchlistSymbol.PRICE_NOTIFICATION_ON -> R.drawable.ic_notifications
    WatchlistSymbol.PRICE_NOTIFICATION_OFF -> R.drawable.ic_notifications_off
    WatchlistSymbol.ACTIVE_ALARMS -> R.drawable.ic_notifications_active
}

@StringRes
private fun WatchlistSymbol.text(): Int = when (this) {
    WatchlistSymbol.TOKEN -> R.string.asset_kind_token
    WatchlistSymbol.TRADFI -> R.string.asset_kind_tradfi
    WatchlistSymbol.ALPHA -> R.string.asset_kind_alpha
    WatchlistSymbol.PRICE_NOTIFICATION_ON -> R.string.a11y_price_notification_on
    WatchlistSymbol.PRICE_NOTIFICATION_OFF -> R.string.a11y_price_notification_off
    WatchlistSymbol.ACTIVE_ALARMS -> R.string.watchlist_symbols_legend_alarms
}
