package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.domain.convert.CurrencyConversion
import com.cryptochecker.app.domain.convert.CurrencyConversion.QuoteKind
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * «≈ Umrechnung»: Faktor von der Quote-Währung eines Paars in eine Zielwährung
 * (z. B. USDT → CHF, EUR → CHF, BTC → CHF).
 *
 * Quote → USD: Stablecoins mit Marktkurs ([CurrencyConversion.stableToUsd]: USD/USDT 1, andere
 * ihr Marktkurs in USDT von der [PortfolioPriceSource] — ohne Kurs kein Faktor, wie die
 * Portfolio-Regel [com.cryptochecker.app.domain.portfolio.PortfolioStables]), Fiat über [FxRateSource],
 * alles andere als Krypto über den USDT-Kurs der [PortfolioPriceSource]. Danach USD → Ziel über
 * [FxRateSource]. Beide Quellen haben eigene Zwischenspeicher; Alter der Devisenkurse: [staleFxTime]. Krypto- und
 * Stablecoin-Kurse älter als [CurrencyConversion.QUOTE_MAX_AGE_MILLIS] ergeben keinen Faktor ([freshMarket]).
 * USDT zum Marktkurs (Owner-Entscheid Okt. 2026): USDT und alles, was über USDT bewertet wird, × Live-Kurs USDT/USD
 * ([DepegReadingStore.liveUsdtUsd], gemessen mit Portfolio oder Depeg-Alarm); ohne frische Messung 1 (bisherige Rechnung).
 */
