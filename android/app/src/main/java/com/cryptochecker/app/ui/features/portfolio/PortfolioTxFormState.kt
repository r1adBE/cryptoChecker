// Zustand des Erfassen-Blatts (`PortfolioTxSheet`): alle Eingaben als Compose-Zustand, über `rememberSaveable`
// gesichert ([TxFormState.saver]), dazu die je Durchgang abgeleiteten Werte ([TxFormState.derive]) und die Abläufe
// (Vorbelegen, Datumswechsel, Umschalten der Eingabewährung, Entwurf zum Speichern). Die Regeln der Prüfung stehen in
// `PortfolioTxForm.check` (domain, ohne Oberfläche testbar); hier nur das Lesen der Texte.
package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.cryptochecker.app.data.local.model.NOTE_MAX
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioDecimal
import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioTxForm
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.settings.inputDecimalSeparator
import com.cryptochecker.app.util.PriceFormat
import kotlinx.coroutines.delay
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/** Zustand des Blatts für [initial]; im Blatt über [rememberTxFormState] (übersteht Drehen und Prozess-Ende). */
@Composable
internal fun rememberTxFormState(initial: TxDraft): TxFormState {
    // Dezimalzeichen der Region (Sprache der App, Region des Geräts) — wie bei den Alarmen
    val decimalSeparator = remember { inputDecimalSeparator() }
    return rememberSaveable(saver = TxFormState.saver(initial, decimalSeparator)) { TxFormState(initial, decimalSeparator) }
}

/**
 * Eingaben des Erfassen-Blatts. Gesichert werden die Felder und Merker; der geladene Devisenkurs ([entryFx]) nicht —
 * er wird nach dem Wiederherstellen neu geladen.
 */
@Stable
internal class TxFormState(val initial: TxDraft, val decimalSeparator: Char) {
    val isEdit = initial.id != 0L
    // Schenkung an Ehe-/Lebenspartner bzw. Zins nur bei der passenden Art des Entwurfs
    val initialSpouse = initial.giftToSpouse && initial.type == PortfolioTxType.GIFT
    val initialInterest = initial.interest && initial.type == PortfolioTxType.REWARD

