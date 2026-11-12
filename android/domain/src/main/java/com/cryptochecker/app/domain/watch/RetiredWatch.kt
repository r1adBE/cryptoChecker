package com.cryptochecker.app.domain.watch

import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.marketdata.config.ExchangeRetirement
import com.cryptochecker.marketdata.config.ExchangesConfig

/**
 * Ein Paar einer stillgelegten Börse in der Anzeige — nach der EINEN Regel [ExchangeRetirement.retiredNow] (gespeicherter
 * Marker ODER Ende erreicht), nicht erst nach dem nächsten Abruf: Zeile, Paarseite, Widgets, Handy → Uhr und Alarme
 * zeigen keinen Kurs mehr («—» mit kurzem Grund), auch offline und bei deaktivierten Paaren. Wie iOS
 * `Watch.retiredShown` (`NotTraded.swift`).
 */
object RetiredWatch {
    /**
     * Name der stillgelegten Börse des Paars; null = nicht stillgelegt. Entschieden über den Schlüssel der Börse, gezeigt
     * der Name aus dem Katalog ([displayName]).
     */
    fun exchangeOf(watch: WatchEntity, now: Long = ExchangeRetirement.clock()): String? =
        ExchangeRetirement.retiredNow(watch.exchangeKey, watch.lastError, now)?.let { displayName(watch) }

    /** Anzeigename der Börse für die Texte der Stilllegung: aus dem Katalog ([ExchangesConfig]), sonst der gespeicherte. */
    fun displayName(watch: WatchEntity): String =
        ExchangesConfig.EXCHANGES[watch.exchangeKey]?.name ?: watch.exchangeName.ifBlank { watch.exchangeKey }

    /** Anzeigename aus dem Katalog zum Schlüssel [key]; sonst der Schlüssel. */
    fun nameOfKey(key: String): String = ExchangesConfig.EXCHANGES[key]?.name ?: key

    /**
     * Paar für die Anzeige: stillgelegt → ohne Kurs und ohne Veränderung, mit dem Marker der Börse als Zustand (der Grund
     * steht dann wie bei «nicht gehandelt» an seinem Platz); sonst unverändert (dasselbe Objekt).
     */
    fun shown(watch: WatchEntity, now: Long = ExchangeRetirement.clock()): WatchEntity {
        val exchange = exchangeOf(watch, now) ?: return watch
        val marker = ExchangeRetirement.marker(exchange)
        if (watch.lastPrice == null && watch.change24h == null && watch.lastError == marker) return watch
        return watch.copy(lastPrice = null, change24h = null, lastError = marker)
    }
}
