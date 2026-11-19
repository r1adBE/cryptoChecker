// Laden im Portfolio (Teil von `PortfolioViewModel`): Start einer Ansicht, Kurse, Tagesschlüsse des Verlaufs,
// Devisen-Reihe, gemerktes «Heute» bei veralteten Kursen, Coin- und Börsenliste, `safe` für Abrufe.
// Eigene Datei, weil die Abrufe von den abgeleiteten Flows der Klasse getrennt lesbar sein sollen;
// Erweiterungsfunktionen, der Zustand bleibt in der Klasse.
package com.cryptochecker.app.ui.features.portfolio

import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.domain.local.isDatabaseKeyUnavailable
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioCostFx
import com.cryptochecker.app.domain.portfolio.PortfolioDailyPrices
import com.cryptochecker.app.domain.portfolio.PortfolioDecimal
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryFx
import com.cryptochecker.app.domain.portfolio.PortfolioPriceFallback
import com.cryptochecker.app.domain.portfolio.withAddresses
import com.cryptochecker.app.domain.util.LoadProgress
import com.cryptochecker.app.domain.watch.ChangeBasisMath
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
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

/**
 * Frische je Coin ([PortfolioPriceFallback.mayRecordShare]): Wert je offenem Coin mit Abfragezeit. Die Gesamtsicht kommt
 * aus dem Zwischenspeicher der Aufnahme ([com.cryptochecker.app.widget.PortfolioSnapshotUpdater.summaries]) — die
 * Aufnahme gleich danach rechnet mit denselben Eingaben nicht noch einmal.
 */
internal fun PortfolioViewModel.mayRecord(input: PortfolioWidgetInput): Boolean {
    val open = snapshotUpdater.summaries.summarize(input.transactions.map { it.toTrade() }, input.prices.prices, costMethod.value).open
    val values = open.mapNotNull { p -> p.value?.let { PortfolioCalculator.normalizeCoin(p.coin) to it } }.toMap()
    return PortfolioPriceFallback.mayRecordShare(
        values,
        input.prices.times,
        System.currentTimeMillis(),
        staleAfterMillis.value,
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
    ) {
        return
    }
    val today = PortfolioHistoryFx.fxRequestEnd(LocalDate.now(ZoneId.systemDefault()), utcToday)
    val rates = if (code == "USD") {
        emptyMap()
    } else {
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

/**
 * Beim Öffnen (60 s Zwischenspeicher) bzw. per Ziehen oder Menü ([force]). Die Abrufe laufen gleichzeitig: Adressen,
 * Kurse, Tagesschlüsse, Devisenkurs, Devisen-Reihe und Kurse für «Seit Kauf» hängen nicht voneinander ab — Werte
 * erscheinen, sobald ihr Teil da ist. Bringen die Adressen neue Coins, lädt [start] deren Kurse nach.
 * Während des Ladens ein dünner Balken ([PortfolioLoadState.progress]); beim Ziehen kurz der Kreisel.
 */
fun PortfolioViewModel.refresh(force: Boolean) {
    if (load.busyFlow.value) return
    viewModelScope.launch {
        load.busyFlow.value = true
        load.manualFlow.value = force
        val progress = LoadProgress(REFRESH_STEPS)
        load.progressFlow.value = progress.current()
        // Ein Schritt fertig: Balken vor ([LoadProgressBar])
        suspend fun step(block: suspend () -> Unit) {
            block()
            load.progressFlow.value = progress.step()
        }
        try {
            val txs = (transactions.value ?: repository.getTransactions())
                .withAddresses(safe { repository.getWatchedAddresses() } ?: watchedAddresses.value)
            val code = currency.value
            coroutineScope {
                // Adress-Guthaben (fällige Adressen; ohne Adressen keine Anfrage)
                launch { step { safe { addressRefresher.refresh(force) } } }
                launch { step { loadPrices(txs.flatMap { it.coins() }.toSet(), force) } }
                // Fehlgeschlagene Coins erneut versuchen (der Rest kommt aus dem Zwischenspeicher)
                launch { step { loadCloses(historyRequest(txs, historyRange.value)) } }
                // Kein Kurs (auch kein höchstens 24 h alter): keine Umrechnung statt eines toten Kurses
                launch { step { _fxRate.value = safe { fxSource.usdTo(code) } } }
                // Fehlten die Devisen-Tageskurse oder enden sie vor dem heutigen UTC-Tag, erneut laden
                launch {
                    step {
                        val utcToday = LocalDate.now(ZoneOffset.UTC)
                        if (_fxSeries.value?.let { it.rates == null || PortfolioHistoryFx.fxLoadOutdated(it.to, utcToday) } != false) {
                            loadFxSeries(code, fxSeriesFrom(txs, historyRange.value))
                        }
                    }
                }
                // Kurse für «Seit Kauf» in der Anzeigewährung (loadCostFx prüft selbst, ob die geladenen noch reichen)
                launch { step { summary.value?.let(PortfolioCostFx::firstDay)?.let { loadCostFx(code, LocalDate.ofEpochDay(it)) } } }
            }
        } finally {
            load.progressFlow.value = null
            load.manualFlow.value = false
            load.busyFlow.value = false
        }
    }
}

/** Gleichzeitige Schritte in [refresh] (für den Balken). */
private const val REFRESH_STEPS = 6

internal suspend fun PortfolioViewModel.loadPrices(coins: Set<String>, force: Boolean) {
    if (coins.isEmpty()) return
    // USDT/USD messen (höchstens alle 5 Minuten, parallel zu den Kursen): Live-Kurs der Bewertung
    viewModelScope.launch { safe { usdtPeg.checkIfDue() } }
    val startedAt = System.currentTimeMillis()
    val fetched = safe { priceSource.prices(coins, force) }
    // Dauer für die Kopfzeile des Portfolio-Widgets («Uhrzeit · Dauer», wie das Listen-Widget) — nur, wenn wirklich
    // abgefragt wurde (nicht aus dem Zwischenspeicher)
    if (fetched?.times?.values?.any { it >= startedAt } == true) {
        snapshotUpdater.notePriceFetch(System.currentTimeMillis() - startedAt)
    }
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
    viewModelScope.launch { _exchanges.value = safe { priceSource.spotExchangeNames() }.orEmpty() }
}

/** Abruf ohne Absturz: Fehler → null; protokolliert (Datenbank-Schlüssel nicht lesbar nur als Hinweis). */
internal suspend fun <T> PortfolioViewModel.safe(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    if (e.isDatabaseKeyUnavailable()) {
        Timber.d(e, "Portfolio: Datenbank-Schlüssel nicht verfügbar")
    } else {
        Timber.w(e, "Portfolio: Abruf fehlgeschlagen")
    }
    null
}
