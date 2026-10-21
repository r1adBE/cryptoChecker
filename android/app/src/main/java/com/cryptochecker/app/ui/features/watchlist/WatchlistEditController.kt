// Änderungen an der Merkliste (Teil von `WatchlistViewModel`): Gruppen, Notiz, Hinzufügen aus der
// Vorschau, Löschen mit Rückgängig, Favorit, Deaktivieren, Kursmitteilung, Vorlesen, Reihenfolge.
// Eigene Klasse, weil es die schreibenden Aktionen sind; sie besitzt ihren Zustand (Rückgängig-Speicher,
// umbenannte Gruppen) privat und läuft im viewModelScope, den das ViewModel als [scope] mitgibt.
package com.cryptochecker.app.ui.features.watchlist

import com.cryptochecker.app.data.AddMoments
import com.cryptochecker.app.data.WatchMove
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.WatchSnapshot
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

    fun selectGroup(group: String?) {
        scope.launch { settingsRepository.setWatchlistGroup(group) }
    }

    fun setNote(watch: WatchEntity, note: String?) {
        scope.launch { watchRepository.setNote(watch.id, note) }
    }

    fun setGroup(watch: WatchEntity, group: String?) {
        scope.launch {
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
        scope.launch {
            val renamed = oldName != null && oldName != name
            if (renamed && memberIds.isNotEmpty()) renamedGroups[oldName] = name
            watchRepository.applyGroupEdit(oldName, name, memberIds)
            if (renamed) widgetPrefs.replaceGroup(oldName, name.takeIf { memberIds.isNotEmpty() })
            widgetUpdater.updateAll()
        }
    }

    /** Gruppe löschen: Paare bleiben in der Merkliste; die Ansicht springt auf «Alle». */
    fun deleteGroup(name: String) {
        scope.launch {
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
     * Die Erlaubnis für Mitteilungen fragt die Oberfläche (wie der Hinzufügen-Tab nach «ADDED»).
     */
    fun addPreview(watch: WatchEntity, onDone: (id: Long?, added: Boolean) -> Unit) {
        scope.launch {
            val id = watchRepository.addWatch(
                MarketInfo(key = watch.marketKey, name = watch.marketName),
                MarketPair(watch.baseAsset, watch.quoteAsset, watch.pairId, watch.contractType),
            )
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
     */
    fun deleteWithUndo(watch: WatchEntity) {
        scope.launch {
            undoMutex.withLock {
                val snapshot = watchRepository.snapshot(watch.id) ?: return@withLock
                undo.put(watch.id, listOf(snapshot))
                notifier.cancelPrice(watch.id)
                notifier.cancelActivity(watch.id)
                // Alte Alarm-Mitteilungen weg: ihr «Anpassen» zeigte sonst auf ein Paar, das es nicht mehr gibt
                notifier.cancelAlarms(snapshot.alarms.map { it.id })
                watchRepository.deleteWatch(watch.id)
                widgetUpdater.updateAll()
            }
        }
    }

    /** «Rückgängig» für [watchId]; tut nichts, wenn inzwischen etwas anderes gelöscht wurde. */
    fun undoDelete(watchId: Long) {
        scope.launch {
            undoMutex.withLock {
                val snapshots = undo.take(watchId) ?: return@withLock
                var any = false
                for (snapshot in snapshots) {
                    if (!watchRepository.restore(snapshot)) continue
                    any = true
                    // Kurs-Meldung wie vorher wieder zeigen (letzter bekannter Kurs) — nur nach den
                    // Einstellungen (Kurs-Mitteilungen an, dauerhaft nur wenn eingestellt)
                    notifier.showPriceNow(snapshot.watch)
                }
                if (any) widgetUpdater.updateAll()
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
            val count = undoMutex.withLock {
                val ids = watchRepository.getWatches().filter { it.isNotTraded }.map { it.id }
                val snapshots = ids.mapNotNull { watchRepository.snapshot(it) }
                if (snapshots.isEmpty()) return@withLock 0
                undo.put(NOT_TRADED_UNDO_KEY, snapshots)
                // Kurs-Meldungen in einem Schritt (Sammelmeldung nur einmal neu)
                notifier.cancelAllPrices(snapshots.map { it.watch.id })
                snapshots.forEach { notifier.cancelActivity(it.watch.id) }
                notifier.cancelAlarms(snapshots.flatMap { it.alarms }.map { it.id })
                watchRepository.deleteWatches(snapshots.map { it.watch.id })
                widgetUpdater.updateAll()
                snapshots.size
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
            val count = undoMutex.withLock {
                val snapshots = ids.mapNotNull { watchRepository.snapshot(it) }
                if (snapshots.isEmpty()) return@withLock 0
                undo.put(SELECTION_UNDO_KEY, snapshots)
                // Kurs-Meldungen in einem Schritt (Sammelmeldung nur einmal neu)
                notifier.cancelAllPrices(snapshots.map { it.watch.id })
                snapshots.forEach { notifier.cancelActivity(it.watch.id) }
                notifier.cancelAlarms(snapshots.flatMap { it.alarms }.map { it.id })
                watchRepository.deleteWatches(snapshots.map { it.watch.id })
                widgetUpdater.updateAll()
                snapshots.size
            }
            onDone(count)
        }
    }

    /** Mehrfachauswahl › «Favorit»: alle [ids] zu Favoriten machen bzw. ([favorite] = false) entfernen. */
    fun setFavorite(ids: Collection<Long>, favorite: Boolean) {
        scope.launch {
            ids.forEach { watchRepository.setFavorite(it, favorite) }
            widgetUpdater.updateAll()
        }
    }

    /** Mehrfachauswahl › «Gruppe»: alle [ids] in [group] (null = keine Gruppe). */
    fun setGroup(ids: Collection<Long>, group: String?) {
        scope.launch {
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
        scope.launch {
            watchRepository.setDisabled(ids, disabled)
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
            val count = undoMutex.withLock {
                val snapshots = watchRepository.getWatches().mapNotNull { watchRepository.snapshot(it.id) }
                // Erst die Benachrichtigungen wegräumen, danach die Einträge —
                // sonst fehlen hinterher die Ids, um sie zu schliessen.
                val ids = snapshots.map { it.watch.id }
                notifier.cancelAllPrices(ids)
                // Aktivitäts-Meldungen ebenso (wie beim Löschen einzelner Paare): sonst blieben sie zu gelöschten Paaren stehen
                ids.forEach { notifier.cancelActivity(it) }
                notifier.cancelAlarms(watchRepository.getAllAlarmIds())
                if (snapshots.isNotEmpty()) undo.put(CLEAR_ALL_UNDO_KEY, snapshots)
                watchRepository.deleteAllWatches()
                widgetUpdater.updateAll()
                snapshots.size
            }
            onDone(count)
        }
    }

    fun setNotificationEnabled(watch: WatchEntity, enabled: Boolean) {
        scope.launch {
            watchRepository.setNotificationEnabled(watch.id, enabled)
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
        scope.launch {
            // In einer gefilterten Ansicht nur unter den Paaren der Gruppe
            val filter = groupFilter()
            watchRepository.move(watch.id, move, filter?.selected) { filter?.kinds?.get(it.id) == AssetKind.ALPHA }
            widgetUpdater.updateAll()
        }
    }

    /** Neue Reihenfolge nach dem Ziehen einer Karte. */
    fun reorder(orderedIds: List<Long>) {
        scope.launch {
            watchRepository.reorder(orderedIds)
            widgetUpdater.updateAll()
        }
    }

    /** Stern, Aktionen-Menü bzw. nach rechts wischen: Favorit an/aus. */
    fun toggleFavorite(watch: WatchEntity) {
        scope.launch {
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
        val turnOnSpeech = enabled && !settingsRepository.cached.speaksPairPrices
        scope.launch {
            watchRepository.setTtsEnabled(watch.id, enabled)
            if (turnOnSpeech) {
                settingsRepository.setTtsEnabled(true)
                settingsRepository.setTtsAlarmsOnly(false)
            }
        }
        return turnOnSpeech
    }
}

/** «Rückgängig»-Schlüssel für «Merkliste leeren» (wie [NOT_TRADED_UNDO_KEY]; Watch-Ids sind immer positiv). */
internal const val CLEAR_ALL_UNDO_KEY = -3L

/** Bis zu so vielen wieder aktivierten Paaren einzeln abfragen, darüber ein ganzer Durchlauf. */
private const val ENABLE_SINGLE_REFRESH_MAX = 3
