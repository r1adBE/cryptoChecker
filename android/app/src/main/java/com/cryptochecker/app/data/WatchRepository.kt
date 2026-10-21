package com.cryptochecker.app.data

import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.watch.WatchFilter
import androidx.room.withTransaction
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.local.WatchDao
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.AlarmWithWatch
import com.cryptochecker.app.data.local.model.GROUP_NAME_MAX
import com.cryptochecker.app.data.local.model.NOTE_MAX
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.domain.watch.WatchEdit
import com.cryptochecker.app.domain.watch.WatchIdentity
import com.cryptochecker.app.domain.watch.WatchlistLimits
import com.cryptochecker.app.util.TextLimit
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.FuturesContractType
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Zugriff auf Watchlist und Alarme. */
@Singleton
class WatchRepository @Inject constructor(
    internal val database: AppDatabase,
    internal val watchDao: WatchDao,
) {
    fun observeWatches(): Flow<List<WatchEntity>> = watchDao.observeWatches()

    fun observeWatch(id: Long): Flow<WatchEntity?> = watchDao.observeWatch(id)

    suspend fun getWatches(): List<WatchEntity> {
        // Einmal je Prozess vor dem ersten Lesen (Aktualisierung, Widgets, Alarme): Vertragsarten angleichen
        if (!contractTypesNormalized) {
            contractTypesNormalized = true
            runCatchingCancellable { normalizeContractTypes() }
        }
        return watchDao.getWatches()
    }

    @Volatile
    private var contractTypesNormalized = false

    /**
     * Datenbank öffnen, falls noch nicht geschehen (blockiert, nicht auf dem Hauptthread): Erst danach sagt
     * [com.cryptochecker.app.data.local.DatabaseEncryptionNotice], wie die Merkliste in diesem Prozess liegt. Ein Fehler
     * (z. B. Schlüssel gerade nicht lesbar) zeigt sich beim nächsten Zugriff.
     */
    fun openDatabase() {
        runCatching { database.openHelper.writableDatabase }
    }

    suspend fun getWatch(id: Long): WatchEntity? = watchDao.getWatch(id)

    /**
     * Inverse Perpetuals (Binance COIN-M, OKX «-USD-SWAP», Deribit BTC/ETH), die vor Build 47 als PERPETUAL
     * gespeichert wurden, auf INVERSE_PERPETUAL umstellen — wie die Paarlisten sie heute liefern. Kennung,
     * Kurs, Alarme bleiben; nur so passt die Dublettenprüfung zur Paarliste. Einmal je Prozess ([getWatches]; wirkt
     * auch nach dem Wiederherstellen einer älteren Sicherung), ohne passende Einträge ohne Schreibzugriff.
     */
    suspend fun normalizeContractTypes() {
        val ids = watchDao.getWatches()
            .filter { FuturesContractType.normalized(it.marketKey, it.pairId, it.contractType) != it.contractType }
            .map { it.id }
        if (ids.isNotEmpty()) watchDao.updateContractType(ids, FuturesContractType.INVERSE_PERPETUAL.name)
    }

    // Paare anlegen (einzeln, gesammelt, mit Obergrenze): `WatchRepositoryAdd.kt`

    /**
     * «Paar bearbeiten»: Börse, Paar und Kontrakt eines Eintrags ändern — derselbe Eintrag
     * (Id, Gruppe, Favorit, Notiz, Platz, Meldung, Sprachansage, Alarme bleiben). Kurs,
     * 24-h-Wert, Fehler/«nicht gehandelt» und Meldekurs des alten Paars werden verworfen,
     * Alarm-Bezüge am alten Kurs ebenso ([WatchDao.resetPriceReferences]). Steht das neue
     * Paar schon als anderer Eintrag in der Liste: [PairEditResult.DUPLICATE], nichts geändert.
     */
    suspend fun changePair(id: Long, market: MarketInfo, pair: MarketPair): PairEditResult =
        database.withTransaction {
            val watch = watchDao.getWatch(id)
            if (watch == null) {
                PairEditResult.MISSING
            } else {
                val current = WatchEdit.Key.of(
                    watch.marketKey, watch.baseAsset, watch.quoteAsset, watch.contractType.name, watch.pairId,
                )
                val target = WatchEdit.Key.of(market.key, pair.base, pair.quote, pair.contractType.name, pair.pairId)
                val existing =
                    watchDao.findWatchId(target.marketKey, target.base, target.quote, target.contract, target.pool)
                when (WatchEdit.decide(id, current, target, existing)) {
                    WatchEdit.Outcome.UNCHANGED -> PairEditResult.UNCHANGED
                    WatchEdit.Outcome.DUPLICATE -> PairEditResult.DUPLICATE
                    WatchEdit.Outcome.CHANGED -> {
                        watchDao.updateWatch(
                            watch.copy(
                                marketKey = market.key,
                                marketName = market.name,
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
    suspend fun setMissingPercentReferences(watchId: Long, price: Double) =
        watchDao.setMissingPercentReferences(watchId, price)

    suspend fun deleteWatch(id: Long) = watchDao.deleteWatch(id)

    /** Mehrere Paare (samt Alarmen, per Fremdschlüssel) in einem Vorgang löschen. */
    suspend fun deleteWatches(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        database.withTransaction { ids.forEach { watchDao.deleteWatch(it) } }
    }

    /** Paar mit allen Alarmen festhalten (vor dem Löschen); null, wenn es das Paar nicht gibt. */
    suspend fun snapshot(id: Long): WatchSnapshot? = database.withTransaction {
        watchDao.getWatch(id)?.let { WatchSnapshot(it, watchDao.getAlarms(id)) }
    }

    /**
     * Gelöschtes Paar unverändert zurückholen: gleiche Id (Ids werden nie neu vergeben,
     * Room-Primärschlüssel mit AUTOINCREMENT), gleicher Platz, Gruppe, Notiz, Favorit,
     * Einstellungen und alle Alarme mit ihren Ids — in einem Vorgang.
     * false, wenn das nicht geht (Paar besteht noch oder wurde inzwischen neu angelegt).
     */
    suspend fun restore(snapshot: WatchSnapshot): Boolean = database.withTransaction {
        val watch = snapshot.watch
        val blocked = watchDao.getWatch(watch.id) != null ||
            watchDao.findWatchId(
                watch.marketKey, watch.baseAsset, watch.quoteAsset, watch.contractType.name,
                WatchIdentity.pool(watch.marketKey, watch.pairId),
            ) != null
        if (blocked) {
            false
        } else {
            val id = watchDao.insertWatch(watch)
            if (id == watch.id) {
                snapshot.alarms.forEach { watchDao.insertAlarm(it.copy(watchId = watch.id)) }
                true
            } else {
                false
            }
        }
    }

    suspend fun deleteAllWatches() = watchDao.deleteAllWatches()

    suspend fun setNotificationEnabled(id: Long, enabled: Boolean) =
        watchDao.setNotificationEnabled(id, enabled)

    suspend fun setFavorite(id: Long, favorite: Boolean) = watchDao.setFavorite(id, favorite)

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
    suspend fun setNote(id: Long, note: String?) =
        watchDao.setNote(id, TextLimit.clean(note, NOTE_MAX))

    /** Gruppe setzen (höchstens [GROUP_NAME_MAX] Zeichen); null oder leer = keine Gruppe. */
    suspend fun setGroup(id: Long, groupName: String?) =
        watchDao.setGroup(id, TextLimit.clean(groupName, GROUP_NAME_MAX))

    /**
     * «Gruppe bearbeiten» in einem Vorgang: Die bisherige Gruppe [oldName]
     * (null = neue Gruppe) wird aufgelöst, danach erhalten genau [memberIds]
     * den Namen [newName]. Das deckt Umbenennen, Hinzufügen, Entfernen und
     * Verschieben aus anderen Gruppen ab. Gleicht [newName] einer anderen
     * bestehenden Gruppe, werden beide zusammengeführt.
     */
    suspend fun applyGroupEdit(oldName: String?, newName: String, memberIds: Set<Long>) {
        val name = TextLimit.clean(newName, GROUP_NAME_MAX) ?: return
        database.withTransaction {
            if (oldName != null) watchDao.clearGroup(oldName)
            memberIds.forEach { watchDao.setGroup(it, name) }
        }
    }

    /** Gruppe löschen: Die Paare bleiben in der Merkliste, nur ohne Gruppe. */
    suspend fun clearGroup(groupName: String) = watchDao.clearGroup(groupName)

    suspend fun setTtsEnabled(id: Long, enabled: Boolean) =
        watchDao.setTtsEnabled(id, enabled)

    /** Paare deaktivieren ([disabled] = true) bzw. wieder aktivieren. */
    suspend fun setDisabled(ids: Collection<Long>, disabled: Boolean) {
        if (ids.isNotEmpty()) watchDao.setDisabled(ids.toList(), disabled)
    }

    suspend fun setNotifiedPrice(id: Long, price: Double?, time: Long = System.currentTimeMillis()) =
        watchDao.setNotifiedPrice(id, price, if (price == null) 0 else time)

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
     * Neuer Alarm (id 0) nur unter der Obergrenze je Paar ([WatchlistLimits.MAX_ALARMS_PER_PAIR], Zählen und Einfügen
     * in einem Vorgang); null = Paar hat schon so viele. Bearbeiten (feste id) zählt nicht.
     */
    suspend fun saveAlarm(alarm: AlarmEntity): Long? =
        if (alarm.id == 0L) database.withTransaction {
            if (WatchlistLimits.canAddAlarm(watchDao.getAlarms(alarm.watchId).size)) watchDao.insertAlarm(alarm) else null
        } else {
            watchDao.updateAlarm(alarm)
            alarm.id
        }

    suspend fun deleteAlarm(id: Long) = watchDao.deleteAlarm(id)

    suspend fun setAlarmEnabled(id: Long, enabled: Boolean) {
        watchDao.setAlarmEnabled(id, enabled)
        // Wieder eingeschaltet: «Nahe am Hoch/Tief», Kursmarken, Funding und Open Interest melden wieder (wirkt nur bei diesen)
        if (enabled) watchDao.rearmAlarm(id)
    }

    companion object {
        /**
         * Kurs-Mitteilung neuer Paare (Start-Auswahl und später hinzugefügte): an (Entscheid 16. Oktober 2026 —
         * wer einen Kurs-Checker installiert, will den Kurs sehen; vorher aus). Bestehende Paare und gesicherte
         * Werte bleiben, wie sie sind (Sicherung: [BackupManager]). Auswahl je Paar: Einstellungen › Alarme ›
         * Kursmitteilungen › Paare. Wie iOS `Watch.newPairNotificationEnabled`.
         */
        const val DEFAULT_NOTIFICATION_ENABLED = true

        /**
         * Gesicherte Paare OHNE Angabe: an. Bis Build 41 hatte jedes Paar die Kurs-Benachrichtigung
         * standardmässig an (Spalten-Standard, iOS ebenso); fehlt das Feld, stammt die Sicherung aus
         * so einer Version — «an» erhält ihr damaliges Verhalten.
         */
        const val LEGACY_NOTIFICATION_ENABLED = true
    }
}