    var type by mutableStateOf(initial.type)
    var coinQuery by mutableStateOf(initial.coin)
    var amountText by mutableStateOf(PriceFormat.amountForInput(initial.amount, decimalSeparator))
    var priceText by mutableStateOf(PriceFormat.amountForInput(initial.priceUsdt, decimalSeparator))
    // Kurs, an dem ein mehrdeutiger Preis («60.000») gemessen wird: gespeicherter bzw. aktueller Kurs
    var priceHint by mutableStateOf(initial.priceUsdt?.takeIf { it.signum() > 0 }?.toDouble())
    // Vorbelegter Preis darf bei einem Coin-Wechsel ersetzt werden, ein getippter nicht
    var priceAuto by mutableStateOf(!isEdit && initial.priceUsdt != null)
    // Preis = geprüfter Tagesschluss eines vergangenen Tags (Belohnung/Einzahlung), markiert; Tag als Text
    var priceHistoricDay by mutableStateOf<String?>(null)
    var pricedCoin by mutableStateOf(if (initial.priceUsdt != null || isEdit) initial.coin else "")
    var time by mutableLongStateOf(initial.time)
    // Einzahlung: ursprüngliches Kaufdatum (optional, ≤ Datum der Einzahlung) für die Haltedauer im Steuer-Export
    var acquiredTime by mutableStateOf(initial.acquiredTime)
    // Höchstens 120 Zeichen wie die Notiz in der Merkliste (eine längere alte Notiz wird beim Speichern gekürzt)
    var note by mutableStateOf(initial.note.orEmpty().take(NOTE_MAX))
    // Gebühr und Börse (D1): zugeklappt, ausser beim Bearbeiten mit Angaben
    var feeText by mutableStateOf(PriceFormat.amountForInput(initial.fee, decimalSeparator))
    var feeUnit by mutableStateOf(initial.feeUnit)
    var exchange by mutableStateOf(initial.exchange.orEmpty())
    // Umbuchung: von / nach (freier Text, Vorschläge aus den Börsen)
    var transferFrom by mutableStateOf(initial.transferFrom.orEmpty())
    var transferTo by mutableStateOf(initial.transferTo.orEmpty())
    // Schenkung an Ehe-/Lebenspartner (eigenes Feld, nur bei einer Schenkung)
    var giftToSpouse by mutableStateOf(initialSpouse)
    // Belohnung als Zins (Lending; AT: Einkunft beim Zufluss) — eigenes Feld, nur bei einer Belohnung
    var interest by mutableStateOf(initialInterest)
    // Gebühr in einem dritten Coin
    var feeCoinText by mutableStateOf(initial.feeCoin.orEmpty())
    var feeCoinAmountText by mutableStateOf(PriceFormat.amountForInput(initial.feeCoinAmount, decimalSeparator))
    var feeCoinPriceText by mutableStateOf(PriceFormat.amountForInput(initial.feeCoinPriceUsdt, decimalSeparator))
    var feeCoinPriceAuto by mutableStateOf(false)
    // Tausch: erhaltener Coin und Menge, Wert des Tauschs (optional, gesamt)
    var toCoinQuery by mutableStateOf(initial.toCoin.orEmpty())
    var toAmountText by mutableStateOf(PriceFormat.amountForInput(initial.toAmount, decimalSeparator))
    var swapValueText by mutableStateOf(PriceFormat.amountForInput(initial.swapValueUsdt, decimalSeparator))
    // K-2: Preis/Wert in der Anzeigewährung eingeben (gespeichert wird immer USDT, umgerechnet mit dem Tageskurs)
    var entryFiat by mutableStateOf(false)
    // Umschalten auf die Anzeigewährung: Felder (noch USDT) umrechnen, sobald der Kurs da ist
    var pendingToFiat by mutableStateOf(false)
    // Kauf: auch die Notiz steht im zugeklappten Abschnitt «Gebühr, Börse, Notiz»
    var showLedger by mutableStateOf(
        initial.fee != null || !initial.exchange.isNullOrBlank() || initial.feeCoin != null ||
            (initial.type == PortfolioTxType.BUY && !initial.note.isNullOrBlank())
    )
    var tried by mutableStateOf(false)

    // Nicht gesichert: Devisenkurs der Eingabe in der Anzeigewährung (wird neu geladen)
    var entryFx by mutableStateOf<Pair<BigDecimal, LocalDate>?>(null)
    var entryFxKey by mutableStateOf<String?>(null)
    var entryFxState by mutableStateOf<EntryFxState?>(null)

