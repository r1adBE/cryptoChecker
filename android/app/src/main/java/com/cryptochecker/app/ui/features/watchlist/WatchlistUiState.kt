// Zustand der Merkliste aus dem ViewModel in einem Stand ([WatchlistUiState]): Der Bildschirm sammelt ihn einmal
// statt rund 30 einzelner Flows. Die Live-Kurse bleiben bewusst draussen (eigener State, nur die Zeilen lesen ihn).
// Was der Bildschirm selbst merkt (Suche, offene Blätter), steht in [WatchlistScreenState].
// Eigene Datei, weil es reine Daten und Abbildungen ohne Android-Bezug sind (Unit-Tests in WatchlistUiStateTest).
package com.cryptochecker.app.ui.features.watchlist

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.activity.ActivityReport
import com.cryptochecker.app.domain.activity.ActivitySensitivity
import com.cryptochecker.app.domain.starter.StarterPairs
import com.cryptochecker.app.domain.watch.AssetKind
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.domain.watch.ChangeStamp
import com.cryptochecker.app.domain.watch.ColumnSort
import com.cryptochecker.app.domain.watch.LiveStatus
import com.cryptochecker.app.domain.watch.WatchFilter
import com.cryptochecker.app.settings.AppSettings
import com.cryptochecker.app.settings.WatchlistSettings

/** Alles, was der Merkliste-Bildschirm vom ViewModel liest (ohne die Live-Kurse). */
data class WatchlistUiState(
    val content: WatchlistContent,
    val display: WatchlistDisplay,
    val live: WatchlistLive,
    val starter: StarterUiState,
) {
    /** Gespeicherte Paare; leer, solange die Datenbank noch nicht geantwortet hat (siehe [loaded]). */
    val watches: List<WatchEntity> get() = content.watches.orEmpty()

    /** Liste und Gruppen-Auswahl geladen? Bis dahin Platzhalter statt kurz «leer» bzw. der ungefilterten Liste. */
    val loaded: Boolean get() = content.watches != null && content.groupFilter != null

    val groups: List<String> get() = content.groupFilter?.groups.orEmpty()

    /** Gewählte Gruppe der Chips; null = «Alle». */
    val selectedGroup: String? get() = content.groupFilter?.selected

    /** Art je Paar (Symbol vor der Glocke, «Alpha»-Chip). */
    val kinds: Map<Long, AssetKind> get() = content.groupFilter?.kinds.orEmpty()
}

/** Gespeicherte Merkliste und was aus der Datenbank daran hängt (Gruppen, Alarme, Aktivität). */
data class WatchlistContent(
    /** null, bis die Datenbank zum ersten Mal geantwortet hat. */
    val watches: List<WatchEntity>?,
    /** null, bis Liste und Einstellung geladen sind. */
    val groupFilter: GroupFilter?,
    /** Aktive Alarme je Paar. */
    val alarmCounts: Map<Long, Int>,
    /** Alle Alarme, zu denen die Glocke führt; null bis geladen. */
    val alarmTotal: Int?,
    /** Letzte Auslösung je Paar (Epoch-ms). */
    val alarmFiredAt: Map<Long, Long>,
    /** Letzte Auslösung irgendeines Alarms (0 = nie); null bis geladen. */
    val latestAlarmTrigger: Long?,
    /** Ergebnisse «Ungewöhnliche Aktivität» je Paar. */
    val activity: Map<Long, ActivityReport>,
) {
    companion object {
        /** Vor der ersten Antwort der Datenbank. */
        val Initial = WatchlistContent(
            watches = null,
            groupFilter = null,
            alarmCounts = emptyMap(),
            alarmTotal = null,
            alarmFiredAt = emptyMap(),
            latestAlarmTrigger = null,
            activity = emptyMap(),
        )
    }
}

