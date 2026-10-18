// Datentypen der Merkliste rund um [WatchRepository]: Sammelaufnahme, Sortieren, Paar-Identität, Kurs- und Fehler-Schreibwerte.
package com.cryptochecker.app.data

import com.cryptochecker.app.data.local.WatchDao
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.watch.WatchIdentity
import com.cryptochecker.marketdata.model.FuturesContractType

/** Ergebnis einer Sammelaufnahme in die Watchlist. */
data class BulkAddResult(
    val added: Int,
    val skipped: Int,
)

/** Richtung beim manuellen Sortieren. */
enum class WatchMove { TOP, UP, DOWN, BOTTOM }

/**
 * Alles, was zu einem Paar in der Datenbank steht: der Eintrag selbst (Id, Platz,
 * Gruppe, Notiz, Favorit, Meldung, Sprachansage …) und seine Alarme mit ihren Ids.
 * Grundlage für «Rückgängig» nach dem Löschen per Wischen.
 */
data class WatchSnapshot(val watch: WatchEntity, val alarms: List<AlarmEntity>)

/** Ergebnis von «Paar bearbeiten» ([WatchRepository.changePair]). */
enum class PairEditResult { SAVED, UNCHANGED, DUPLICATE, MISSING }

/**
 * Welches Paar ein Eintrag zeigt — genau die Spalten, nicht der normalisierte Schlüssel
 * ([WatchIdentity]). Ein Kurs gilt nur für den Eintrag, solange er noch dieses Paar ist
 * («Paar bearbeiten» und Sicherungen behalten bzw. vergeben Ids neu).
 */
data class PairIdentity(
    val marketKey: String,
    val baseAsset: String,
    val quoteAsset: String,
    /** Name von `FuturesContractType`, wie in der Datenbank gespeichert. */
    val contractType: String,
    val pairId: String?,
) {
    companion object {
        fun of(watch: WatchEntity): PairIdentity =
            PairIdentity(watch.marketKey, watch.baseAsset, watch.quoteAsset, watch.contractType.name, watch.pairId)
    }
}

/** Paar dieses Eintrags ([PairIdentity]). */
val WatchEntity.pairIdentity: PairIdentity get() = PairIdentity.of(this)

/**
 * Neuer Kurs für ein Paar; [change24h] siehe `WatchEntity.change24h` (null = kein 24-h-Bezug),
 * [priceChangedAt] und [exchangeTime] siehe `WatchEntity` bzw. `PriceFreshness`.
 * [pair]: für welches Paar der Kurs geholt wurde — gespeichert wird nur, wenn der Eintrag noch
 * dieses Paar ist ([WatchDao.updatePriceForPair]); null = ohne Prüfung.
 */
data class PriceWrite(
    val id: Long,
    val price: Double,
    val time: Long,
    val change24h: Double?,
    val priceChangedAt: Long = time,
    val exchangeTime: Long? = null,
    val pair: PairIdentity? = null,
)

/** Fehlgeschlagene Abfrage für ein Paar; [pair] wie bei [PriceWrite]. */
data class ErrorWrite(val id: Long, val error: String?, val time: Long, val pair: PairIdentity? = null)
