package com.cryptochecker.wear

import android.content.Context
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
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Checkbox
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import com.cryptochecker.marketdata.watch.WatchCoins
import com.cryptochecker.marketdata.watch.WatchFreshness
import com.cryptochecker.marketdata.watch.WatchInterval
import com.cryptochecker.marketdata.watch.WatchPair
import com.cryptochecker.marketdata.watch.WatchPhoneQuotes
import com.cryptochecker.marketdata.watch.WatchPortfolio
import com.cryptochecker.marketdata.watch.WatchSettings
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * Uhr-App: Coins der gewählten Liste («Wie Handy» = Favoriten des Handys, oder «Eigene Liste») mit Kurs und
 * Änderung über 24 h (Börse klein), darunter «Aktualisieren», der Schalter «Liste vom Handy» und (eigene Liste) «Coins
 * wählen» mit den gängigen Coins zum Antippen (höchstens 8). Wahl und Kurse kommen aus dem gemeinsamen [WatchStore]
 * (auch Änderungen vom Handy, solange die App offen ist). Kurse vom Handy (Wahl «Kurse vom Handy, sonst selbst» und
 * Handy verbunden) übernimmt sie, ohne selbst abzufragen; sonst lädt sie beim Öffnen, bei jeder anderen Liste
 * ([WatchFetcher]: ein neuer Abruf bricht den alten ab), über «Aktualisieren» und offen im Intervall des Handys
 * ([WatchInterval]). Oben der Portfolio-Wert vom Handy, falls dort eingeschaltet. Veraltete Kurse ([WatchFreshness], je
 * Coin ab dem Stand seines Abrufs) grau mit Alter statt Börse («+1.23%  ·  vor 47 Min.»), wie die Merkliste des Handys.
 */
