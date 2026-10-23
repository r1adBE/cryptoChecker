// Laden im Portfolio (Teil von `PortfolioViewModel`): Start einer Ansicht, Kurse, Tagesschlüsse des Verlaufs,
// Devisen-Reihe, gemerktes «Heute» bei veralteten Kursen, Coin- und Börsenliste, `safe` für Abrufe.
// Eigene Datei, weil die Abrufe von den abgeleiteten Flows der Klasse getrennt lesbar sein sollen;
// Erweiterungsfunktionen, der Zustand bleibt in der Klasse.
package com.cryptochecker.app.ui.features.portfolio

import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.local.isDatabaseKeyUnavailable
import com.cryptochecker.app.data.portfolio.PortfolioDailyPrices
import com.cryptochecker.app.data.portfolio.withAddresses
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioCostFx
import com.cryptochecker.app.domain.portfolio.PortfolioDecimal
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryFx
import com.cryptochecker.app.domain.portfolio.PortfolioPriceFallback
import com.cryptochecker.app.domain.watch.ChangeBasisMath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import com.cryptochecker.app.data.portfolio.coins
import com.cryptochecker.app.data.portfolio.spotMarketNames
import com.cryptochecker.app.data.portfolio.stored

/**
 * «Heute» (und «Grösste Bewegungen» bleibt leer) aus der zuletzt gespeicherten Momentaufnahme,
 * wenn sie zur eingestellten %-Basis passt (Tagesbeginn: gerechnet für den Tag der Aufnahme —
 * die Karte steht dann mit «Stand …» da). Fehler bleiben still («—»).
 */
internal suspend fun PortfolioViewModel.showStoredToday(fxRate: Double?) {
    val snapshot = safe { withContext(Dispatchers.IO) { snapshotStore.snapshot() } } ?: return
    if (snapshot.empty || snapshot.time <= 0L) return
    if (!ChangeBasisMath.isCurrent(snapshot.stamp, changeBasis.value, snapshot.time)) return
    _todayPercent.value = snapshot.changePercent?.takeIf { it.isFinite() }
    // Faktor der Aufnahme (Devisenkurs × USDT/USD), ohne USDT-Wert der Devisenkurs
    val rate = snapshotUsdtFactor(snapshot, fxRate)
    _todayAmount.value = snapshot.changeAmount?.let { amount -> rate?.let { amount / it } }
    // Zeilen: Gewinn/Verlust je Coin aus derselben Aufnahme (Summe = «Heute»)
    _coinPnl.value = snapshot.coinPnl
}

/** Frische je Coin ([PortfolioPriceFallback.mayRecordShare]): Wert je offenem Coin mit Abfragezeit. */
internal fun PortfolioViewModel.mayRecord(input: PortfolioWidgetInput): Boolean {
    val open = PortfolioCalculator.summarize(input.transactions.map { it.toTrade() }, input.prices.prices, costMethod.value).open
    val values = open.mapNotNull { p -> p.value?.let { PortfolioCalculator.normalizeCoin(p.coin) to it } }.toMap()
    return PortfolioPriceFallback.mayRecordShare(
        values, input.prices.times, System.currentTimeMillis(), staleAfterMillis.value,
        anyPrice = input.prices.updatedAt > 0L,
    )
}

