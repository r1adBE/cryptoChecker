// Vorbelegen im Erfassen-Blatt (Teil von [TxFormState]): heutiger Kurs, geprüfter Tagesschluss eines vergangenen Tags,
// Kurs des Gebühr-Coins und Tageskurs der Anzeigewährung. Eigene Datei, weil die Abläufe mit Wartezeit und Abruf
// vom Zustand getrennt lesbar sein sollen; Erweiterungsfunktionen, der Zustand bleibt in der Klasse.
package com.cryptochecker.app.ui.features.portfolio

import com.cryptochecker.app.domain.portfolio.PortfolioDecimal
import com.cryptochecker.app.domain.portfolio.PortfolioTxForm
import com.cryptochecker.app.util.PriceFormat
import kotlinx.coroutines.delay
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Kurs vorbelegen, sobald ein (anderer) Coin feststeht; ein getippter Preis bleibt, der Kurs dient dann nur als
 * Hinweis für eine mehrdeutige Eingabe. Nicht für einen anderen Tag. [coin]/[coinValid] beim Start des Effekts.
 */
internal suspend fun TxFormState.prefillPrice(coin: String, coinValid: Boolean, currentPrice: suspend (String) -> Double?) {
    if (isEdit || !coinValid || coin == pricedCoin) return
    // Erst nach kurzer Tipp-Pause, nicht bei jedem Buchstaben
    delay(400)
    val current = currentPrice(coin)?.takeIf { it.isFinite() && it > 0.0 }
    pricedCoin = coin
    priceHint = current
    val today = localDate(time) == LocalDate.now()
    if (!PortfolioTxForm.mayPrefill(isEdit, today)) {
        // Anderer Tag: kein heutiger Kurs im Feld (ein noch vorbelegter wird geleert)
        if (priceAuto) { priceText = ""; priceAuto = false }
    } else if (!entryFiat && (priceText.isBlank() || priceAuto)) {
        priceText = current?.let { PriceFormat.amountForInput(PortfolioDecimal.of(it), decimalSeparator) }.orEmpty()
        priceAuto = current != null
    }
}

/** Belohnung/Einzahlung an einem vergangenen Tag: geprüften Tagesschluss vorbelegen (markiert). */
internal suspend fun TxFormState.prefillHistoricClose(coin: String, coinValid: Boolean, historicClose: suspend (String, LocalDate) -> BigDecimal?) {
    // Ein vorbelegter Tagesschluss (anderer Coin) wird ersetzt, ein getippter Preis bleibt
    val replaceable = priceText.isBlank() || priceHistoricDay != null
    // Eingabe in der Anzeigewährung: kein USDT-Tagesschluss ins Feld
    if (entryFiat || !coinValid || !PortfolioTxForm.mayPrefillHistoricClose(type, isEdit, localDate(time) == LocalDate.now(), replaceable)) {
        return
    }
    if (priceHistoricDay != null) { priceText = ""; priceHistoricDay = null }
    delay(400)
    val day = localDate(time)
    val close = historicClose(coin, day) ?: return
    if (priceText.isBlank()) {
        priceText = PriceFormat.amountForInput(close, decimalSeparator)
        priceHint = close.toDouble()
        priceAuto = false
        priceHistoricDay = PortfolioFormat.date(time)
    }
}

/** Gebühr-Coin: Preis mit dem aktuellen Kurs vorbelegen — nur bei einer Transaktion von heute. */
internal suspend fun TxFormState.prefillFeeCoinPrice(feeCoinSymbol: String, coin: String, currentPrice: suspend (String) -> Double?) {
    val validFeeCoin = feeCoinSymbol.length in 1..15 && feeCoinSymbol.all { it.isLetterOrDigit() } && feeCoinSymbol != coin
    if (!validFeeCoin) return
    if (feeCoinPriceAuto) { feeCoinPriceText = ""; feeCoinPriceAuto = false }
    if (!PortfolioTxForm.mayPrefillFeeCoinPrice(isEdit, localDate(time) == LocalDate.now(), feeCoinPriceText.isBlank())) {
        return
    }
    delay(400)
    val current = currentPrice(feeCoinSymbol)?.takeIf { it.isFinite() && it > 0.0 } ?: return
    if (feeCoinPriceText.isBlank()) {
        feeCoinPriceText = PriceFormat.amountForInput(PortfolioDecimal.of(current), decimalSeparator)
        feeCoinPriceAuto = true
    }
}

/** Tageskurs USD → Anzeigewährung für das Datum der Transaktion (heute: aktueller Kurs), nur in der Anzeigewährung. */
internal suspend fun TxFormState.loadEntryFx(currency: String, day: LocalDate, load: suspend (String, LocalDate) -> EntryFx?) {
    if (!entryFiat || currency == "USD") return
    val key = "$currency|$day"
    if (entryFxKey != key || entryFxState == EntryFxState.LOADING || entryFxState == null) {
        entryFxKey = key
        entryFxState = EntryFxState.LOADING
        entryFx = null
        val loaded = load(currency, day)
        entryFx = loaded
        entryFxState = if (loaded != null) EntryFxState.READY else EntryFxState.FAILED
    }
    val rate = entryFx?.rate
    if (pendingToFiat) {
        pendingToFiat = false
        if (rate == null) {
            // Kein Kurs: bei USDT bleiben (Felder unverändert)
            entryFiat = false
        } else {
            priceText = convertEntry(priceText, decimalSeparator, priceHint) { PortfolioTxForm.usdtToFiat(it, rate) }
            swapValueText = convertEntry(swapValueText, decimalSeparator, null) { PortfolioTxForm.usdtToFiat(it, rate) }
            priceAuto = false
            priceHistoricDay = null
        }
    }
}
