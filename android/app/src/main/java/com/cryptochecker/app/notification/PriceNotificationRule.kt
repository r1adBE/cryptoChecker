package com.cryptochecker.app.notification

/**
 * Kurs-Meldung nach einer bewussten Handlung (Paar-Mitteilung einschalten, «Alle an», «Rückgängig»
 * nach dem Löschen): ein gemeinsamer Entscheid, damit keine Stelle die Einstellungen übergeht.
 * Gleich wie iOS `PriceNotificationRule.swift`.
 */
object PriceNotificationRule {
    /**
     * Jetzt zeigen? Nur mit eingeschalteten Kurs-Mitteilungen ([masterOn], Einstellungen), Mitteilung
     * des Paars an, Paar aktiv und gehandelt, mit gültigem Kurs.
     */
    fun showNow(masterOn: Boolean, pairOn: Boolean, disabled: Boolean, notTraded: Boolean, price: Double?): Boolean =
        masterOn && pairOn && !disabled && !notTraded && price != null && price > 0.0 && price.isFinite()

    /**
     * Bezug der Melde-Schwelle nach dem Einschalten: gezeigt → der gezeigte Kurs; nicht gezeigt → keiner,
     * damit die nächste Aktualisierung (mit Kurs-Mitteilungen an) sofort meldet.
     */
    fun referenceAfterEnable(shown: Boolean, price: Double?): Double? = if (shown) price else null
}
