// Schreibwege der Aktualisierung (Teil von `WatchRepository`): Kurse und Fehler eines Durchlaufs, Veränderungen,
// Meldekurse und ausgelöste bzw. wieder scharfe Alarme. Erweiterungsfunktionen; Datenbank und DAO in der Klasse.
package com.cryptochecker.app.data

import androidx.room.withTransaction
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.domain.alarm.AlarmEvaluator
import com.cryptochecker.app.domain.refresh.PriceWriteOrder
import com.cryptochecker.app.domain.refresh.StoredError

/**
 * Einen Kurs speichern; mit [PriceWrite.pair] nur für dasselbe Paar, und nur, wenn er nicht älter ist als der
 * gespeicherte ([PriceWriteOrder]). @return true = gespeichert
 */
suspend fun WatchRepository.updatePrice(write: PriceWrite): Boolean = writePrice(write)

suspend fun WatchRepository.writePrice(
    write: PriceWrite,
    now: Long = System.currentTimeMillis(),
): Boolean {
    val pair = write.pair
    val plausibleUntil = PriceWriteOrder.plausibleUntil(now)
    if (pair == null) {
        return watchDao.updatePrice(
            write.id, write.price, write.time, write.change24h, write.priceChangedAt, write.exchangeTime, plausibleUntil,
        ) > 0
    }
    return watchDao.updatePriceForPair(
        write.id, write.price, write.time, write.change24h, write.priceChangedAt, write.exchangeTime, plausibleUntil,
        pair.exchangeKey, pair.baseAsset, pair.quoteAsset, pair.contractType, pair.pairId,
    ) > 0
}

suspend fun WatchRepository.writeError(write: ErrorWrite) {
    // Nur kurze Codes speichern, keine rohen Ausnahmetexte (Hosts, URLs)
    val error = StoredError.sanitize(write.error)
    val pair = write.pair
    if (pair == null) {
        watchDao.updateError(write.id, error)
    } else {
        watchDao.updateErrorForPair(
            write.id, error, pair.exchangeKey, pair.baseAsset, pair.quoteAsset, pair.contractType, pair.pairId,
        )
    }
}

suspend fun WatchRepository.updateError(id: Long, error: String?) =
    watchDao.updateError(id, StoredError.sanitize(error))

/** Später Bezug der Tages-Basis: Veränderung nachtragen, wenn der Kurs noch derselbe ist. */
suspend fun WatchRepository.fillChange(id: Long, time: Long, change: Double): Boolean =
    watchDao.fillChange(id, time, change) > 0

/**
 * Schreibt die Ergebnisse eines ganzen Durchlaufs in einem Vorgang.
 * Einzeln wäre das bei vielen Paaren eine Transaktion je Paar, und nach
 * jeder würde die Watchlist komplett neu geladen.
 *
 * @return Ids, deren Kurs gespeichert wurde. Fehlt eine Id, war der Eintrag inzwischen ein
 * anderes Paar ([PriceWrite.pair]) oder der gespeicherte Kurs neuer ([PriceWriteOrder]) — dann darf auch kein
 * Alarm mit diesem Kurs geprüft werden.
 */
suspend fun WatchRepository.applyRefresh(prices: List<PriceWrite>, errors: List<ErrorWrite>): Set<Long> {
    if (prices.isEmpty() && errors.isEmpty()) return emptySet()
    return database.withTransaction {
        val written = HashSet<Long>()
        prices.forEach { if (writePrice(it)) written += it.id }
        errors.forEach { writeError(it) }
        written
    }
}

/** Setzt den Meldekurs für mehrere Paare in einem Vorgang. */
suspend fun WatchRepository.setNotifiedPrices(prices: List<Pair<Long, Double>>, time: Long) {
    if (prices.isEmpty()) return
    database.withTransaction {
        prices.forEach { (id, price) -> watchDao.setNotifiedPrice(id, price, time) }
    }
}

/**
 * Ausgelösten Alarm speichern ([AlarmEvaluator.triggered]) — nur, wenn er seit dem Lesen
 * unverändert ist (eingeschaltet, gleiche Bedingung, Schwelle, Fenster, Wiederholung, Währung).
 * Sonst hat der Nutzer ihn während der Aktualisierung bearbeitet oder abgeschaltet: Dann
 * bleibt seine Änderung und es wird nicht gemeldet (Rückgabe false).
 */
suspend fun WatchRepository.markAlarmTriggered(
    alarm: AlarmEntity,
    price: Double,
    time: Long,
    /** Volumen-Spike: Startzeit der gemeldeten Stundenkerze. */
    candleOpenTime: Long? = null,
    /** «Nahe am Hoch/Tief»: gemeldete Marke (Hoch/Tief bzw. Kurs beim neuen Hoch/Tief). */
    nearLevel: Double? = null,
): Boolean {
    val after = AlarmEvaluator.triggered(alarm, price, time, candleOpenTime, nearLevel)
    return watchDao.markAlarmTriggered(
        id = alarm.id,
        time = time,
        price = price,
        referencePrice = after.referencePrice,
        referenceAt = after.referenceAt,
        enabled = after.enabled,
        condition = alarm.condition.name,
        threshold = alarm.threshold,
        windowHours = alarm.windowHours,
        repeating = alarm.repeating,
        currency = alarm.currency,
    ) > 0
}

/** Neuer Bezug (Bewegungs-/Prozentalarm) — nur, wenn der Alarm seit dem Lesen unverändert ist. */
suspend fun WatchRepository.setAlarmReferenceIfUnchanged(alarm: AlarmEntity, price: Double, time: Long) {
    watchDao.setAlarmReferenceIfUnchanged(
        alarm.id, price, time, alarm.condition.name, alarm.threshold, alarm.windowHours, alarm.currency,
    )
}

/** «Nahe am Hoch/Tief», Kursmarke (PRICE_ABOVE/PRICE_BELOW), Funding bzw. Open Interest wieder scharf stellen. */
suspend fun WatchRepository.rearmAlarm(id: Long) = watchDao.rearmAlarm(id)

/** Wie [rearmAlarm], aber nur, wenn der Alarm seit dem Lesen unverändert ist (Aktualisierung). */
suspend fun WatchRepository.rearmAlarmIfUnchanged(alarm: AlarmEntity) {
    watchDao.rearmAlarmIfUnchanged(alarm.id, alarm.condition.name, alarm.threshold, alarm.windowHours, alarm.currency)
}
