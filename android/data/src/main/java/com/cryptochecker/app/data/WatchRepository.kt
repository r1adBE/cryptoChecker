package com.cryptochecker.app.data

import com.cryptochecker.app.domain.watch.WatchFilter
import androidx.room.withTransaction
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.local.WatchDao
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.AlarmWithWatch
import com.cryptochecker.app.data.local.model.GROUP_NAME_MAX
import com.cryptochecker.app.data.local.model.NOTE_MAX
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.domain.refresh.AlarmLogStore
import com.cryptochecker.app.notification.AlarmDeliveryMarkers
import com.cryptochecker.app.domain.watch.WatchEdit
import com.cryptochecker.app.util.TextLimit
import com.cryptochecker.marketdata.model.ExchangePair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Zugriff auf Watchlist und Alarme; Schreibwege des Nutzers fragen zuerst [userWriteRefused] (Sperre der Merkliste). */
@Singleton
class WatchRepository @Inject constructor(
    val database: AppDatabase,
    val watchDao: WatchDao,
    /** Alarm-Protokoll: letzte Prüfungen gelöschter Paare entfernen ([forgetDeletedAlarms]). */
    private val alarmLogStore: AlarmLogStore,
    /** Vermerke «wird gemeldet» gelöschter Alarme aufräumen ([forgetDeletedAlarms], [deleteAlarm]). */
    private val deliveryMarkers: AlarmDeliveryMarkers,
) {
    fun observeWatches(): Flow<List<WatchEntity>> = watchDao.observeWatches()

    fun observeWatch(id: Long): Flow<WatchEntity?> = watchDao.observeWatch(id)

    suspend fun getWatches(): List<WatchEntity> = watchDao.getWatches()

    /**
     * Datenbank öffnen, falls noch nicht geschehen (blockiert, nicht auf dem Hauptthread): Erst danach sagt
     * [com.cryptochecker.app.data.local.DatabaseEncryptionNotice], wie die Merkliste in diesem Prozess liegt. Ein Fehler
     * (z. B. Schlüssel gerade nicht lesbar) zeigt sich beim nächsten Zugriff.
     */
    fun openDatabase() {
        runCatching { database.openHelper.writableDatabase }
    }

    suspend fun getWatch(id: Long): WatchEntity? = watchDao.getWatch(id)

    // Paare anlegen (einzeln, gesammelt, mit Obergrenze): `WatchRepositoryAdd.kt`

    /**
     * «Paar bearbeiten»: Börse, Paar und Kontrakt eines Eintrags ändern — derselbe Eintrag
     * (Id, Gruppe, Favorit, Notiz, Platz, Meldung, Sprachansage, Alarme bleiben). Kurs,
     * 24-h-Wert, Fehler/«nicht gehandelt» und Meldekurs des alten Paars werden verworfen,
     * Alarm-Bezüge am alten Kurs ebenso ([WatchDao.resetPriceReferences]). Steht das neue
     * Paar schon als anderer Eintrag in der Liste: [PairEditResult.DUPLICATE], nichts geändert. Gesperrt
     * ([userWriteRefused]): [PairEditResult.LOCKED].
     */
    suspend fun changePair(id: Long, exchange: ExchangeInfo, pair: ExchangePair): PairEditResult =
        if (userWriteRefused()) PairEditResult.LOCKED else database.withTransaction {
            val watch = watchDao.getWatch(id)
            if (watch == null) {
                PairEditResult.MISSING
            } else {
                val current = WatchEdit.Key.of(
                    watch.exchangeKey, watch.baseAsset, watch.quoteAsset, watch.contractType.name, watch.pairId,
                )
                val target = WatchEdit.Key.of(exchange.key, pair.base, pair.quote, pair.contractType.name, pair.pairId)
                val existing =
                    watchDao.findWatchId(target.exchangeKey, target.base, target.quote, target.contract, target.pool)
                when (WatchEdit.decide(id, current, target, existing)) {
                    WatchEdit.Outcome.UNCHANGED -> PairEditResult.UNCHANGED
                    WatchEdit.Outcome.DUPLICATE -> PairEditResult.DUPLICATE
                    WatchEdit.Outcome.CHANGED -> {
                        watchDao.updateWatch(
                            watch.copy(
                                exchangeKey = exchange.key,
                                exchangeName = exchange.name,
                                baseAsset = pair.base,
                                quoteAsset = pair.quote,
                                contractType = pair.contractType,
                                pairId = pair.pairId,
                                lastPrice = null,
                                previousPrice = null,
                                lastUpdate = 0,
                                notifiedPrice = null,
                                notifiedAt = 0,
                                lastError = null,
                                change24h = null,
                                priceChangedAt = 0,
                                exchangeTime = null,
                            )
                        )
                        watchDao.resetPriceReferences(id)
                        PairEditResult.SAVED
                    }
                }
            }
        }

    /** Nach dem ersten Kurs des bearbeiteten Paars: Prozentalarme messen ab diesem Kurs. */
    suspend fun setMissingPercentReferences(watchId: Long, price: Double) {
        if (!userWriteRefused(notify = false)) watchDao.setMissingPercentReferences(watchId, price)
    }

    /** true = gelöscht; false = abgelehnt (eindeutig: dann keine Folgeaktionen wie Rückgängig/Mitteilungen). */
    suspend fun deleteWatch(id: Long): Boolean {
        if (userWriteRefused()) return false
        watchDao.deleteWatch(id)
        forgetDeletedAlarms()
        return true
    }

    /** Mehrere Paare (samt Alarmen, per Fremdschlüssel) in einem Vorgang löschen; Rückgabe wie [deleteWatch]. */
    suspend fun deleteWatches(ids: Collection<Long>): Boolean {
        if (ids.isEmpty() || userWriteRefused()) return false
        database.withTransaction { ids.forEach { watchDao.deleteWatch(it) } }
        forgetDeletedAlarms()
        return true
    }

    /**
     * Nach dem Löschen von Paaren (Alarme per Fremdschlüssel weg): deren letzte Prüfungen aus dem Alarm-Protokoll
     * entfernen — sonst wüchse der Stand, den jeder Durchlauf versiegelt schreibt, mit jedem gelöschten
     * Paar. Versiegeltes Schreiben nicht auf dem Hauptfaden.
     */
    private suspend fun forgetDeletedAlarms() {
        val ids = watchDao.getAlarmIds().toHashSet()
        withContext(Dispatchers.IO) {
            alarmLogStore.retainAlarms(ids)
            // Nicht bei Kopie im Speicher — der nächste Prozess bringt die Alarme der Datei zurück
            if (AlarmDeliveryMarkers.prunable()) deliveryMarkers.retain(ids)
        }
    }

    /** Paar mit allen Alarmen festhalten (vor dem Löschen); null, wenn es das Paar nicht gibt. */
    suspend fun snapshot(id: Long): WatchSnapshot? = database.withTransaction {
        watchDao.getWatch(id)?.let { WatchSnapshot(it, watchDao.getAlarms(id)) }
    }

    // Gelöschtes Paar zurückholen: [restore] in WatchRepositoryAdd.kt (mit Obergrenze der Merkliste)

    /** Rückgabe wie [deleteWatch]. */
    suspend fun deleteAllWatches(): Boolean {
        if (userWriteRefused()) return false
        watchDao.deleteAllWatches()
        forgetDeletedAlarms()
        return true
    }

    /** true = geschrieben, false = abgelehnt (Merkliste gesperrt): dann keine Folgeaktionen (Mitteilung zeigen/entfernen). */
    suspend fun setNotificationEnabled(id: Long, enabled: Boolean): Boolean {
        if (userWriteRefused()) return false
        watchDao.setNotificationEnabled(id, enabled)
        return true
    }

    /** true = geschrieben, false = abgelehnt (wie [setDisabled]): dann keine Rückmeldung (Banner, Haptik). */
    suspend fun setFavorite(id: Long, favorite: Boolean): Boolean {
        if (userWriteRefused()) return false
        watchDao.setFavorite(id, favorite)
        return true
    }

    /**
     * Verschiebt ein Paar innerhalb seiner Abteilung (Favoriten bzw. übrige —
     * Favoriten bleiben immer oben). Mit [group] nur unter den Paaren dieser
     * Gruppe (gefilterte Ansicht); ausgeblendete Paare behalten ihren Platz.
     * Danach wird die ganze Liste fortlaufend neu nummeriert, so stimmt die
     * Reihenfolge auch bei gleichen Altwerten.
     */
    suspend fun move(
        id: Long,
        move: WatchMove,
        group: String? = null,
        /** Ansicht «Alpha»: ist das Paar ein Alpha-Token? ([WatchFilter.ALPHA]) */
        isAlpha: (WatchEntity) -> Boolean = { false },
    ) {
        if (userWriteRefused()) return
        val all = watchDao.getWatches()                     // schon in Anzeige-Reihenfolge
        val watch = all.firstOrNull { it.id == id } ?: return
        val section = all.filter {
            it.favorite == watch.favorite && WatchFilter.matches(group, it.groupName, it.favorite, isAlpha(it))
        }.toMutableList()
        val from = section.indexOfFirst { it.id == id }
        // Paar gehört nicht zur gefilterten Gruppe: nichts zu verschieben
        if (from < 0) return
        val to = when (move) {
            WatchMove.TOP -> 0
            WatchMove.UP -> (from - 1).coerceAtLeast(0)
            WatchMove.DOWN -> (from + 1).coerceAtMost(section.lastIndex)
            WatchMove.BOTTOM -> section.lastIndex
        }
        if (from == to) return
        section.add(to, section.removeAt(from))

        saveOrder(placeInSlots(all, section))
    }

    /**
     * Übernimmt eine per Ziehen festgelegte Reihenfolge der sichtbaren Paare.
     * In einer gefilterten Ansicht enthält [orderedIds] nur die Paare der
     * Gruppe: Sie tauschen untereinander die Plätze, alle anderen bleiben, wo
     * sie sind. Favoriten bleiben trotzdem immer vor den übrigen.
     */
    suspend fun reorder(orderedIds: List<Long>) {
        if (userWriteRefused()) return
        val all = watchDao.getWatches()
        val byId = all.associateBy { it.id }
        val wanted = orderedIds.distinct().mapNotNull { byId[it] }
        saveOrder(placeInSlots(all, wanted))
    }

    /**
     * Setzt [subset] in neuer Reihenfolge auf die Plätze, die seine Paare in
     * [all] bisher belegen; die übrigen Paare bleiben unverändert.
     */
    private fun placeInSlots(all: List<WatchEntity>, subset: List<WatchEntity>): List<WatchEntity> {
        val ids = subset.map { it.id }.toSet()
        val slots = all.indices.filter { all[it].id in ids }
        val result = all.toMutableList()
        slots.forEachIndexed { i, slot -> result[slot] = subset[i] }
        // Favoriten bleiben in jedem Fall oben (stabile Aufteilung).
        return result.filter { it.favorite } + result.filterNot { it.favorite }
    }

    private suspend fun saveOrder(ordered: List<WatchEntity>) {
        database.withTransaction {
            ordered.forEachIndexed { index, w ->
                if (w.sortOrder != index) watchDao.setSortOrder(w.id, index)
            }
        }
    }

    /** Notiz setzen; null oder leer = keine Notiz. */
    suspend fun setNote(id: Long, note: String?) {
        if (!userWriteRefused()) watchDao.setNote(id, TextLimit.clean(note, NOTE_MAX))
    }

    /**
     * Gruppe setzen (höchstens [GROUP_NAME_MAX] Zeichen); null oder leer = keine Gruppe.
     * true = geschrieben, false = abgelehnt: die Mehrfachauswahl bleibt dann stehen.
     */
    suspend fun setGroup(id: Long, groupName: String?): Boolean {
        if (userWriteRefused()) return false
        watchDao.setGroup(id, TextLimit.clean(groupName, GROUP_NAME_MAX))
        return true
    }

    /**
     * «Gruppe bearbeiten» in einem Vorgang: Die bisherige Gruppe [oldName]
     * (null = neue Gruppe) wird aufgelöst, danach erhalten genau [memberIds]
     * den Namen [newName]. Das deckt Umbenennen, Hinzufügen, Entfernen und
     * Verschieben aus anderen Gruppen ab. Gleicht [newName] einer anderen
     * bestehenden Gruppe, werden beide zusammengeführt.
     */
    suspend fun applyGroupEdit(oldName: String?, newName: String, memberIds: Set<Long>) {
        val name = TextLimit.clean(newName, GROUP_NAME_MAX) ?: return
        if (userWriteRefused()) return
        database.withTransaction {
            if (oldName != null) watchDao.clearGroup(oldName)
            memberIds.forEach { watchDao.setGroup(it, name) }
        }
    }

    /** Gruppe löschen: Die Paare bleiben in der Merkliste, nur ohne Gruppe. */
    suspend fun clearGroup(groupName: String) {
        if (!userWriteRefused()) watchDao.clearGroup(groupName)
    }

    /** true = geschrieben, false = abgelehnt (Merkliste gesperrt): dann keine Folgeaktionen (Sprachausgabe umstellen). */
    suspend fun setTtsEnabled(id: Long, enabled: Boolean): Boolean {
        if (userWriteRefused()) return false
        watchDao.setTtsEnabled(id, enabled)
        return true
    }

    /** Paare deaktivieren ([disabled] = true) bzw. wieder aktivieren; true = geschrieben. */
    suspend fun setDisabled(ids: Collection<Long>, disabled: Boolean): Boolean {
        if (ids.isEmpty() || userWriteRefused()) return false
        watchDao.setDisabled(ids.toList(), disabled)
        return true
    }

    /** Meldekurs nach dem Ein- bzw. Ausschalten der Kurs-Mitteilung (Folge-Schreibweg: gesperrt ohne eigenen Hinweis). */
    suspend fun setNotifiedPrice(id: Long, price: Double?, time: Long = System.currentTimeMillis()) {
        if (!userWriteRefused(notify = false)) watchDao.setNotifiedPrice(id, price, if (price == null) 0 else time)
    }

    /** Veränderung der Paare [ids] entfernen (in Blöcken, Grenze der SQL-Variablen). */
    suspend fun clearChanges(ids: List<Long>) {
        if (ids.isEmpty()) return
        database.withTransaction { ids.chunked(500).forEach { watchDao.clearChanges(it) } }
    }

    // ---------------- Alarme ----------------

    fun observeAlarms(watchId: Long): Flow<List<AlarmEntity>> = watchDao.observeAlarms(watchId)

    fun observeAllAlarms(): Flow<List<AlarmWithWatch>> = watchDao.observeAllAlarms()

    fun observeEnabledAlarmCount(watchId: Long): Flow<Int> =
        watchDao.observeEnabledAlarmCount(watchId)

    suspend fun getEnabledAlarms(watchId: Long): List<AlarmEntity> =
        watchDao.getEnabledAlarms(watchId)

    /** Alle aktiven Alarme auf einmal — statt einer Abfrage je Paar. */
    suspend fun getAllEnabledAlarms(): List<AlarmEntity> = watchDao.getAllEnabledAlarms()

    /** Ids aller Alarme (auch ausgeschalteter): ihre Mitteilungen vor dem Löschen aller Paare entfernen. */
    suspend fun getAllAlarmIds(): List<Long> = watchDao.getAllAlarms().map { it.id }

    /** Paare mit mindestens einem aktiven Alarm (für die Planung der Hintergrund-Aktualisierung). */
    fun observeWatchIdsWithEnabledAlarms(): Flow<List<Long>> = watchDao.observeWatchIdsWithEnabledAlarms()

    /**
     * Neuer bzw. bearbeiteter Alarm; null = nicht gespeichert (Paar voll oder abgelehnt). Nur Kurzform
     * von [trySaveAlarm] (`WatchRepositoryAlarmSave.kt`), das «voll» und «abgelehnt» unterscheidet.
     */
    suspend fun saveAlarm(alarm: AlarmEntity): Long? = (trySaveAlarm(alarm) as? AlarmSaveResult.Saved)?.alarm?.id

    /** Kurzform von [trySaveAlarmMerged]; null = nicht gespeichert (Paar voll oder abgelehnt). */
    suspend fun saveAlarmMerged(id: Long, build: (AlarmEntity?) -> AlarmEntity): AlarmEntity? =
        (trySaveAlarmMerged(id, build) as? AlarmSaveResult.Saved)?.alarm

    /** true = gelöscht; false = abgelehnt (dann keine Folgeaktionen wie Mitteilung/Protokoll entfernen). */
    suspend fun deleteAlarm(id: Long): Boolean {
        if (userWriteRefused()) return false
        watchDao.deleteAlarm(id)
        // Vermerk des gelöschten Alarms weg (eine neue Id könnte ihn sonst erben); nicht bei
        // Kopie im Speicher (der Alarm kommt mit dem nächsten Prozess aus der Datei zurück)
        if (AlarmDeliveryMarkers.prunable()) withContext(Dispatchers.IO) { deliveryMarkers.remove(listOf(id)) }
        return true
    }

    /** true = geschrieben, false = abgelehnt (Merkliste gesperrt): dann keine Folgeaktionen (Mitteilung entfernen). */
    suspend fun setAlarmEnabled(id: Long, enabled: Boolean, fromUser: Boolean = true): Boolean {
        if (fromUser && userWriteRefused()) return false // Hintergrund («1 h später») nie gesperrt
        // FUN3-02: «fällt um ≥ 100 %» löst nie aus — nicht einschalten (erst im Editor anpassen)
        val alarm = if (enabled) watchDao.getAlarm(id) else null
        if (alarm != null && !AlarmJson.toggleAllowed(alarm.condition, alarm.threshold, enable = true)) return false
        watchDao.setAlarmEnabled(id, enabled)
        // Wieder eingeschaltet: «Nahe am Hoch/Tief», Kursmarken, Funding und Open Interest melden wieder (wirkt nur bei diesen)
        if (enabled) watchDao.rearmAlarm(id)
        return true
    }

    companion object {
        /**
         * Kurs-Mitteilung neuer Paare (Start-Auswahl und später hinzugefügte): an (Entscheid 16. Oktober 2026 —
         * wer einen Kurs-Checker installiert, will den Kurs sehen; vorher aus). Bestehende Paare und gesicherte
         * Werte bleiben, wie sie sind (Sicherung: [BackupManager]). Auswahl je Paar: Einstellungen › Alarme ›
         * Kursmitteilungen › Paare. Wie iOS `Watch.newPairNotificationEnabled`.
         */
        const val DEFAULT_NOTIFICATION_ENABLED = true
    }
}
