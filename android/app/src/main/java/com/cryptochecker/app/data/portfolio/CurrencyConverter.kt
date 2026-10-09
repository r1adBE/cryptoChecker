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
 * Quote → USD: USD-Stablecoins = 1, Fiat über [FxRateSource], alles andere
 * als Krypto über den USDT-Kurs der [PortfolioPriceSource]. Danach USD → Ziel
 * über [FxRateSource]. Beide Quellen haben eigene Zwischenspeicher.
 */
@Singleton
class CurrencyConverter @Inject constructor(
    private val fxRateSource: FxRateSource,
    private val priceSource: PortfolioPriceSource,
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
            val usdToTarget = if (to in CurrencyConversion.USD_STABLES) 1.0 else fxRateSource.usdTo(to)
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
        // Krypto-Kurse gesammelt holen, damit nicht jede Quote einzeln abfragt
        val cryptos = wanted.filter { classify(it) == QuoteKind.CRYPTO }
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
            QuoteKind.USD -> 1.0
            QuoteKind.FIAT -> CurrencyConversion.quoteToUsd(kind, fxRateSource.cached(from), null)
            QuoteKind.CRYPTO -> CurrencyConversion.quoteToUsd(kind, null, priceSource.cached(listOf(from)).prices[from])
            QuoteKind.INVALID -> null
        }
        val usdToTarget = when (classify(to)) {
            QuoteKind.USD -> 1.0
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

    private suspend fun quoteToUsd(quote: String): Double? {
        val code = CurrencyConversion.normalize(quote)
        return when (val kind = classify(code)) {
            QuoteKind.USD -> 1.0
            QuoteKind.FIAT -> CurrencyConversion.quoteToUsd(kind, fxRateSource.usdTo(code), null)
            QuoteKind.CRYPTO -> CurrencyConversion.quoteToUsd(kind, null, priceSource.price(code))
            QuoteKind.INVALID -> null
        }
    }
}