    /** Werte dieses Durchgangs: gelesene Felder, Prüfung ([PortfolioTxForm.check]) und Änderungen. */
    fun derive(currency: String): TxFormDerived {
        val sep = decimalSeparator
        val fields = PortfolioTxForm.fields(type)
        val isSwap = type == PortfolioTxType.SWAP
        // Menge ohne Kurs-Hinweis (mehrdeutig → Dezimalzeichen der Region), Preis am Kurs gemessen.
        // Exakt gelesen (BigDecimal): 0.00000001 BTC = 1 Satoshi, ohne Gleitkomma-Rundung
        val amount = PriceFormat.parseDecimalAmount(amountText, sep)?.takeIf { it.signum() > 0 }
        // Eingabe in der Anzeigewährung: nur mit Preis-/Wertfeld und nicht für USD (USDT gilt als USD)
        val day = localDate(time)
        val fiatPossible = currency != "USD" && (fields.price || isSwap)
        val fxRate = entryFx?.first?.takeIf { entryFxState == EntryFxState.READY && entryFxKey == "$currency|$day" }
        val fiatActive = entryFiat && fiatPossible && !pendingToFiat
        val priceHintEntry = if (fiatActive) fxRate?.let { r -> priceHint?.times(r.toDouble()) } else priceHint
        val priceEntered: BigDecimal? = if (priceText.isBlank()) null else PriceFormat.parseDecimalAmount(priceText, sep, priceHintEntry)
        // Gespeichert wird USDT: Eingabe in der Anzeigewährung ÷ Tageskurs
        val price: BigDecimal? = if (fiatActive) PortfolioTxForm.fiatToUsdt(priceEntered, fxRate) else priceEntered
        val toAmount = PriceFormat.parseDecimalAmount(toAmountText, sep)?.takeIf { it.signum() > 0 }
        val swapValueEntered = if (swapValueText.isBlank()) null else PriceFormat.parseDecimalAmount(swapValueText, sep)
        val swapValue = if (fiatActive) PortfolioTxForm.fiatToUsdt(swapValueEntered, fxRate) else swapValueEntered
        val feeCoinAmount = PriceFormat.parseDecimalAmount(feeCoinAmountText, sep)
        val feeCoinPrice = if (feeCoinPriceText.isBlank()) null else PriceFormat.parseDecimalAmount(feeCoinPriceText, sep)
        val check = PortfolioTxForm.check(
            PortfolioTxForm.Entry(
                type = type,
                coinQuery = coinQuery,
                amount = amount,
                priceBlank = priceText.isBlank(),
                priceEntered = priceEntered,
                price = price,
                imported = !initial.importKey.isNullOrBlank(),
                feeBlank = feeText.isBlank(),
                fee = if (feeText.isBlank()) null else PriceFormat.parseDecimalAmount(feeText, sep),
                feeUnit = feeUnit,
                feeCoin = feeCoinText,
                feeCoinAmountBlank = feeCoinAmountText.isBlank(),
                feeCoinAmount = feeCoinAmount,
                feeCoinPriceBlank = feeCoinPriceText.isBlank(),
                feeCoinPrice = feeCoinPrice,
                toCoinQuery = toCoinQuery,
                toAmount = toAmount,
                swapValueBlank = swapValueText.isBlank(),
                swapValue = swapValue,
            )
        )
        // Gespeicherte Gebühr, die das Blatt für diese Art nicht zeigen kann (z. B. CSV-Import: Auszahlung mit
        // USDT-Gebühr, Einzahlung mit Gebühr): beim Bearbeiten ohne Änderung von Art und Gebühr unverändert
        // behalten — sonst würde sie still gelöscht oder als Gebühr im Coin umgedeutet
        val keepStoredFee = isEdit && type == initial.type && initial.fee != null &&
            feeText == PriceFormat.amountForInput(initial.fee, sep) &&
            !(fields.feeWithUnit || (fields.coinFeeOnly && initial.feeUnit == PortfolioFeeUnit.COIN))
        return TxFormDerived(
            check = check,
            amount = amount,
            day = day,
            fiatPossible = fiatPossible,
            fxRate = fxRate,
            fiatActive = fiatActive,
            price = price,
            toAmount = toAmount,
            swapValue = swapValue,
            keepStoredFee = keepStoredFee,
            feeCoinSymbol = PortfolioCalculator.normalizeCoin(feeCoinText),
            feeCoinAmount = feeCoinAmount,
            feeCoinPrice = feeCoinPrice,
            // Der vorbelegte Kurs ist der heutige — gilt nur für eine Transaktion von heute (Ortszeit)
            isToday = localDate(time) == LocalDate.now(),
            dirty = isDirty(),
            type = type,
        )
    }

    /** Vom Nutzer geändert? Vorbelegte Preise (heutiger Kurs, Tagesschluss) zählen nicht als Änderung. */
    private fun isDirty(): Boolean {
        val sep = decimalSeparator
        return type != initial.type || coinQuery != initial.coin ||
            amountText != PriceFormat.amountForInput(initial.amount, sep) ||
            (!priceAuto && priceHistoricDay == null && priceText != PriceFormat.amountForInput(initial.priceUsdt, sep)) ||
            time != initial.time || acquiredTime != initial.acquiredTime || note != initial.note.orEmpty().take(NOTE_MAX) ||
            feeText != PriceFormat.amountForInput(initial.fee, sep) || feeUnit != initial.feeUnit ||
            exchange != initial.exchange.orEmpty() || transferFrom != initial.transferFrom.orEmpty() ||
            transferTo != initial.transferTo.orEmpty() || giftToSpouse != initialSpouse || interest != initialInterest ||
            feeCoinText != initial.feeCoin.orEmpty() ||
            feeCoinAmountText != PriceFormat.amountForInput(initial.feeCoinAmount, sep) ||
            (!feeCoinPriceAuto && feeCoinPriceText != PriceFormat.amountForInput(initial.feeCoinPriceUsdt, sep)) ||
            toCoinQuery != initial.toCoin.orEmpty() ||
            toAmountText != PriceFormat.amountForInput(initial.toAmount, sep) ||
            swapValueText != PriceFormat.amountForInput(initial.swapValueUsdt, sep)
    }

