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
import com.cryptochecker.app.domain.portfolio.PortfolioCompare
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
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
        com.cryptochecker.app.domain.portfolio.PortfolioStables.usdtTo(fx, com.cryptochecker.app.domain.portfolio.PortfolioStables.usdtUsd(live))
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
    private val _todaySkipped = MutableStateFlow<List<String>>(emptyList())
    val todaySkipped: StateFlow<List<String>> = _todaySkipped.asStateFlow()

    /**
     * Kursveränderung je Coin (gleiche Basis wie [todayPercent], aus der letzten Momentaufnahme)
     * für «Grösste Bewegungen»; leer = noch keine Vergleichsbasis.
     */
    private val _coinChanges = MutableStateFlow<Map<String, Double>>(emptyMap())
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

    val summary: StateFlow<PortfolioSummary?> = combine(calcTransactions, _prices, costMethod, _closes) { txs, p, method, loaded ->
        // «Ausgebucht»: Auszahlung ohne Preis zum Tagesschluss ihres Tags (danach aktueller Kurs); ohne geladene
        // Schlusskurse unbekannt → «unvollständig» (PortfolioSummary.withdrawnGainIncomplete); Gebühr-Zeilen ebenso
        txs?.let { list -> PortfolioCalculator.summarize(list.map { it.toTrade() }, p.prices, method, marketPriceAt(p, loaded)) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null) // Rechnen nicht auf dem Main-Thread

    /**
     * Marktkurs eines Coins zu einem Zeitpunkt (Tagesschluss seines Tags, danach aktueller Kurs) wie in [summary] — für
     * die Gebühr-Zeilen der Coin-Ansicht ([PortfolioCalculator.ledger] `feePrice`), damit sie dieselbe Zahl zeigt.
     */
    val feePrice: StateFlow<(String, Long) -> Double?> = combine(_prices, _closes) { p, loaded -> marketPriceAt(p, loaded) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, marketPriceAt(PortfolioPrices(), null))

    private fun marketPriceAt(p: PortfolioPrices, loaded: HistoryCloses?): (String, Long) -> Double? {
        val closes = loaded?.routed(p)?.closes.orEmpty()
        return { coin, time -> PortfolioPeriodChange.marketPriceAt(coin, p.prices[PortfolioCalculator.normalizeCoin(coin)], closes, time) }
    }

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
     * Gewinn/Verlust seit dem Tagesschluss vor [days] Tagen ([PortfolioPeriodChange.gain]). [display]: in der
     * Anzeigewährung mit denselben Tageskursen wie der Wertverlauf ([PortfolioHistoryFx.rateFunction]) — Kopf und Kurve
     * zeigen dieselbe Zahl; als Gegenwert zum heutigen Kurs ([PortfolioHistoryFx.forCard]), weil die Karte Beträge mit
     * dem heutigen Kurs anzeigt. Ohne passende Tageskurse wie bisher USDT (× heutiger Kurs in der Karte).
     */
    private fun periodChange(days: Int, display: Boolean): StateFlow<TodayChange?> = combine(
        summary, _closes, calcTransactions, _prices,
        combine(addressHistories, currency, _fxRate, _fxSeries, liveUsdtUsd) { a, code, fx, series, live ->
            PeriodFx(a, code, fx, series, live)
        },
    ) { current, loaded, txs, p, (addresses, code, fxRate, fxSeries, live) ->
        if (current == null || loaded == null || txs == null) return@combine null
        val holdings = current.open.associate { it.coin to it.holdings }
        if (holdings.isEmpty()) return@combine null
        // Ladung für ältere Coins: neue Coins fehlen noch → lieber «—»
        if (!loaded.request.coins.containsAll(holdings.keys.map { PortfolioCalculator.normalizeCoin(it) })) {
            return@combine null
        }
        val prices = current.open.mapNotNull { p -> p.currentPrice?.let { p.coin to it } }.toMap()
        val today = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
        // Tageskerzen der eingetragenen Börse, sonst Binance skaliert (PortfolioExchangeRoute.historyCloses)
        // Tageskurse, die bis zum Stichtag zurückreichen: umrechnen wie der Wertverlauf
        // Auch USD: USDT zum Marktkurs ([PortfolioHistoryFx.convertsUsdt]); Devisenkurs dort an jedem Tag 1
        val rate = fxRate?.takeIf { display && PortfolioHistoryFx.convertsUsdt(code, usdtMarket = true) && it > 0.0 && it.isFinite() }
        val rates = if (PortfolioHistoryFx.convertsUsdtAsUsd(code)) {
            fxSeries?.takeIf { it.currency == code && !it.from.isAfter(LocalDate.ofEpochDay(today - days)) }?.rates
        } else {
            PortfolioHistoryFx.usdParity(today - days - PortfolioHistoryFx.LEAD_DAYS, today)
        }
        // USDT zum Marktkurs: je Tag × USDT/USD-Tagesschluss, ab heute × Live-Kurs (wie der Wertverlauf)
        val usdt = com.cryptochecker.app.domain.portfolio.UsdtUsdRates(live, loaded.daily.usdtUsd)
        val fx = if (rate != null && !rates.isNullOrEmpty()) PortfolioHistoryFx.rateFunction(rates, rate, today, usdt) else null
        // Bestände nach derselben Einstandsmethode wie die Karte (FIFO je Wallet: je Wallet gekappt)
        val change = PortfolioPeriodChange.gain(
            txs.map { it.toTrade() }, prices, loaded.routed(p).closes, today - days, addresses = addresses, method = current.method,
            fx = fx,
        )
        // Gegenwert zum Faktor der Karte (Devisenkurs × USDT/USD jetzt, [displayFxRate])
        if (change != null && fx != null && rate != null) PortfolioHistoryFx.forCard(change, rate * usdt.current().rate) else change
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Hinweis an «7 T»/«1 J»: offene Coins, deren Verlauf im letzten Jahr Binance auf den Börsenkurs
     * skaliert nutzt ([PortfolioExchangeRoute.scaledHint]); leer = keiner (eigene Tageskerzen).
     */
    val periodScaled: StateFlow<List<String>> = combine(summary, _closes, _prices) { current, loaded, p ->
        if (current == null || loaded == null) return@combine emptyList()
        val today = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
        PortfolioExchangeRoute.scaledHint(
            loaded.routed(p).scaled, loaded.daily.binanceBefore, today - PortfolioPeriodChange.YEAR_DAYS,
            current.open.map { it.coin },
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Wertverlauf des gewählten Zeitraums; null, solange dessen Tageskurse (und bei einer
     * Umrechnungswährung die Devisen-Tageskurse) noch laden (Platzhalter). Auch zugeklappt
     * gerechnet — die Zeile zeigt die Änderung —, aber nur für den gewählten Zeitraum.
     * Umgerechnet wird je Punkt mit dem Kurs seines Tags ([PortfolioHistoryFx]), heute mit dem
     * aktuellen. Gerechnet abseits des Hauptthreads.
     */
    val history: StateFlow<PortfolioHistoryUi?> = combine(
        combine(calcTransactions, addressHistories, costMethod) { txs, addresses, method -> Triple(txs, addresses, method) },
        _closes,
        _prices,
        historyRange,
        combine(currency, _fxRate, _fxSeries, liveUsdtUsd) { code, fx, series, live -> HistoryFx(code, fx, series, live) },
    ) { (txs, addresses, method), loaded, p, range, (code, fx, fxSeries, live) ->
        if (txs == null || loaded == null) return@combine null
        if (!loaded.covers(historyRequest(txs, range))) return@combine null
        // Auch USD: USDT zum Marktkurs (je Tag × USDT/USD-Tagesschluss), Devisenkurs dort an jedem Tag 1
        val converted = PortfolioHistoryFx.convertsUsdt(code, usdtMarket = true) && fx != null
        val viaFx = PortfolioHistoryFx.convertsUsdtAsUsd(code)
        // Devisen-Tageskurse dieses Zeitraums noch nicht da: Platzhalter statt still falscher Kurve
        if (converted && viaFx && (fxSeries == null || fxSeries.currency != code || fxSeries.from.isAfter(fxSeriesFrom(txs, range)))) {
            return@combine null
        }
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone).toEpochDay()
        // Eigene Börse, sonst Binance skaliert — Dochte und Eröffnung mit demselben Faktor wie die Schlüsse
        val routed = loaded.routed(p)
        val series = PortfolioHistory.build(
            trades = txs.map { it.toTrade() },
            closes = routed.closes,
            livePrices = p.prices,
            range = range,
            todayEpochDay = today,
            dayEndMillis = dayEndIn(zone),
            ranges = routed.ranges,
            // Adress-Guthaben ab ihrer ersten Beobachtung (Wert und Gewinn/Verlust)
            addresses = addresses,
            method = method,
        )
        val scaled = series.points.firstOrNull()?.let { first ->
            val drawn = txs.flatMap { it.coins() }.map { PortfolioCalculator.normalizeCoin(it) }.distinct().filter { it !in series.skipped }
            PortfolioExchangeRoute.scaledHint(routed.scaled, loaded.daily.binanceBefore, first.epochDay, drawn)
        }.orEmpty()
        if (!converted) return@combine PortfolioHistoryUi(range, series, PortfolioFormat.USDT, false, scaled = scaled)
        // USDT zum Marktkurs: je Tag × USDT/USD-Tagesschluss (Tag ohne Schluss: 1, als Näherung markiert)
        val usdt = com.cryptochecker.app.domain.portfolio.UsdtUsdRates(live, loaded.daily.usdtUsd)
        val dailyRates = if (viaFx) fxSeries?.rates else PortfolioHistoryFx.usdParity(fxSeriesFrom(txs, range).toEpochDay(), today)
        val result = PortfolioHistoryFx.convert(series, dailyRates, fx, today, usdt)
        // Vergleich mit USDT nur mit Tageskursen — mit dem heutigen Kurs wäre er bedeutungslos
        if (result.approximate) {
            return@combine PortfolioHistoryUi(range, result.series, code, true, true, scaled = scaled, usdtApproximate = result.usdtApproximate)
        }
        PortfolioHistoryUi(
            range = range,
            series = result.series,
            unit = code,
            converted = true,
            approximateFx = false,
            usdtSeries = series,
            compare = PortfolioCompare.build(result.series.points, series.points),
            scaled = scaled,
            usdtApproximate = result.usdtApproximate,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    internal val _coins = MutableStateFlow<List<String>>(emptyList())

    /** Wählbare Coins für die Suche (leer, solange nicht geladen). */
    val coins: StateFlow<List<String>> = _coins.asStateFlow()

    internal var started = false

    init {
        viewModelScope.launch { repository.migrateHoldingsOnce() }
        // Alte Entscheide (früher genügte derselbe Coin): einmal übernehmen; «Nur anzeigen» ohne passende Menge zählt
        // wieder (Hinweis mit «Rückgängig»)
        viewModelScope.launch {
            combine(transactions.filterNotNull(), watchedAddresses) { txs, list -> txs to list }
                .filter { (_, list) -> list.any { it.overlapLegacy } }
                .collect { (txs, list) ->
                    val trades = withContext(Dispatchers.Default) { txs.map { it.toTrade() } }
                    val legacy = list.filter { it.overlapLegacy }
                    val restored = legacy.filter { a ->
                        val revert = WatchedAddressRules.overlapLegacyRevert(a, trades)
                        safe { repository.migrateWatchedAddressOverlap(a.id, revert) } != null && revert
                    }
                    if (restored.isEmpty()) return@collect
                    val own = txs.map { PortfolioCalculator.normalizeCoin(it.coin) }.toSet()
                    val previous = _addressOverlapRestored.value
                    _addressOverlapRestored.value = AddressOverlapNotice(
                        ids = previous?.ids.orEmpty() + restored.map { it.id },
                        names = previous?.names.orEmpty() + restored.map { it.label ?: WatchedAddressRules.shortAddress(it.address) },
                        coins = (previous?.coins.orEmpty() + restored.flatMap { WatchedAddressRules.overlap(it, own) }).distinct(),
                    )
                }
        }
        // Später aufgetauchte Überschneidung Adresse ↔ eigene Transaktionen mit passender Menge: einmal automatisch
        // «Nur anzeigen» (Hinweis); nur gleicher Coin: zählt weiter (Hinweis an der Adresse)
        viewModelScope.launch {
            combine(transactions.filterNotNull(), watchedAddresses) { txs, list ->
                WatchedAddressRules.overlapAutoShowOnly(list.filter { !it.overlapLegacy }, txs.map { it.toTrade() }) to txs
            }
                .flowOn(Dispatchers.Default)
                .filter { (due, _) -> due.isNotEmpty() }
                .collect { (due, txs) ->
                    val own = txs.map { PortfolioCalculator.normalizeCoin(it.coin) }.toSet()
                    val switched = due.filter { a -> safe { repository.setWatchedAddressOverlapShowOnly(a.id) } != null }
                    if (switched.isEmpty()) return@collect
                    val previous = _addressOverlapNotice.value
                    _addressOverlapNotice.value = AddressOverlapNotice(
                        ids = previous?.ids.orEmpty() + switched.map { it.id },
                        names = previous?.names.orEmpty() + switched.map { it.label ?: WatchedAddressRules.shortAddress(it.address) },
                        coins = (previous?.coins.orEmpty() + switched.flatMap { WatchedAddressRules.overlap(it, own) }).distinct(),
                    )
                }
        }
        // Portfolio-Widget: jede neue Berechnung (Transaktionen, Kurse, Währung) als Momentaufnahme
        viewModelScope.launch {
            combine(calcTransactions.filterNotNull(), _prices, currency, _fxRate) { txs, p, code, fx ->
                PortfolioWidgetInput(txs, p, code, fx)
            }
                .distinctUntilChanged()
                .collect { input ->
                    // Ohne geladene Kurse nichts aufnehmen (sonst stünde kurz 0 im Widget)
                    if (input.transactions.isNotEmpty() && input.prices.updatedAt <= 0L) return@collect
                    // Zu wenig frische Kurse (ohne Netz, nach einem Neustart): nicht als «jetzt» aufnehmen
                    // (Widget, Verlauf, Alarme blieben sonst mit alten Kursen stehen); «Heute» dann aus der
                    // letzten Momentaufnahme. Frische je Coin: ein einzelner alter Coin hält die übrigen
                    // nicht an, solange mindestens 80 % des Werts frisch sind
                    if (input.transactions.isNotEmpty() && !withContext(Dispatchers.Default) { mayRecord(input) }) {
                        if (_todayPercent.value == null) showStoredToday(input.fxRate)
                        return@collect
                    }
                    safe { snapshotUpdater.record(input.transactions, input.prices.prices, input.currency, input.fxRate, routing = input.prices) }
                        ?.let { snapshot ->
                            // Betrag in der Anzeigewährung der Aufnahme → USDT (wie der Gesamtwert): Faktor der Aufnahme
                            // (Devisenkurs × USDT/USD, [snapshotUsdtFactor])
                            val rate = snapshotUsdtFactor(snapshot, input.fxRate)
                            val amount = snapshot.changeAmount
                                ?.takeUnless { snapshot.empty }
                                ?.let { value -> rate?.let { value / it } }
                            val percent = snapshot.changePercent?.takeUnless { snapshot.empty }
                            // «Gewinn heute» kommt schon berichtigt aus der Aufnahme (Käufe/Verkäufe heraus,
                            // Coins ohne Vergleichskurs fehlen) — dieselbe Zahl wie Widget und Portfolio-Alarm
                            _todayPercent.value = percent
                            _todayAmount.value = amount
                            _todaySkipped.value = snapshot.todaySkipped
                            _coinChanges.value = snapshot.coinChanges
                            _coinPnl.value = if (snapshot.empty) emptyMap() else snapshot.coinPnl
                        }
                }
        }
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
