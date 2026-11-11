package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.LifecycleStartEffect
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.live.LiveDemand
import com.cryptochecker.app.domain.live.LiveExchange
import com.cryptochecker.app.domain.live.LivePair
import com.cryptochecker.app.domain.live.LiveQuote
import com.cryptochecker.app.domain.live.LiveRules
import com.cryptochecker.app.domain.watch.LiveStatus
import com.cryptochecker.app.domain.watch.isNotTraded

/**
 * Live-Kurse (WebSocket) über ein gespeichertes Paar legen — nur für die Anzeige; gespeichert
 * wird gesammelt (`PriceRefresher.applyLive`). Die 24-h-Veränderung des Stroms gilt nur bei
 * Basis «Letzte 24 Std.» und Börsen mit gleitendem Wert ([LiveRules.chooseChange]); ob der
 * Stempel passt, prüft die Anzeige ohnehin (`ChangeView`).
 */
internal object LiveOverlay {

    fun apply(watch: WatchEntity, quote: LiveQuote?, rollingBasis: Boolean): WatchEntity {
        // Deaktiviert: letzter gespeicherter Kurs bleibt stehen (ein später Takt ändert nichts)
        if (quote == null || watch.isNotTraded || watch.disabled) return watch
        // «Seit letzter Aktualisierung»: Live-Kurs gegen den zuletzt gespeicherten (wie iOS)
        val previous = if (watch.lastPrice != null && watch.lastPrice != quote.price) watch.lastPrice else watch.previousPrice
        return watch.copy(
            previousPrice = previous,
            lastPrice = quote.price,
            // Erster Takt mit gleichem Kurs (kein Handel) macht ein stehendes Buch nicht frisch
            lastUpdate = LiveRules.updateTime(quote, watch.lastPrice, watch.lastUpdate),
            lastError = null,
            // Frische wie beim Speichern (PriceRefresher.applyLive): Börsenzeit nur als belegte Handelszeit
            priceChangedAt = if (watch.lastPrice != quote.price) quote.time else watch.priceChangedAt,
            exchangeTime = LiveRules.exchangeTime(quote, watch.lastPrice, watch.exchangeTime),
            change24h = LiveRules.chooseChange(
                rollingBasis = rollingBasis,
                stampCurrent = true,
                exchangeRolling = LiveExchange.fromExchangeKey(watch.exchangeKey)?.rollingChange == true,
                live = quote.change24h,
                existing = watch.change24h,
            ),
        )
    }

    /**
     * Frische eines Paars am Live-Strom ([onLiveSocket]) am letzten Takt messen ([LiveStatus.freshnessTime]): Ein ruhiges
     * Paar, dessen Strom gesund ist, wird nicht «veraltet», obwohl `lastUpdate` nur mit einem Kurswechsel weiterrückt.
     * Für Zeile, Paarseite, Status-Pille und «nur veraltete» gleich; gespeichert wird nichts davon.
     */
    fun atHeartbeat(watch: WatchEntity, onLiveSocket: Boolean, now: Long): WatchEntity {
        if (!onLiveSocket || watch.isNotTraded || watch.disabled) return watch
        val time = LiveStatus.freshnessTime(watch.lastUpdate, onLiveSocket = true, now = now)
        return if (time == watch.lastUpdate) watch else watch.copy(lastUpdate = time)
    }
}

/**
 * Ein Paar mit seinem Live-Kurs darüber, als eigener Neuzusammensetzungs-Bereich: [live] wird
 * hier über `derivedStateOf` nur für DIESES Paar gelesen — ein Tick setzt also nur die Zeile
 * (bzw. das offene Aktionsblatt) neu zusammen, deren Kurs sich geändert hat, nicht den Bildschirm.
 * Am Live-Strom ([onLiveSocket]) gilt als Zeit der letzte Takt ([LiveOverlay.atHeartbeat], [now]).
 */
@Composable
internal fun WithLiveQuote(
    watch: WatchEntity,
    live: State<Map<Long, LiveQuote>>,
    rollingBasis: Boolean,
    onLiveSocket: Boolean = false,
    now: Long = 0L,
    content: @Composable (WatchEntity) -> Unit,
) {
    val quote by remember(watch.id, live) { derivedStateOf { live.value[watch.id] } }
    val shown = remember(watch, quote, rollingBasis, onLiveSocket, now) {
        LiveOverlay.atHeartbeat(LiveOverlay.apply(watch, quote, rollingBasis), onLiveSocket, now)
    }
    content(shown)
}

/** Was der Live-Strom von einem Paar braucht. */
internal fun WatchEntity.toLivePair(): LivePair = LivePair(
    watchId = id,
    exchangeKey = exchangeKey,
    exchangeName = exchangeName,
    pairId = pairId,
    base = baseAsset,
    quote = quoteAsset,
    contract = contractType.name,
    // Deaktivierte Paare werden wie nicht gehandelte nie abonniert
    notTraded = isNotTraded || disabled,
)

/**
 * Live-Kurse auch auf der Paarseite (wie iOS): Solange sie zu sehen ist, läuft der Strom ([LiveDemand]) und ihr Paar
 * ([watch]) ist abonniert — auch wenn es nicht in der gezeigten Gruppe der Merkliste steht. Deaktiviert, nicht mehr
 * gehandelt bzw. Vorschau (null) nie.
 */
@Composable
internal fun PairLiveEffect(viewModel: WatchlistViewModel, watch: WatchEntity?) {
    val pair = watch?.takeIf { !it.disabled && !it.isNotTraded }?.toLivePair()
    LaunchedEffect(pair) { viewModel.setPageLivePair(pair) }
    DisposableEffect(Unit) { onDispose { viewModel.setPageLivePair(null) } }
    LifecycleStartEffect(Unit) {
        viewModel.setLiveVisible(true, LiveDemand.Owner.PAIR_PAGE)
        onStopOrDispose { viewModel.setLiveVisible(false, LiveDemand.Owner.PAIR_PAGE) }
    }
}
