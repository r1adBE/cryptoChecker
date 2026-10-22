// Sperre der Merkliste (Teil von `WatchRepository`, seit Build 103, Review F-08): Steht die gespeicherte Merkliste nur leer
// im Arbeitsspeicher ([WatchlistStorageLock]), lehnt das Repository jeden Schreibweg des Nutzers ab — wie das Portfolio.
package com.cryptochecker.app.data

import androidx.room.withTransaction
import com.cryptochecker.app.data.local.DatabaseEncryptionNotice
import com.cryptochecker.app.data.local.WatchlistStorageLock
import com.cryptochecker.app.data.local.WatchlistWriteRefusal

/**
 * Schreibweg des Nutzers gerade gesperrt ([WatchlistStorageLock.blocksWrites])? Dann schreibt der Aufrufer nichts; mit
 * [notify] löst die Ablehnung den kurzen Hinweis aus ([WatchlistWriteRefusal]) — ohne bei Folge-Schreibwegen, die nur
 * zusammen mit einem schon gemeldeten laufen (z. B. Meldekurs nach dem Einschalten). Öffnet dazu die Datenbank: erst
 * danach ist [DatabaseEncryptionNotice.storedKept] verlässlich. Hintergrundarbeit (Kurse, Alarm-Zustand, Vertragsarten)
 * fragt hier nicht: Sie schreibt in die leere Datenbank im Arbeitsspeicher, dort geht nichts Gespeichertes verloren.
 */
internal suspend fun WatchRepository.userWriteRefused(notify: Boolean = true): Boolean {
    val blocked = database.withTransaction { WatchlistStorageLock.blocksWrites(DatabaseEncryptionNotice.storedKept.value) }
    if (blocked && notify) WatchlistWriteRefusal.report()
    return blocked
}
