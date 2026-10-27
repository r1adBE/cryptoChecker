// Sperre der Merkliste (Teil von `WatchRepository`, Review F-08): Steht die gespeicherte Merkliste nur leer
// im Arbeitsspeicher ([WatchlistStorageLock]), lehnt das Repository jeden Schreibweg des Nutzers ab — wie das Portfolio.
package com.cryptochecker.app.data

import androidx.room.withTransaction
import com.cryptochecker.app.data.local.DatabaseEncryptionNotice
import com.cryptochecker.app.data.local.WatchlistNotSavedGate
import com.cryptochecker.app.data.local.WatchlistStorageLock
import com.cryptochecker.app.data.local.WatchlistWriteRefusal

/**
 * Schreibweg des Nutzers gerade gesperrt ([WatchlistStorageLock.blocksWrites])? Dann schreibt der Aufrufer nichts; mit
 * [notify] löst die Ablehnung den kurzen Hinweis aus ([WatchlistWriteRefusal]) — ohne bei Folge-Schreibwegen, die nur
 * zusammen mit einem schon gemeldeten laufen (z. B. Meldekurs nach dem Einschalten). Öffnet dazu die Datenbank: erst
 * danach ist [DatabaseEncryptionNotice.storedKept] verlässlich. Hintergrundarbeit (Kurse, Alarm-Zustand, Vertragsarten)
 * fragt hier nicht: Sie schreibt in die leere Datenbank im Arbeitsspeicher, dort geht nichts Gespeichertes verloren.
 */
suspend fun WatchRepository.userWriteRefused(notify: Boolean = true): Boolean {
    val blocked = database.withTransaction { WatchlistStorageLock.blocksWrites(DatabaseEncryptionNotice.storedKept.value) }
    return writeRefused(blocked, notify)
}

/**
 * Entscheidung von [userWriteRefused] nach dem Öffnen der Datenbank ([blocked] = gesperrt), ohne Room testbar
 * (`WatchlistWriteConsentTest`). [report] (Hinweis auslösen) ist nur für Tests austauschbar.
 */
suspend fun writeRefused(
    blocked: Boolean,
    notify: Boolean,
    report: () -> Unit = { WatchlistWriteRefusal.report() },
): Boolean {
    if (blocked && notify) report()
    if (blocked) return true
    // F-08, ARC3-R02: nur im Arbeitsspeicher ohne gespeicherten Stand fragt die AKTION vorher
    // ([confirmUserWrite]) — hier nur die Sicherung: ohne Bestätigung ablehnen, ohne Hinweis und ohne zu warten (nie unter
    // einer Sperre). Folge-Schreibwege ([notify] aus) laufen nur nach einem bestätigten Schreibweg und lehnen nie ab
    return notify && WatchlistNotSavedGate.needsConfirmation()
}

/**
 * Review ARC3-R01/R02, UX3-01/03/04: Rückfrage «Merkliste wird nicht gespeichert» EINMAL je Nutzeraktion,
 * VOR der Aktion — vor `withRefreshLock`/`undoMutex`, Rückgängig-Eintrag, Mitteilungen, Banner und Haptik; Mehrfach-Aktionen
 * (viele Paare, Startpaare) fragen so genau einmal. false = «Abbrechen»: nichts tun, keine Seiteneffekte. Gesperrte
 * Merkliste (KEPT) fragt nicht (true): Dort lehnt der Schreibweg selbst ab und zeigt den Hinweis. Öffnet zuerst die
 * Datenbank, erst danach ist der Zustand verlässlich. Wie iOS `AppData.confirmWatchlistWrite`.
 */
suspend fun WatchRepository.confirmUserWrite(): Boolean {
    val blocked = database.withTransaction { WatchlistStorageLock.blocksWrites(DatabaseEncryptionNotice.storedKept.value) }
    return blocked || WatchlistNotSavedGate.confirm()
}
