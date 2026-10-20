// Speichert den offenen Entwurf des Erfassen-Blatts (`TxDraft`) über Drehen, Dunkelmodus und Sprachwechsel:
// Das Blatt bleibt offen (der Formularzustand selbst ist schon in `TxFormState.saver`). Eigene Datei, weil die
// Umwandlung reines Kotlin ist und ohne Compose geprüft wird (`PortfolioTxDraftSaverTest`).
package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.runtime.saveable.Saver
import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.csvimport.CsvRestoreState
import java.math.BigDecimal

/** Fassung der gespeicherten Liste; eine fremde Fassung wird verworfen (Blatt geschlossen statt falsch gefüllt). */
private const val DRAFT_SAVE_VERSION = 1L

/**
 * Entwurf als Liste aus Text, Zahlen und Wahrheitswerten (Bundle-tauglich): Beträge exakt als
 * `BigDecimal.toString()`, Arten über ihren Namen, der Zustand für «Import rückgängig» als sein JSON.
 */
internal fun TxDraft.toSaved(): ArrayList<Any?> = arrayListOf(
    DRAFT_SAVE_VERSION, id, coin, type.name, amount?.toString(), priceUsdt?.toString(), time, note,
    fee?.toString(), feeUnit.name, exchange, transferFrom, transferTo, feeCoin, feeCoinAmount?.toString(),
    feeCoinPriceUsdt?.toString(), importKey, acquiredTime, toCoin, toAmount?.toString(), swapValueUsdt?.toString(),
    importBatch, giftToSpouse, linkedImportKey, importRestoreState?.encode(), feeRow, interest,
)

/** Gegenstück zu [toSaved]; null bei fremder Fassung oder unlesbarer Liste. */
internal fun txDraftFromSaved(saved: List<Any?>): TxDraft? = runCatching {
    if (saved.size != 27 || saved[0] != DRAFT_SAVE_VERSION) return null
    fun text(i: Int) = saved[i] as String?
    fun decimal(i: Int) = text(i)?.let(::BigDecimal)
    TxDraft(
        id = saved[1] as Long,
        coin = saved[2] as String,
        type = PortfolioTxType.valueOf(saved[3] as String),
        amount = decimal(4),
        priceUsdt = decimal(5),
        time = saved[6] as Long,
        note = text(7),
        fee = decimal(8),
        feeUnit = PortfolioFeeUnit.fromName(saved[9] as String),
        exchange = text(10),
        transferFrom = text(11),
        transferTo = text(12),
        feeCoin = text(13),
        feeCoinAmount = decimal(14),
        feeCoinPriceUsdt = decimal(15),
        importKey = text(16),
        acquiredTime = saved[17] as Long?,
        toCoin = text(18),
        toAmount = decimal(19),
        swapValueUsdt = decimal(20),
        importBatch = text(21),
        giftToSpouse = saved[22] as Boolean,
        linkedImportKey = text(23),
        importRestoreState = CsvRestoreState.decode(text(24)),
        feeRow = saved[25] as Boolean,
        interest = saved[26] as Boolean,
    )
}.getOrNull()

/** Für `rememberSaveable(stateSaver = …)`: kein Entwurf = nichts gespeichert (Blatt bleibt zu). */
internal val TxDraftSaver: Saver<TxDraft?, Any> = Saver(
    save = { it?.toSaved() },
    restore = { saved -> (saved as? List<*>)?.let { txDraftFromSaved(it) } },
)
