package com.cryptochecker.marketdata.config

/**
 * Börsen, deren öffentliche Kurs-Schnittstelle zu einem festen Zeitpunkt endet (Entscheid Inhaber, 12.11.2026): Bitpanda
 * bis 31. Januar 2027, ab 1. Februar 2027 00:00 UTC von selbst — ohne App-Update — still gelegt. Ab dann keine Anfrage
 * mehr an diese Börse (Kurse, Sammelliste, Paarliste), ihre Paare bleiben in der Merkliste grau mit Grund ([marker],
 * gespeichert als `lastError`, zählt als «nicht gehandelt», `NotTraded`), Alarme bleiben gespeichert, prüfen aber
 * nichts; im Explorer nicht mehr angeboten. Der CSV-Import der Exporte dieser Börse bleibt (Dateiformat, keine
 * Schnittstelle). Ein Zeitpunkt (UTC), keine Zeitzone; [clock] austauschbar für Tests. Gemeinsame Regel für Handy, Widgets
 * und Uhr; Swift-Spiegel `ExchangeRetirement.swift` (Paritäts-Fixture `exchange_retirement.json`).
 */
object ExchangeRetirement {
    /** Schlüssel (= Name) der Börse Bitpanda. */
    const val BITPANDA = "Bitpanda"

    /** 1. Februar 2027 00:00:00 UTC (Epoch-ms): ab diesem Zeitpunkt liefert Bitpanda keine öffentlichen Kurse mehr. */
    const val BITPANDA_END_MILLIS = 1_801_440_000_000L

    /** Anfang des gespeicherten «Fehlers» (`lastError`) eines stillgelegten Paars; danach der Name der Börse. */
    const val MARKER_PREFIX = "Exchange retired: "

    /** Ende je Börse (Schlüssel bzw. Name, Gross-/Kleinschreibung egal). */
    private val ENDS: Map<String, Long> = mapOf(BITPANDA to BITPANDA_END_MILLIS)

    /** Uhr der Regel (Epoch-ms); Tests setzen eine feste Zeit und danach [resetClock]. */
    @Volatile
    var clock: () -> Long = { System.currentTimeMillis() }

    fun resetClock() {
        clock = { System.currentTimeMillis() }
    }

    /** Ende der öffentlichen Kurse der Börse [exchange] (Schlüssel oder Name); null = kein Ende bekannt. */
    fun endsAt(exchange: String?): Long? {
        val name = exchange?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return ENDS.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    /** Ist die Börse [exchange] zum Zeitpunkt [now] stillgelegt (ab dem Ende einschliesslich)? */
    fun isRetired(exchange: String?, now: Long = clock()): Boolean {
        val end = endsAt(exchange) ?: return false
        return now >= end
    }

    /** Gespeicherter «Fehler» für ein Paar der stillgelegten Börse [exchangeName]. */
    fun marker(exchangeName: String): String = MARKER_PREFIX + exchangeName.trim()

    /** Ist [lastError] der Marker einer stillgelegten Börse? */
    fun isMarker(lastError: String?): Boolean = lastError?.startsWith(MARKER_PREFIX) == true

    /** Name der Börse aus dem Marker; null, wenn [lastError] keiner ist. */
    fun exchangeOf(lastError: String?): String? =
        lastError?.takeIf { isMarker(it) }?.removePrefix(MARKER_PREFIX)?.trim()?.takeIf { it.isNotEmpty() }

    /** Stillgelegte Börsen unter [exchanges] (in der Reihenfolge des ersten Vorkommens). */
    fun retiredAmong(exchanges: Iterable<String>, now: Long = clock()): List<String> =
        exchanges.filter { isRetired(it, now) }.distinctBy { it.lowercase() }

    /**
     * Hinweis in der Merkliste («Bitpanda liefert seit … keine öffentlichen Kurse mehr …», mit ✕): die stillgelegten
     * Börsen unter den Paaren ([exchanges], Namen), ohne die schon mit ✕ geschlossenen ([dismissed], klein geschrieben
     * verglichen). Leer = kein Hinweis; ohne Paare dieser Börse verschwindet er von selbst.
     */
    fun noticeExchanges(exchanges: Iterable<String>, dismissed: Set<String>, now: Long = clock()): List<String> {
        val closed = dismissed.mapTo(HashSet()) { it.trim().lowercase() }
        return retiredAmong(exchanges, now).filter { it.trim().lowercase() !in closed }
    }
}