/** Was die Einstellungen an der Darstellung der Merkliste festlegen ([WatchlistSettings]). */
data class WatchlistDisplay(
    /** Mini-Chart in den Zeilen (Einstellung; ob Platz ist, entscheidet [sparklinesFit]). */
    val sparklineEnabled: Boolean,
    /** Karte bzw. ⚡-Chip «Hier passiert gerade etwas». */
    val activityCardEnabled: Boolean,
    /** Sortieren nach Spalte; null = eigene Reihenfolge. */
    val columnSort: ColumnSort?,
    /** Zielwährung der «≈ Umrechnung»; null = ausgeschaltet. */
    val convertTarget: String?,
    /**
     * Eine Frische-Schwelle für alles, was «veraltet» zeigt (Status-Pille, Zeile, Screenreader):
     * [LiveStatus.pollingStaleAfterMillis]; Paare am Live-Strom je Paar schon nach 2 Minuten.
     */
    val staleAfterMillis: Long,
    val changeBasis: ChangeBasis,
    val showChangePeriod: Boolean,
    /** Hauptschalter «Kursmitteilungen»: aus = jede Zeile zeigt die durchgestrichene Glocke. */
    val priceNotificationsOn: Boolean,
    /** Gesten-Hinweis noch zeigen (bis er weggeklickt wird). */
    val showGestureHint: Boolean,
    /** Karte «Ersten Alarm setzen» geschlossen. */
    val firstAlarmCardDismissed: Boolean,
    val activitySensitivity: ActivitySensitivity,
    /** Chart-Art im Aktionsblatt (Linie statt Kerzen). */
    val sheetChartLine: Boolean,
) {
    companion object {
        fun of(s: WatchlistSettings) = WatchlistDisplay(
            sparklineEnabled = s.sparkline,
            activityCardEnabled = s.activityCard,
            columnSort = ColumnSort.decode(s.columnSort),
            convertTarget = s.portfolioCurrency.takeIf { s.showConverted },
            staleAfterMillis = LiveStatus.pollingStaleAfterMillis(s.backgroundIntervalMinutes),
            changeBasis = s.changeBasis,
            showChangePeriod = s.showChangePeriod,
            priceNotificationsOn = s.priceNotifications,
            showGestureHint = !s.gestureHintSeen,
            firstAlarmCardDismissed = s.firstAlarmCardDismissed,
            activitySensitivity = s.activitySensitivity,
            sheetChartLine = s.sheetChartLine,
        )

        /**
         * Stand bis zum ersten Lesen: aus dem Zwischenspeicher [cached], aber vorsichtig, wo ein falscher Wert
         * aufblitzen würde — kein Gesten-Hinweis, Karte «Ersten Alarm setzen» als geschlossen, Frische 45 Min.
         */
        fun initial(cached: AppSettings) = of(WatchlistSettings.of(cached)).copy(
            staleAfterMillis = INITIAL_STALE_AFTER_MILLIS,
            showGestureHint = false,
            firstAlarmCardDismissed = true,
        )
    }
}

/** Was sich laufend ändert: Aktualisierung, Netz, Live-Strom, Umrechnungsfaktoren, Stempel der Änderungen. */
data class WatchlistLive(
    val refreshing: Boolean,
    /** Gerät hat Internet? Ohne: ruhige Statuszeile «Offline · Stand …». */
    val online: Boolean,
    /** Börsen, deren Live-Strom gerade Kurse liefert. */
    val liveExchanges: List<String>,
    /** Paare, deren Börse gerade per WebSocket liefert (eigene Frische-Schwelle). */
    val liveWatchIds: Set<Long>,
    /** Faktor je Quote-Währung in die Zielwährung der «≈ Umrechnung». */
    val convertRates: Map<String, Double>,
    /** Abrufzeit alter Devisenkurse (älter als 3 h) je Quote-Währung. */
    val convertFxTimes: Map<String, Long>,
    /** Faktor je Quote-Währung in CHF, nur fürs Sortieren nach Kurs. */
    val sortRates: Map<String, Double>,
    /** Basis und Tagesbeginn der gespeicherten Veränderungen; null bis bekannt. */
    val changeStamp: ChangeStamp?,
)

/** Start-Auswahl der leeren Merkliste ([WatchlistStarterController]). */
data class StarterUiState(
    val coins: List<StarterPairs.Coin>,
    /** Abgewählte Symbole; leer = alle ausgewählt. */
    val deselected: Set<String>,
    val prices: StarterPricesState,
    /** Hinzufügen läuft (Knopf gesperrt). */
    val adding: Boolean,
    /** Logos geladen bzw. aufgegeben (bis dahin Platzhalter). */
    val logosReady: Boolean,
    /** Quote der Start-Paare (USDT, in den USA USD). */
    val quote: String,
)

/**
 * Paare der gewählten Gruppen-Ansicht: alle (null = «Alle»), die Favoriten (★), «Token», «Alpha» oder eine
 * Gruppe ([WatchFilter.matches]).
 */
internal fun filterByGroup(
    watches: List<WatchEntity>,
    selectedGroup: String?,
    kinds: Map<Long, AssetKind>,
): List<WatchEntity> =
    if (selectedGroup == null) watches
    else watches.filter {
        WatchFilter.matches(selectedGroup, it.groupName, it.favorite, alpha = kinds[it.id] == AssetKind.ALPHA)
    }

/**
 * Mini-Chart zeigen? Nur eingeschaltet und mit genug Breite, damit der Paarname nicht gequetscht wird;
 * grössere Schrift braucht entsprechend mehr Platz.
 */
internal fun sparklinesFit(enabled: Boolean, screenWidthDp: Int, fontScale: Float): Boolean =
    enabled && screenWidthDp >= SPARKLINE_MIN_SCREEN_DP * fontScale.coerceAtLeast(1f)

/** Frische-Schwelle, bis die Einstellungen gelesen sind (wie bisher fest 45 Minuten). */
private const val INITIAL_STALE_AFTER_MILLIS = 45 * 60_000L

/** Mindestbreite des Bildschirms (dp, bei Schriftgrösse 100 %) für das Mini-Chart. */
internal const val SPARKLINE_MIN_SCREEN_DP = 360
