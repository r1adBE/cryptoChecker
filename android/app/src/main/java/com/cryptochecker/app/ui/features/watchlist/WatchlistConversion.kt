// «≈ 61’234 CHF» in der Merkliste: umgerechneter Kurs je Zeile und Abrufzeit eines alten Devisenkurses.
// Eigene Datei, weil es reine Rechnung ohne Android-Bezug ist (wie `WatchlistConversion.swift`).
package com.cryptochecker.app.ui.features.watchlist

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.convert.CurrencyConversion
import com.cryptochecker.app.util.PriceFormat

/**
 * «≈ 61’234 CHF» für die Merkliste: nur mit Zielwährung, bekanntem Faktor,
 * gültigem Kurs und wenn die Quote nicht schon die Zielwährung ist.
 */
internal fun convertedPrice(watch: WatchEntity, target: String?, rates: Map<String, Double>): String? {
    if (target == null || CurrencyConversion.sameCurrency(watch.quoteAsset, target)) return null
    val price = watch.lastPrice?.takeIf { it > 0.0 } ?: return null
    val rate = rates[CurrencyConversion.normalize(watch.quoteAsset)] ?: return null
    val value = CurrencyConversion.convert(price, rate) ?: return null
    return "≈ " + PriceFormat.priceWithCurrency(value, target)
}

/**
 * Abrufzeit des Devisenkurses hinter der ≈ Umrechnung von [watch], wenn älter als 3 h
 * ([CurrencyConversion.FX_STALE_MILLIS]; [fxTimes] aus [WatchlistViewModel.convertFxTimes]); sonst null.
 */
internal fun convertedFxTime(watch: WatchEntity, fxTimes: Map<String, Long>, now: Long): Long? =
    fxTimes[CurrencyConversion.normalize(watch.quoteAsset)]?.takeIf { now - it > CurrencyConversion.FX_STALE_MILLIS }
