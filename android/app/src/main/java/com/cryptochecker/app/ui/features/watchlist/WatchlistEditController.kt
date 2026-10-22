// Änderungen an der Merkliste (Teil von `WatchlistViewModel`): Gruppen, Notiz, Hinzufügen aus der
// Vorschau, Löschen mit Rückgängig, Favorit, Deaktivieren, Kursmitteilung, Vorlesen, Reihenfolge.
// Eigene Klasse, weil es die schreibenden Aktionen sind; sie besitzt ihren Zustand (Rückgängig-Speicher,
// umbenannte Gruppen) privat und läuft im viewModelScope, den das ViewModel als [scope] mitgibt.
package com.cryptochecker.app.ui.features.watchlist

import com.cryptochecker.app.data.AddMoments
import com.cryptochecker.app.data.AddWatchOutcome
import com.cryptochecker.app.data.RestoreOutcome
import com.cryptochecker.app.data.restore
import com.cryptochecker.app.data.WatchMove
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.WatchSnapshot
import com.cryptochecker.app.data.confirmUserWrite
import com.cryptochecker.app.data.tryAddWatch
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.domain.refresh.PriceRefresher
import com.cryptochecker.app.domain.watch.AssetKind
import com.cryptochecker.app.domain.watch.UndoSlot
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.notification.AppNotifier
import com.cryptochecker.app.notification.PriceNotificationRule
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.widget.WidgetPrefs
import com.cryptochecker.app.widget.WidgetUpdater
import com.cryptochecker.marketdata.model.MarketPair
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class WatchlistEditController(
    private val scope: CoroutineScope,
    private val watchRepository: WatchRepository,
    private val settingsRepository: SettingsRepository,
    private val priceRefresher: PriceRefresher,
    private val notifier: AppNotifier,
    private val widgetUpdater: WidgetUpdater,
    private val widgetPrefs: WidgetPrefs,
    private val addMoments: AddMoments,
    /** Aktuelle Gruppen-Auswahl ([WatchlistContentFlows.groupFilter]); null bis geladen. */
    private val groupFilter: () -> GroupFilter?,
    /** Ganzer Durchlauf ohne 15-s-Sperre ([WatchlistRefreshController.refreshAll]). */
    private val refreshAll: () -> Unit,
) {
    /**
     * Umbenannte Gruppen (alt → neu): Verschwindet die gewählte Gruppe durch
     * Umbenennen, folgt die Auswahl dem neuen Namen statt auf «Alle» zu springen.
     */
    private val renamedGroups = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Neuer Name der umbenannten Gruppe [oldName]; null, wenn sie nicht umbenannt wurde. */
    fun renamedGroup(oldName: String): String? = renamedGroups[oldName]

    private val undoMutex = Mutex()

    /**
     * Zuletzt Gelöschtes: ein Paar (Schlüssel = seine Id), alle nicht gehandelten
     * ([NOT_TRADED_UNDO_KEY]), die Mehrfachauswahl ([SELECTION_UNDO_KEY]) oder die ganze Merkliste ([CLEAR_ALL_UNDO_KEY]).
     */
    private val undo = UndoSlot<Long, List<WatchSnapshot>>()

    /** Seit Build 105 (F-08): Rückfrage EINMAL je Aktion vorher ([confirmUserWrite], vor [undoMutex]); «Abbrechen» = nichts. */
    private fun launchWrite(block: suspend () -> Unit) = scope.launch { if (watchRepository.confirmUserWrite()) block() }

    fun selectGroup(group: String?) {
        scope.launch { settingsRepository.setWatchlistGroup(group) }
    }

    fun setNote(watch: WatchEntity, note: String?) {
        launchWrite { watchRepository.setNote(watch.id, note) }
    }

    fun setGroup(watch: WatchEntity, group: String?) {
        launchWrite {
            watchRepository.setGroup(watch.id, group)
            widgetUpdater.updateAll()
        }
    }

    /**
     * «Gruppe bearbeiten» übernehmen (ein Vorgang): [oldName] = bisheriger
     * Name, null für eine neue Gruppe. Eine neue Gruppe ohne Paare entsteht
     * gar nicht; eine bestehende ohne Paare verschwindet.
     */
    fun saveGroup(oldName: String?, newName: String, memberIds: Set<Long>) {
        val name = newName.trim()
        if (name.isEmpty()) return
        if (oldName == null && memberIds.isEmpty()) return
        launchWrite {
            val renamed = oldName != null && oldName != name
            if (renamed && memberIds.isNotEmpty()) renamedGroups[oldName] = name
            watchRepository.applyGroupEdit(oldName, name, memberIds)
            if (renamed) widgetPrefs.replaceGroup(oldName, name.takeIf { memberIds.isNotEmpty() })
            widgetUpdater.updateAll()
        }
    }

    /** Gruppe löschen: Paare bleiben in der Merkliste; die Ansicht springt auf «Alle». */
    fun deleteGroup(name: String) {
        launchWrite {
            renamedGroups.remove(name)
            if (groupFilter()?.selected == name) settingsRepository.setWatchlistGroup(null)
            watchRepository.clearGroup(name)
            widgetPrefs.replaceGroup(name, null)
            widgetUpdater.updateAll()
        }
    }

    /**
     * Vorschau-Paar (Aktionsblatt aus «Heute auffällig», nur im Speicher) in die Merkliste: wie
     * «Hinzufügen» im Hinzufügen-Tab — Dublettenprüfung in [WatchRepository.addWatch] (gleiche
     * Standardwerte, Kurs-Benachrichtigung an, Gruppe nach [com.cryptochecker.app.domain.watch.AutoGroup]),
     * Erst-Moment beim allerersten Paar ([com.cryptochecker.app.data.AddMoments.afterExplorerAdd]),
     * danach gleich den Kurs holen (nach [onDone]). [onDone]: Id des neuen Eintrags, bzw. des schon vorhandenen
     * (stand das Paar inzwischen schon in der Merkliste); null, wenn nichts zu finden ist.
     * Die Erlaubnis für Mitteilungen fragt die Oberfläche (wie der Hinzufügen-Tab nach «ADDED»). Merkliste voll
     * ([com.cryptochecker.app.domain.watch.WatchlistLimits]): nichts angelegt, nur [onFull] (Hinweis).
     */
    fun addPreview(watch: WatchEntity, onFull: () -> Unit, onDone: (id: Long?, added: Boolean) -> Unit) {
        launchWrite {
            val outcome = watchRepository.tryAddWatch(
                MarketInfo(key = watch.marketKey, name = watch.marketName),
                MarketPair(watch.baseAsset, watch.quoteAsset, watch.pairId, watch.contractType),
            )
            if (outcome == AddWatchOutcome.Full) {
                onFull()
                return@launchWrite
            }
            // Merkliste gesperrt: nichts angelegt, den kurzen Hinweis zeigt WatchlistLockedHint
            if (outcome == AddWatchOutcome.Locked) return@launchWrite
            val id = (outcome as? AddWatchOutcome.Added)?.id
            if (id != null) {
                addMoments.afterExplorerAdd(id)
                onDone(id, true)
                priceRefresher.refreshOne(id)
            } else {
                val existing = watchRepository.getWatches().firstOrNull {
                    it.marketKey == watch.marketKey && it.contractType == watch.contractType &&
                        it.baseAsset.equals(watch.baseAsset, ignoreCase = true) &&
                        it.quoteAsset.equals(watch.quoteAsset, ignoreCase = true)
                }
                onDone(existing?.id, false)
            }
        }
    }

    /**
     * Löschen per Wischen bzw. Aktionen-Blatt (ohne Rückfrage), mit «Rückgängig»: Das Paar wird sofort
     * wirklich gelöscht (Alarme, Meldungen und Widgets sehen es nicht mehr), vorher
     * aber vollständig festgehalten ([WatchSnapshot]). [undoDelete] legt es mit
     * derselben Id und denselben Alarm-Ids wieder an. Nur die letzte Löschung lässt
     * sich zurückholen; Löschen und Zurückholen laufen nacheinander ([undoMutex]).
     * Seit Build 105: erst nach dem Löschen ruft es [onDeleted] (Banner, Haptik; liefert den Rückgängig-Schlüssel — liegt
     * dort schon etwas, Sammlung solange der Banner steht, [RemovedBatch], kommt das Paar dazu), danach Rückgängig-Eintrag
     * und Mitteilungen. «Abbrechen» in der Rückfrage bzw. abgelehnt: nichts davon.
     */
    fun deleteWithUndo(watch: WatchEntity, onDeleted: () -> Long = { watch.id }) {
        launchWrite {
            undoMutex.withLock {
                val snapshot = watchRepository.snapshot(watch.id) ?: return@withLock
                if (!watchRepository.deleteWatch(watch.id)) return@withLock
                undo.merge(onDeleted(), listOf(snapshot)) { held, added -> held + added }
                notifier.cancelPrice(watch.id)
                notifier.cancelActivity(watch.id)
                // Alte Alarm-Mitteilungen weg: ihr «Anpassen» zeigte sonst auf ein Paar, das es nicht mehr gibt
                notifier.cancelAlarms(snapshot.alarms.map { it.id })
                widgetUpdater.updateAll()
            }
        }
    }

    /**
     * «Rückgängig» für [watchId]; tut nichts, wenn inzwischen etwas anderes gelöscht wurde. Seit Build 103 nur bis zur
     * Obergrenze der Merkliste: [onFull] bekommt die Zahl der deshalb nicht zurückgeholten Paare (nur wenn > 0).
     */
    fun undoDelete(watchId: Long, onFull: (Int) -> Unit = {}) {
        launchWrite {
            undoMutex.withLock {
                val snapshots = undo.take(watchId) ?: return@withLock
                // Nach «Merkliste leeren» bzw. vielen Paaren keine Kurs-Meldung je Paar: Android drosselt rund
                // 5 Meldungen pro Sekunde und verwirft den Rest still; wie unter iOS meldet dann erst die
                // nächste Aktualisierung (seit Build 95)
                val showPrices = restoresPriceNotifications(snapshots.size)
                var any = false
                var full = 0
                for (snapshot in snapshots) {
                    when (watchRepository.restore(snapshot)) {
                        RestoreOutcome.RESTORED -> Unit
                        RestoreOutcome.FULL -> { full++; continue }
                        RestoreOutcome.BLOCKED -> continue
                    }
                    any = true
                    // Kurs-Meldung wie vorher wieder zeigen (letzter bekannter Kurs) — nur nach den
                    // Einstellungen (Kurs-Mitteilungen an, dauerhaft nur wenn eingestellt)
                    if (showPrices) notifier.showPriceNow(snapshot.watch)
                }
                if (any) widgetUpdater.updateAll()
                if (full > 0) onFull(full)
            }
        }
    }

    /**
     * «Nicht gehandelte Paare entfernen» (Überlaufmenü, nach Rückfrage): alle Paare, die ihre
     * Börse nicht mehr führt, samt Alarmen löschen — wie [deleteWithUndo] festgehalten, damit
     * «Rückgängig» ([undoDelete] mit [NOT_TRADED_UNDO_KEY]) alle zurückholt.
     * [onDone] bekommt die Zahl der gelöschten Paare (0 = nichts geschehen).
     */
    fun deleteNotTradedWithUndo(onDone: (Int) -> Unit) {
        scope.launch {
            // Seit Build 105: Rückfrage vor der Sperre; «Abbrechen» bzw. abgelehnt = 0 (kein Banner, nichts weggeräumt)
            val count = if (!watchRepository.confirmUserWrite()) 0 else undoMutex.withLock {
                val ids = watchRepository.getWatches().filter { it.isNotTraded }.map { it.id }
                deleteSnapshots(NOT_TRADED_UNDO_KEY, ids.mapNotNull { watchRepository.snapshot(it) })
            }
            onDone(count)
        }
    }

    /**
     * Mehrfachauswahl › «Löschen»: alle [ids] samt Alarmen löschen — festgehalten wie
     * [deleteNotTradedWithUndo], «Rückgängig» ([undoDelete] mit [SELECTION_UNDO_KEY]) holt alle
     * zurück. [onDone] bekommt die Zahl der gelöschten Paare.
     */
    fun deleteSelectedWithUndo(ids: Collection<Long>, onDone: (Int) -> Unit) {
        scope.launch {
            val count = if (!watchRepository.confirmUserWrite()) 0 else undoMutex.withLock {
                deleteSnapshots(SELECTION_UNDO_KEY, ids.mapNotNull { watchRepository.snapshot(it) })
            }
            onDone(count)
        }
    }

    /**
     * Festgehaltene Paare löschen (unter [undoMutex]); erst NACH dem Löschen Rückgängig-Eintrag unter [key] und Kurs-,
     * Aktivitäts- und Alarm-Mitteilungen weg (seit Build 105). Rückgabe: Zahl der gelöschten Paare, 0 = nichts geschehen.
     */
    private suspend fun deleteSnapshots(key: Long, snapshots: List<WatchSnapshot>): Int {
        if (snapshots.isEmpty() || !watchRepository.deleteWatches(snapshots.map { it.watch.id })) return 0
        undo.put(key, snapshots)
        // Kurs-Meldungen in einem Schritt (Sammelmeldung nur einmal neu)
        notifier.cancelAllPrices(snapshots.map { it.watch.id })
        snapshots.forEach { notifier.cancelActivity(it.watch.id) }
        notifier.cancelAlarms(snapshots.flatMap { it.alarms }.map { it.id })
        widgetUpdater.updateAll()
        return snapshots.size
    }

    /** Mehrfachauswahl › «Favorit»: alle [ids] zu Favoriten machen bzw. ([favorite] = false) entfernen. */
    fun setFavorite(ids: Collection<Long>, favorite: Boolean) {
        launchWrite {
            ids.forEach { watchRepository.setFavorite(it, favorite) }
            widgetUpdater.updateAll()
        }
    }

    /** Mehrfachauswahl › «Gruppe»: alle [ids] in [group] (null = keine Gruppe). */
    fun setGroup(ids: Collection<Long>, group: String?) {
        launchWrite {
            ids.forEach { watchRepository.setGroup(it, group) }
            widgetUpdater.updateAll()
        }
    }

    /**
     * Aktionsblatt bzw. Mehrfachauswahl › «Deaktivieren»/«Aktivieren». Deaktiviert: Kurs- und
     * Aktivitäts-Meldungen weg, keine Abfragen und Alarme mehr (Alarme bleiben gespeichert).
     * Wieder aktiviert: Melde-Bezug leeren und sofort den Kurs holen — die Aktualisierung zeigt
     * die Kurs-Meldung dann wieder nach den Einstellungen (wie nach dem Einschalten).
     */
    fun setDisabled(ids: Collection<Long>, disabled: Boolean) {
        if (ids.isEmpty()) return
        launchWrite {
            // Abgelehnt: keine Mitteilungen weg, keine Abfragen
            if (!watchRepository.setDisabled(ids, disabled)) return@launchWrite
            if (disabled) {
                notifier.cancelAllPrices(ids)
                ids.forEach { notifier.cancelActivity(it) }
            } else {
                ids.forEach { watchRepository.setNotifiedPrice(it, null) }
            }
            widgetUpdater.updateAll()
            // Einzelne Paare gezielt, viele auf einmal mit einem Durchlauf (gesammelte Abfragen je Börse)
            if (!disabled) {
                if (ids.size <= ENABLE_SINGLE_REFRESH_MAX) ids.forEach { priceRefresher.refreshOne(it) } else refreshAll()
            }
        }
    }

    /**
     * «Merkliste leeren» (nach Rückfrage): alle Paare samt Alarmen löschen — seit Build 94 wie
     * [deleteNotTradedWithUndo] vorher vollständig festgehalten (Paar mit Id, Platz, Gruppe, Notiz und Alarmen), damit
     * «Rückgängig» ([undoDelete] mit [CLEAR_ALL_UNDO_KEY]) alles mit denselben Ids zurückholt. [onDone] bekommt die
     * Zahl der gelöschten Paare (0 = nichts geschehen).
     */
    fun deleteAllWithUndo(onDone: (Int) -> Unit = {}) {
        scope.launch {
            val count = if (!watchRepository.confirmUserWrite()) 0 else undoMutex.withLock {
                val snapshots = watchRepository.getWatches().mapNotNull { watchRepository.snapshot(it.id) }
                // Ids vorher festhalten; die Mitteilungen gehen seit Build 105 erst nach dem Löschen weg (abgelehnt: bleiben)
                val ids = snapshots.map { it.watch.id }
                val alarmIds = watchRepository.getAllAlarmIds()
                if (!watchRepository.deleteAllWatches()) return@withLock 0
                notifier.cancelAllPrices(ids)
                // Aktivitäts-Meldungen ebenso (wie beim Löschen einzelner Paare): sonst blieben sie zu gelöschten Paaren stehen
                ids.forEach { notifier.cancelActivity(it) }
                notifier.cancelAlarms(alarmIds)
                if (snapshots.isNotEmpty()) undo.put(CLEAR_ALL_UNDO_KEY, snapshots)
                widgetUpdater.updateAll()
                snapshots.size
            }
            onDone(count)
        }
    }

    fun setNotificationEnabled(watch: WatchEntity, enabled: Boolean) {
        launchWrite {
            // Abgelehnt (Merkliste gesperrt): nichts zeigen bzw. entfernen, das Paar meldet wie bisher
            if (!watchRepository.setNotificationEnabled(watch.id, enabled)) return@launchWrite
            if (enabled) {
                // Einschalten ist eine bewusste Handlung: einmal zeigen und den
                // Bezugspunkt für die Melde-Schwelle auf den aktuellen Kurs setzen.
                // Deaktiviertes Paar: nur merken, gemeldet wird erst nach dem Aktivieren
                // Nur nach den Einstellungen (PriceNotificationRule); nicht gezeigt: Bezug leer, die nächste
                // Aktualisierung meldet
                watchRepository.getWatch(watch.id)?.takeIf { !it.disabled }?.let { updated ->
                    val shown = notifier.showPriceNow(updated)
                    watchRepository.setNotifiedPrice(updated.id, PriceNotificationRule.referenceAfterEnable(shown, updated.lastPrice))
                }
            } else {
                notifier.cancelPrice(watch.id)
                watchRepository.setNotifiedPrice(watch.id, null)
            }
        }
    }

    /** Paar im Sortiermodus verschieben. */
    fun move(watch: WatchEntity, move: WatchMove) {
        launchWrite {
            // In einer gefilterten Ansicht nur unter den Paaren der Gruppe
            val filter = groupFilter()
            watchRepository.move(watch.id, move, filter?.selected) { filter?.kinds?.get(it.id) == AssetKind.ALPHA }
            widgetUpdater.updateAll()
        }
    }

    /** Neue Reihenfolge nach dem Ziehen einer Karte. */
    fun reorder(orderedIds: List<Long>) {
        launchWrite {
            watchRepository.reorder(orderedIds)
            widgetUpdater.updateAll()
        }
    }

    /** Stern, Aktionen-Menü bzw. nach rechts wischen: Favorit an/aus. */
    fun toggleFavorite(watch: WatchEntity) {
        launchWrite {
            watchRepository.setFavorite(watch.id, !watch.favorite)
            widgetUpdater.updateAll()
        }
    }

    /**
     * «Kurs vorlesen» für ein Paar. Einschalten, während die Sprachausgabe aus ist oder «Nur Alarme»
     * gilt: beides passend umstellen, sonst bliebe der Schalter wirkungslos ([com.cryptochecker.app.settings.AppSettings.speaksPairPrices]).
     * @return true, wenn die Sprachausgabe dafür eingeschaltet wurde (kurzer Hinweis im Blatt).
     */
    fun setTtsEnabled(watch: WatchEntity, enabled: Boolean): Boolean {
        // Merkliste gesperrt: kein Hinweis «Sprachausgabe an» (die Rückgabe gilt sofort, das Schreiben folgt erst)
        val blocked = com.cryptochecker.app.data.local.WatchlistStorageLock
            .blocksWrites(com.cryptochecker.app.data.local.DatabaseEncryptionNotice.storedKept.value)
        val turnOnSpeech = enabled && !blocked && !settingsRepository.cached.speaksPairPrices
        // Seit Build 105: kommt erst die Rückfrage (F-08), keinen Hinweis vorwegnehmen («Abbrechen» änderte nichts)
        val asksFirst = com.cryptochecker.app.data.local.WatchlistNotSavedGate.needsConfirmation()
        launchWrite {
            // Abgelehnt (Merkliste gesperrt): die Sprachausgabe bleibt, wie sie ist
            if (watchRepository.setTtsEnabled(watch.id, enabled) && turnOnSpeech) {
                settingsRepository.setTtsEnabled(true)
                settingsRepository.setTtsAlarmsOnly(false)
            }
        }
        return turnOnSpeech && !asksFirst
    }
}

/** «Rückgängig»-Schlüssel für «Merkliste leeren» (wie [NOT_TRADED_UNDO_KEY]; Watch-Ids sind immer positiv). */
internal const val CLEAR_ALL_UNDO_KEY = -3L

/** Bis zu so vielen wieder aktivierten Paaren einzeln abfragen, darüber ein ganzer Durchlauf. */
private const val ENABLE_SINGLE_REFRESH_MAX = 3

/** Bis zu so vielen zurückgeholten Paaren zeigt «Rückgängig» die Kurs-Meldung gleich wieder, darüber keine. */
internal const val UNDO_PRICE_POST_MAX = 3

/** Kurs-Meldungen beim «Rückgängig» von [restored] Paaren gleich wieder zeigen ([UNDO_PRICE_POST_MAX])? */
internal fun restoresPriceNotifications(restored: Int): Boolean = restored in 1..UNDO_PRICE_POST_MAX