    /** Preis getippt: kein vorbelegter Kurs bzw. Tagesschluss mehr. */
    fun onPriceText(text: String) {
        priceText = text
        priceAuto = false
        priceHistoricDay = null
    }

    /** Preis des Gebühr-Coins getippt: nicht mehr vorbelegt. */
    fun onFeeCoinPriceText(text: String) {
        feeCoinPriceText = text
        feeCoinPriceAuto = false
    }

    /**
     * Neues Datum gewählt; [coinValid]/[coin] aus dem letzten Durchgang (zum Wieder-Vorbelegen mit dem heutigen Kurs).
     */
    fun onDateSelected(date: LocalDate, coinValid: Boolean, coin: String) {
        time = timeFor(date, initial.time)
        // Ursprüngliches Kaufdatum höchstens das Datum der Einzahlung
        acquiredTime = acquiredTime?.coerceAtMost(time)
        // Tagesschluss gehört zum alten Tag: leeren (der neue wird geladen)
        if (priceHistoricDay != null) { priceText = ""; priceHistoricDay = null }
        // Heutiger Kurs des Gebühr-Coins gilt nur für heute
        if (feeCoinPriceAuto && date != LocalDate.now()) { feeCoinPriceText = ""; feeCoinPriceAuto = false }
        // Vorbelegter heutiger Kurs gilt nur für heute: anderer Tag → leeren, zurück auf heute → wieder vorbelegen
        val action = PortfolioTxForm.onDateChange(
            isEdit = isEdit,
            isToday = date == LocalDate.now(),
            priceAuto = priceAuto,
            priceBlank = priceText.isBlank(),
        )
        when (action) {
            PortfolioTxForm.PriceAction.CLEAR -> { priceText = ""; priceAuto = false }
            PortfolioTxForm.PriceAction.PREFILL -> {
                val current = priceHint?.takeIf { !entryFiat && coinValid && coin == pricedCoin && it.isFinite() && it > 0.0 }
                if (current != null) {
                    priceText = PriceFormat.amountForInput(PortfolioDecimal.of(current), decimalSeparator)
                    priceAuto = true
                }
            }
            PortfolioTxForm.PriceAction.KEEP -> Unit
        }
    }

    /** Ursprüngliches Kaufdatum gewählt: unverändertes Datum behält die Uhrzeit, sonst 12:00 Ortszeit; nie nach der Einzahlung. */
    fun onAcquiredSelected(date: LocalDate) {
        val original = acquiredTime ?: initial.acquiredTime
        val picked = if (original != null && localDate(original) == date) original
        else date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        acquiredTime = picked.coerceAtMost(time)
    }

    /** Zurück auf USDT: Felder umrechnen (ohne Kurs leeren — sonst stünde ein Fiat-Betrag als USDT da). */
    fun switchToUsdt(d: TxFormDerived) {
        if (entryFiat) {
            if (d.fiatActive) {
                priceText = d.price?.let { PriceFormat.amountForInput(roundForInput(it), decimalSeparator) }.orEmpty()
                swapValueText = d.swapValue?.let { PriceFormat.amountForInput(roundForInput(it), decimalSeparator) }.orEmpty()
                priceAuto = false
                priceHistoricDay = null
            }
            entryFiat = false
            pendingToFiat = false
        }
    }

    /** Auf die Anzeigewährung: umgerechnet wird, sobald der Kurs da ist ([loadEntryFx]). */
    fun switchToFiat() {
        if (!entryFiat) { entryFiat = true; pendingToFiat = true }
    }

