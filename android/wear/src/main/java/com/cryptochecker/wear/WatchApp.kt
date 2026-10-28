package com.cryptochecker.wear

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Checkbox
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import com.cryptochecker.marketdata.watch.WatchCoins
import com.cryptochecker.marketdata.watch.WatchSettings
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Uhr-App: Coins der gewählten Liste («Wie Handy» = Favoriten des Handys, oder «Eigene Liste») mit Kurs und
 * Änderung über 24 h (Börse klein), darunter «Aktualisieren», der Umschalter der Liste und (eigene Liste) «Coins
 * wählen» mit den gängigen Coins zum Antippen (höchstens 8). Lädt beim Öffnen und nach jeder Änderung.
 */
@Composable
fun WatchApp(store: WatchStore, onQuotesChanged: () -> Unit) {
    MaterialTheme {
        val context = LocalContext.current
        var settings by remember { mutableStateOf(store.settings) }
        var cached by remember { mutableStateOf(store.quotes()) }
        var loading by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        var choosing by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        fun refresh() {
            scope.launch {
                loading = true
                val quotes = runCatching { WatchPriceSource.fetch(settings.shown) }.getOrNull().orEmpty()
                if (quotes.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    store.saveQuotes(quotes, now)
                    cached = quotes to now
                    failed = false
                    onQuotesChanged()
                } else {
                    failed = settings.shown.isNotEmpty()
                }
                loading = false
            }
        }

        /** Änderung auf der Uhr: speichern und ans Handy geben (neuere Seite gewinnt). */
        fun change(next: WatchSettings) {
            val stamped = next.copy(time = System.currentTimeMillis())
            settings = stamped
            store.settings = stamped
            scope.launch { WatchDataLayer.publish(context, stamped) }
        }

        // Beim Öffnen: Stand des Handys holen (Wahl, Favoriten)
        LaunchedEffect(Unit) { settings = WatchDataLayer.pull(context, store) }
        LaunchedEffect(settings.shown) { refresh() }
        BackHandler(enabled = choosing) { choosing = false }

        Scaffold(timeText = { TimeText() }) {
            if (choosing) {
                ChooseList(settings.own) { coin -> change(settings.copy(own = WatchCoins.toggle(settings.own, coin))) }
            } else {
                QuoteList(settings, cached.first, cached.second, loading, failed,
                    onRefresh = { refresh() },
                    onToggleMode = {
                        val mode = if (settings.mode == WatchSettings.Mode.PHONE) WatchSettings.Mode.OWN else WatchSettings.Mode.PHONE
                        change(settings.copy(mode = mode))
                    },
                    onChoose = { choosing = true })
            }
        }
    }
}

@Composable
private fun QuoteList(
    settings: WatchSettings,
    quotes: Map<String, WatchQuote>,
    time: Long,
    loading: Boolean,
    failed: Boolean,
    onRefresh: () -> Unit,
    onToggleMode: () -> Unit,
    onChoose: () -> Unit,
) {
    val coins = settings.shown
    val phone = settings.mode == WatchSettings.Mode.PHONE
    ScalingLazyColumn(state = rememberScalingLazyListState(), modifier = Modifier.fillMaxWidth()) {
        if (coins.isEmpty()) {
            item {
                Text(stringResource(if (phone) R.string.watch_phone_empty else R.string.watch_empty), textAlign = TextAlign.Center)
            }
        }
        coins.forEach { coin ->
            item {
                val q = quotes[coin]
                val price = q?.let { WatchFormat.price(it.price) } ?: "—"
                val change = WatchFormat.change(q?.change24h)
                val spoken = stringResource(R.string.a11y_watch_row, coin, price, change)
                Chip(
                    onClick = onRefresh,
                    label = { Text("$coin  $price", maxLines = 1) },
                    secondaryLabel = { Text(change + (q?.source?.let { "  ·  $it" } ?: ""), maxLines = 1) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = spoken },
                )
            }
        }
        item {
            val status = when {
                loading -> stringResource(R.string.watch_loading)
                failed -> stringResource(R.string.watch_error)
                time > 0 -> stringResource(R.string.watch_updated, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(time)))
                else -> ""
            }
            Text(status, textAlign = TextAlign.Center, style = MaterialTheme.typography.caption3)
        }
        item {
            Chip(onClick = onRefresh, label = { Text(stringResource(R.string.watch_refresh)) }, modifier = Modifier.fillMaxWidth())
        }
        item {
            // «Wie Handy» ↔ «Eigene Liste»
            Chip(
                onClick = onToggleMode,
                label = { Text(stringResource(if (phone) R.string.watch_mode_phone else R.string.watch_mode_own)) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (!phone) {
            item {
                Chip(onClick = onChoose, label = { Text(stringResource(R.string.watch_choose)) },
                    colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ChooseList(own: List<String>, onToggle: (String) -> Unit) {
    ScalingLazyColumn(state = rememberScalingLazyListState(), modifier = Modifier.fillMaxWidth()) {
        item {
            Text(stringResource(R.string.watch_choose_hint, WatchCoins.MAX), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.caption2)
        }
        WatchCoins.TOP.forEach { coin ->
            item {
                val checked = coin in own
                ToggleChip(
                    checked = checked,
                    onCheckedChange = { onToggle(coin) },
                    label = { Text(coin) },
                    toggleControl = { Checkbox(checked = checked) },
                    enabled = checked || own.size < WatchCoins.MAX,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
