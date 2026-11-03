package com.cryptochecker.marketdata.watch

/**
 * Takt der Uhr (Uhr-App offen und Komplikation): das Intervall der Kurs-Aktualisierung des Handys
 * (`backgroundIntervalMinutes`, übertragen in [WatchSettings.interval]), begrenzt auf [MIN_MINUTES]…[MAX_MINUTES].
 *
 * Grenzen: Unter 15 Minuten aktualisiert keine der Uhren verlässlich — Wear OS fragt die Komplikation höchstens im
 * Takt aus dem Manifest (`UPDATE_PERIOD_SECONDS` = 900, zur Laufzeit nicht änderbar), watchOS lädt Zeitleisten nur im
 * Rahmen seines Tagesbudgets (Zeitleiste `.after`, etwa alle 15 Minuten). Über 60 Minuten (grösste Wahl des Handys;
 * aus einer Sicherung kann mehr kommen) lädt die Uhr trotzdem stündlich. Die Komplikation lädt, sobald ein Kurs älter
 * ist als das Intervall weniger [EARLY_MINUTES] ([refetchAfterMillis]: 15 → 10 Min., 30 → 25 Min., 60 → 55 Min.), damit
 * die nächste Anfrage des Systems nicht knapp vorher kommt und eine ganze Runde verpasst. Wie iOS `WatchInterval`
 * (Paritäts-Fixture `watch_link.json`).
 */
object WatchInterval {
    const val DEFAULT_MINUTES = 15
    const val MIN_MINUTES = 15
    const val MAX_MINUTES = 60
    private const val EARLY_MINUTES = 5

    /** Intervall des Handys auf das für die Uhr Verlässliche begrenzt. */
    fun clamp(minutes: Int): Int = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)

    /** Ab diesem Alter lädt die Uhr einen Kurs selbst neu (ohne Kurse vom Handy). */
    fun refetchAfterMillis(minutes: Int): Long = (clamp(minutes) - EARLY_MINUTES) * 60_000L
}
