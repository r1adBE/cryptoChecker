package com.cryptochecker.app.domain.diagnostics

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Gezählte Fehler im Hintergrund — nur Zahlen je Bereich und Zeitpunkt des letzten, nie Texte. */
data class BackgroundErrorSummary(
    val counts: Map<String, Int> = emptyMap(),
    /** Letzter gezählter Fehler (ms); 0 = keiner bekannt. */
    val lastAt: Long = 0,
    /** Fehlerart je Bereich: Bereich → (einfacher Typname der Ausnahme → Anzahl); nie Meldungstexte. */
    val kinds: Map<String, Map<String, Int>> = emptyMap(),
)

/**
 * F-25: Fehler in Hintergrundarbeit sind im Release sonst unsichtbar (Timber nur im Debug-Build). Statt
 * eines Protokolls nur ein Zähler je Bereich; im Diagnosebericht eine Zeile. Gleiches Format wie `BackgroundErrors.swift`
 * (Testfälle `background_errors.json`).
 */
object BackgroundErrors {
    const val REFRESH = "refresh"
    const val STORAGE = "storage"
    const val WIDGETS = "widgets"
    const val LOGOS = "logos"
    const val COINS = "coins"
    const val LIVE = "live"
    const val ALARMS = "alarms"
    const val SCHEDULE = "schedule"
    const val STARTUP = "startup"
    const val OTHER = "other"

    /** Feste Bereiche — nur diese Codes gelangen in den Bericht (kein Platz für freien Text). */
    val AREAS: Set<String> = setOf(REFRESH, STORAGE, WIDGETS, LOGOS, COINS, LIVE, ALARMS, SCHEDULE, STARTUP, OTHER)

    /** Unbekannter Code → [OTHER]. */
    fun area(code: String): String = if (code in AREAS) code else OTHER

    // Bezeichnungen der App-Bereiche (appScope) → Bereich; Android-spezifisch
    private val LABEL_AREAS = mapOf(
        "CoinLogoSync" to LOGOS,
        "CoinListSync" to COINS,
        "LivePriceStream" to LIVE,
        "LiveWidgetGate" to LIVE,
        "PriceRefresher" to REFRESH,
        "RefreshSideChecks" to REFRESH,
        "RefreshEffects" to REFRESH,
        "DayReferences" to REFRESH,
        "ManualRefresh" to REFRESH,
        "ChartRefresher" to REFRESH,
        "SparklineRepository" to REFRESH,
        "InFlightRequests" to REFRESH,
        "ExactCheckReceiver" to REFRESH,
        "ListWidgetRenderer" to WIDGETS,
        "PulseBackgroundFetch" to WIDGETS,
        "Empfänger-Arbeit" to WIDGETS,
        "TtsSpeaker" to ALARMS,
        "Hintergrundaufgabe beim Start" to STARTUP,
    )

    /** Bereich zur Bezeichnung von appScope bzw. launchAsync (Android); Unbekanntes → [OTHER]. */
    fun areaForLabel(label: String): String = LABEL_AREAS[label] ?: when {
        label.contains("Widget") -> WIDGETS
        label.startsWith("Neuplanung") -> SCHEDULE
        label.startsWith("Kursmarke") -> ALARMS
        else -> OTHER
    }

    /**
     * Zeile im Bericht: «Background errors: refresh 2, logos 1 (last 2026-10-22 08:14 UTC)» bzw. «… none». Nach Anzahl
     * absteigend, bei Gleichstand nach Code; Zeit immer in UTC.
     */
    fun line(summary: BackgroundErrorSummary): String {
        val merged = HashMap<String, Int>()
        summary.counts.forEach { (code, n) -> if (n > 0) area(code).let { merged[it] = (merged[it] ?: 0) + n } }
        if (merged.isEmpty()) return "Background errors: none"
        val parts = merged.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .joinToString(", ") { "${it.key} ${it.value}" }
        val last = if (summary.lastAt > 0) " (last ${UTC.format(Instant.ofEpochMilli(summary.lastAt))} UTC)" else ""
        return "Background errors: $parts$last"
    }

    /** Höchstens so viele verschiedene Fehlerarten je Bereich; weitere zählen als [OTHER_KIND]. */
    const val MAX_KINDS = 5
    const val OTHER_KIND = "Other"
    const val UNKNOWN_KIND = "Unknown"

    /** Feste Arten ohne Ausnahme: alle Kursabrufe eines Laufs gescheitert bzw. Kurse geholt, aber nicht gespeichert. */
    const val ALL_FAILED_KIND = "AllFetchesFailed"
    const val SAVE_FAILED_KIND = "SaveFailed"
    private const val KIND_MAX_LENGTH = 40

    /**
     * Fehlerart aus einem Typnamen (Android: einfacher Klassenname der Ausnahme, iOS: Typ des Fehlers): nur Buchstaben A–Z,
     * Ziffern und «_», höchstens 40 Zeichen; leer → [UNKNOWN_KIND]. Kein Meldungstext, keine personenbezogenen Daten.
     */
    fun kind(typeName: String): String =
        typeName.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' }.take(KIND_MAX_LENGTH)
            .ifEmpty { UNKNOWN_KIND }

    /** Android: Fehlerart einer Ausnahme (einfacher Klassenname; anonyme Klasse → Name ohne Paket). */
    fun kindOf(error: Throwable): String =
        kind(error.javaClass.simpleName.ifEmpty { error.javaClass.name.substringAfterLast('.') })

    /** Fach für [kind] in einem Bereich, der schon [known] Arten hat: bekannt oder noch Platz → [kind], sonst [OTHER_KIND]. */
    fun kindSlot(known: Set<String>, kind: String): String =
        if (kind in known || known.size < MAX_KINDS) kind else OTHER_KIND

    /**
     * Zeile «Background error kinds: refresh SocketTimeoutException 2, IOException 1; logos IOException 1»; null = keine
     * Arten gezählt (Zeile entfällt). Bereiche nach Summe absteigend, dann Code; Arten nach Anzahl absteigend, dann Name.
     */
    fun kindsLine(summary: BackgroundErrorSummary): String? {
        val merged = HashMap<String, HashMap<String, Int>>()
        summary.kinds.forEach { (code, kinds) ->
            kinds.forEach { (k, n) ->
                if (n > 0) merged.getOrPut(area(code)) { HashMap() }.let { it[kind(k)] = (it[kind(k)] ?: 0) + n }
            }
        }
        if (merged.isEmpty()) return null
        val areas = merged.entries.sortedWith(compareByDescending<Map.Entry<String, Map<String, Int>>> { it.value.values.sum() }
            .thenBy { it.key })
        return "Background error kinds: " + areas.joinToString("; ") { (code, kinds) ->
            code + " " + kinds.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .joinToString(", ") { "${it.key} ${it.value}" }
        }
    }

    private val UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(ZoneOffset.UTC)
}
