// Hintergrund-Aufgaben im Portfolio (Teil von `PortfolioViewModel`, gestartet im `init`): Überschneidungen
// beobachteter Adressen mit eigenen Transaktionen und Momentaufnahmen fürs Portfolio-Widget.
// Eigene Datei, weil die Sammler von den abgeleiteten Flows der Klasse getrennt lesbar sein sollen;
// Erweiterungsfunktionen, der Zustand bleibt in der Klasse.
package com.cryptochecker.app.ui.features.portfolio

import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.WatchedAddressRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Alte Entscheide (früher genügte derselbe Coin): einmal übernehmen; «Nur anzeigen» ohne passende Menge zählt
 * wieder (Hinweis mit «Rückgängig»)
 */
internal fun PortfolioViewModel.restoreLegacyAddressOverlap() {
    viewModelScope.launch {
        combine(transactions.filterNotNull(), watchedAddresses) { txs, list -> txs to list }
            .filter { (_, list) -> list.any { it.overlapLegacy } }
            .collect { (txs, list) ->
                val trades = withContext(Dispatchers.Default) { txs.map { it.toTrade() } }
                val legacy = list.filter { it.overlapLegacy }
                val restored = legacy.filter { a ->
                    val revert = WatchedAddressRules.overlapLegacyRevert(a, trades)
                    safe { repository.migrateWatchedAddressOverlap(a.id, revert) } != null && revert
                }
                if (restored.isEmpty()) return@collect
                val own = txs.map { PortfolioCalculator.normalizeCoin(it.coin) }.toSet()
                val previous = _addressOverlapRestored.value
                _addressOverlapRestored.value = AddressOverlapNotice(
                    ids = previous?.ids.orEmpty() + restored.map { it.id },
                    names = previous?.names.orEmpty() + restored.map { it.label ?: WatchedAddressRules.shortAddress(it.address) },
                    coins = (previous?.coins.orEmpty() + restored.flatMap { WatchedAddressRules.overlap(it, own) }).distinct(),
                )
            }
    }
}

/**
 * Später aufgetauchte Überschneidung Adresse ↔ eigene Transaktionen mit passender Menge: einmal automatisch
 * «Nur anzeigen» (Hinweis); nur gleicher Coin: zählt weiter (Hinweis an der Adresse)
 */
internal fun PortfolioViewModel.switchOverlappingAddresses() {
    viewModelScope.launch {
        combine(transactions.filterNotNull(), watchedAddresses) { txs, list ->
            WatchedAddressRules.overlapAutoShowOnly(list.filter { !it.overlapLegacy }, txs.map { it.toTrade() }) to txs
        }
            .flowOn(Dispatchers.Default)
            .filter { (due, _) -> due.isNotEmpty() }
            .collect { (due, txs) ->
                val own = txs.map { PortfolioCalculator.normalizeCoin(it.coin) }.toSet()
                val switched = due.filter { a -> safe { repository.setWatchedAddressOverlapShowOnly(a.id) } != null }
                if (switched.isEmpty()) return@collect
                val previous = _addressOverlapNotice.value
                _addressOverlapNotice.value = AddressOverlapNotice(
                    ids = previous?.ids.orEmpty() + switched.map { it.id },
                    names = previous?.names.orEmpty() + switched.map { it.label ?: WatchedAddressRules.shortAddress(it.address) },
                    coins = (previous?.coins.orEmpty() + switched.flatMap { WatchedAddressRules.overlap(it, own) }).distinct(),
                )
            }
    }
}

/**
 * Portfolio-Widget: jede neue Berechnung (Transaktionen, Kurse, Währung) als Momentaufnahme
 */
internal fun PortfolioViewModel.recordWidgetSnapshots() {
    viewModelScope.launch {
        // Merkzettel der Anzeigewährung mit: kommen die Kurse dafür später, nimmt das Widget gleich neu auf
        combine(calcTransactions.filterNotNull(), _prices, currency, _fxRate, widgetDisplayFx) { txs, p, code, fx, display ->
            PortfolioWidgetInput(txs, p, code, fx, display)
        }
            .distinctUntilChanged()
            .collect { input ->
                // Ohne geladene Kurse nichts aufnehmen (sonst stünde kurz 0 im Widget)
                if (input.transactions.isNotEmpty() && input.prices.updatedAt <= 0L) return@collect
                // Zu wenig frische Kurse (ohne Netz, nach einem Neustart): nicht als «jetzt» aufnehmen
                // (Widget, Verlauf, Alarme blieben sonst mit alten Kursen stehen); «Heute» dann aus der
                // letzten Momentaufnahme. Frische je Coin: ein einzelner alter Coin hält die übrigen
                // nicht an, solange mindestens 80 % des Werts frisch sind
                if (input.transactions.isNotEmpty() && !withContext(Dispatchers.Default) { mayRecord(input) }) {
                    if (_todayPercent.value == null) showStoredToday(input.fxRate)
                    return@collect
                }
                safe {
                    snapshotUpdater.record(
                        input.transactions, input.prices.prices, input.currency, input.fxRate, routing = input.prices,
                        displayFx = input.displayFx,
                    )
                }
                    ?.let { snapshot ->
                        // Betrag in der Anzeigewährung der Aufnahme → USDT (wie der Gesamtwert): Faktor der Aufnahme
                        // (Devisenkurs × USDT/USD, [snapshotUsdtFactor])
                        val rate = snapshotUsdtFactor(snapshot, input.fxRate)
                        val amount = snapshot.changeAmount
                            ?.takeUnless { snapshot.empty }
                            ?.let { value -> rate?.let { value / it } }
                        val percent = snapshot.changePercent?.takeUnless { snapshot.empty }
                        // «Gewinn heute» kommt schon berichtigt aus der Aufnahme (Käufe/Verkäufe heraus,
                        // Coins ohne Vergleichskurs fehlen) — dieselbe Zahl wie Widget und Portfolio-Alarm
                        _todayPercent.value = percent
                        _todayAmount.value = amount
                        _todaySkipped.value = snapshot.todaySkipped
                        _coinChanges.value = snapshot.coinChanges
                        _coinPnl.value = if (snapshot.empty) emptyMap() else snapshot.coinPnl
                    }
            }
    }
}
