// Ergebnis und Fehler von Sichern/Wiederherstellen ([BackupManager]).
package com.cryptochecker.app.data

/**
 * Was eine Wiederherstellung übernommen hat. [appLockKept] = eine Sicherung ohne Passwort wollte die
 * Portfolio-Sperre ausschalten, sie blieb an ([BackupRestorePolicy.appLock]).
 */
data class RestoreResult(
    val watches: Int,
    val alarms: Int,
    val appLockKept: Boolean = false,
    /** Ids der ersetzten Paare: ihre Kurs- und Aktivitäts-Meldungen gehören zu den alten Paaren. */
    val replacedWatchIds: List<Long> = emptyList(),
    /** Ids der ersetzten Alarme: ihre Alarm-Meldungen gehören zu den alten Alarmen. */
    val replacedAlarmIds: List<Long> = emptyList(),
    /** Ids der ersetzten Portfolio-Alarme (leer, wenn die Sicherung keine enthielt und sie blieben). */
    val replacedPortfolioAlarmIds: List<Long> = emptyList(),
    /** Paare über den Grenzen bzw. mit unzulässiger Kennung ([BackupRestoreLimits]), nicht übernommen (seit Build 99). */
    val droppedPairs: Int = 0,
    /** Alarme über den Grenzen bzw. weggelassener Paare ([BackupRestoreLimits]), nicht übernommen (seit Build 99). */
    val droppedAlarms: Int = 0,
)

/**
 * Was die Rückfrage vor dem Wiederherstellen nennt ([BackupManager.preview]): [invalid] = ungültige Einträge (zu
 * bestätigen, `acceptInvalid`), [droppedPairs]/[droppedAlarms] = über den Grenzen ([BackupRestoreLimits]).
 */
data class RestorePreview(val invalid: Int = 0, val droppedPairs: Int = 0, val droppedAlarms: Int = 0)

/**
 * Die eben geschriebene Sicherungsdatei liest sich nicht so zurück, wie sie geschrieben wurde (Grösse oder Inhalt weichen
 * ab — z. B. ein Cloud-Anbieter, der die Datei kürzt). Die Datei taugt nicht als Sicherung: in eine neue Datei sichern.
 */
class BackupNotVerifiedException(message: String) : java.io.IOException(message)

/**
 * Merkliste oder Portfolio sind in diesem Lauf nur leer im Arbeitsspeicher geöffnet, weil der Keystore den Schlüssel
 * der vorhandenen Datei gerade nicht herausgibt ([com.cryptochecker.app.data.local.DatabaseEncryptionNotice.isStoredKept]).
 * Sichern ergäbe eine leere Datei, Einspielen ginge beim Neustart verloren — beides erst nach einem Neustart.
 */
class BackupStorageKeptException : IllegalStateException("Gespeicherter Stand gerade nicht lesbar")

/**
 * Einspielen abgelehnt: Merkliste oder Portfolio liegen in diesem Lauf nur im Arbeitsspeicher, weil kein Schlüssel da ist
 * ([com.cryptochecker.app.data.local.DatabaseEncryptionNotice.isInMemory]: neu ohne Datei oder Kopie einer alten
 * unverschlüsselten Datei). Das Eingespielte wäre nach dem Neustart weg, die Einstellungen aber schon ersetzt.
 */
class BackupStorageInMemoryException : IllegalStateException("Datenbank nur im Arbeitsspeicher")

/**
 * Seit Build 102: Einspielen abgelehnt, bevor etwas ersetzt wird — das Journal des Wiederherstellens ([RestoreJournal])
 * liess sich nicht schreiben (Keystore-Schlüssel gerade nicht verfügbar oder Speicherfehler). Ohne Journal rollte ein
 * mittendrin beendeter Lauf nichts zurück; später erneut versuchen.
 */
class BackupJournalUnavailableException(cause: Throwable?) :
    IllegalStateException("Journal des Wiederherstellens nicht geschrieben", cause)

/** Sicherung enthält mehr ungültige Portfolio-Einträge, als der Nutzer bestätigt hat. */
class UnconfirmedInvalidEntriesException(val invalid: Int) :
    IllegalStateException("$invalid ungültige Portfolio-Einträge nicht bestätigt")
