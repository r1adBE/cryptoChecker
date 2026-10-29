// Ausweich-Kette der Tagesschlüsse (Teil von `HistoricPriceSource`): eigene Börse des Coins und Kraken/OKX/Bybit über
// deren eigene Tageskerzen (`OwnCandleDataSource`: Zeitgrenze, Sperre/Pause je Host, kurz im Speicher). Eigene Datei,
// weil die Kette neben Binance/Coinbase lesbar bleiben soll; Erweiterungsfunktionen, Abhängigkeiten in der Klasse.
package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.data.remote.CandleInterval
import com.cryptochecker.app.data.remote.candles.CandlePair
import com.cryptochecker.app.domain.portfolio.HistoricCloseChain
import com.cryptochecker.app.domain.portfolio.HistoricCloseChain.Outcome
import com.cryptochecker.app.domain.portfolio.HistoricCloseChain.Verdict
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneOffset

/** Eigene Börse je Coin so lange gemerkt (Wege ändern sich nur mit neuen Transaktionen). */
private const val ROUTE_MEMO_MILLIS = 60_000L

/**
 * Tageskerzen je Abfrage: immer gleich viele, damit alle Tage desselben Paars denselben Eintrag im Speicher der Kerzen
 * treffen (höchstens 1000; Kraken liefert 720, OKX und Coinbase etwa 600 — ältere Tage bleiben dort ohne Schluss).
 */
private const val CHAIN_DAYS = 1000

/**
 * Paar einer Kettenquelle ([HistoricCloseChain.pairs]) für die eigenen Kerzen. USDC gilt wie USDT (≈ 1 USD, wie
 * Coinbase USDC); die Kennung bestimmt das Paar, die Gegenwährung nur die Umrechnung im Verlauf.
 */
fun chainCandlePair(source: String, base: String, p: HistoricCloseChain.ChainPair): CandlePair =
    CandlePair(source, HistoricCloseChain.provider(source), base, if (p.quote == "USDC") "USDT" else p.quote, p.pairId)

/**
 * Eingetragene Börse je Coin ([resolveHistoryRoutes], ohne Netz), [ROUTE_MEMO_MILLIS] gemerkt. Binance, freier Text und
 * Coins ohne Paar fehlen (dann die Kette ohne eigene Börse).
 */
suspend fun HistoricPriceSource.ownRoutes(coins: Collection<String>): Map<String, CandlePair> {
    val now = System.currentTimeMillis()
    val out = HashMap<String, CandlePair>()
    val open = ArrayList<String>()
    for (coin in coins.distinct()) {
        val hit = routeMemo[coin]
        if (hit != null && now - hit.second in 0 until ROUTE_MEMO_MILLIS) hit.first?.let { out[coin] = it } else open += coin
    }
    if (open.isEmpty()) return out
    val routes = try {
        resolveHistoryRoutes(portfolioDao, exchangeRepository, open)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.d(e, "Tagesschluss: eigene Börsen nicht lesbar")
        return out
    }
    for (coin in open) {
        val pair = routes.pairs[coin]
        routeMemo[coin] = pair to now
        pair?.let { out[coin] = it }
    }
    return out
}

/**
 * Schluss der Tageskerze, die genau bei [start] beginnt, und jüngster Schluss derselben Reihe (Identitätsprüfung);
 * null = keine laufende Reihe oder der Tag fehlt (z. B. älter als die Börse liefert).
 */
suspend fun HistoricPriceSource.seriesClose(pair: CandlePair, start: Long): Pair<Double, Double?>? {
    val candles = try {
        ownCandles.candles(pair, CandleInterval.D1, CHAIN_DAYS)?.value
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    } ?: return null
    val close = candles.firstOrNull { it.openTime == start }?.close?.takeIf { it > 0.0 && it.isFinite() } ?: return null
    return close to candles.lastOrNull()?.close
}

/**
 * Eigene Börse [own] des Coins: deren Tageskerze des Tags, Fiat mit dem Devisenkurs des Tags (jüngster Schluss mit dem
 * heutigen) in USDT. Prüfung wie Binance-Spot (1/3…3) — die aktuellen Portfolio-Kurse kommen von dieser Börse. Ist die
 * Börse zugleich ein Glied der Kette und hat keinen Schluss, folgt deren Kettenabfrage (Coinbase: jedes Alter).
 */