@Composable
fun WatchApp(store: WatchStore, onQuotesChanged: () -> Unit) {
    MaterialTheme {
        val context = LocalContext.current
        val settings by store.settingsFlow.collectAsState()
        val cache by store.quotesFlow.collectAsState()
        val portfolio by store.portfolioFlow.collectAsState()
        var pulled by remember { mutableStateOf(false) }
        var choosing by remember { mutableStateOf(false) }
        // Uhrzeit für das Alter der Kurse, jede Minute neu (grau ab 3 × Intervall auch ohne neue Kurse)
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        val scope = rememberCoroutineScope()
        val fetcher = remember(store) {
            WatchFetcher(
                scope,
                shown = { store.shown },
                fetch = { coins -> WatchPriceSource.fetch(coins) },
                save = { quotes, startedAt ->
                    store.mergeQuotes(quotes, startedAt)
                    onQuotesChanged()
                },
                plan = { coins, force ->
                    val s = store.settings
                    val usePhone = s.source == WatchSettings.Source.PHONE && WatchPhoneQuotes.usePhone(
                        s.source, WatchDataLayer.phoneConnected(context), store.phoneTime, System.currentTimeMillis(), s.interval,
                    )
                    WatchPhoneQuotes.selfFetch(coins, store.quotes().mapValues { it.value.time }, store.phoneCoins, usePhone,
                        s.interval, System.currentTimeMillis(), force)
                },
            )
        }
        val loading by fetcher.loading.collectAsState()
        val failed by fetcher.failed.collectAsState()

        /** Änderung auf der Uhr: nach dem Stand des Handys mit höherer Nummer speichern und ans Handy geben. */
        fun change(edit: (WatchSettings) -> WatchSettings) = WatchDataLayer.change(context, store, edit)

        // Beim Öffnen: Stand des Handys holen (Wahl, Favoriten, Kurse, Portfolio-Wert), erst dann laden
        LaunchedEffect(Unit) {
            WatchDataLayer.pull(context, store)
            pulled = true
        }
        // Neu laden bei anderer Liste und bei anderer Kursquelle («Immer selbst abrufen» sofort), wie iOS takeFromPhone
        LaunchedEffect(settings.shown, settings.source, pulled) { if (pulled) fetcher.refresh() }
        // Offen: im Intervall des Handys, nur was fällig ist
        LaunchedEffect(settings.interval, pulled) {
            while (pulled) {
                delay(WatchInterval.clamp(settings.interval) * 60_000L)
                fetcher.refresh(force = false)
            }
        }
        LaunchedEffect(Unit) {
            while (true) {
                now = System.currentTimeMillis()
                delay(AGE_TICK_MS)
            }
        }
        BackHandler(enabled = choosing) { choosing = false }
        // Listenzustände hier, damit die Scroll-Anzeige am Rand der gezeigten Liste folgt (runde Uhren: unter den Coins
        // stehen noch «Aktualisieren» und die Listenwahl)
        val listState = rememberScalingLazyListState()
        val chooseState = rememberScalingLazyListState()

        Scaffold(
            timeText = { TimeText() },
            positionIndicator = { PositionIndicator(scalingLazyListState = if (choosing) chooseState else listState) },
        ) {
            if (choosing) {
                ChooseList(settings.own, state = chooseState) { coin ->
                    change { it.copy(own = WatchCoins.toggle(it.own, coin)) }
                }
            } else {
                QuoteList(settings, cache, portfolio, now, loading, failed,
                    state = listState,
                    onRefresh = { fetcher.refresh() },
                    onToggleMode = {
                        // Ziel nach der gezeigten Wahl, nicht nach einem neueren Stand des Handys
                        val target = if (settings.mode == WatchSettings.Mode.PHONE) WatchSettings.Mode.OWN else WatchSettings.Mode.PHONE
                        change { it.copy(mode = target) }
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
    portfolio: WatchPortfolio?,
    now: Long,
    loading: Boolean,
    failed: Boolean,
    state: ScalingLazyListState,
    onRefresh: () -> Unit,
    onToggleMode: () -> Unit,
    onChoose: () -> Unit,
) {
    val coins = settings.shown
    val phone = settings.mode == WatchSettings.Mode.PHONE
    ScalingLazyColumn(state = state, modifier = Modifier.fillMaxWidth()) {
        portfolio?.let { p -> item { PortfolioRow(p, now, settings.interval) } }
        if (coins.isEmpty()) {
            item {
                Text(stringResource(if (phone) R.string.watch_phone_empty else R.string.watch_empty), textAlign = TextAlign.Center)
            }
        }
        coins.forEach { key ->
            item { QuoteRow(key, cache[key], now, settings.interval, loading) }
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
            // Schalter «Liste vom Handy»: an = Favoriten des Handys, aus = eigene Liste (wie watchOS `Toggle`)
            ToggleChip(
                checked = phone,
                onCheckedChange = { onToggleMode() },
                label = { Text(stringResource(R.string.watch_list_from_phone)) },
                toggleControl = { Switch(checked = phone) },
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
 * Zeile eines Coins bzw. Paars des Handys ([key], Anzeige [WatchPair.label], z. B. «BANK Perp»): sieht aus wie ein
 * Chip, hat aber keine Aktion (neu laden nur über «Aktualisieren», wie iOS) — für den Screenreader ein Text ohne Rolle
 * «Schaltfläche». Unter dem Kurs die Börse, die ihn geliefert hat. Veraltet: Kurs grau, Alter statt Börse. Ohne Kurs
 * «—» und, sobald nichts mehr lädt, der Grund: kurz sichtbar in der zweiten Zeile ([missingShort], «nur vom Handy» /
 * «nicht erreichbar»), ausführlich im Screenreader ([missingReason]).
 */
@Composable
private fun QuoteRow(key: String, stored: WatchCached?, now: Long, interval: Int, loading: Boolean) {
    val context = LocalContext.current
    // Börse stillgelegt (ExchangeRetirement): kein alter Kurs, «—» mit dem Grund
    val cached = stored?.takeUnless { WatchPair.parse(key)?.let { p -> WatchPairQuotes.retired(p, now) } == true }
    val coin = WatchPair.label(key)
    val q = cached?.quote
    val price = q?.let { WatchFormat.price(it.price) } ?: "—"
    val change = WatchFormat.change(q?.change24h)
    val outdated = cached != null && WatchFreshness.isOutdated(cached.time, now, interval)
    val row = stringResource(R.string.a11y_watch_row, coin, price, change)
    val missing = cached == null && !loading
    val spoken = when {
        cached != null && outdated -> "$row, ${WatchAge.spoken(context, cached.time, now)}"
        missing -> "$row, ${missingReason(context, key)}"
        else -> row
    }
    val detail = if (cached != null && outdated) WatchAge.short(cached.time, now) else q?.source
    // Ohne Kurs steht «—» schon oben: die zweite Zeile nennt nur den Grund (darf umbrechen)
    val second = if (missing) missingShort(context, key) else change + (detail?.let { "  ·  $it" } ?: "")
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
        // Zu eng: Schrift schrumpft bis 60 % statt Ziffern abzuschneiden (wie iOS minimumScaleFactor)
        FitText("$coin  $price", style = MaterialTheme.typography.button,
            color = if (outdated) MaterialTheme.colors.onSurfaceVariant else MaterialTheme.colors.onSurface)
        Text(second, maxLines = if (missing) 2 else 1, style = MaterialTheme.typography.caption2,
            color = MaterialTheme.colors.onSurface)
    }
}

@Composable
private fun ChooseList(own: List<String>, state: ScalingLazyListState, onToggle: (String) -> Unit) {
    ScalingLazyColumn(state = state, modifier = Modifier.fillMaxWidth()) {
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

/**
 * Grund für «—» ([WatchPairQuotes.missingReason] mit dem letzten eigenen Abruf, [WatchPairOutcomes]): Pool, Laufzeit-
 * Kontrakt → die Uhr kann das Paar nicht selbst laden; gefragt, aber keine gefragte Börse führt das Paar (z. B. ein
 * Kraken-Paar, das Binance, Bybit und OKX nicht haben) → Kurse dieser Börse nur vom Handy; gefragt, aber keine Börse
 * antwortete → Börsen gerade nicht erreichbar; noch nicht gefragt → eigene Börse nicht erreichbar bzw. nur vom Handy.
 * Wie iOS `WatchText.missingReason`.
 */
internal fun missingReason(context: Context, key: String): String {
    val pair = WatchPair.parse(key) ?: return context.getString(R.string.watch_error)
    return when (WatchPairQuotes.missingReason(pair, WatchPairOutcomes[pair.key])) {
        WatchPairQuotes.Missing.RETIRED -> context.getString(R.string.watch_no_price_retired, pair.exchange)
        WatchPairQuotes.Missing.PHONE_ONLY -> context.getString(R.string.a11y_watch_no_price_phone_only)
        WatchPairQuotes.Missing.OWN_UNREACHABLE -> context.getString(R.string.a11y_watch_no_price_unreachable, pair.exchange)
        WatchPairQuotes.Missing.UNREACHABLE -> context.getString(R.string.a11y_watch_no_price_exchanges_unreachable)
        WatchPairQuotes.Missing.VIA_PHONE -> context.getString(R.string.a11y_watch_no_price_via_phone, pair.exchange)
    }
}

/**
 * Sichtbarer Kurzgrund für «—» in der zweiten Zeile, dieselben Fälle wie [missingReason]
 * ([WatchPairQuotes.Missing.viaPhone]): «nur vom Handy» bzw. «nicht erreichbar». Wie iOS `WatchText.missingShort`.
 */
internal fun missingShort(context: Context, key: String): String {
    val pair = WatchPair.parse(key) ?: return context.getString(R.string.watch_no_price_unreachable)
    val reason = WatchPairQuotes.missingReason(pair, WatchPairOutcomes[pair.key])
    if (reason == WatchPairQuotes.Missing.RETIRED) return context.getString(R.string.watch_no_price_retired, pair.exchange)
    return context.getString(if (reason.viaPhone) R.string.watch_no_price_phone else R.string.watch_no_price_unreachable)
}

/** Takt, in dem die Uhr-App das Alter der Kurse neu bewertet. */
private const val AGE_TICK_MS = 60_000L
