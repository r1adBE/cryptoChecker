// Kurse für den Steuer-Export (Teil von `PortfolioExportViewModel`): Devisen-Tageskurse, Tageskurse für
// Gebühr-Zeilen und Tausch ohne Wert, Netzwerkgebühren als Veräusserung, FR-Gesamtwert, Tagesschlusskurse.
// Eigene Datei, weil die Kursbeschaffung je Vorlage unterschiedlich ist und vom Bauen der Dateien getrennt
// lesbar sein soll; Erweiterungsfunktionen auf dem ViewModel.
package com.cryptochecker.app.ui.features.portfolio

import com.cryptochecker.app.data.portfolio.HistoricPriceSource
import com.cryptochecker.app.domain.portfolio.AddressBalance
import com.cryptochecker.app.domain.portfolio.CostMethod
import com.cryptochecker.app.domain.portfolio.CutoffExport
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioDecimal
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryFx
import com.cryptochecker.app.domain.portfolio.PortfolioLedger
import com.cryptochecker.app.domain.portfolio.PortfolioStables
import com.cryptochecker.app.domain.portfolio.PortfolioTrade
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.TaxMethod
import com.cryptochecker.app.domain.portfolio.TaxPeriod
import com.cryptochecker.app.domain.portfolio.TaxTemplate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Devisen-Tageskurse USD → [currency] von [from] bis [to] (höchstens heute); USD = leer; Fehler = null. */
internal suspend fun PortfolioExportViewModel.rates(currency: String, from: LocalDate, to: LocalDate): Map<Long, BigDecimal>? {
    if (currency == "USD") return emptyMap()
    val today = LocalDate.now(ZoneId.systemDefault())
    return try {
        historic.usdToSeries(currency, from.minusDays(PortfolioHistoryFx.LEAD_DAYS.toLong()), if (to.isAfter(today)) today else to)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "Arbeitsunterlage: Devisenkurse fehlen")
        null
    }?.mapNotNull { (day, rate) -> PortfolioDecimal.of(rate)?.let { day to it } }?.toMap()
}

/**
 * Tageskurse der Vorlage: FR [frPrices], Netzwerkgebühren als Veräusserung ([TaxTemplate.networkFeeIsDisposal]: UK,
 * US, CA, AU, JP) [networkFeePrices]; dazu für alle Vorlagen die Gebühr-Zeilen, Verschenkt/bezahlt ohne Marktwert und
 * Tausch ohne Wert zum Tageskurs ([feeDisposalPrices]).
 */
internal suspend fun PortfolioExportViewModel.taxPrices(
    tpl: TaxTemplate,
    method: TaxMethod,
    trades: List<PortfolioTrade>,
    period: TaxPeriod,
): ((String, Long) -> BigDecimal?)? {
    val base = when {
        method == TaxMethod.FR_PTA -> frPrices(trades, period)
        tpl.networkFeeIsDisposal -> networkFeePrices(trades, period)
        else -> null
    }
    val fees = feeDisposalPrices(trades, period) ?: return base
    return { coin, time -> fees(coin, time) ?: base?.invoke(coin, time) }
}

/**
 * Gebühr-Zeilen ([PortfolioLedger.isFeeDisposal], CSV «Fee»), Verschenkt/bezahlt ohne Marktwert und Tausch ohne Wert
 * ([PortfolioLedger.dayCloseNeeds]) bis Ende [period]: zum Tageskurs — der Coin zum Tagesschluss SEINES UTC-Tags
 * (gleicher Kurs wie Karte und Wertverlauf, [PortfolioPeriodChange.marketPriceAt]); nur für genau diese (Coin, Zeit),
 * sonst null. Ohne solche Transaktionen null.
 */
internal suspend fun PortfolioExportViewModel.feeDisposalPrices(trades: List<PortfolioTrade>, period: TaxPeriod): ((String, Long) -> BigDecimal?)? {
    val end = CutoffExport.endOfDayMillis(period.end, ZoneId.systemDefault())
    val keys = PortfolioLedger.dayCloseNeeds(trades.filter { it.time <= end && !AddressBalance.isAddressTrade(it.id) })
    if (keys.isEmpty()) return null
    val wanted = keys.filter { PortfolioStables.needsQuote(it.first) }.map { it.first to utcDay(it.second) }.distinct()
    val closes = dailyCloses(wanted, ::utcDay)
    return { coin, time ->
        when {
            (coin to time) !in keys -> null
            !PortfolioStables.needsQuote(coin) -> BigDecimal.ONE
            else -> closes(coin, time)
        }
    }
}

/** UTC-Tag von [time] (Tagesschluss dieses Tags). */
internal fun PortfolioExportViewModel.utcDay(time: Long): LocalDate = Instant.ofEpochMilli(time).atZone(ZoneOffset.UTC).toLocalDate()

/** Tag (UTC) des Schlusskurses für [time]: der Vortag (letzter bekannter Schluss davor). */
internal fun PortfolioExportViewModel.closeDay(time: Long): LocalDate = Instant.ofEpochMilli(time).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)

