// Gespeicherter Inhalt der Merkliste (Teil von `WatchlistViewModel`): Paare, Gruppen-Filter, Zählungen
// und Auslösungen der Alarme sowie «Ungewöhnliche Aktivität», gebündelt zu [WatchlistContent].
// Eigene Klasse, weil diese Ableitungen aus der Datenbank eine eigene Aufgabe sind; das ViewModel besitzt
// sie und gibt [scope] (seinen viewModelScope) mit.
package com.cryptochecker.app.ui.features.watchlist

import com.cryptochecker.app.data.CoinLogoRepository
import com.cryptochecker.app.data.TokenIdentityStore
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.portfolio.PortfolioAlarmRepository
import com.cryptochecker.app.domain.activity.ActivityReport
import com.cryptochecker.app.domain.watch.AssetKind
import com.cryptochecker.app.domain.watch.WatchFilter
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.settings.WatchlistSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

internal class WatchlistContentFlows(
    scope: CoroutineScope,
    watchList: SharedFlow<List<WatchEntity>>,
    watchlistSettings: Flow<WatchlistSettings>,
    watchRepository: WatchRepository,
    private val settingsRepository: SettingsRepository,
    coinLogoRepository: CoinLogoRepository,
    private val tokenIdentityStore: TokenIdentityStore,
    portfolioAlarmRepository: PortfolioAlarmRepository,
    /** Ergebnisse «Ungewöhnliche Aktivität» je Paar; abgelaufene Signale filtert die Oberfläche. */
    private val activity: StateFlow<Map<Long, ActivityReport>>,
    /** Neuer Name einer eben umbenannten Gruppe ([WatchlistEditController.renamedGroup]). */
    private val renamedGroup: (String) -> String?,
) {
    /**
     * Gespeicherte Merkliste OHNE Live-Kurse: ein WebSocket-Tick soll nicht die ganze Liste neu
     * ausgeben (das setzte bei jedem Tick den ganzen Bildschirm neu zusammen). Den Live-Kurs legt
     * jede Zeile selbst darüber ([WatchlistViewModel.livePrices], [LiveOverlay.apply]); gespeichert wird alle 10 s.
     */
    private val shownList: SharedFlow<List<WatchEntity>> = watchList

    /**
     * null bis die Datenbank zum ersten Mal geantwortet hat — so zeigt die
     * Liste Platzhalter statt kurz «leer». Eine Quelle für beides, damit
     * «geladen» und Inhalt nie auseinanderlaufen.
     */
    private val watchesOrNull: StateFlow<List<WatchEntity>?> = shownList
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Vorhandene Gruppen und die gewählte (null = «Alle»). Eine Gruppe gibt
     * es, solange mindestens ein Paar sie nutzt; verschwindet die gewählte,
     * gilt wieder «Alle». null, bis Liste und Einstellung geladen sind —
     * so blitzt beim Öffnen nicht kurz die ungefilterte Liste auf.
     */
    val groupFilter: StateFlow<GroupFilter?> = combine(
        watchList,
        watchlistSettings.map { it.group }.distinctUntilChanged(),
        coinLogoRepository.tradFiPairs,
        coinLogoRepository.alphaSymbols,
        coinLogoRepository.alphaNames,
    ) { list, group, tradFiPairs, alphaSymbols, alphaNames ->
        val groups = groupsOf(list)
        val groupNames = list.map { it.groupName }
        val tokens = WatchFilter.showsTokens(groupNames)
        // Art je Paar (Symbol vor der Glocke, «Alpha»-Chip); die Listen liegen gespeichert vor
        val kinds = list.associate {
            it.id to it.assetKind(tradFiPairs, alphaSymbols, alphaNames, tokenIdentityStore.pairName(it.marketKey, it.baseAsset))
        }
        val alphaFlags = list.map { kinds[it.id] == AssetKind.ALPHA }
        val alpha = WatchFilter.showsAlpha(alphaFlags)
        // «FAV» gibt es immer (auch ohne Favoriten); «Token» nur, solange es TradFi- und andere Paare
        // gibt, «Alpha» nur mit Alpha- und anderen Paaren
        if (!WatchFilter.isAvailable(group, groupNames, alphaFlags)) {
            val renamed = group?.let { renamedGroup(it) }?.takeIf { it in groups }
            settingsRepository.setWatchlistGroup(renamed)
            GroupFilter(groups, renamed, tokens, alpha, kinds)
        } else {
            GroupFilter(groups, group, tokens, alpha, kinds)
        }
    }
        // Erster Zugriff liest und dekodiert die Namensdatei (TokenIdentityStore): nicht auf Main (seit Build 103)
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /** Alle Alarme der Datenbank (Paar-Alarme), für die Zählungen unten. */
    private val pairAlarms = watchRepository.observeAllAlarms()

    /**
     * Alle Alarme, die die Übersicht zeigen würde: Paar-Alarme auch ausgeschaltet, Portfolio-Alarme nur mit
     * eingeschaltetem Portfolio (wie [com.cryptochecker.app.ui.features.alarms.AlarmsOverviewViewModel]); null bis
     * geladen. Entscheidet das Ziel der Glocke ([com.cryptochecker.app.domain.watch.AlarmBellRoute]).
     */
    private val alarmTotal: StateFlow<Int?> = combine(
        pairAlarms,
        portfolioAlarmRepository.observeAll(),
        watchlistSettings.map { it.portfolioEnabled }.distinctUntilChanged(),
    ) { pairs, portfolioAlarms, portfolioEnabled ->
        pairs.size + if (portfolioEnabled) portfolioAlarms.size else 0
    }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /** Anzahl aktiver Alarme je Paar, für die Anzeige in der Liste (auch im Aktionsblatt des Markt-Tabs). */
    val alarmCounts: StateFlow<Map<Long, Int>> = pairAlarms
        .map { alarms ->
            alarms.filter { it.alarm.enabled }
                .groupingBy { it.alarm.watchId }
                .eachCount()
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Letzte Auslösung irgendeines Alarms (Epoch-ms, 0 = nie); null bis zum ersten Stand aus
     * der Datenbank — für den kurzen Puls der Glocke ([com.cryptochecker.app.domain.watch.AlarmPulse]).
     */
    private val latestAlarmTrigger: StateFlow<Long?> = pairAlarms
        .map { alarms -> alarms.maxOfOrNull { it.alarm.lastTriggeredAt } ?: 0L }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Letzte Auslösung je Paar (Epoch-ms; auch abgeschaltete Einmal-Alarme, die nach dem Auslösen
     * aus sind) — für «Alarm ausgelöst» im Screenreader-Satz der Zeile ([com.cryptochecker.app.util.A11ySpeech.firedRecently]).
     */
    private val alarmFiredAt: StateFlow<Map<Long, Long>> = pairAlarms
        .map { alarms ->
            alarms.filter { it.alarm.lastTriggeredAt > 0 }
                .groupBy { it.alarm.watchId }
                .mapValues { (_, list) -> list.maxOf { it.alarm.lastTriggeredAt } }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Gespeicherte Merkliste mit Gruppen, Alarmen und Aktivität in einem Stand. */
    val content: StateFlow<WatchlistContent> = combine(
        combine(watchesOrNull, groupFilter, alarmCounts, alarmTotal, alarmFiredAt) { watches, filter, counts, total, firedAt ->
            WatchlistContent.Initial.copy(
                watches = watches,
                groupFilter = filter,
                alarmCounts = counts,
                alarmTotal = total,
                alarmFiredAt = firedAt,
            )
        },
        latestAlarmTrigger,
        activity,
    ) { base, latest, reports -> base.copy(latestAlarmTrigger = latest, activity = reports) }
        .stateIn(
            scope, SharingStarted.WhileSubscribed(5_000),
            WatchlistContent(
                watches = watchesOrNull.value,
                groupFilter = groupFilter.value,
                alarmCounts = alarmCounts.value,
                alarmTotal = alarmTotal.value,
                alarmFiredAt = alarmFiredAt.value,
                latestAlarmTrigger = latestAlarmTrigger.value,
                activity = activity.value,
            ),
        )
}