/** Von Tab und Detailansicht: Kurse laden und bei neuen Coins nachladen. */
fun PortfolioViewModel.start() {
    if (started) return
    started = true
    viewModelScope.launch {
        calcTransactions.filterNotNull()
            .map { list -> list.flatMap { it.coins() }.toSet() }
            .distinctUntilChanged()
            .collect { coins ->
                // Zuerst Speicher und gemerkter Stand (ohne Netz, nach einem Neustart), dann frisch
                _prices.value = priceSource.stored(coins)
                loadPrices(coins, force = false)
            }
    }
    // Adress-Guthaben beim Öffnen (nur fällige, 10 min Zwischenspeicher; ohne Adressen keine Anfrage)
    viewModelScope.launch { addressRefresher.refresh(force = false) }
    // Netz wieder da: Kurse auffrischen (60 s Zwischenspeicher), wie die Merkliste
    viewModelScope.launch {
        online.drop(1).filter { it }.collect { refresh(force = false) }
    }
    // Verlauf parallel und nur für den gewählten Zeitraum: Tageskerzen sind je Coin 12 h
    // zwischengespeichert; ein überholter Abruf (Coins oder Zeitraum geändert) wird abgebrochen
    viewModelScope.launch {
        combine(calcTransactions.filterNotNull(), historyRange) { txs, range -> historyRequest(txs, range) }
            .distinctUntilChanged()
            .collectLatest { request -> loadCloses(request) }
    }
    viewModelScope.launch {
        currency.collect { code ->
            _fxRate.value = fxSource.cached(code)
            _fxRate.value = safe { fxSource.usdTo(code) }
            // Für das Portfolio-Widget merken, auch wenn gerade keine Aufnahme gelingt («Umrechnung» statt USDT)
            if (safe { snapshotUpdater.rememberFx(code, _fxRate.value) } == true) safe { snapshotUpdater.redrawWidgets() }
        }
    }
    // Devisen-Tageskurse für den Verlauf: eine Abfrage je Währung und Zeitraum (6 h zwischengespeichert)
    viewModelScope.launch {
        combine(currency, calcTransactions.filterNotNull(), historyRange) { code, txs, range ->
            code to fxSeriesFrom(txs, range)
        }
            .distinctUntilChanged()
            .collectLatest { (code, from) -> loadFxSeries(code, from) }
    }
    // «Seit Kauf»/Einstand in der Anzeigewährung: Kurse ab dem frühesten Erwerbstag der offenen Posten
    viewModelScope.launch {
        combine(currency, summary.filterNotNull()) { code, s -> code to PortfolioCostFx.firstDay(s) }
            .distinctUntilChanged()
            .collectLatest { (code, first) -> first?.let { loadCostFx(code, LocalDate.ofEpochDay(it)) } }
    }
}

/**
 * Kurse für «Seit Kauf»/Einstand in der Anzeigewährung ab [from] ([CostFxLoad]): dieselben Quellen wie der Steuer-Export
 * (Devisen-Tageskurse ab [PortfolioHistoryFx.LEAD_DAYS] Tagen davor, USDT/USD-Tagesschlüsse). USDT braucht keine;
 * schon geladen (gleiche Währung, Zeitraum abgedeckt, bis zum heutigen UTC-Tag) → nichts zu tun. Ohne den letzten Tag
 * blieben Kurse eines neuen Tags aus, bis die App neu startet.
 */
internal suspend fun PortfolioViewModel.loadCostFx(code: String, from: LocalDate) {
    if (code == PortfolioFormat.USDT) return
    val known = _costFx.value
    val utcToday = LocalDate.now(ZoneOffset.UTC)
    if (known != null && known.currency == code && !known.from.isAfter(from) && known.rates != null &&
        !PortfolioHistoryFx.fxLoadOutdated(known.to, utcToday)
    ) return
    val today = PortfolioHistoryFx.fxRequestEnd(LocalDate.now(ZoneId.systemDefault()), utcToday)
    val rates = if (code == "USD") emptyMap() else {
        safe { historicSource.usdToSeries(code, from.minusDays(PortfolioHistoryFx.LEAD_DAYS.toLong()), today) }
            ?.takeIf { it.isNotEmpty() }
            ?.mapNotNull { (day, rate) -> PortfolioDecimal.of(rate)?.let { day to it } }?.toMap()
    }
    val days = (java.time.temporal.ChronoUnit.DAYS.between(from, today) + 1 + PortfolioHistoryFx.LEAD_DAYS)
        .coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    val usdt = safe { historySource.usdtUsdDaily(days) }.orEmpty()
        .mapNotNull { (day, close) -> PortfolioDecimal.of(close)?.let { day to it } }.toMap()
    // Inzwischen andere Währung gewählt: nicht überschreiben
    if (currency.value == code) _costFx.value = CostFxLoad(code, from, rates, usdt, today)
}