@Singleton
class CurrencyConverter @Inject constructor(
    private val fxRateSource: FxRateSource,
    private val priceSource: PortfolioPriceSource,
    private val depegReadings: com.cryptochecker.app.domain.alarm.DepegReadingStore,
) {
    private val fiat: List<String> get() = FxRateSource.CURRENCIES

    fun classify(quote: String): QuoteKind = CurrencyConversion.classify(quote, fiat)

    /** Faktor Quote → Ziel; gleiche Währung = 1.0, null wenn ein Kurs fehlt. */
    suspend fun rate(quote: String, target: String): Double? {
        if (CurrencyConversion.sameCurrency(quote, target)) return 1.0
        val to = CurrencyConversion.normalize(target)
        if (classify(to) != QuoteKind.USD && classify(to) != QuoteKind.FIAT) return null
        return try {
            val quoteUsd = quoteToUsd(quote) ?: return null
            val usdToTarget = if (to in CurrencyConversion.USD_STABLES) usdToStable(to) else fxRateSource.usdTo(to)
            CurrencyConversion.rate(quoteUsd, usdToTarget)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "Umrechnung %s → %s nicht möglich", quote, target)
            null
        }
    }

    /** Faktoren für mehrere Quote-Währungen; fehlende bleiben weg. Schlüssel in Grossbuchstaben. */
    suspend fun rates(quotes: Collection<String>, target: String): Map<String, Double> {
        val wanted = quotes.map { CurrencyConversion.normalize(it) }.filter { it.isNotEmpty() }.distinct()
        // Krypto- und Stablecoin-Kurse (ausser USD/USDT) gesammelt holen, damit nicht jede Quote einzeln abfragt
        val cryptos = wanted.filter {
            val kind = classify(it)
            kind == QuoteKind.CRYPTO || (kind == QuoteKind.USD && CurrencyConversion.stableNeedsMarket(it))
        }
        if (cryptos.isNotEmpty()) {
            runCatching { priceSource.prices(cryptos) }.onFailure { if (it is CancellationException) throw it }
        }
        val result = HashMap<String, Double>()
        for (quote in wanted) rate(quote, target)?.let { result[quote] = it }
        return result
    }

    /** Faktor nur aus den Zwischenspeichern, ohne Netz (für den ersten Aufbau). */
    fun cachedRate(quote: String, target: String): Double? {
        if (CurrencyConversion.sameCurrency(quote, target)) return 1.0
        val to = CurrencyConversion.normalize(target)
        val from = CurrencyConversion.normalize(quote)
        val quoteUsd = when (val kind = classify(from)) {
            QuoteKind.USD -> CurrencyConversion.quoteToUsd(kind, null, stableMarket(from), from, liveUsdtUsd())
            QuoteKind.FIAT -> CurrencyConversion.quoteToUsd(kind, fxRateSource.cached(from), null)
            QuoteKind.CRYPTO -> CurrencyConversion.quoteToUsd(kind, null, freshMarket(priceSource.cached(listOf(from)), from), usdtUsd = liveUsdtUsd())
            QuoteKind.INVALID -> null
        }
        val usdToTarget = when (classify(to)) {
            QuoteKind.USD -> usdToStable(to)
            QuoteKind.FIAT -> fxRateSource.cached(to)
            else -> null
        }
        return CurrencyConversion.rate(quoteUsd, usdToTarget)
    }

    /** Wie [cachedRate] für mehrere Quote-Währungen. */
    fun cachedRates(quotes: Collection<String>, target: String): Map<String, Double> =
        quotes.map { CurrencyConversion.normalize(it) }.filter { it.isNotEmpty() }.distinct()
            .mapNotNull { q -> cachedRate(q, target)?.let { q to it } }
            .toMap()

    /**
     * Devisenkurs im Faktor [quote] → [target], wenn älter als 3 h: seine Abrufzeit
     * ([CurrencyConversion.staleFxTime]); null = frisch oder kein Devisenkurs beteiligt (USD, Stablecoins,
     * Krypto in USD).
     */
    fun staleFxTime(quote: String, target: String, now: Long = System.currentTimeMillis()): Long? {
        if (CurrencyConversion.sameCurrency(quote, target)) return null
        val from = CurrencyConversion.normalize(quote)
        val to = CurrencyConversion.normalize(target)
        val quoteAt = if (classify(from) == QuoteKind.FIAT) fxRateSource.fetchedAt(from) else null
        val targetAt = if (classify(to) == QuoteKind.FIAT) fxRateSource.fetchedAt(to) else null
        return CurrencyConversion.staleFxTime(quoteAt, targetAt, now)
    }

    /** Wie [staleFxTime] für mehrere Quote-Währungen; nur alte Kurse. Schlüssel in Grossbuchstaben. */
    fun staleFxTimes(quotes: Collection<String>, target: String, now: Long = System.currentTimeMillis()): Map<String, Long> =
        quotes.map { CurrencyConversion.normalize(it) }.filter { it.isNotEmpty() }.distinct()
            .mapNotNull { q -> staleFxTime(q, target, now)?.let { q to it } }
            .toMap()

    /**
     * Ältester Zeitpunkt (laut Quelle, [FxRateSource.rateTime]) der Devisenkurse im Faktor [quote] → [target] — für das
     * Alter im Fiat-Alarm ([com.cryptochecker.app.domain.convert.FiatAlarmGuard.fxTooOld]); null = kein Devisenkurs beteiligt.
     */
    fun fxRateTime(quote: String, target: String): Long? {
        if (CurrencyConversion.sameCurrency(quote, target)) return null
        val from = CurrencyConversion.normalize(quote)
        val to = CurrencyConversion.normalize(target)
        val quoteAt = if (classify(from) == QuoteKind.FIAT) fxRateSource.rateTime(from) else null
        val targetAt = if (classify(to) == QuoteKind.FIAT) fxRateSource.rateTime(to) else null
        return listOfNotNull(quoteAt, targetAt).minOrNull()
    }

    /**
     * Stand von USDT/USD im Faktor [quote] → [target] ([com.cryptochecker.app.domain.convert.FiatAlarmGuard.usdtState]):
     * nicht beteiligt, gemessen, angenommen (USDT = 1) oder unsicher (nur über USDC, USDC/USDT mehr als 1 % neben 1).
     */
    fun usdtState(quote: String, target: String, now: Long = System.currentTimeMillis()) =
        com.cryptochecker.app.domain.convert.FiatAlarmGuard.usdtState(
            com.cryptochecker.app.domain.convert.FiatAlarmGuard.involvesUsdt(quote, target, fiat),
            depegReadings.reading.value,
            now,
        )

    /** Wie [usdtState] für mehrere Quote-Währungen; nur angenommene bzw. unsichere. Schlüssel in Grossbuchstaben. */
    fun usdtNotes(quotes: Collection<String>, target: String, now: Long = System.currentTimeMillis()) =
        quotes.map { CurrencyConversion.normalize(it) }.filter { it.isNotEmpty() }.distinct()
            .mapNotNull { q ->
                usdtState(q, target, now).takeIf {
                    it == com.cryptochecker.app.domain.convert.FiatAlarmGuard.UsdtState.ASSUMED ||
                        it == com.cryptochecker.app.domain.convert.FiatAlarmGuard.UsdtState.UNSURE
                }?.let { q to it }
            }
            .toMap()

    /**
     * Marktkurs eines Stablecoins in USDT, nur aus dem Zwischenspeicher der Portfolio-Kurse (keine
     * zusätzliche Abfrage); null = keiner bekannt (dann kein Faktor). USD/USDT brauchen keinen.
     */
    private fun stableMarket(code: String): Double? =
        if (!CurrencyConversion.stableNeedsMarket(code)) null else freshMarket(priceSource.cached(listOf(code)), code)

    /**
     * Kurs von [code] aus [prices], nur wenn höchstens [CurrencyConversion.QUOTE_MAX_AGE_MILLIS] alt: Die Kursquelle
     * behält bei einem Fehler den letzten bekannten Kurs (für das Portfolio richtig) — für die Umrechnung von Alarmen
     * und «≈» wäre ein Kurs von gestern still falsch.
     */
    private fun freshMarket(prices: PortfolioPrices, code: String, now: Long = System.currentTimeMillis()): Double? =
        CurrencyConversion.freshQuotePrice(prices.prices[code], prices.times[code], now)

    /** Live-Kurs USDT/USD ([DepegReadingStore.liveUsdtUsd]); null = keiner (USDT gilt 1, bisherige Rechnung). */
    private fun liveUsdtUsd(): Double? = depegReadings.liveUsdtUsd()

    /**
     * Einheiten der Ziel-Stablecoin-Währung [code] je USD: USDT = 1 ÷ USDT/USD (Marktkurs, ohne Messung 1); USD und
     * andere Stablecoins 1 (wie bisher).
     */
    private fun usdToStable(code: String): Double =
        if (code == "USDT") 1.0 / (CurrencyConversion.stableToUsd("USDT", null, liveUsdtUsd()) ?: 1.0) else 1.0

    private suspend fun quoteToUsd(quote: String): Double? {
        val code = CurrencyConversion.normalize(quote)
        return when (val kind = classify(code)) {
            // Stablecoin ausser USD/USDT: Marktkurs (wie das Portfolio), ohne Kurs kein Faktor; USDT × USDT/USD
            QuoteKind.USD -> CurrencyConversion.quoteToUsd(
                kind, null, if (CurrencyConversion.stableNeedsMarket(code)) freshMarket(priceSource.prices(listOf(code)), code) else null,
                code, liveUsdtUsd(),
            )
            QuoteKind.FIAT -> CurrencyConversion.quoteToUsd(kind, fxRateSource.usdTo(code), null)
            // Höchstens 15 min alt: scheitert die Abfrage, bleibt sonst der letzte Kurs beliebig lange stehen
            QuoteKind.CRYPTO -> CurrencyConversion.quoteToUsd(kind, null, freshMarket(priceSource.prices(listOf(code)), code), usdtUsd = liveUsdtUsd())
            QuoteKind.INVALID -> null
        }
    }
}
