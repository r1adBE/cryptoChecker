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
 * [FxRateSource]. Beide Quellen haben eigene Zwischenspeicher; Alter der Devisenkurse: [staleFxTime].
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
            QuoteKind.CRYPTO -> CurrencyConversion.quoteToUsd(kind, null, priceSource.cached(listOf(from)).prices[from], usdtUsd = liveUsdtUsd())
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
     * Marktkurs eines Stablecoins in USDT, nur aus dem Zwischenspeicher der Portfolio-Kurse (keine
     * zusätzliche Abfrage); null = keiner bekannt (dann kein Faktor). USD/USDT brauchen keinen.
     */
    private fun stableMarket(code: String): Double? =
        if (!CurrencyConversion.stableNeedsMarket(code)) null else priceSource.cached(listOf(code)).prices[code]

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
                kind, null, if (CurrencyConversion.stableNeedsMarket(code)) priceSource.price(code) else null, code,
                liveUsdtUsd(),
            )
            QuoteKind.FIAT -> CurrencyConversion.quoteToUsd(kind, fxRateSource.usdTo(code), null)
            QuoteKind.CRYPTO -> CurrencyConversion.quoteToUsd(kind, null, priceSource.price(code), usdtUsd = liveUsdtUsd())
            QuoteKind.INVALID -> null
        }
    }
}
