// Sperre der Merkliste (Teil von `WatchRepository`): Steht die gespeicherte Merkliste nur leer
// im Arbeitsspeicher ([WatchlistStorageLock]), lehnt das Repository jeden Schreibweg des Nutzers ab — wie das Portfolio.
package com.cryptochecker.app.data

import androidx.room.withTransaction
import com.cryptochecker.app.data.local.DatabaseEncryptionNotice
import com.cryptochecker.app.data.local.WatchlistNotSavedGate
import com.cryptochecker.app.data.local.WatchlistStorageLock
import com.cryptochecker.app.data.local.WatchlistWriteRefusal
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.io.IOException

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
    // Rückfrage «nicht gespeichert»: nur im Arbeitsspeicher ohne gespeicherten Stand fragt die AKTION vorher
    // ([confirmUserWrite]) — hier nur die Sicherung: ohne Bestätigung ablehnen, ohne Hinweis und ohne zu warten (nie unter
    // einer Sperre). Folge-Schreibwege ([notify] aus) laufen nur nach einem bestätigten Schreibweg und lehnen nie ab
    return notify && WatchlistNotSavedGate.needsConfirmation()
}

/**
 * Rückfrage «Merkliste wird nicht gespeichert» EINMAL je Nutzeraktion,
 * VOR der Aktion — vor `withRefreshLock`/`undoMutex`, Rückgängig-Eintrag, Mitteilungen, Banner und Haptik; Mehrfach-Aktionen
 * (viele Paare, Startpaare) fragen so genau einmal. false = «Abbrechen»: nichts tun, keine Seiteneffekte. Gesperrte
 * Merkliste (KEPT) fragt nicht (true): Dort lehnt der Schreibweg selbst ab und zeigt den Hinweis. Öffnet zuerst die
 * Datenbank, erst danach ist der Zustand verlässlich. Wie iOS `AppData.confirmWatchlistWrite`.
 */
suspend fun WatchRepository.confirmUserWrite(): Boolean {
    val blocked = database.withTransaction { WatchlistStorageLock.blocksWrites(DatabaseEncryptionNotice.storedKept.value) }
    return blocked || WatchlistNotSavedGate.confirm()
}

/**
 * Schreibweg des Nutzers mit Schutz vor Schreibfehlern: Scheitert das Schreiben an der Datenbank bzw. Datei (Speicher voll,
 * nicht beschreibbar — [isWriteFailure]), stürzt die App nicht ab. Room hat die Transaktion zurückgerollt (der gespeicherte
 * Stand ist unverändert), der kurze Hinweis «Konnte nicht gespeichert werden» kommt ([WatchlistWriteRefusal.reportFailed])
 * und [failed] geht zurück — der Aufrufer behandelt das wie «abgelehnt» (keine Folgeaktionen). Andere Fehler (auch
 * Verletzungen einer Bedingung, die Aufrufer selbst behandeln) gehen weiter. [report] nur für Tests austauschbar.
 * Gegenstück iOS: `SharedStorage.watchlistWriteFailed` mit Hinweis und erneutem Schreiben.
 */
suspend fun <T> guardedWrite(
    failed: T,
    report: () -> Unit = { WatchlistWriteRefusal.reportFailed() },
    block: suspend () -> T,
): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    if (!isWriteFailure(e)) throw e
    Timber.w(e, "Merkliste/Einstellungen nicht geschrieben")
    report()
    failed
}

/**
 * Schreibfehler des Speichers? Fehler der Datenbank ([android.database.SQLException], z. B. Speicher voll) ausser einer
 * verletzten Bedingung ([android.database.sqlite.SQLiteConstraintException]) sowie Ein-/Ausgabefehler ([IOException],
 * DataStore der Einstellungen).
 */
fun isWriteFailure(e: Throwable): Boolean =
    (e is android.database.SQLException && e !is android.database.sqlite.SQLiteConstraintException) || e is IOException

/** Schreibfehler des Speichers ([isWriteFailure]) melden: kurzer Hinweis «Konnte nicht gespeichert werden». */
fun reportWriteFailure(e: Throwable) {
    if (isWriteFailure(e)) WatchlistWriteRefusal.reportFailed()
}
