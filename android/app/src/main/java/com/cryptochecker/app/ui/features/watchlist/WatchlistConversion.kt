// «≈ 61’234 CHF» in der Merkliste: umgerechneter Kurs je Zeile und Abrufzeit eines alten Devisenkurses.
// Eigene Datei, weil es reine Rechnung ohne Android-Bezug ist (wie `WatchlistConversion.swift`).
package com.cryptochecker.app.ui.features.watchlist

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.convert.CurrencyConversion
import com.cryptochecker.app.util.PriceFormat

/** Quote für Faktor und Schlüssel der Umrechnung ([CurrencyConversion.conversionQuote]: Aster «U» = USD). */
internal val WatchEntity.conversionQuote: String get() = CurrencyConversion.conversionQuote(exchangeKey, quoteAsset)

/**
 * «≈ 61’234 CHF» für die Merkliste: nur mit Zielwährung, bekanntem Faktor,
 * gültigem Kurs und wenn die Quote nicht schon die Zielwährung ist.
 */
internal fun convertedPrice(watch: WatchEntity, target: String?, rates: Map<String, Double>): String? {
    val quote = watch.conversionQuote
    if (target == null || CurrencyConversion.sameCurrency(quote, target)) return null
    val price = watch.lastPrice?.takeIf { it > 0.0 } ?: return null
    val rate = rates[quote] ?: return null
    val value = CurrencyConversion.convert(price, rate) ?: return null
    return "≈ " + PriceFormat.priceWithCurrency(value, target)
}

/**
 * Vermerk zur USDT-Umrechnung hinter der ≈ Umrechnung von [watch] ([notes] aus [WatchlistLive.convertUsdtNotes]):
 * USDT = 1 angenommen bzw. unsicher (nur über USDC); null = nichts zu vermerken. Gleiche Regel wie iOS.
 */
internal fun convertedUsdtNote(
    watch: WatchEntity,
    target: String?,
    notes: Map<String, com.cryptochecker.app.domain.convert.FiatAlarmGuard.UsdtState>,
): com.cryptochecker.app.domain.convert.FiatAlarmGuard.UsdtState? {
    if (target == null || CurrencyConversion.sameCurrency(watch.conversionQuote, target)) return null
    return notes[watch.conversionQuote]
}

/**
 * Abrufzeit des Devisenkurses hinter der ≈ Umrechnung von [watch], wenn älter als 3 h
 * ([CurrencyConversion.FX_STALE_MILLIS]; [fxTimes] aus [WatchlistLive.convertFxTimes]); sonst null.
 */
internal fun convertedFxTime(watch: WatchEntity, fxTimes: Map<String, Long>, now: Long): Long? =
    fxTimes[watch.conversionQuote]?.takeIf { now - it > CurrencyConversion.FX_STALE_MILLIS }
