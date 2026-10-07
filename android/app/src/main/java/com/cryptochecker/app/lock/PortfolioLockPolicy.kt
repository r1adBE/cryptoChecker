package com.cryptochecker.app.lock

/** Was der Portfolio-Bereich gerade zeigen darf. */
enum class PortfolioAccess {
    /** Einstellungen noch nicht gelesen und eine Sperre wäre fällig: nichts zeigen. */
    PENDING,

    /** Portfolio-Sperre an und in dieser Sitzung noch nicht entsperrt: ruhiger Sperr-Zustand. */
    LOCKED,

    /** Frei: Sperre aus oder in dieser Sitzung entsperrt. */
    OPEN,
}

/**
 * Regeln der Portfolio-Sperre (reines Kotlin, testbar; gespiegelt in
 * `Shared/Portfolio/PortfolioLockPolicy.swift`).
 *
 * Gesperrt ist nur das Portfolio (Tab, Detailansicht, Erfassen-Blätter, Stichtag-Export,
 * Sicherung mit Portfolio-Daten, Portfolio-Widget). Merkliste, Hinzufügen, Zyklus,
 * Optionen und die übrigen Widgets sind immer frei. Entsperrt wird einmal je Sitzung;
 * nach mehr als [BACKGROUND_LIMIT_MILLIS] im Hintergrund wird wieder gesperrt.
 */
object PortfolioLockPolicy {

    /** Länger im Hintergrund → beim Zurückkehren wieder sperren (wie bisher die App-Sperre). */
    const val BACKGROUND_LIMIT_MILLIS = 60_000L

    /**
     * Zustand des Portfolio-Bereichs.
     * @param lockSetting Einstellung «Portfolio-Sperre»; null = noch nicht gelesen.
     * @param lockRequested Sitzung gesperrt (Kaltstart oder nach dem Hintergrund-Limit).
     */
    fun access(lockSetting: Boolean?, lockRequested: Boolean): PortfolioAccess = when {
        !lockRequested -> PortfolioAccess.OPEN
        lockSetting == null -> PortfolioAccess.PENDING
        lockSetting -> PortfolioAccess.LOCKED
        else -> PortfolioAccess.OPEN
    }

    /** Portfolio gerade gesperrt (Einstellung an und Sitzung nicht entsperrt)? */
    fun isLocked(lockSetting: Boolean, lockRequested: Boolean): Boolean = lockSetting && lockRequested

    /** Nach der Rückkehr aus dem Hintergrund (seit [backgroundSince], 0 = nie) wieder sperren? */
    fun relockAfterBackground(
        backgroundSince: Long,
        now: Long,
        limitMillis: Long = BACKGROUND_LIMIT_MILLIS,
    ): Boolean = backgroundSince > 0L && now - backgroundSince > limitMillis

    /** Sichern verlangt Entsperren, wenn die Datei Portfolio-Daten enthält und gesperrt ist. */
    fun backupExportNeedsUnlock(locked: Boolean, hasPortfolioData: Boolean): Boolean = locked && hasPortfolioData

    /**
     * Wiederherstellen verlangt Entsperren, solange gesperrt: Eine Sicherung kann die Sperre
     * ausschalten und dabei (ältere Sicherungen ohne Portfolio) das bestehende stehen lassen.
     */
    fun restoreNeedsUnlock(locked: Boolean): Boolean = locked

    /** Ausschalten der Sperre verlangt Entsperren, solange gesperrt (sonst wäre sie über die Optionen zu umgehen). */
    fun disableNeedsUnlock(locked: Boolean): Boolean = locked

    /** «Zum Portfolio hinzufügen» aus der Merkliste (Blatt zeigt Bestände) verlangt Entsperren, solange gesperrt. */
    fun quickAddNeedsUnlock(locked: Boolean): Boolean = locked

    /**
     * Zeile «Portfolio-Sperre» in den Optionen: nur mit eingeschaltetem Portfolio. Der Wert
     * bleibt beim Ausblenden erhalten (Portfolio-Widget und Sicherung bleiben geschützt).
     */
    fun showSetting(portfolioEnabled: Boolean): Boolean = portfolioEnabled

    /** Portfolio-Widget: ohne App-Sitzung, also gesperrt, solange die Einstellung an ist. */
    fun widgetLocked(lockSetting: Boolean): Boolean = lockSetting
}
