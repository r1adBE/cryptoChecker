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
 * Einstellungen und die übrigen Widgets sind immer frei. Entsperrt wird einmal je Sitzung;
 * nach mehr als [BACKGROUND_LIMIT_MILLIS] im Hintergrund wird wieder gesperrt.
 */
object PortfolioLockPolicy {

    /** Länger im Hintergrund → beim Zurückkehren wieder sperren. */
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

    /**
     * Nach der Rückkehr aus dem Hintergrund (seit [backgroundSince], 0 = nie) wieder sperren? Beide Zeiten von der
     * monotonen Uhr (Android `SystemClock.elapsedRealtime`, iOS Systemlaufzeit); liegt [now] dennoch vor
     * [backgroundSince] (Uhr gesprungen), lieber sperren.
     */
    fun relockAfterBackground(
        backgroundSince: Long,
        now: Long,
        limitMillis: Long = BACKGROUND_LIMIT_MILLIS,
    ): Boolean = backgroundSince > 0L && (now - backgroundSince > limitMillis || now < backgroundSince)

    /**
     * Enthält eine Sicherung Portfolio-Daten? Eigene Trades und beobachtete Adressen (auch xpub) zählen
     * gleich: beide verraten Bestände. Dann verlangt Sichern im gesperrten Zustand Entsperren, und der
     * Passwortschutz ist vorgewählt.
     */
    fun backupHasPortfolioData(transactions: Int, watchedAddresses: Int): Boolean =
        transactions > 0 || watchedAddresses > 0

    /** Sichern verlangt Entsperren, wenn die Datei Portfolio-Daten enthält und gesperrt ist. */
    fun backupExportNeedsUnlock(locked: Boolean, hasPortfolioData: Boolean): Boolean = locked && hasPortfolioData

    /** Wiederherstellen verlangt Entsperren, solange gesperrt (die Sicherung kann die Sperre ausschalten). */
    fun restoreNeedsUnlock(locked: Boolean): Boolean = locked

    /** Ausschalten der Sperre verlangt Entsperren, solange gesperrt (sonst wäre sie über die Einstellungen zu umgehen). */
    fun disableNeedsUnlock(locked: Boolean): Boolean = locked

    /**
     * «Portfolio in Systemsicherung» einschalten verlangt Entsperren, solange gesperrt (sonst gelangten
     * die Trades über die Systemsicherung an der Sperre vorbei). Ausschalten schützt und geht immer.
     */
    fun systemBackupNeedsUnlock(locked: Boolean, enabling: Boolean): Boolean = locked && enabling

    /** Ab Android 9 (API 28) gilt `requireFlags="clientSideEncryption"` in backup_rules.xml. */
    const val SYSTEM_BACKUP_MIN_SDK = 28

    /**
     * «Portfolio in Systemsicherung» nur ab Android 9 (nur Android): Android 8/8.1 kennt `requireFlags`
     * nicht und gäbe die Portfolio-Kopie unverschlüsselt an Google. Darunter Schalter aus und gesperrt.
     */
    fun systemBackupSupported(sdkInt: Int): Boolean = sdkInt >= SYSTEM_BACKUP_MIN_SDK

    /** Wirksamer Wert des Schalters: auf Android 8/8.1 immer aus, egal was gespeichert ist. */
    fun systemBackupEffective(setting: Boolean, sdkInt: Int): Boolean = setting && systemBackupSupported(sdkInt)

    /** «Zum Portfolio hinzufügen» aus der Merkliste (Blatt zeigt Bestände) verlangt Entsperren, solange gesperrt. */
    fun quickAddNeedsUnlock(locked: Boolean): Boolean = locked

    /**
     * Zeile «Portfolio-Sperre» unter Einstellungen › Portfolio: nur mit eingeschaltetem Portfolio. Der Wert
     * bleibt beim Ausblenden erhalten (Portfolio-Widget und Sicherung bleiben geschützt).
     */
    fun showSetting(portfolioEnabled: Boolean): Boolean = portfolioEnabled

    /**
     * Fenster schützen (FLAG_SECURE: kein Vorschaubild in «Zuletzt verwendet», keine
     * Bildschirmfotos), solange die Sperre an ist und Portfolio-Beträge sichtbar sein können
     * (Portfolio-Tab, Coin-Detail mit Verlauf, Erfassen-Blätter). Unabhängig vom Entsperr-Zustand:
     * Entsperrt zeigt der Bildschirm Beträge.
     * Vor Android 13 ([sdkInt] < 33, ohne `setRecentsScreenshotEnabled`) mit Sperre auf jedem Bildschirm: Beträge
     * stehen auch ausserhalb des Portfolios (Alarm-Übersicht mit Portfolio-Alarmen, Widget-Einstellungen …), und
     * nur FLAG_SECURE hält sie dort aus dem Vorschaubild. Ab 13 verdeckt `MainActivity` das Vorschaubild mit der
     * Sperre ohnehin; ohne Sperre gilt: nur Portfolio-Bildschirme, und die nur mit Sperre.
     */
    fun secureWindow(lockSetting: Boolean, portfolioVisible: Boolean, sdkInt: Int): Boolean =
        lockSetting && (portfolioVisible || sdkInt < RECENTS_SCREENSHOT_API)

    /** Ab Android 13 (API 33) gibt es `Activity.setRecentsScreenshotEnabled`. */
    private const val RECENTS_SCREENSHOT_API = 33

    /** Portfolio-Widget: ohne App-Sitzung, also gesperrt, solange die Einstellung an ist. */
    fun widgetLocked(lockSetting: Boolean): Boolean = lockSetting
}
