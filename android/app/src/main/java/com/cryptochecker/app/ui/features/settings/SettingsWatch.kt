package com.cryptochecker.app.ui.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.wearable.PhoneWatchSync
import com.cryptochecker.marketdata.watch.WatchCoins
import com.cryptochecker.marketdata.watch.WatchSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

// «Widgets» › Uhr (Build 154): Coin-Liste der Wear-OS-App vom Handy aus wählen. Abgleich: PhoneWatchSync.

@HiltViewModel
internal class WatchSettingsViewModel @Inject constructor(private val sync: PhoneWatchSync) : ViewModel() {
    val state = sync.state

    init {
        sync.refreshFromWatch()
    }

    fun setMode(mode: WatchSettings.Mode) { sync.setMode(mode) }
    fun toggle(coin: String) { sync.toggleOwn(coin) }
}

/** Wahl «Wie Handy» (Favoriten ★) oder «Eigene Liste» mit bis zu [WatchCoins.MAX] Coins. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WatchSettingsCard(viewModel: WatchSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    GroupCard(title = stringResource(R.string.settings_watch)) {
        SettingsAnchor("widgets.watch") {
            Text(
                stringResource(R.string.settings_watch_hint, WatchCoins.MAX),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
        RadioRow(
            selected = state.mode == WatchSettings.Mode.PHONE,
            onClick = { viewModel.setMode(WatchSettings.Mode.PHONE) },
            title = stringResource(R.string.settings_watch_mode_phone),
            subtitle = state.phone.joinToString(" · ").ifEmpty { stringResource(R.string.settings_watch_none) },
        )
        RadioRow(
            selected = state.mode == WatchSettings.Mode.OWN,
            onClick = { viewModel.setMode(WatchSettings.Mode.OWN) },
            title = stringResource(R.string.settings_watch_mode_own),
            subtitle = state.own.joinToString(" · "),
        )
        if (state.mode == WatchSettings.Mode.OWN) {
            Text(
                stringResource(R.string.settings_watch_own_title, state.own.size, WatchCoins.MAX),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                WatchCoins.TOP.forEach { coin ->
                    val selected = coin in state.own
                    FilterChip(
                        selected = selected,
                        onClick = { viewModel.toggle(coin) },
                        enabled = selected || state.own.size < WatchCoins.MAX,
                        label = { Text(coin) },
                    )
                }
            }
        }
    }
}
