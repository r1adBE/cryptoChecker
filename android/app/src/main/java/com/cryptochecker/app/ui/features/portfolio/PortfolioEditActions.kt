// Erfassen und Ändern im Portfolio (Teil von `PortfolioViewModel`): Kurs/Tagesschluss/Devisenkurs zum
// Vorbelegen, Bestände für die Prüfung, Speichern, Portfolio einschalten, Löschen (Transaktion, Coin, Import),
// Wiederherstellen, alles löschen, Alarm anlegen. Eigene Datei, weil es die schreibenden Aktionen sind.
package com.cryptochecker.app.ui.features.portfolio

import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.portfolio.PortfolioAlarmEntity
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.alarm.PortfolioAlarmKind
import com.cryptochecker.app.domain.portfolio.CostMethod
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioOff
import com.cryptochecker.app.domain.portfolio.PortfolioTxForm
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import com.cryptochecker.app.notification.alarmsAllowed
import com.cryptochecker.app.data.portfolio.clearStored

/** Aktueller USDT-Kurs zum Vorbelegen; null, wenn keiner zu haben ist. */
suspend fun PortfolioViewModel.currentPrice(coin: String): Double? = safe { priceSource.price(coin) }

/**
 * Geprüfter Tagesschluss von [coin] am Tag [date] in USDT (Wert beim Erhalt einer Belohnung/Einzahlung);
 * null ohne bestätigten Kurs (gleiche Identitätsprüfung wie der Stichtag-Export, [HistoricPriceSource]).
 */
suspend fun PortfolioViewModel.historicClose(coin: String, date: LocalDate): BigDecimal? = safe {
    val current = priceSource.price(coin)
    historicSource.dailyCloseUsdt(coin, date, current).price?.takeIf { it > 0.0 && it.isFinite() }
        ?.let { com.cryptochecker.app.domain.portfolio.PortfolioDecimal.of(it) }
}

/** Exakter Bestand von [coin] ohne die Transaktion [excludingId] (Prüfung «mehr als vorhanden»). */
fun PortfolioViewModel.holdingsOf(coin: String, excludingId: Long): BigDecimal {
    // Nur eigener Bestand: Adress-Guthaben sind keine Posten, ein Verkauf kann sie nicht abgeben
    val trades = calcTransactions.value.orEmpty().filter { it.id != excludingId }.map { it.toTrade() }
    return PortfolioCalculator.position(coin, trades, null).ledgerHoldingsExact
}

/**
 * FIFO je Wallet ([CostMethod.FIFO_WALLET]): Bestand von [coin] in [wallet] ohne die Transaktion [excludingId]
 * (offene Posten dieser Wallet, [PortfolioTxForm.walletHoldings]); andere Methoden: null.
 */
fun PortfolioViewModel.walletHoldingsOf(coin: String, wallet: String?, excludingId: Long): BigDecimal? {
    if (costMethod.value != CostMethod.FIFO_WALLET) return null
    val trades = calcTransactions.value.orEmpty().filter { it.id != excludingId }.map { it.toTrade() }
    return PortfolioTxForm.walletHoldings(PortfolioCalculator.openLots(coin, trades, CostMethod.FIFO_WALLET), wallet)
}

/**
 * Faktor USDT → [currency] für die Eingabe in der Anzeigewährung (K-2): Devisenkurs USD → Währung × USDT/USD
 * ([PortfolioTxForm.entryRate], wie CSV-Import und Steuer-Export). Heute der aktuelle Kurs und der Live-Kurs USDT/USD,
 * sonst der Tageskurs (EZB, letzter Geschäftstag ≤ [date]) mit seinem tatsächlichen Datum und der USDT/USD-Tagesschluss
 * DES Tags [date] (fehlt er: × 1, [EntryFx.usdtAssumed]). null = kein Kurs.
 */
