// Review ARC5-R01: Rückmeldungen der Merkliste-Aktionen nach der Rückfrage F-08 (Banner, Haptik, Erlaubnis,
// Blatt bzw. Auswahl zu) als Einmal-Ereignisse für den GERADE gezeigten Bildschirm. Die Rückrufe an das ViewModel schicken
// nur noch Daten ([WatchUiEvent]); ausgeführt wird in [WatchlistUiEffects] bzw. `MarketCoinUiEffects` — auch wenn die
// Activity inzwischen neu erstellt wurde (alte Komposition: Banner weg, abgemeldeter Erlaubnis-Launcher).
package com.cryptochecker.app.ui.features.watchlist

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.WatchlistStorageLock
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.ui.components.CollectScreenEvents
import com.cryptochecker.app.ui.components.HapticEvent
import com.cryptochecker.app.ui.components.HapticPlayer
import com.cryptochecker.app.ui.components.ScreenEvents

/** Text eines Banners, erst im aktuellen Bildschirm aufgelöst (Sprache kann inzwischen gewechselt haben). */
internal data class BannerText(
    @StringRes val res: Int = 0,
    val arg: String? = null,
    @PluralsRes val plural: Int = 0,
    val count: Int = 0,
) {
    fun resolve(resources: Resources): String = when {
        plural != 0 -> resources.getQuantityString(plural, count, count)
        arg != null -> resources.getString(res, arg)
        else -> resources.getString(res)
    }
}

/** Rückmeldung einer Aktion für den aktuellen Bildschirm. */
internal sealed interface WatchUiEvent {
    /** Kurzes Banner; mit [undoKey] daneben «Rückgängig» (`undoDelete`, Merkliste inzwischen voll → Hinweis). */
    data class Banner(val text: BannerText, val undoKey: Long? = null) : WatchUiEvent

    data class Haptic(val event: HapticEvent) : WatchUiEvent

    /** Mitteilungen eingeschaltet: jetzt die Erlaubnis fragen. */
    data object RequestNotifications : WatchUiEvent

    /** Aktionsblatt von [watchId] schliessen (nur, wenn noch dieses offen ist). */
    data class CloseActions(val watchId: Long) : WatchUiEvent

    /** Mehrfachauswahl beenden. */
    data object EndSelection : WatchUiEvent

    /** «Gruppe bearbeiten» schliessen. */
    data object CloseGroupEdit : WatchUiEvent

    /** Paar gelöscht (Wischen, Blatt): Sammel-Banner aus [WatchUiEventsHolder.removed] mit «Rückgängig». */
    data class Removed(val generation: Int, val undoKey: Long) : WatchUiEvent

    /** Vorschau: Merkliste voll, nichts angelegt. */
    data object PreviewFull : WatchUiEvent

    /** Vorschau übernommen: [id] des neuen bzw. schon vorhandenen Eintrags (null = nichts), [added] = neu angelegt. */
    data class PreviewAdded(val id: Long?, val added: Boolean) : WatchUiEvent
}

/** Ereignisse und Sammel-Banner eines Bildschirms; ein ViewModel, darum übersteht beides das Neuerstellen der Activity. */
internal class WatchUiEventsHolder : ViewModel() {
    val events = ScreenEvents<WatchUiEvent>()

    /** Weiteres Löschen, solange der Banner steht: ein Sammel-Banner ([RemovedBatch]). */
    val removed = RemovedBatch()

    fun send(vararg items: WatchUiEvent) = items.forEach { events.send(it) }
}

/** [WatchUiEventsHolder] des Bildschirms ([key]: Merkliste bzw. Markt, je ein eigener Empfänger). */
@Composable
internal fun rememberWatchUiEvents(key: String): WatchUiEventsHolder = viewModel(key = key) { WatchUiEventsHolder() }

/** Schlüssel der Merkliste für [rememberWatchUiEvents]. */
internal const val WATCHLIST_UI_EVENTS = "watchlist_ui_events"

/** «Merkliste inzwischen voll» nach «Rückgängig»: [n] Paare nicht zurückgeholt. */
private fun undoFull(n: Int) = WatchUiEvent.Banner(BannerText(plural = R.plurals.watchlist_undo_full, count = n))

/**
 * Wischen, Screenreader-Aktion und Aktionen-Blatt «Löschen»: ohne Rückfrage, mit «Rückgängig». Haptik und
 * Banner erst nach dem Löschen; der Rückgängig-Schlüssel kommt gleich aus [WatchUiEventsHolder.removed].
 */
