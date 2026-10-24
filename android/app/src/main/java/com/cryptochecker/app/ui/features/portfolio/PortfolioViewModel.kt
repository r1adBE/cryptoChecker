package com.cryptochecker.app.ui.features.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.portfolio.FxRateSource
import com.cryptochecker.app.data.portfolio.HistoricPriceSource
import com.cryptochecker.app.data.portfolio.PortfolioAlarmRepository
import com.cryptochecker.app.data.portfolio.PortfolioHistorySource
import com.cryptochecker.app.data.portfolio.PortfolioPriceSource
import com.cryptochecker.app.data.portfolio.PortfolioPrices
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.data.portfolio.withAddresses
import com.cryptochecker.app.domain.portfolio.AddressBalanceHistory
import com.cryptochecker.app.domain.portfolio.CostMethod
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioChange
import com.cryptochecker.app.domain.portfolio.PortfolioDisplayFx
import com.cryptochecker.app.domain.portfolio.PortfolioExchangeRoute
import com.cryptochecker.app.domain.portfolio.PortfolioHistory
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryFx
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryRange
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryView
import com.cryptochecker.app.domain.portfolio.PortfolioPeriodChange
import com.cryptochecker.app.domain.portfolio.PortfolioPriceFallback
import com.cryptochecker.app.domain.portfolio.PortfolioSummary
import com.cryptochecker.app.domain.portfolio.TodayChange
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import com.cryptochecker.app.domain.portfolio.WatchedAddressRules
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.widget.PortfolioSnapshotUpdater
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Adressen, die einmal automatisch auf «Nur anzeigen» gestellt wurden ([WatchedAddressRules.overlapAutoShowOnly]):
 * [ids] für «Rückgängig», [names] (Bezeichnung oder gekürzte Adresse) und [coins] für den Hinweis.
 */
data class AddressOverlapNotice(val ids: List<Long>, val names: List<String>, val coins: List<String>)

/** Was das Portfolio-Widget aus einer Berechnung braucht. */
internal data class PortfolioWidgetInput(
    val transactions: List<PortfolioTxEntity>,
    val prices: PortfolioPrices,
    val currency: String,
    val fxRate: Double?,
    /** Merkzettel für «Seit Kauf»/«7 T» des Widgets in der Anzeigewährung ([PortfolioViewModel.widgetDisplayFx]). */
    val displayFx: PortfolioDisplayFx? = null,
)

/**
 * Portfolio-Tab, Detailansicht und Erfassen-Blatt (auch aus der Merkliste).
 * Kurse werden erst geladen, wenn eine Ansicht [start] aufruft — das Blatt
 * allein holt nur den Kurs des gewählten Coins.
 */