suspend fun PortfolioViewModel.entryFx(currency: String, date: LocalDate): EntryFx? = safe {
    val today = LocalDate.now(ZoneId.systemDefault())
    if (!date.isBefore(today)) {
        val fx = fxSource.usdTo(currency)?.takeIf { it > 0.0 && it.isFinite() }
            ?.let { com.cryptochecker.app.domain.portfolio.PortfolioDecimal.of(it) }
        val usdt = com.cryptochecker.app.domain.portfolio.PortfolioDecimal.of(liveUsdtUsd.value)
        PortfolioTxForm.entryRate(fx, usdt)?.let { EntryFx(it, today, PortfolioTxForm.entryUsdtAssumed(fx, usdt)) }
    } else {
        historicSource.usdTo(currency, date)?.let { fx ->
            val day = fx.date?.let { runCatchingCancellable { LocalDate.parse(it) }.getOrNull() } ?: date
            val rate = fx.rate.takeIf { it > 0.0 && it.isFinite() }
                ?.let { com.cryptochecker.app.domain.portfolio.PortfolioDecimal.of(it) }
            val days = (java.time.temporal.ChronoUnit.DAYS.between(date, today) + 1).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            val close = try {
                historySource.usdtUsdDaily(days)[date.toEpochDay()]
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null // ohne Schluss: × 1 (Näherung, wie der Steuer-Export)
            }
            val usdt = com.cryptochecker.app.domain.portfolio.PortfolioDecimal.of(close)
            PortfolioTxForm.entryRate(rate, usdt)?.let { EntryFx(it, day, PortfolioTxForm.entryUsdtAssumed(rate, usdt)) }
        }
    }
}

/**
 * Speichern. Build 46: die erste Transaktion mit ausgeschaltetem Portfolio schaltet es NICHT still ein
 * ([PortfolioOff.askEnableOnSave]) — dann zuerst [onAskEnablePortfolio] (Rückfrage, [enablePortfolio]).
 * Gespeichert: [onSaved] (Blatt zu); Fehler beim Schreiben: [onFailed] (Blatt bleibt offen mit den
 * Eingaben) — nie still verworfen.
 */
fun PortfolioViewModel.save(draft: TxDraft, onAskEnablePortfolio: () -> Unit = {}, onSaved: () -> Unit = {}, onFailed: () -> Unit = {}) {
    val amount = draft.amount ?: return
    viewModelScope.launch {
        // Vor dem Schreiben: gab es schon Transaktionen? (nur nötig bei einer neuen mit Portfolio aus)
        val enabled = settingsRepository.current().portfolioEnabled
        val hadTransactions = PortfolioOff.enableOnSave(draft.id == 0L, enabled) &&
            safe { repository.getTransactions().isNotEmpty() } != false
        val saved = safe {
            repository.save(
                PortfolioTxEntity(
                    id = draft.id,
                    coin = draft.coin,
                    type = draft.type,
                    amount = amount,
                    priceUsdt = draft.priceUsdt,
                    time = draft.time,
                    note = draft.note,
                    fee = draft.fee,
                    feeUnit = draft.fee?.let { draft.feeUnit.name },
                    exchange = draft.exchange,
                    transferFrom = draft.transferFrom,
                    transferTo = draft.transferTo,
                    feeCoin = draft.feeCoin,
                    feeCoinAmount = draft.feeCoinAmount,
                    feeCoinPriceUsdt = draft.feeCoinPriceUsdt,
                    importKey = draft.importKey,
                    acquiredTime = draft.acquiredTime,
                    toCoin = draft.toCoin,
                    toAmount = draft.toAmount,
                    swapValueUsdt = draft.swapValueUsdt,
                    importBatch = draft.importBatch,
                    giftToSpouse = draft.giftToSpouse,
                    linkedImportKey = draft.linkedImportKey,
                    importRestoreState = draft.importRestoreState,
                    feeRow = draft.feeRow && draft.type == PortfolioTxType.SELL,
                    interest = draft.interest && draft.type == PortfolioTxType.REWARD,
                )
            )
            true
        } == true
        if (!saved) {
            onFailed()
            return@launch
        }
        // Rückfrage vor dem Schliessen, damit der Aufrufer das Blatt durch den Dialog ersetzen kann
        if (PortfolioOff.askEnableOnSave(draft.id == 0L, enabled, hadTransactions)) onAskEnablePortfolio()
        onSaved()
        // Börse eingetragen: gleich mit ihrem Kurs bewerten (nicht erst nach dem 60-s-Zwischenspeicher)
        if (!draft.exchange.isNullOrBlank() || !draft.transferTo.isNullOrBlank()) refresh(force = true)
    }
}

