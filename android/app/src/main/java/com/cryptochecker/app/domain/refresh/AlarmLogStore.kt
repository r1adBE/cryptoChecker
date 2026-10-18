package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.domain.alarm.AlarmLog
import com.cryptochecker.app.domain.alarm.AlarmLogEntry
import com.cryptochecker.app.domain.alarm.AlarmLogState
import com.cryptochecker.app.widget.PortfolioSnapshotSeal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.crypto.SecretKey

/**
 * Speicher des Alarm-Protokolls ([AlarmLog]), neben «Letzte Prüfungen» ([CheckRunStore]): eine Datei in
 * `noBackupFilesDir` (nie in einer Sicherung, nur dieses Gerät). Inhalt mit Paaren und Schwellen wie die Merkliste, die
 * verschlüsselt in der Datenbank liegt — darum versiegelt (AES-GCM, Schlüssel im Keystore unter eigenem Alias
 * [KEY_ALIAS], wie [com.cryptochecker.app.data.RestoreJournal]); ohne Schlüssel nie Klartext: dann bleibt der Stand nur
 * im Speicher. Bereitgestellt von `LocalDataModule`, geschrieben von Hintergrund-Prüfung und Aktualisierung in der App
 * ([RefreshResultProcessor]).
 *
 * Darf die Prüfung nie stören: jeder Fehler bleibt still, ein Schreibvorgang je Durchlauf, Live-Kurse höchstens alle
 * [AlarmLog.LIVE_WRITE_INTERVAL_MILLIS] (ein neuer Protokoll-Eintrag sofort, [AlarmLog.startsEvent]).
 */
