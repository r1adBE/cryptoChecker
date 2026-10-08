package com.cryptochecker.app.data

import androidx.room.withTransaction
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.local.WatchDao
import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.AlarmWithWatch
import com.cryptochecker.app.data.local.model.NOTE_MAX
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.domain.watch.AutoGroup
import com.cryptochecker.app.domain.watch.WatchEdit
import com.cryptochecker.marketdata.model.CurrencyPairInfo
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Ergebnis einer Sammelaufnahme in die Watchlist. */
data class BulkAddResult(
    val added: Int,
    val skipped: Int,
)

/** Richtung beim manuellen Sortieren. */
enum class WatchMove { TOP, UP, DOWN, BOTTOM }

/**
 * Alles, was zu einem Paar in der Datenbank steht: der Eintrag selbst (Id, Platz,
 * Gruppe, Notiz, Favorit, Meldung, Sprachansage …) und seine Alarme mit ihren Ids.
 * Grundlage für «Rückgängig» nach dem Löschen per Wischen.
 */
data class WatchSnapshot(val watch: WatchEntity, val alarms: List<AlarmEntity>)

/** Ergebnis von «Paar bearbeiten» ([WatchRepository.changePair]). */
enum class PairEditResult { SAVED, UNCHANGED, DUPLICATE, MISSING }

/** Neuer Kurs für ein Paar; [change24h] siehe `WatchEntity.change24h` (null = kein 24-h-Bezug). */
data class PriceWrite(val id: Long, val price: Double, val time: Long, val change24h: Double?)

/** Fehlgeschlagene Abfrage für ein Paar. */
data class ErrorWrite(val id: Long, val error: String?, val time: Long)