    /**
     * Kurs vorbelegen, sobald ein (anderer) Coin feststeht; ein getippter Preis bleibt, der Kurs dient dann nur als
     * Hinweis für eine mehrdeutige Eingabe. Nicht für einen anderen Tag. [coin]/[coinValid] beim Start des Effekts.
     */
    suspend fun prefillPrice(coin: String, coinValid: Boolean, currentPrice: suspend (String) -> Double?) {
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
    suspend fun prefillHistoricClose(coin: String, coinValid: Boolean, historicClose: suspend (String, LocalDate) -> BigDecimal?) {
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
    suspend fun prefillFeeCoinPrice(feeCoinSymbol: String, coin: String, currentPrice: suspend (String) -> Double?) {
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

    /** K-2: Tageskurs USD → Anzeigewährung für das Datum der Transaktion (heute: aktueller Kurs), nur in der Anzeigewährung. */
    suspend fun loadEntryFx(currency: String, day: LocalDate, load: suspend (String, LocalDate) -> Pair<BigDecimal, LocalDate>?) {
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
        val rate = entryFx?.first
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

    /** Devisenkurs für diesen Tag nicht ladbar: Anzeigewährung gesperrt (mit Hinweis). */
    fun fxFailed(currency: String, day: LocalDate): Boolean =
        entryFxState == EntryFxState.FAILED && entryFxKey == "$currency|$day"

    /** Entwurf zum Speichern: nur die Felder der gewählten Art; Herkunft (CSV-Import) bleibt erhalten. */
    fun toDraft(d: TxFormDerived): TxDraft {
        val fields = d.fields
        val isSwap = d.isSwap
        val third = d.thirdFee == PortfolioTxForm.ThirdFee.COMPLETE || d.thirdFee == PortfolioTxForm.ThirdFee.INCOMPLETE
        // Gebühr im dritten Coin, die diese Art im Blatt nicht zeigt (CSV-Import): bei gleicher Art behalten
        val keepThird = !fields.thirdCoinFee && isEdit && type == initial.type && initial.feeCoin != null
        return initial.copy(
            coin = d.coin,
            type = type,
            amount = d.amount,
            priceUsdt = if (fields.price) d.price else null,
            time = time,
            note = note.trim().takeIf { it.isNotEmpty() },
            fee = if (d.keepStoredFee) initial.fee else d.fee?.takeIf { it.signum() > 0 },
            feeUnit = if (d.keepStoredFee) initial.feeUnit else d.effectiveFeeUnit,
            exchange = if (fields.exchange) exchange.trim().takeIf { it.isNotEmpty() } else null,
            transferFrom = if (fields.route) transferFrom.trim().takeIf { it.isNotEmpty() } else null,
            transferTo = if (fields.route) transferTo.trim().takeIf { it.isNotEmpty() } else null,
            giftToSpouse = type == PortfolioTxType.GIFT && giftToSpouse,
            interest = type == PortfolioTxType.REWARD && interest,
            // Gebühr-Zeile bleibt eine, solange die Art «Verkauf» bleibt
            feeRow = type == PortfolioTxType.SELL && initial.feeRow,
            feeCoin = if (keepThird) initial.feeCoin else if (third) d.feeCoinSymbol else null,
            feeCoinAmount = if (keepThird) initial.feeCoinAmount else if (third) d.feeCoinAmount else null,
            feeCoinPriceUsdt = if (keepThird) initial.feeCoinPriceUsdt else if (third && !d.feeInReceived) d.feeCoinPrice else null,
            // Nur bei einer Einzahlung, nie nach ihrem Datum
            acquiredTime = if (type == PortfolioTxType.DEPOSIT) acquiredTime?.coerceAtMost(time) else null,
            // Tausch: erhaltener Coin, Menge und Wert (USDT; leer = unbekannt)
            toCoin = if (isSwap) d.toCoin else null,
            toAmount = if (isSwap) d.toAmount else null,
            swapValueUsdt = if (isSwap) d.swapValue?.takeIf { it.signum() >= 0 } else null,
        )
    }

    companion object {
        /** Sichert die Felder und Merker in fester Reihenfolge (Bundle-taugliche Werte: Text, Zahlen, Enums). */
        fun saver(initial: TxDraft, decimalSeparator: Char): Saver<TxFormState, Any> = listSaver(
            save = { s ->
                listOf<Any?>(
                    s.type, s.coinQuery, s.amountText, s.priceText, s.priceHint, s.priceAuto, s.priceHistoricDay,
                    s.pricedCoin, s.time, s.acquiredTime, s.note, s.feeText, s.feeUnit, s.exchange, s.transferFrom,
                    s.transferTo, s.giftToSpouse, s.interest, s.feeCoinText, s.feeCoinAmountText, s.feeCoinPriceText,
                    s.feeCoinPriceAuto, s.toCoinQuery, s.toAmountText, s.swapValueText, s.entryFiat, s.pendingToFiat,
                    s.showLedger, s.tried,
                )
            },
            restore = { v ->
                TxFormState(initial, decimalSeparator).apply {
                    type = v[0] as PortfolioTxType
                    coinQuery = v[1] as String
                    amountText = v[2] as String
                    priceText = v[3] as String
                    priceHint = v[4] as Double?
                    priceAuto = v[5] as Boolean
                    priceHistoricDay = v[6] as String?
                    pricedCoin = v[7] as String
                    time = v[8] as Long
                    acquiredTime = v[9] as Long?
                    note = v[10] as String
                    feeText = v[11] as String
                    feeUnit = v[12] as PortfolioFeeUnit
                    exchange = v[13] as String
                    transferFrom = v[14] as String
                    transferTo = v[15] as String
                    giftToSpouse = v[16] as Boolean
                    interest = v[17] as Boolean
                    feeCoinText = v[18] as String
                    feeCoinAmountText = v[19] as String
                    feeCoinPriceText = v[20] as String
                    feeCoinPriceAuto = v[21] as Boolean
                    toCoinQuery = v[22] as String
                    toAmountText = v[23] as String
                    swapValueText = v[24] as String
                    entryFiat = v[25] as Boolean
                    pendingToFiat = v[26] as Boolean
                    showLedger = v[27] as Boolean
                    tried = v[28] as Boolean
                }
            },
        )
    }
}

/** Abgeleitete Werte eines Durchgangs ([TxFormState.derive]); die Prüfung selbst in [check]. */
@Stable
internal class TxFormDerived(
    val check: PortfolioTxForm.Check,
    /** Menge > 0; null = leer, unlesbar oder ≤ 0. */
    val amount: BigDecimal?,
    /** Tag der Transaktion (Ortszeit). */
    val day: LocalDate,
    /** Eingabe in der Anzeigewährung möglich (Preis-/Wertfeld, nicht USD). */
    val fiatPossible: Boolean,
    /** Geladener Devisenkurs dieses Tags; null = (noch) keiner. */
    val fxRate: BigDecimal?,
    /** Felder stehen in der Anzeigewährung. */
    val fiatActive: Boolean,
    /** Preis in USDT. */
    val price: BigDecimal?,
    val toAmount: BigDecimal?,
    /** Tausch: Wert in USDT. */
    val swapValue: BigDecimal?,
    val keepStoredFee: Boolean,
    val feeCoinSymbol: String,
    val feeCoinAmount: BigDecimal?,
    val feeCoinPrice: BigDecimal?,
    val isToday: Boolean,
    val dirty: Boolean,
    val type: PortfolioTxType,
) {
    val fields: PortfolioTxForm.Fields get() = check.fields
    val isSwap: Boolean get() = type == PortfolioTxType.SWAP
    val coin: String get() = check.coin
    val coinValid: Boolean get() = check.coinValid
    val priceZero: Boolean get() = check.priceZero
    val priceValid: Boolean get() = check.priceValid
    val toCoin: String get() = check.toCoin
    val toCoinValid: Boolean get() = check.toCoinValid
    val swapValueValid: Boolean get() = check.swapValueValid
    val hasFee: Boolean get() = check.hasFee
    /** Gebühr dieser Art (null = leer, unlesbar oder Art ohne Gebühr). */
    val fee: BigDecimal? get() = check.fee
    val feeValid: Boolean get() = check.feeValid
    val effectiveFeeUnit: PortfolioFeeUnit get() = check.feeUnit
    val feeInReceived: Boolean get() = check.feeInReceived
    val thirdFee: PortfolioTxForm.ThirdFee get() = check.thirdFee
    val feeConsumes: Boolean get() = check.feeConsumes
    val valid: Boolean get() = check.valid
}
