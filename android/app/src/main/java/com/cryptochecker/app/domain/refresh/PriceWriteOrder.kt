package com.cryptochecker.app.domain.refresh

/**
 * Reihenfolge der Kurs-Schreiber: Ein Ergebnis übernimmt seinen Kurs nur, wenn er nicht älter ist als der gespeicherte
 * (`lastUpdate`). Ein später ankommender älterer Kurs (z. B. langsame Börse, Live-Kurs mit älterer Zeit) überschreibt
 * so keinen neueren. Umgesetzt in der Bedingung von [com.cryptochecker.app.data.local.WatchDao.updatePriceForPair]
 * bzw. `updatePrice` (`lastUpdate <= :time OR lastUpdate > :plausibleUntil`); nicht geschrieben → keine Alarme mit
 * diesem Kurs. Wie iOS `PriceWriteOrder` (dort in `Outcome.apply(to:)`); gemeinsame Testfälle `price_write_order.json`.
 */
object PriceWriteOrder {
    /**
     * Liegt der gespeicherte Zeitpunkt mehr als so weit in der Zukunft, gilt er als unplausibel (Uhr zurückgestellt)
     * und sperrt keinen neuen Kurs — sonst stünde das Paar bis dahin still.
     */
    const val FUTURE_TOLERANCE_MILLIS = 3_600_000L

    /** Spätester noch plausibler gespeicherter Zeitpunkt zu [now]. */
    fun plausibleUntil(now: Long): Long = now + FUTURE_TOLERANCE_MILLIS

    /**
     * @param newTime Zeit des neuen Kurses
     * @param storedTime Zeit des gespeicherten Kurses (`lastUpdate`); 0 = noch keiner
     * @return true = neuen Kurs übernehmen (gleich alt zählt als neu)
     */
    fun accepts(newTime: Long, storedTime: Long, now: Long): Boolean =
        newTime >= storedTime || storedTime > plausibleUntil(now)
}