/** Zugriff auf Watchlist und Alarme. */
@Singleton
class WatchRepository @Inject constructor(
    private val database: AppDatabase,
    private val watchDao: WatchDao,
) {
    fun observeWatches(): Flow<List<WatchEntity>> = watchDao.observeWatches()

    fun observeWatch(id: Long): Flow<WatchEntity?> = watchDao.observeWatch(id)

    suspend fun getWatches(): List<WatchEntity> = watchDao.getWatches()

    suspend fun getWatch(id: Long): WatchEntity? = watchDao.getWatch(id)

    /**
     * Legt ein Paar in der Watchlist an.
     * @param notificationEnabled Kurs-Benachrichtigung des Paares (Start-Merkliste: aus)
     * @return die Id des Eintrags, oder null, wenn er bereits vorhanden war.
     */
    suspend fun addWatch(
        market: MarketInfo,
        pair: CurrencyPairInfo,
        group: String? = null,
        notificationEnabled: Boolean = true,
    ): Long? {
        val existing = watchDao.findWatchId(
            market.key,
            pair.currencyBase,
            pair.currencyCounter,
            pair.contractType.name
        )
        if (existing != null) return null

        val id = watchDao.insertWatch(
            WatchEntity(
                marketKey = market.key,
                marketName = market.name,
                baseAsset = pair.currencyBase,
                quoteAsset = pair.currencyCounter,
                contractType = pair.contractType,
                pairId = pair.currencyPairId,
                sortOrder = watchDao.nextSortOrder(),
                // Nur neue Paare erhalten die gewählte Gruppe; bestehende bleiben unverändert.
                // Ohne Wahl: TradFi-Futures nach «TradFi», Laufzeit-Futures nach «QTLY».
                groupName = AutoGroup.resolve(group, pair),
                notificationEnabled = notificationEnabled,
            )
        )
        return if (id > 0) id else null
    }

    /**
     * Legt mehrere Paare auf einmal an. Bereits vorhandene werden übersprungen,
     * nicht doppelt eingetragen.
     */
    suspend fun addWatches(
        market: MarketInfo,
        pairs: List<CurrencyPairInfo>,
        group: String? = null,
    ): BulkAddResult {
        var added = 0
        var skipped = 0

        for (pair in pairs) {
            if (addWatch(market, pair, group) != null) added++ else skipped++
        }

        return BulkAddResult(added = added, skipped = skipped)
    }

    /**
     * «Paar bearbeiten»: Börse, Paar und Kontrakt eines Eintrags ändern — derselbe Eintrag
     * (Id, Gruppe, Favorit, Notiz, Platz, Meldung, Sprachansage, Alarme bleiben). Kurs,
     * 24-h-Wert, Fehler/«nicht gehandelt» und Meldekurs des alten Paars werden verworfen,
     * Alarm-Bezüge am alten Kurs ebenso ([WatchDao.resetPriceReferences]). Steht das neue
     * Paar schon als anderer Eintrag in der Liste: [PairEditResult.DUPLICATE], nichts geändert.
     */
    suspend fun changePair(id: Long, market: MarketInfo, pair: CurrencyPairInfo): PairEditResult =
        database.withTransaction {
            val watch = watchDao.getWatch(id)
            if (watch == null) {
                PairEditResult.MISSING
            } else {
                val current = WatchEdit.Key(watch.marketKey, watch.baseAsset, watch.quoteAsset, watch.contractType.name)
                val target = WatchEdit.Key(market.key, pair.currencyBase, pair.currencyCounter, pair.contractType.name)
                val existing = watchDao.findWatchId(target.marketKey, target.base, target.quote, target.contract)
                when (WatchEdit.decide(id, current, target, existing)) {
                    WatchEdit.Outcome.UNCHANGED -> PairEditResult.UNCHANGED
                    WatchEdit.Outcome.DUPLICATE -> PairEditResult.DUPLICATE
                    WatchEdit.Outcome.CHANGED -> {
                        watchDao.updateWatch(
                            watch.copy(
                                marketKey = market.key,
                                marketName = market.name,
                                baseAsset = pair.currencyBase,
                                quoteAsset = pair.currencyCounter,
                                contractType = pair.contractType,
                                pairId = pair.currencyPairId,
                                lastPrice = null,
                                previousPrice = null,
                                lastUpdate = 0,
                                notifiedPrice = null,
                                notifiedAt = 0,
                                lastError = null,
                                change24h = null,
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
            watchDao.findWatchId(watch.marketKey, watch.baseAsset, watch.quoteAsset, watch.contractType.name) != null
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
    suspend fun move(id: Long, move: WatchMove, group: String? = null) {
        val all = watchDao.getWatches()                     // schon in Anzeige-Reihenfolge
        val watch = all.firstOrNull { it.id == id } ?: return
        val section = all.filter {
            it.favorite == watch.favorite && (group == null || it.groupName == group)
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
        watchDao.setNote(id, note?.trim()?.take(NOTE_MAX)?.takeIf { it.isNotEmpty() })

    /** Gruppe setzen; null oder leer = keine Gruppe. */
    suspend fun setGroup(id: Long, groupName: String?) =
        watchDao.setGroup(id, groupName?.trim()?.takeIf { it.isNotEmpty() })

    /**
     * «Gruppe bearbeiten» in einem Vorgang: Die bisherige Gruppe [oldName]
     * (null = neue Gruppe) wird aufgelöst, danach erhalten genau [memberIds]
     * den Namen [newName]. Das deckt Umbenennen, Hinzufügen, Entfernen und
     * Verschieben aus anderen Gruppen ab. Gleicht [newName] einer anderen
     * bestehenden Gruppe, werden beide zusammengeführt.
     */
    suspend fun applyGroupEdit(oldName: String?, newName: String, memberIds: Set<Long>) {
        val name = newName.trim().takeIf { it.isNotEmpty() } ?: return
        database.withTransaction {
            if (oldName != null) watchDao.clearGroup(oldName)
            memberIds.forEach { watchDao.setGroup(it, name) }
        }
    }

    /** Gruppe löschen: Die Paare bleiben in der Merkliste, nur ohne Gruppe. */
    suspend fun clearGroup(groupName: String) = watchDao.clearGroup(groupName)

    suspend fun setTtsEnabled(id: Long, enabled: Boolean) =
        watchDao.setTtsEnabled(id, enabled)

    suspend fun setNotifiedPrice(id: Long, price: Double?, time: Long = System.currentTimeMillis()) =
        watchDao.setNotifiedPrice(id, price, if (price == null) 0 else time)

    suspend fun updatePrice(id: Long, price: Double, time: Long, change24h: Double?) =
        watchDao.updatePrice(id, price, time, change24h)

    suspend fun updateError(id: Long, error: String?) =
        watchDao.updateError(id, error)

    /** Veränderung der Paare [ids] entfernen (in Blöcken, Grenze der SQL-Variablen). */
    suspend fun clearChanges(ids: List<Long>) {
        if (ids.isEmpty()) return
        database.withTransaction { ids.chunked(500).forEach { watchDao.clearChanges(it) } }
    }

    /** Später Bezug der Tages-Basis: Veränderung nachtragen, wenn der Kurs noch derselbe ist. */
    suspend fun fillChange(id: Long, time: Long, change: Double): Boolean =
        watchDao.fillChange(id, time, change) > 0

    /**
     * Schreibt die Ergebnisse eines ganzen Durchlaufs in einem Vorgang.
     * Einzeln wäre das bei vielen Paaren eine Transaktion je Paar, und nach
     * jeder würde die Watchlist komplett neu geladen.
     */
    suspend fun applyRefresh(prices: List<PriceWrite>, errors: List<ErrorWrite>) {
        if (prices.isEmpty() && errors.isEmpty()) return
        database.withTransaction {
            prices.forEach { watchDao.updatePrice(it.id, it.price, it.time, it.change24h) }
            errors.forEach { watchDao.updateError(it.id, it.error) }
        }
    }

    /** Setzt den Meldekurs für mehrere Paare in einem Vorgang. */
    suspend fun setNotifiedPrices(prices: List<Pair<Long, Double>>, time: Long) {
        if (prices.isEmpty()) return
        database.withTransaction {
            prices.forEach { (id, price) -> watchDao.setNotifiedPrice(id, price, time) }
        }
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

    suspend fun saveAlarm(alarm: AlarmEntity): Long =
        if (alarm.id == 0L) watchDao.insertAlarm(alarm)
        else {
            watchDao.updateAlarm(alarm)
            alarm.id
        }

    suspend fun deleteAlarm(id: Long) = watchDao.deleteAlarm(id)

    suspend fun setAlarmEnabled(id: Long, enabled: Boolean) {
        watchDao.setAlarmEnabled(id, enabled)
        // Wieder eingeschaltet: «Nahe am Hoch/Tief», Kursmarken, Funding und Open Interest melden wieder (wirkt nur bei diesen)
        if (enabled) watchDao.rearmAlarm(id)
    }

    suspend fun markAlarmTriggered(
        alarm: AlarmEntity,
        price: Double,
        time: Long,
        /** Volumen-Spike: Startzeit der gemeldeten Stundenkerze. */
        candleOpenTime: Long? = null,
        /** «Nahe am Hoch/Tief»: gemeldete Marke (Hoch/Tief bzw. Kurs beim neuen Hoch/Tief). */
        nearLevel: Double? = null,
    ) = watchDao.markAlarmTriggered(
        id = alarm.id,
        time = time,
        price = price,
        referencePrice = when {
            alarm.condition.isNearExtreme -> nearLevel ?: price
            alarm.condition.isPercent -> price
            else -> alarm.referencePrice
        },
        referenceAt = when (alarm.condition) {
            // Bewegungs-Alarm: nach dem Auslösen beginnt ein neues Zeitfenster
            AlarmCondition.MOVE_PERCENT_WINDOW -> time
            // Volumen-Spike: dieselbe Kerze nicht nochmals melden
            AlarmCondition.VOLUME_SPIKE -> candleOpenTime ?: alarm.referenceAt
            // Nahe am Hoch/Tief, Kursmarke, Funding und Open Interest: gemeldet (> 0) bis zur Wiederscharfstellung
            AlarmCondition.NEAR_HIGH, AlarmCondition.NEAR_LOW,
            AlarmCondition.PRICE_ABOVE, AlarmCondition.PRICE_BELOW,
            AlarmCondition.FUNDING_ABOVE, AlarmCondition.FUNDING_BELOW,
            AlarmCondition.OI_UP, AlarmCondition.OI_DOWN -> time
            else -> alarm.referenceAt
        },
        enabled = alarm.repeating,
    )

    suspend fun setAlarmReference(id: Long, price: Double, time: Long) =
        watchDao.setAlarmReference(id, price, time)

    /** «Nahe am Hoch/Tief», Kursmarke (PRICE_ABOVE/PRICE_BELOW), Funding bzw. Open Interest wieder scharf stellen. */
    suspend fun rearmAlarm(id: Long) = watchDao.rearmAlarm(id)
}