@HiltViewModel
class PortfolioViewModel @Inject constructor(
    internal val repository: PortfolioRepository,
    internal val priceSource: PortfolioPriceSource,
    internal val fxSource: FxRateSource,
    internal val settingsRepository: SettingsRepository,
    internal val snapshotUpdater: PortfolioSnapshotUpdater,
    internal val historySource: PortfolioHistorySource,
    internal val alarmRepository: PortfolioAlarmRepository,
    internal val historicSource: HistoricPriceSource,
    /** Dürfen Alarme erscheinen? Für « · Mitteilungen aus» in der Bestätigung nach dem Speichern. */
    internal val appNotifier: com.cryptochecker.app.notification.AppNotifier,
    /** Netz da? Für die Status-Pille («Offline · Stand 14:05») wie in der Merkliste. */
    connectivity: com.cryptochecker.app.util.ConnectivityMonitor,
    /** Letzte Momentaufnahme (nur lesen): «Heute» zum gemerkten Stand, wenn die Kurse veraltet sind. */
    internal val snapshotStore: com.cryptochecker.app.widget.PortfolioSnapshotStore,
    /** Portfolio-Logos abgleichen, wenn das Speichern das Portfolio einschaltet. */
    internal val coinLogoSync: com.cryptochecker.app.data.CoinLogoSync,
    /** Guthaben der beobachteten Adressen (10 min Zwischenspeicher, manuell 60 s). */
    internal val addressRefresher: com.cryptochecker.app.data.portfolio.WatchedAddressRefresher,
    /** Letzte Messung USDT/USD: Live-Kurs der Bewertung (USDT zum Marktkurs) und Zeile zu «USDT = 1» der Gesamtkarte. */
    depegReadings: com.cryptochecker.app.domain.alarm.DepegReadingStore,
    /** USDT/USD messen, sobald ein Portfolio da ist (höchstens alle 5 Minuten, wie nach einer Kursaktualisierung). */
    internal val depegChecker: com.cryptochecker.app.domain.alarm.DepegAlarmChecker,
    /** «Unbekannt lassen» bei Einträgen ohne Einstand (nur Ids, auf diesem Gerät). */
    internal val costGapStore: com.cryptochecker.app.data.portfolio.PortfolioCostGapStore,
) : ViewModel() {

    /** null, bis die Datenbank geantwortet hat. Neueste zuerst. */
    val transactions: StateFlow<List<PortfolioTxEntity>?> = repository.observeTransactions()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Beobachtete Adressen (nur lesen), Reihenfolge des Hinzufügens. */
    val watchedAddresses: StateFlow<List<WatchedAddress>> = repository.watchedAddresses
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    internal val _addressOverlapNotice = MutableStateFlow<AddressOverlapNotice?>(null)

    /** Hinweis «einmal automatisch Nur anzeigen» mit «Rückgängig»; der Bildschirm zeigt ihn und ruft [consumeAddressOverlapNotice]. */
    val addressOverlapNotice: StateFlow<AddressOverlapNotice?> = _addressOverlapNotice.asStateFlow()

    internal val _addressOverlapRestored = MutableStateFlow<AddressOverlapNotice?>(null)

    /**
     * Einmaliger Hinweis: früher automatisch auf «Nur anzeigen» gestellte Adressen ohne passende Menge zählen wieder
     * ([WatchedAddressRules.overlapLegacyRevert]); der Bildschirm zeigt ihn und ruft [consumeAddressOverlapRestored].
     */
    val addressOverlapRestored: StateFlow<AddressOverlapNotice?> = _addressOverlapRestored.asStateFlow()

    /**
     * Eingabe aller Berechnungen (Gesamtwert, Zeiträume, Verlauf, Widget, Alarme): echte Transaktionen +
     * Adress-Guthaben als Einzahlung ohne Kaufpreis ([AddressBalance.withAddresses]). Listen und Blatt
     * nutzen [transactions]. null, bis die Datenbank geantwortet hat.
     */
    val calcTransactions: StateFlow<List<PortfolioTxEntity>?> = combine(transactions, watchedAddresses) { txs, addresses ->
        txs?.withAddresses(addresses)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Beobachtete Adress-Guthaben ([AddressBalanceHistory.of]) für Heute / 7 T / 1 J und den Wertverlauf: zählen
     * ab ihrer ersten Beobachtung, nie rückwirkend.
     */
    val addressHistories: StateFlow<List<AddressBalanceHistory>> = watchedAddresses
        .map { AddressBalanceHistory.of(it) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    internal val _prices = MutableStateFlow(PortfolioPrices())
    val prices: StateFlow<PortfolioPrices> = _prices.asStateFlow()

    /** Gerät online (Status-Pille; kommt das Netz zurück, werden die Kurse aufgefrischt). */
    val online: StateFlow<Boolean> = connectivity.online

    /** Ab diesem Alter gelten die Portfolio-Kurse als veraltet ([PortfolioPriceFallback.staleAfterMillis]). */
    val staleAfterMillis: StateFlow<Long> = settingsRepository.settings
        .map { PortfolioPriceFallback.staleAfterMillis(it.backgroundIntervalMinutes) }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            PortfolioPriceFallback.staleAfterMillis(settingsRepository.cached.backgroundIntervalMinutes),
        )

    /** Letzte Messung USDT/USD ([com.cryptochecker.app.domain.alarm.DepegAlarm.cardNote] mit Uhr). */
    val depegReading: StateFlow<com.cryptochecker.app.domain.alarm.DepegAlarm.Reading?> = depegReadings.reading

    /**
     * Live-Kurs USDT/USD der Bewertung ([com.cryptochecker.app.domain.alarm.DepegAlarm.liveUsdtUsd]): die letzte Messung,
     * höchstens 6 Std. alt ([com.cryptochecker.app.domain.alarm.DepegAlarm.LIVE_MAX_AGE_MILLIS]) — jede Minute neu geprüft; null = keiner (USDT = 1 als Näherung, Karte markiert sie).
     */
    val liveUsdtUsd: StateFlow<Double?> = combine(depegReadings.reading, minuteTicks()) { reading, now ->
        com.cryptochecker.app.domain.alarm.DepegAlarm.liveUsdtUsd(reading, now)
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, depegReadings.liveUsdtUsd())

    /** Schwelle des Depeg-Alarms in Zehntel-Prozent (gilt auch für die Warnzeile der Karte). */
    val depegThresholdTenths: StateFlow<Int> = settingsRepository.settings
        .map { it.depegThresholdTenths }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.cached.depegThresholdTenths)

    internal val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    val currency: StateFlow<String> = settingsRepository.settings
        .map { it.portfolioCurrency }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.cached.portfolioCurrency)

    /** USD → [currency]; null = unbekannt (Umrechnungszeile ausblenden). */
    internal val _fxRate = MutableStateFlow<Double?>(null)
    val fxRate: StateFlow<Double?> = _fxRate.asStateFlow()

    /**
     * Faktor USDT → [currency] für Gesamtkarte und Zeilen: Devisenkurs USD → Währung × USDT/USD
     * ([PortfolioStables.usdtTo], USDT zum Marktkurs; ohne Messung × 1). null = kein Devisenkurs.
     */
    val displayFxRate: StateFlow<Double?> = combine(_fxRate, liveUsdtUsd) { fx, live ->
        com.cryptochecker.app.domain.portfolio.PortfolioStables.usdtTo(fx, com.cryptochecker.app.domain.portfolio.PortfolioStables.usdtUsdLive(live))
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Veränderung «heute» in Prozent wie im Portfolio-Widget (aus der letzten
     * Momentaufnahme, siehe PortfolioSnapshotMath); null = noch keine Vergleichsbasis.
     */
    internal val _todayPercent = MutableStateFlow<Double?>(null)
    val todayPercent: StateFlow<Double?> = _todayPercent.asStateFlow()

    /**
     * Betrag zu [todayPercent] in USDT (aus der Momentaufnahme in die Bewertungswährung zurückgerechnet);
     * null = noch keine Vergleichsbasis.
     */
    internal val _todayAmount = MutableStateFlow<Double?>(null)
    val todayAmount: StateFlow<Double?> = _todayAmount.asStateFlow()

    /** Coins, die in «Heute» fehlen (kein Vergleichskurs, [PortfolioSnapshot.todaySkipped]). */
    internal val _todaySkipped = MutableStateFlow<List<String>>(emptyList())
    val todaySkipped: StateFlow<List<String>> = _todaySkipped.asStateFlow()

    /**
     * Kursveränderung je Coin (gleiche Basis wie [todayPercent], aus der letzten Momentaufnahme)
     * für «Grösste Bewegungen»; leer = noch keine Vergleichsbasis.
     */
    internal val _coinChanges = MutableStateFlow<Map<String, Double>>(emptyMap())
    val coinChanges: StateFlow<Map<String, Double>> = _coinChanges.asStateFlow()

    /**
     * MEIN Gewinn/Verlust je Coin in USDT über dieselbe %-Basis ([PortfolioSnapshot.coinPnl],
     * [com.cryptochecker.app.domain.portfolio.PortfolioPeriodChange.todayByCoin]) für die Positionszeilen;
     * die Summe ist «Heute» der Gesamtkarte. Leer = noch keine Vergleichsbasis.
     */
    internal val _coinPnl = MutableStateFlow<Map<String, PortfolioChange>>(emptyMap())
    val coinPnl: StateFlow<Map<String, PortfolioChange>> = _coinPnl.asStateFlow()

    /** %-Basis (Beschriftung von «heute» und «Grösste Bewegungen»). */
    val changeBasis: StateFlow<ChangeBasis> = settingsRepository.settings
        .map { it.changeBasis.storage }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.cached.changeBasis.storage)

    /** «Beträge verbergen»: alle Beträge als «•••», Prozente bleiben. */
    val hideAmounts: StateFlow<Boolean> = settingsRepository.settings
        .map { it.hidePortfolioAmounts }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.cached.hidePortfolioAmounts)

    /** Einstandsmethode FIFO | Ø (Standard FIFO) für Einstand, «Seit Kauf» und realisiert. */
    val costMethod: StateFlow<CostMethod> = settingsRepository.settings
        .map { it.costMethod }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.cached.costMethod)

    /** Geladene Tagesschlusskurse (Verlauf, «7 T»/«1 J», Kurs einer Auszahlung ohne Preis); null = noch nicht geladen. */
    internal val _closes = MutableStateFlow<HistoryCloses?>(null)

    val summary: StateFlow<PortfolioSummary?> = combine(
        calcTransactions, _prices, costMethod, _closes, currency.map { it != PortfolioFormat.USDT }.distinctUntilChanged(),
    ) { txs, p, method, loaded, withParts ->
        // «Ausgebucht»: Auszahlung ohne Preis zum Tagesschluss ihres Tags (danach aktueller Kurs); ohne geladene
        // Schlusskurse unbekannt → «unvollständig» (PortfolioSummary.withdrawnGainIncomplete); Gebühr-Zeilen ebenso.
        // Herkunftsteile des Ø-Einstands nur für «Seit Kauf» in der Anzeigewährung ([costFx]); in USDT linear ohne
        txs?.let { list ->
            PortfolioCalculator.summarize(list.map { it.toTrade() }, p.prices, method, marketPriceAt(p, loaded), withParts = withParts)
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null) // Rechnen nicht auf dem Main-Thread

    // PERF3-R1: Kursfunktion der Coin-Ansicht nicht mehr hier, sondern in coinDetailFlow (CoinFeePrices)

    /** Zeitraum des Wertverlaufs (zuletzt gewählt, nur auf diesem Gerät). */
    val historyRange: StateFlow<PortfolioHistoryRange> = settingsRepository.settings
        .map { it.portfolioHistoryRange }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.cached.portfolioHistoryRange)

    /** Karte «Wertverlauf» aufgeklappt (Standard zu; nur auf diesem Gerät). */
    val historyExpanded: StateFlow<Boolean> = settingsRepository.settings
        .map { it.portfolioHistoryExpanded }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.cached.portfolioHistoryExpanded)

    /** Darstellung des Wertverlaufs: Währung, USDT oder Vergleich (zuletzt gewählt, nur auf diesem Gerät). */
    val historyView: StateFlow<PortfolioHistoryView> = settingsRepository.settings
        .map { it.portfolioHistoryView }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.cached.portfolioHistoryView)

    /** Wertverlauf als Kerzen statt Linie (zuletzt gewählt, nur auf diesem Gerät). */
    val historyCandles: StateFlow<Boolean> = settingsRepository.settings
        .map { it.portfolioHistoryCandles }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.cached.portfolioHistoryCandles)

    /** Wertverlauf als «Gewinn/Verlust» statt «Wert» (zuletzt gewählt, nur auf diesem Gerät). */
    val historyPnl: StateFlow<Boolean> = settingsRepository.settings
        .map { it.portfolioHistoryPnl }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.cached.portfolioHistoryPnl)

    /**
     * «Gewinn 7 T» im Gesamtwert-Kopf: Gewinn/Verlust seit dem Tagesschluss vor
     * [PortfolioPeriodChange.DAYS] Tagen, Käufe/Verkäufe im Zeitraum berücksichtigt (USDT,
     * [PortfolioPeriodChange.gain]). Aus den ohnehin geladenen Tagesschlusskursen — jede Ladung reicht
     * mindestens [PortfolioHistory.MAX_DAYS] Tage zurück, egal welcher Zeitraum gewählt ist.
     * null = unbekannt («—»): Kurse laden noch, decken nicht alle Coins ab oder passen nicht.
     */
    /** Tageskurse USD → Umrechnungswährung für den Verlauf und «7 T»/«1 J»; null = noch nicht geladen. */
    internal val _fxSeries = MutableStateFlow<FxSeriesLoad?>(null)

    /** Kurse ab dem frühesten Erwerbstag der offenen Posten für [costFx]; null = noch nicht geladen. */
    internal val _costFx = MutableStateFlow<CostFxLoad?>(null)

    /**
     * «Seit Kauf» und Einstand der Gesamtkarte in der Anzeigewährung zum Kurs der Erwerbstage
     * ([com.cryptochecker.app.domain.portfolio.PortfolioCostFx]): Wert heute × [displayFxRate] − Einstand je Teil zum
     * Faktor seines Tags (Regel des Steuer-Exports). null = nicht umgerechnet (USDT, kein Devisenkurs) oder die Kurse
     * laden noch — die Karte zeigt dann in der Anzeigewährung «—» statt einer Zahl zum heutigen Kurs.
     */
    val costFx: StateFlow<com.cryptochecker.app.domain.portfolio.PortfolioCostFxFigures?> = costFxFlow()

    /**
     * Merkzettel fürs Portfolio-Widget ([PortfolioDisplayFx]): Einstand je Coin zum Kurs der Erwerbstage ([costFx]) und
     * die Tagesfaktoren der letzten Tage wie «7 T» der Karte ([periodChange]) — damit jede Aufnahme, auch im
     * Hintergrund, «Seit Kauf» und «7 T» in der Anzeigewährung wie die Karte zeigt statt USDT × heutiger Kurs. null in
     * USDT bzw. solange die Kurse laden. Ohne den Live-Kurs USDT/USD (gilt erst ab heute, kommt aus der Aufnahme).
     */
    internal val widgetDisplayFx: StateFlow<PortfolioDisplayFx?> = widgetDisplayFxFlow()

    val weekChange: StateFlow<TodayChange?> = periodChange(PortfolioPeriodChange.DAYS, display = true)

    /** «7 T» rein in USDT (ohne Devisenkurs) — eigene Zahl neben der Anzeige in der Währung. */
    val weekChangeUsdt: StateFlow<TodayChange?> = periodChange(PortfolioPeriodChange.DAYS, display = false)

    /**
     * Zeile unter dem Gesamtwert «↘ −38’412.95 USDT −27.85% · Gewinn 1 J»: gleiche Regel wie «Gewinn 7 T»
     * mit dem Tagesschluss vor [PortfolioPeriodChange.YEAR_DAYS] Tagen. null = keine Daten (z. B.
     * Kerzen reichen nicht ein Jahr zurück) — dann entfällt die Zeile.
     */
    val yearChange: StateFlow<TodayChange?> = periodChange(PortfolioPeriodChange.YEAR_DAYS, display = true)

    /**
     * Hinweis an «7 T»/«1 J»: offene Coins, deren Verlauf im letzten Jahr Binance auf den Börsenkurs
     * skaliert nutzt ([PortfolioExchangeRoute.scaledHint]); leer = keiner (eigene Tageskerzen).
     */
    val periodScaled: StateFlow<List<String>> = periodScaledFlow()

    /**
     * Wertverlauf des gewählten Zeitraums; null, solange dessen Tageskurse (und bei einer
     * Umrechnungswährung die Devisen-Tageskurse) noch laden (Platzhalter). Auch zugeklappt
     * gerechnet — die Zeile zeigt die Änderung —, aber nur für den gewählten Zeitraum.
     * Umgerechnet wird je Punkt mit dem Kurs seines Tags ([PortfolioHistoryFx]), heute mit dem
     * aktuellen. Gerechnet abseits des Hauptthreads.
     */
    val history: StateFlow<PortfolioHistoryUi?> = historyFlow()

    /** Neuer Portfolio-Alarm: ein Anlegen zur Zeit ([addAlarm], Doppeltipp). */
    internal val alarmCreateGuard = com.cryptochecker.app.ui.features.alarms.AlarmCreateGuard()

    internal val _coins = MutableStateFlow<List<String>>(emptyList())

    /** Wählbare Coins für die Suche (leer, solange nicht geladen). */
    val coins: StateFlow<List<String>> = _coins.asStateFlow()

    internal var started = false

    init {
        viewModelScope.launch { repository.migrateHoldingsOnce() }
        // Hintergrund-Aufgaben in PortfolioBackgroundJobs.kt
        restoreLegacyAddressOverlap()
        switchOverlappingAddresses()
        recordWidgetSnapshots()
    }

    fun setHistoryRange(range: PortfolioHistoryRange) {
        viewModelScope.launch { settingsRepository.setPortfolioHistoryRange(range) }
    }

    fun setHistoryExpanded(expanded: Boolean) {
        viewModelScope.launch { settingsRepository.setPortfolioHistoryExpanded(expanded) }
    }

    fun setHistoryView(view: PortfolioHistoryView) {
        viewModelScope.launch { settingsRepository.setPortfolioHistoryView(view) }
    }

    fun setHistoryPnl(pnl: Boolean) {
        viewModelScope.launch { settingsRepository.setPortfolioHistoryPnl(pnl) }
    }

    fun setHistoryCandles(candles: Boolean) {
        viewModelScope.launch { settingsRepository.setPortfolioHistoryCandles(candles) }
    }

    internal val _exchanges = MutableStateFlow<List<String>>(emptyList())

    /** Spot-Börsen der App für das Feld «Börse» ([PortfolioPriceSource.spotMarketNames]). */
    val exchanges: StateFlow<List<String>> = _exchanges.asStateFlow()

    /** «Beträge verbergen» umschalten; das Portfolio-Widget zeichnet gleich neu. */
    fun setHideAmounts(hidden: Boolean) {
        viewModelScope.launch {
            settingsRepository.setHidePortfolioAmounts(hidden)
            safe { snapshotUpdater.redrawWidgets() }
        }
    }

    fun setCurrency(code: String) {
        viewModelScope.launch { settingsRepository.setPortfolioCurrency(code) }
    }

}
