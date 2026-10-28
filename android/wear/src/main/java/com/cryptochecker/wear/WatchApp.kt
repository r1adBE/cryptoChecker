package com.cryptochecker.wear

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
import com.cryptochecker.marketdata.watch.WatchFreshness
import com.cryptochecker.marketdata.watch.WatchSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Uhr-App: Coins der gewählten Liste («Wie Handy» = Favoriten des Handys, oder «Eigene Liste») mit Kurs und
 * Änderung über 24 h (Börse klein), darunter «Aktualisieren», der Umschalter der Liste und (eigene Liste) «Coins
 * wählen» mit den gängigen Coins zum Antippen (höchstens 8). Wahl und Kurse kommen aus dem gemeinsamen [WatchStore]
 * (auch Änderungen vom Handy, solange die App offen ist). Lädt beim Öffnen und bei jeder anderen Liste ([WatchFetcher]:
 * ein neuer Abruf bricht den alten ab), sonst nur über «Aktualisieren». Veraltete Kurse ([WatchFreshness], je Coin ab
 * dem Stand seines Abrufs) grau mit Alter statt Börse («+1.23%  ·  vor 47 Min.»), wie die Merkliste des Handys.
 */
@Composable
fun WatchApp(store: WatchStore, onQuotesChanged: () -> Unit) {
    MaterialTheme {
        val context = LocalContext.current
        val settings by store.settingsFlow.collectAsState()
        val cache by store.quotesFlow.collectAsState()
        var choosing by remember { mutableStateOf(false) }
        // Uhrzeit für das Alter der Kurse, jede Minute neu (grau ab 45 Min. auch ohne neue Kurse)
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        val scope = rememberCoroutineScope()
        val fetcher = remember(store) {
            WatchFetcher(
                scope,
                shown = { store.shown },
                fetch = { coins -> WatchPriceSource.fetch(coins) },
                save = { quotes ->
                    store.mergeQuotes(quotes, System.currentTimeMillis())
                    onQuotesChanged()
                },
            )
        }
        val loading by fetcher.loading.collectAsState()
        val failed by fetcher.failed.collectAsState()

        /** Änderung auf der Uhr: mit nächster Änderungsnummer speichern und ans Handy geben. */
        fun change(edit: (WatchSettings) -> WatchSettings) {
            val next = store.updateSettings { edit(it).nextRevision() }
            scope.launch { WatchDataLayer.publish(context, next) }
        }

        // Beim Öffnen: Stand des Handys holen (Wahl, Favoriten)
        LaunchedEffect(Unit) { WatchDataLayer.pull(context, store) }
        LaunchedEffect(settings.shown) { fetcher.refresh() }
        LaunchedEffect(Unit) {
            while (true) {
                now = System.currentTimeMillis()
                delay(AGE_TICK_MS)
            }
        }
        BackHandler(enabled = choosing) { choosing = false }

        Scaffold(timeText = { TimeText() }) {
            if (choosing) {
                ChooseList(settings.own) { coin -> change { it.copy(own = WatchCoins.toggle(it.own, coin)) } }
            } else {
                QuoteList(settings, cache, now, loading, failed,
                    onRefresh = { fetcher.refresh() },
                    onToggleMode = {
                        change {
                            it.copy(mode = if (it.mode == WatchSettings.Mode.PHONE) WatchSettings.Mode.OWN else WatchSettings.Mode.PHONE)
                        }
                    },
                    onChoose = { choosing = true })
            }
        }
    }
}

@Composable
private fun QuoteList(
    settings: WatchSettings,
    cache: Map<String, WatchCached>,
    now: Long,
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
            item { QuoteRow(coin, cache[coin], now) }
        }
        item {
            // Stand der Liste = ältester gezeigter Kurs (je Coin eigene Zeit)
            val time = WatchCache.listTime(cache, coins)
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

/**
 * Zeile eines Coins: sieht aus wie ein Chip, hat aber keine Aktion (neu laden nur über «Aktualisieren», wie iOS) — für
 * den Screenreader ein Text ohne Rolle «Schaltfläche». Veraltet: Kurs grau, Alter statt Börse.
 */
@Composable
private fun QuoteRow(coin: String, cached: WatchCached?, now: Long) {
    val context = LocalContext.current
    val q = cached?.quote
    val price = q?.let { WatchFormat.price(it.price) } ?: "—"
    val change = WatchFormat.change(q?.change24h)
    val outdated = cached != null && WatchFreshness.isOutdated(cached.time, now)
    val row = stringResource(R.string.a11y_watch_row, coin, price, change)
    val spoken = if (cached != null && outdated) "$row, ${WatchAge.spoken(context, cached.time, now)}" else row
    val detail = if (cached != null && outdated) WatchAge.short(cached.time, now) else q?.source
    Column(
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ChipDefaults.Height)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colors.surface)
            .padding(ChipDefaults.ContentPadding)
            .clearAndSetSemantics { contentDescription = spoken },
    ) {
        Text("$coin  $price", maxLines = 1, style = MaterialTheme.typography.button,
            color = if (outdated) MaterialTheme.colors.onSurfaceVariant else MaterialTheme.colors.onSurface)
        Text(change + (detail?.let { "  ·  $it" } ?: ""), maxLines = 1, style = MaterialTheme.typography.caption2,
            color = MaterialTheme.colors.onSurface)
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

/** Takt, in dem die Uhr-App das Alter der Kurse neu bewertet. */
private const val AGE_TICK_MS = 60_000L