internal suspend fun PortfolioViewModel.loadFxSeries(code: String, from: LocalDate) {
    if (code == "USD") return
    val to = PortfolioHistoryFx.fxRequestEnd(LocalDate.now(ZoneId.systemDefault()), LocalDate.now(ZoneOffset.UTC))
    val rates = safe { historicSource.usdToSeries(code, from, to) }
        ?.takeIf { it.isNotEmpty() }
    _fxSeries.value = FxSeriesLoad(code, from, rates, to)
}

/** Beim Öffnen (60 s Zwischenspeicher) bzw. per Ziehen ([force]). */
fun PortfolioViewModel.refresh(force: Boolean) {
    if (_refreshing.value) return
    viewModelScope.launch {
        _refreshing.value = true
        try {
            // Adress-Guthaben zuerst (fällige Adressen; ohne Adressen keine Anfrage)
            addressRefresher.refresh(force)
            val txs = (transactions.value ?: repository.getTransactions())
                .withAddresses(safe { repository.getWatchedAddresses() } ?: watchedAddresses.value)
            loadPrices(txs.flatMap { it.coins() }.toSet(), force)
            // Fehlgeschlagene Coins erneut versuchen (der Rest kommt aus dem Zwischenspeicher)
            loadCloses(historyRequest(txs, historyRange.value))
            // Kein Kurs (auch kein höchstens 24 h alter): keine Umrechnung statt eines toten Kurses
            _fxRate.value = safe { fxSource.usdTo(currency.value) }
            // Fehlten die Devisen-Tageskurse oder enden sie vor dem heutigen UTC-Tag, erneut laden
            val utcToday = LocalDate.now(ZoneOffset.UTC)
            if (_fxSeries.value?.let { it.rates == null || PortfolioHistoryFx.fxLoadOutdated(it.to, utcToday) } != false) {
                loadFxSeries(currency.value, fxSeriesFrom(txs, historyRange.value))
            }
            // Kurse für «Seit Kauf» in der Anzeigewährung: fehlten sie oder ist ein neuer Tag dazugekommen, neu laden
            // (loadCostFx prüft selbst, ob die geladenen noch reichen)
            summary.value?.let(PortfolioCostFx::firstDay)?.let { loadCostFx(currency.value, LocalDate.ofEpochDay(it)) }
        } finally {
            _refreshing.value = false
        }
    }
}

internal suspend fun PortfolioViewModel.loadPrices(coins: Set<String>, force: Boolean) {
    if (coins.isEmpty()) return
    // USDT/USD messen (höchstens alle 5 Minuten, parallel zu den Kursen): Live-Kurs der Bewertung
    viewModelScope.launch { safe { depegChecker.checkIfDue() } }
    safe { priceSource.prices(coins, force) }
    // Frisch Geholtes, sonst der gemerkte Stand je Coin (ohne Netz bleibt der letzte Wert stehen)
    safe { priceSource.stored(coins) }?.let { _prices.value = it }
}

internal suspend fun PortfolioViewModel.loadCloses(request: HistoryRequest) {
    val prices = safe { historySource.dailyPrices(request.coins, request.days) }
    val loaded = HistoryCloses(request, prices ?: PortfolioDailyPrices(emptyMap()))
    // Ein überholter Abruf (Coins oder Zeitraum inzwischen geändert) darf den neueren nicht ersetzen
    val current = historyRequest(calcTransactions.value.orEmpty(), historyRange.value)
    if (loaded.covers(current)) _closes.value = loaded
}

fun PortfolioViewModel.loadCoins() {
    if (_coins.value.isNotEmpty()) return
    viewModelScope.launch { _coins.value = safe { priceSource.coins() }.orEmpty() }
}

fun PortfolioViewModel.loadExchanges() {
    if (_exchanges.value.isNotEmpty()) return
    viewModelScope.launch { _exchanges.value = safe { priceSource.spotMarketNames() }.orEmpty() }
}

/** Abruf ohne Absturz: Fehler → null; protokolliert (Datenbank-Schlüssel nicht lesbar nur als Hinweis). */
internal suspend fun <T> PortfolioViewModel.safe(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    if (e.isDatabaseKeyUnavailable()) Timber.d(e, "Portfolio: Datenbank-Schlüssel nicht verfügbar")
    else Timber.w(e, "Portfolio: Abruf fehlgeschlagen")
    null
}
