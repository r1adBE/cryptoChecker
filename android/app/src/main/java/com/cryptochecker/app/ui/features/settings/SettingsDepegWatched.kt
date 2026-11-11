package com.cryptochecker.app.ui.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.alarm.DepegReadingStore
import com.cryptochecker.app.domain.alarm.StableDepeg
import com.cryptochecker.app.domain.alarm.StableDepegStore
import com.cryptochecker.app.domain.util.LocaleNumbers
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

// Einstellungen › Alarme › Depeg: Zeile «Überwacht: USDT 0.9998 · USDC 1.0001 · DAI» unter der Schwelle.

@HiltViewModel
internal class DepegWatchedViewModel @Inject constructor(
    usdtStore: DepegReadingStore,
    stableStore: StableDepegStore,
) : ViewModel() {
    val usdt = usdtStore.reading
    val watched = stableStore.watched
    val readings = stableStore.readings
}

/**
 * Geprüfte Stablecoins mit letztem Kurs ([StableDepeg.lineItems]: höchstens 6 Std. alt, sonst nur das Kürzel). Wie iOS
 * `DepegWatchedLine`.
 */
@Composable
internal fun DepegWatchedLine(viewModel: DepegWatchedViewModel = hiltViewModel()) {
    val usdt by viewModel.usdt.collectAsStateWithLifecycle()
    val watched by viewModel.watched.collectAsStateWithLifecycle()
    val readings by viewModel.readings.collectAsStateWithLifecycle()
    val items = StableDepeg.lineItems(watched, usdt, readings, System.currentTimeMillis())
    val text = items.joinToString(" · ") { (coin, price) -> price?.let { "$coin ${LocaleNumbers.decimal(it, 4)}" } ?: coin }
    Hint(stringResource(R.string.settings_depeg_watched, text))
}