internal fun WatchUiEventsHolder.deleteWithUndo(viewModel: WatchlistViewModel, watch: WatchEntity) {
    viewModel.deleteWithUndo(watch) {
        val key = removed.add(watch.id, watch.displayName)
        send(WatchUiEvent.Removed(removed.generation, key))
        key
    }
}

/** Nach rechts wischen: Favorit an/aus mit kurzem Banner — Haptik und Banner erst nach dem Schreiben. */
internal fun WatchUiEventsHolder.favoriteWithBanner(viewModel: WatchlistViewModel, watch: WatchEntity) {
    viewModel.toggleFavorite(watch) { written ->
        if (!written) return@toggleFavorite
        val text = if (watch.favorite) R.string.favorite_removed else R.string.favorite_added
        send(WatchUiEvent.Haptic(HapticEvent.LIGHT_IMPACT), WatchUiEvent.Banner(BannerText(text, watch.displayName)))
    }
}

/**
 * Deaktivieren bzw. wieder aktivieren (Aktionsblatt, Merkliste und Markt) — UX5-R2, wie iOS: Blatt erst nach
 * der Rückfrage F-08 zu, mit leichter Haptik und Banner; «Abbrechen» = Blatt bleibt, gesperrt = Blatt zu ohne Banner.
 */
internal fun WatchUiEventsHolder.toggleDisabled(viewModel: WatchlistViewModel, watch: WatchEntity) {
    viewModel.setDisabled(listOf(watch.id), !watch.disabled) { written ->
        // Gesperrt (nur lesbar): abgelehnt, Blatt trotzdem zu (wie iOS); «Abbrechen»: Blatt bleibt
        if (written || WatchlistStorageLock.blocksWritesNow()) send(WatchUiEvent.CloseActions(watch.id))
        if (!written) return@setDisabled
        val text = if (watch.disabled) R.string.watch_enabled_banner else R.string.watch_disabled_banner
        send(WatchUiEvent.Haptic(HapticEvent.LIGHT_IMPACT), WatchUiEvent.Banner(BannerText(text, watch.displayName)))
    }
}

/** Banner, Haptik und Lösch-Banner — gleich in Merkliste und Markt. false = Ereignis gehört dem Bildschirm selbst. */
internal fun WatchUiEventsHolder.handleShared(
    event: WatchUiEvent,
    viewModel: WatchlistViewModel,
    banner: WatchlistBanner,
    haptics: HapticPlayer,
    resources: Resources,
): Boolean {
    when (event) {
        is WatchUiEvent.Haptic -> haptics.play(event.event)
        is WatchUiEvent.Banner -> {
            val key = event.undoKey
            if (key == null) banner.show(event.text.resolve(resources))
            else banner.show(event.text.resolve(resources)) { viewModel.undoDelete(key) { n -> send(undoFull(n)) } }
        }
        is WatchUiEvent.Removed -> {
            haptics.play(HapticEvent.DESTRUCTIVE)
            val batch = removed
            banner.show(batch.bannerText(resources), onClosed = { batch.closed(event.generation) }) {
                // Merkliste inzwischen voll → Hinweis, wie viele nicht zurückkamen
                viewModel.undoDelete(event.undoKey) { n -> send(undoFull(n)) }
            }
        }
        else -> return false
    }
    return true
}

/** Rückmeldungen der Merkliste im aktuellen Bildschirm (Blatt, Gruppe, Auswahl in [ui]; Erlaubnis über [requestNotifications]). */
@Composable
internal fun WatchlistUiEffects(
    holder: WatchUiEventsHolder,
    ui: WatchlistScreenState,
    viewModel: WatchlistViewModel,
    banner: WatchlistBanner,
    haptics: HapticPlayer,
    resources: Resources,
    requestNotifications: () -> Unit,
) {
    CollectScreenEvents(holder.events) { event ->
        if (holder.handleShared(event, viewModel, banner, haptics, resources)) return@CollectScreenEvents
        when (event) {
            WatchUiEvent.RequestNotifications -> requestNotifications()
            is WatchUiEvent.CloseActions -> if (ui.actionsFor == event.watchId) ui.actionsFor = null
            WatchUiEvent.EndSelection -> ui.endSelection()
            WatchUiEvent.CloseGroupEdit -> ui.editGroup = null
            else -> Unit
        }
    }
}
