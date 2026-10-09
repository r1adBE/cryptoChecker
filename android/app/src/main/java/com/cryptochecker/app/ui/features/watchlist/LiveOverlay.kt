package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.live.LiveExchange
import com.cryptochecker.app.domain.live.LivePair
import com.cryptochecker.app.domain.live.LiveQuote
import com.cryptochecker.app.domain.live.LiveRules
import com.cryptochecker.app.domain.watch.isNotTraded

/**
 * Live-Kurse (WebSocket) über ein gespeichertes Paar legen — nur für die Anzeige; gespeichert
 * wird gesammelt (`PriceRefresher.applyLive`). Die 24-h-Veränderung des Stroms gilt nur bei
 * Basis «Letzte 24 Std.» und Börsen mit gleitendem Wert ([LiveRules.chooseChange]); ob der
 * Stempel passt, prüft die Anzeige ohnehin (`ChangeView`).
 */
internal object LiveOverlay {

    fun apply(watch: WatchEntity, quote: LiveQuote?, rollingBasis: Boolean): WatchEntity {
        if (quote == null || watch.isNotTraded) return watch
        // «Seit letzter Aktualisierung»: Live-Kurs gegen den zuletzt gespeicherten (wie iOS)
        val previous = if (watch.lastPrice != null && watch.lastPrice != quote.price) watch.lastPrice else watch.previousPrice
        return watch.copy(
            previousPrice = previous,
            lastPrice = quote.price,
            lastUpdate = maxOf(watch.lastUpdate, quote.time),
            lastError = null,
            change24h = LiveRules.chooseChange(
                rollingBasis = rollingBasis,
                stampCurrent = true,
                exchangeRolling = LiveExchange.fromMarketKey(watch.marketKey)?.rollingChange == true,
                live = quote.change24h,
                existing = watch.change24h,
            ),
        )
    }
}

/**
 * Ein Paar mit seinem Live-Kurs darüber, als eigener Neuzusammensetzungs-Bereich: [live] wird
 * hier über `derivedStateOf` nur für DIESES Paar gelesen — ein Tick setzt also nur die Zeile
 * (bzw. das offene Aktionsblatt) neu zusammen, deren Kurs sich geändert hat, nicht den Bildschirm.
 */
@Composable
internal fun WithLiveQuote(
    watch: WatchEntity,
    live: State<Map<Long, LiveQuote>>,
    rollingBasis: Boolean,
    content: @Composable (WatchEntity) -> Unit,
) {
    val quote by remember(watch.id, live) { derivedStateOf { live.value[watch.id] } }
    val shown = remember(watch, quote, rollingBasis) { LiveOverlay.apply(watch, quote, rollingBasis) }
    content(shown)
}

/** Was der Live-Strom von einem Paar braucht. */
internal fun WatchEntity.toLivePair(): LivePair = LivePair(
    watchId = id,
    marketKey = marketKey,
    marketName = marketName,
    pairId = pairId,
    base = baseAsset,
    quote = quoteAsset,
    contract = contractType.name,
    notTraded = isNotTraded,
)
