package com.cryptochecker.marketdata.config

/**
 * Börsen, deren öffentliche Kurs-Schnittstelle zu einem festen Zeitpunkt endet (Entscheid Inhaber, 12.11.2026): Bitpanda
 * bis 31. Januar 2027, ab 1. Februar 2027 00:00 UTC von selbst — ohne App-Update — still gelegt. Ab dann keine Anfrage
 * mehr an diese Börse (Kurse, Sammelliste, Paarliste), ihre Paare bleiben in der Merkliste grau mit Grund ([marker],
 * gespeichert als `lastError`, zählt als «nicht gehandelt», `NotTraded`), Alarme bleiben gespeichert, prüfen aber
 * nichts; im Explorer nicht mehr angeboten. Der CSV-Import der Exporte dieser Börse bleibt (Dateiformat, keine
 * Schnittstelle). Ein Zeitpunkt (UTC), keine Zeitzone; [clock] austauschbar für Tests und den Debug-Schalter «Stichtag
 * vorziehen» (verschiebt nur die Uhr dieser Regel, nicht die des Geräts). Gemeinsame Regel für Handy, Widgets und Uhr;
 * Swift-Spiegel `ExchangeRetirement.swift` (Paritäts-Fixture `exchange_retirement.json`).
 *
 * «Stillgelegt» entscheidet überall EINE Regel ([retiredNow]): gespeicherter Marker ODER Ende erreicht — Zeile, Paarseite,
 * Widgets, Uhr, Handy → Uhr, Alarme, Explorer zeigen dann keinen Kurs mehr («—» mit kurzem Grund), auch offline und bei
 * deaktivierten Paaren. Ab [BITPANDA_NOTICE_MILLIS] (1. Januar 2027) kündigt die App das Ende an ([isAnnounced]); bis zum
 * Ende ändert sich sonst nichts (Kurse kommen, Alarme prüfen).
 */
object ExchangeRetirement {
    /** Schlüssel (= Name) der Börse Bitpanda. */
    const val BITPANDA = "Bitpanda"

    /** 1. Februar 2027 00:00:00 UTC (Epoch-ms): ab diesem Zeitpunkt liefert Bitpanda keine öffentlichen Kurse mehr. */
    const val BITPANDA_END_MILLIS = 1_801_440_000_000L

    /** 1. Januar 2027 00:00:00 UTC (Epoch-ms): ab hier kündigt die App das Ende von Bitpanda an (Hinweis, Grund). */
    const val BITPANDA_NOTICE_MILLIS = 1_798_761_600_000L

    /** Anfang des gespeicherten «Fehlers» (`lastError`) eines stillgelegten Paars; danach der Name der Börse. */
    const val MARKER_PREFIX = "Exchange retired: "

    /** Ende je Börse (Schlüssel bzw. Name, Gross-/Kleinschreibung egal). */
    private val ENDS: Map<String, Long> = mapOf(BITPANDA to BITPANDA_END_MILLIS)

    /** Beginn der Ankündigung je Börse (wie [ENDS]). */
    private val NOTICES: Map<String, Long> = mapOf(BITPANDA to BITPANDA_NOTICE_MILLIS)

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

    /** Beginn der Ankündigung des Endes der Börse [exchange]; null = keine. */
    fun noticeFrom(exchange: String?): Long? {
        val name = exchange?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return NOTICES.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    /** Wird das Ende der Börse [exchange] zum Zeitpunkt [now] angekündigt (ab der Ankündigung, vor dem Ende)? */
    fun isAnnounced(exchange: String?, now: Long = clock()): Boolean {
        val end = endsAt(exchange) ?: return false
        val from = noticeFrom(exchange) ?: return false
        return now in from until end
    }

    /**
     * Die eine Regel «stillgelegt» für alles, was ein Paar zeigt oder prüft: gespeicherter Marker ([lastError]) ODER das
     * Ende der Börse [exchange] (Name bzw. Schlüssel) ist erreicht. Liefert den Namen der Börse; null = nicht stillgelegt.
     * Der Marker zählt auch bei zurückgestellter Uhr, bis ein Abruf wieder einen Kurs bringt.
     */
    fun retiredNow(exchange: String?, lastError: String?, now: Long = clock()): String? =
        exchangeOf(lastError) ?: exchange?.trim()?.takeIf { it.isNotEmpty() && isRetired(it, now) }

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

    /**
     * Ankündigung über der Merkliste («Bitpanda liefert ab 1. Februar 2027 keine öffentlichen Kurse mehr», mit ✕): die
     * Börsen unter [exchanges], deren Ende angekündigt ist ([isAnnounced]), ohne die mit ✕ geschlossenen ([dismissed],
     * eigene Einträge [advanceKey] — das ✕ der Ankündigung schliesst nicht den Hinweis nach dem Ende).
     */
    fun advanceNoticeExchanges(exchanges: Iterable<String>, dismissed: Set<String>, now: Long = clock()): List<String> {
        val closed = dismissed.mapTo(HashSet()) { it.trim().lowercase() }
        return exchanges.filter { isAnnounced(it, now) }.distinctBy { it.lowercase() }
            .filter { advanceKey(it) !in closed }
    }

    /** Eintrag des ✕ der Ankündigung für [exchange] (klein, mit Zusatz; der Hinweis nach dem Ende nimmt nur den Namen). */
    fun advanceKey(exchange: String): String = exchange.trim().lowercase() + ADVANCE_SUFFIX

    private const val ADVANCE_SUFFIX = "#advance"
}