/**
 * Marktwert der Netzwerkgebühren in Krypto (eigene Veräusserung, [TaxEngines.applyRules]) bis Ende [period]: Gebühr
 * im Coin bzw. im dritten Coin ohne erfassten Gebühr-Preis einer Umbuchung, Auszahlung oder Schenkung — der Coin zum
 * Tagesschluss des Vortags (UTC). Fehlt ein Kurs, bleibt der Erlös dieser Zeile leer.
 */
internal suspend fun PortfolioExportViewModel.networkFeePrices(trades: List<PortfolioTrade>, period: TaxPeriod): (String, Long) -> BigDecimal? {
    val end = CutoffExport.endOfDayMillis(period.end, ZoneId.systemDefault())
    val wanted = LinkedHashMap<Pair<String, LocalDate>, Unit>()
    for (t in trades) {
        val moves = t.type == PortfolioTxType.TRANSFER || t.type.isWithdrawalLike
        if (!moves || t.time > end || AddressBalance.isAddressTrade(t.id)) continue
        val coin = PortfolioCalculator.normalizeCoin(t.coin)
        if (t.coinFee.signum() > 0 && t.validPrice == null && PortfolioStables.needsQuote(coin)) wanted[coin to closeDay(t.time)] = Unit
        val third = t.thirdFeeCoin
        if (third != null && t.thirdFeePrice == null && PortfolioStables.needsQuote(third)) wanted[third to closeDay(t.time)] = Unit
    }
    return dailyCloses(wanted.keys)
}

/**
 * FR: Kurse für den Gesamtwert des Portfolios vor jeder Abtretung bis Ende [period] — je Verkauf, Auszahlung und
 * Gebühr in Krypto (Umbuchung/Tausch mit Gebühr im Coin, Gebühr im dritten Coin) die gehaltenen Coins (ohne den
 * verkauften und ohne Stablecoins) zum Tagesschluss des Vortags (UTC; letzter bekannter Schluss davor). Fehlt ein
 * Kurs, bleibt der Einstand dieser Abtretung leer («unvollständig»).
 */
internal suspend fun PortfolioExportViewModel.frPrices(trades: List<PortfolioTrade>, period: TaxPeriod): (String, Long) -> BigDecimal? {
    val zone = ZoneId.systemDefault()
    val end = CutoffExport.endOfDayMillis(period.end, zone)
    // Verkäufe, Auszahlungen (Abgang ohne Verkauf kürzt die PTA anteilig, [TaxEngines.outflows]) und Gebühren in Krypto
    val own = trades.filter { !AddressBalance.isAddressTrade(it.id) }
    val sells = own.filter {
        it.time <= end && (
            it.type.isSaleLike || it.type.isWithdrawalLike ||
                it.thirdFeeCoin != null ||
                ((it.type == PortfolioTxType.TRANSFER || it.type == PortfolioTxType.SWAP) && it.coinFee.signum() > 0)
            )
    }
    // Beobachtete Adressen zählen im Gesamtwert mit ([TaxEngines] FR): ihre Coins brauchen ebenfalls Kurse
    val addressTrades = trades.filter { AddressBalance.isAddressTrade(it.id) }
    val wanted = LinkedHashMap<Pair<String, LocalDate>, Unit>()
    for (t in sells) {
        val day = closeDay(t.time)
        // Verkauf: Coin zum Verkaufspreis; sonst ohne erfassten Wert: auch der Coin selbst braucht einen Kurs
        val sold = PortfolioCalculator.normalizeCoin(t.coin).takeIf { t.type.isSaleLike || t.validPrice != null }
        val held = PortfolioCalculator.holdingsAt(own, t.time - 1, CostMethod.FIFO).keys +
            addressTrades.filter { it.time <= t.time }.map { PortfolioCalculator.normalizeCoin(it.coin) }
        held.filter { it != sold && PortfolioStables.needsQuote(it) }
            .forEach { wanted[it to day] = Unit }
    }
    return dailyCloses(wanted.keys)
}

/**
 * Tagesschlusskurse (USDT) je (Coin, Tag); Abfrage über (Coin, Zeit) → Schluss des Tags [dayOf] (Standard: Vortag);
 * fehlt = null.
 */
internal suspend fun PortfolioExportViewModel.dailyCloses(
    wanted: Collection<Pair<String, LocalDate>>,
    dayOf: (Long) -> LocalDate = ::closeDay,
): (String, Long) -> BigDecimal? {
    if (wanted.isEmpty()) return { _, _ -> null }
    val coins = wanted.map { it.first }.distinct()
    val current = try {
        priceSource.prices(coins).let { p -> CutoffExport.referencePrices(p.prices, p.unchecked) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "Steuer-Vorlage: aktuelle Kurse fehlen")
        emptyMap()
    }
    val closes = HashMap<Pair<String, LocalDate>, BigDecimal>()
    for ((coin, day) in wanted) {
        val r = try {
            historic.dailyCloseUsdt(coin, day, current[coin])
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            HistoricPriceSource.CloseResult(null)
        }
        PortfolioDecimal.of(r.price)?.let { closes[coin to day] = it }
    }
    return { coin, time -> closes[coin to dayOf(time)] }
}