/** «Portfolio-Tab einschalten?» › Einschalten: Tab erscheint sofort; Logos und Widget wie beim Schalter. */
fun PortfolioViewModel.enablePortfolio(onEnabled: () -> Unit = {}) {
    viewModelScope.launch {
        if (safe { settingsRepository.setPortfolioEnabled(true) } != null) {
            onEnabled()
            safe { coinLogoSync.startIfEnabled() }
            safe { snapshotUpdater.redrawWidgets() }
        }
    }
}

/**
 * Löschen; gesperrt ([com.cryptochecker.app.data.portfolio.PortfolioStorageLock]) bleibt alles, der Dauerhinweis nennt es.
 * [onResult] erfährt, ob gelöscht wurde (Haptik nach dem Ergebnis, wie iOS).
 */
fun PortfolioViewModel.delete(id: Long, onResult: (Boolean) -> Unit = {}) {
    viewModelScope.launch { onResult(safe { repository.delete(id); true } == true) }
}

/**
 * Wischen auf einem Coin: alle seine Transaktionen löschen; [onDeleted] erhält sie
 * für «Rückgängig» ([restore]).
 */
fun PortfolioViewModel.deleteCoin(coin: String, onDeleted: (List<PortfolioTxEntity>) -> Unit) {
    viewModelScope.launch {
        val removed = safe { repository.deleteCoin(coin) }.orEmpty()
        if (removed.isNotEmpty()) onDeleted(removed)
    }
}

/** «Import rückgängig»: alle Transaktionen des Laufs [batch] löschen; [onDeleted] erhält sie für «Rückgängig». */
fun PortfolioViewModel.undoImport(batch: String, onDeleted: (List<PortfolioTxEntity>) -> Unit) {
    viewModelScope.launch {
        val removed = safe { repository.undoImport(batch) }.orEmpty()
        if (removed.isNotEmpty()) onDeleted(removed)
    }
}

/** «Rückgängig»: gelöschte Transaktionen mit ihren ids wieder anlegen. */
fun PortfolioViewModel.restore(transactions: List<PortfolioTxEntity>) {
    viewModelScope.launch { safe { repository.restore(transactions) } }
}

/**
 * «Portfolio leeren» (nach Bestätigung): alle Transaktionen löschen; [onCleared] erhält sie für «Rückgängig»
 * ([restore] legt sie mit denselben ids wieder an, die Kurse lädt der Tab danach neu) — seit Build 94 wie das
 * Löschen eines Coins.
 */
fun PortfolioViewModel.clearAll(onCleared: (List<PortfolioTxEntity>) -> Unit = {}) {
    viewModelScope.launch {
        val removed = safe { repository.clearAll() }.orEmpty()
        // Gemerkte Kurse nennen die gehaltenen Coins: mit dem Portfolio weg
        safe { priceSource.clearStored() }
        if (removed.isNotEmpty()) onCleared(removed)
    }
}

/**
 * Neuer Portfolio-Alarm; Beträge in der Umrechnungswährung. [onSaved] bekommt den gespeicherten
 * Alarm und ob Alarme als Mitteilung erscheinen dürfen ([com.cryptochecker.app.notification.alarmsAllowed]).
 */
fun PortfolioViewModel.addAlarm(
    kind: PortfolioAlarmKind,
    threshold: Double,
    repeating: Boolean,
    onSaved: (PortfolioAlarmEntity, Boolean) -> Unit = { _, _ -> },
) {
    // Doppeltipp: ein zweites «Speichern», solange das erste Anlegen läuft, bleibt ohne Wirkung (seit Build 99)
    alarmCreateGuard.launch(viewModelScope, guarded = true) {
        val code = currency.value
        val id = safe { alarmRepository.add(kind, threshold, code, repeating) }
        if (id != null) {
            val saved = PortfolioAlarmEntity(
                id = id, kind = kind, threshold = threshold, currency = code.takeIf { kind.isValue }, repeating = repeating,
            )
            onSaved(saved, runCatchingCancellable { appNotifier.alarmsAllowed() }.getOrDefault(true))
        }
        // Neu und scharf: gleich mit den aktuellen Kursen prüfen (und das Widget nachziehen)
        val txs = calcTransactions.value ?: return@launch
        if (txs.isNotEmpty() && _prices.value.updatedAt > 0L) {
            safe {
                snapshotUpdater.record(
                    txs, _prices.value.prices, currency.value, _fxRate.value, routing = _prices.value, displayFx = widgetDisplayFx.value,
                )
            }
        }
    }
}