internal suspend fun HistoricPriceSource.ownOutcome(
    own: CandlePair,
    b: String,
    date: LocalDate,
    start: Long,
    reference: Double,
    ownName: String?,
    fetch: DayCloseFetch,
): Outcome {
    val nameOk = HistoricCloseChain.nameAllowed(
        b, own = true, exchangeKey = own.exchangeKey, ownName = ownName,
        binanceName = tokenIdentityStore.binanceName(b), providerName = tokenIdentityStore.pairName(own.exchangeKey, b),
    )
    var outcome = if (!nameOk) Outcome(null, rejected = true) else ownDayClose(own, date, start, reference, fetch)
    if (outcome.price == null && own.exchangeKey in HistoricCloseChain.CHAIN && own.exchangeKey != HistoricCloseChain.BINANCE) {
        val chain = if (own.exchangeKey == HistoricCloseChain.COINBASE) {
            coinbaseOutcome(b, date, start, reference, fetch)
        } else {
            chainOutcome(own.exchangeKey, b, start, reference, ownName)
        }
        outcome = chain.copy(rejected = chain.rejected || outcome.rejected)
    }
    return outcome
}

private suspend fun HistoricPriceSource.ownDayClose(
    own: CandlePair,
    date: LocalDate,
    start: Long,
    reference: Double,
    fetch: DayCloseFetch,
): Outcome {
    val (raw, rawLatest) = seriesClose(own, start) ?: return Outcome(null)
    val quote = own.quote.trim().uppercase()
    var close = raw
    var latest = rawLatest
    if (quote != "USDT" && quote != "USD" && quote != "USDC") {
        // Fiat-Paar: Devisenkurs DES Tags (Wochenende → letzter Geschäftstag) bzw. heute für den jüngsten Schluss
        val dayFx = fetch.usdTo(quote, date)?.rate?.takeIf { it > 0.0 && it.isFinite() } ?: return Outcome(null)
        val nowFx = fetch.usdTo(quote, LocalDate.now(ZoneOffset.UTC))?.rate?.takeIf { it > 0.0 && it.isFinite() }
        close = raw / dayFx
        latest = if (rawLatest != null && nowFx != null) rawLatest / nowFx else null
    }
    return when (HistoricCloseChain.decide(close, latest, reference, primary = true, nameOk = true)) {
        Verdict.ACCEPT -> Outcome(close, provider = own.exchangeName)
        Verdict.REJECT -> Outcome(null, rejected = true)
        Verdict.MISSING -> Outcome(null)
    }
}

/**
 * Kettenquelle [source] (Kraken, OKX, Bybit): Paare nach [HistoricCloseChain.pairs], Name nach
 * [HistoricCloseChain.nameAllowed], Schluss nur mit ±25 % gegen den aktuellen Kurs ([HistoricCloseChain.decide]).
 */
suspend fun HistoricPriceSource.chainOutcome(source: String, b: String, start: Long, reference: Double, ownName: String?): Outcome {
    val pairs = HistoricCloseChain.pairs(source, b)
    if (pairs.isEmpty()) return Outcome(null)
    val nameOk = HistoricCloseChain.nameAllowed(
        b, own = false, exchangeKey = source, ownName = ownName,
        binanceName = tokenIdentityStore.binanceName(b), providerName = tokenIdentityStore.pairName(source, b),
    )
    // Klar anderer Name: diese Quelle führt unter dem Kürzel einen anderen Token — keine Abfrage
    if (!nameOk) return Outcome(null, rejected = true)
    var rejected = false
    for (p in pairs) {
        val (close, latest) = seriesClose(chainCandlePair(source, b, p), start) ?: continue
        when (HistoricCloseChain.decide(close, latest, reference, primary = false, nameOk = true)) {
            Verdict.ACCEPT -> return Outcome(close)
            Verdict.REJECT -> rejected = true
            Verdict.MISSING -> Unit
        }
    }
    return Outcome(null, rejected)
}