class AlarmLogStore(
    private val file: File,
    private val keys: PortfolioSnapshotSeal.Keys,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val _state = MutableStateFlow(AlarmLogState.EMPTY)

    /** Stand für die Anzeige; erst nach [load] (bzw. dem ersten Schreiben) gefüllt. */
    val state: StateFlow<AlarmLogState> = _state.asStateFlow()

    private var loaded = false
    private var cachedKey: SecretKey? = null
    private var lastWriteAt = 0L
    private var dirty = false

    /**
     * Seit dem Start notiert bzw. geändert (Alarm gelöscht, «Protokoll löschen»), aber die Datei war noch nicht lesbar
     * (Keystore gerade nicht erreichbar): beim ersten Lesen der Reihe nach auf den Stand der Datei angewendet — sonst
     * kämen gelöschte letzte Prüfungen bzw. geleerte Einträge mit der Datei zurück.
     */
    private val unloadedSteps = ArrayList<(AlarmLogState, Long) -> AlarmLogState>()
    private var unloadedEntryCount = 0
    private var unloadedChanges = false

    /** Datei lesen (abseits des Hauptfadens). */
    suspend fun load() = withContext(Dispatchers.IO) { synchronized(lock) { ensureLoaded() } }

    /** Einträge eines Durchlaufs übernehmen und (ausser bei gedrosselten Live-Kursen) speichern. Wirft nie. */
    fun record(entries: List<AlarmLogEntry>, live: Boolean) {
        if (entries.isEmpty()) return
        runCatching {
            synchronized(lock) {
                val now = clock()
                if (!ensureLoaded()) {
                    // Nicht lesbar: nichts überschreiben, im Speicher behalten und beim nächsten Lesen nachtragen
                    if (unloadedEntryCount < MAX_UNLOADED) {
                        val batch = entries.toList()
                        unloadedSteps += { state, at -> AlarmLog.record(state, batch, at) }
                        unloadedEntryCount += entries.size
                    }
                    _state.value = AlarmLog.record(_state.value, entries, now)
                    return@synchronized
                }
                // Sofort nur, was einen neuen Eintrag beginnt; das Verlängern eines «von … bis …» wartet wie jede Prüfung
                val startsEvent = AlarmLog.startsEvent(_state.value, entries)
                _state.value = AlarmLog.record(_state.value, entries, now)
                dirty = true
                if (!live || startsEvent || now - lastWriteAt >= AlarmLog.LIVE_WRITE_INTERVAL_MILLIS || now < lastWriteAt) {
                    persist(now)
                }
            }
        }.onFailure { Timber.d(it, "Alarm-Protokoll: nicht übernommen") }
    }

    /** Alarm gelöscht: seine letzte Prüfung entfernen. */
    fun removeAlarm(alarmId: Long) = update { AlarmLog.removeAlarm(it, alarmId) }

    /** Nur letzte Prüfungen bestehender Alarme behalten. */
    fun retainAlarms(alarmIds: Set<Long>) = update { AlarmLog.retainAlarms(it, alarmIds) }

    /** «Protokoll löschen». */
    fun clearEvents() = update { AlarmLog.clearEvents(it) }

    private fun update(change: (AlarmLogState) -> AlarmLogState) {
        runCatching {
            synchronized(lock) {
                val readable = ensureLoaded()
                if (!readable) {
                    // Nach dem Lesen der Datei erneut anwenden (auch wenn der Stand im Speicher schon passt)
                    unloadedSteps += { state, _ -> change(state) }
                    unloadedChanges = true
                }
                val next = change(_state.value)
                if (next == _state.value) return@synchronized
                _state.value = next
                dirty = true
                if (readable) persist(clock())
            }
        }.onFailure { Timber.d(it, "Alarm-Protokoll: nicht geändert") }
    }

    /** true = Datei gelesen (oder keine da); false = Schlüssel gerade nicht verfügbar. Nur unter [lock]. */
    private fun ensureLoaded(): Boolean {
        if (loaded) return true
        val now = clock()
        if (!file.exists()) {
            finishLoad(AlarmLogState.EMPTY, now)
            return true
        }
        val key = key() ?: return false
        // Lesefehler (E/A): wie ein gesperrter Schlüssel — später erneut, die Datei nie durch einen leeren Stand ersetzen
        val stored = runCatching { file.readText(Charsets.US_ASCII) }.getOrNull() ?: return false
        val text = try {
            PortfolioSnapshotSeal.open(key, stored)
        } catch (e: Exception) {
            // Keystore-Fehler beim Öffnen: später erneut, nichts überschreiben
            keys.discardIfBroken(e)
            cachedKey = null
            return false
        }
        // Beschädigt oder anderer Schlüssel: nur ein Blick zurück — neu beginnen, die nächste Speicherung ersetzt die Datei
        finishLoad(AlarmLog.decode(text), now)
        return true
    }

    /** Gelesenen Stand übernehmen und die Schritte aus der Zeit davor nachtragen. Nur unter [lock]. */
    private fun finishLoad(fromDisk: AlarmLogState, now: Long) {
        var state = AlarmLog.record(fromDisk, emptyList(), now)
        for (step in unloadedSteps) state = step(state, now)
        _state.value = state
        val replayedChanges = unloadedChanges
        unloadedSteps.clear()
        unloadedEntryCount = 0
        unloadedChanges = false
        loaded = true
        // Löschungen gleich festhalten (die neuen Prüfungen schreibt das nächste Speichern ohnehin mit)
        if (replayedChanges) {
            dirty = true
            persist(now)
        }
    }

    private fun key(): SecretKey? {
        cachedKey?.let { return it }
        val found = runCatching { (keys.lookup() as? PortfolioSnapshotSeal.KeyLookup.Found)?.key }.getOrNull()
        cachedKey = found
        return found
    }

    /** Versiegelt und atomar schreiben (erst Nachbardatei, dann umbenennen). Nur unter [lock]. */
    private fun persist(now: Long) {
        if (!dirty) return
        val key = key() ?: return
        val sealed = try {
            PortfolioSnapshotSeal.seal(key, AlarmLog.encode(_state.value))
        } catch (e: Exception) {
            keys.discardIfBroken(e)
            cachedKey = null
            return
        }
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(sealed, Charsets.US_ASCII)
            if (!tmp.renameTo(file)) {
                file.writeText(sealed, Charsets.US_ASCII)
                tmp.delete()
            }
            lastWriteAt = now
            dirty = false
        }.onFailure { Timber.d(it, "Alarm-Protokoll: nicht geschrieben") }
    }

    companion object {
        const val FILE_NAME = "alarm_log.sealed"

        /** Eigener Keystore-Alias: unabhängig von Datenbank, Widget-Aufnahme und Journal. */
        const val KEY_ALIAS = "cryptochecker_alarm_log_v1"

        /** Höchstens so viele Einträge warten im Speicher, solange die Datei nicht lesbar ist. */
        private const val MAX_UNLOADED = 2_000
    }
}
