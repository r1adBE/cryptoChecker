// Kurse für den Steuer-Export (Teil von `PortfolioExportViewModel`): Devisen-Tageskurse, Tageskurse für
// Gebühr-Zeilen und Tausch ohne Wert, Netzwerkgebühren als Veräusserung, FR-Gesamtwert, Tagesschlusskurse.
// Eigene Datei, weil die Kursbeschaffung je Vorlage unterschiedlich ist und vom Bauen der Dateien getrennt
// lesbar sein soll; Erweiterungsfunktionen auf dem ViewModel.
package com.cryptochecker.app.ui.features.portfolio

import com.cryptochecker.app.data.portfolio.HistoricPriceSource
import com.cryptochecker.app.domain.portfolio.AddressBalance
import com.cryptochecker.app.domain.portfolio.CutoffExport
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioDecimal
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryFx
import com.cryptochecker.app.domain.portfolio.PortfolioLedger
import com.cryptochecker.app.domain.portfolio.PortfolioStables
import com.cryptochecker.app.domain.portfolio.PortfolioTrade
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.TaxDayClose
import com.cryptochecker.app.domain.portfolio.TaxEngines
import com.cryptochecker.app.domain.portfolio.TaxMethod
import com.cryptochecker.app.domain.portfolio.TaxPeriod
import com.cryptochecker.app.domain.portfolio.TaxTemplate
import com.cryptochecker.app.domain.portfolio.frHeldBefore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

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
 * USDT/USD-Tagesschlüsse ab [from] für den Steuer-Export ([TaxExport.rateOn] `usdtUsd`, USDT zum Marktkurs): Tag →
 * Schluss (Kraken, höchstens 720 Tage; Lücken füllt Coinbase, etwa 600 Tage). Ältere Steuerjahre werden NICHT seitenweise
 * nachgeladen: Krakens OHLC liefert nur die jüngsten 720 Kerzen, der Coinbase-Adapter höchstens zwei Seiten — mehr hiesse
 * den Börsen-Adapter umbauen und je Export viele Abfragen. Tage ohne Schluss rechnen darum mit 1 und sind nie still:
 * Vermerk je Zeile ([TaxGainRow.usdtApproximate], [TaxIncomeRow.usdtApproximate]) und eine Zeile in der Jahresübersicht
 * ([TaxYearSummary.usdtApproximate]). null für eine Währung ohne USDT-Umrechnung (USDT selbst).
 */
internal suspend fun PortfolioExportViewModel.usdtUsdCloses(currency: String, from: LocalDate): Map<Long, BigDecimal>? {
    if (!CutoffExport.convertsUsdt(currency)) return null
    val today = LocalDate.now(ZoneId.systemDefault())
    val days = (java.time.temporal.ChronoUnit.DAYS.between(from, today) + 1 + PortfolioHistoryFx.LEAD_DAYS)
        .coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    val closes = try {
        historySource.usdtUsdDaily(days)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "Arbeitsunterlage: USDT/USD-Tagesschlüsse fehlen")
        emptyMap()
    }
    return closes.mapNotNull { (day, close) -> PortfolioDecimal.of(close)?.let { day to it } }.toMap()
}

/**
 * USDT/USD am Stichtag [date] für den Stichtag-Export: heute der Live-Kurs, sonst der Tagesschluss DES Tags (plausibel,
 * [TaxExport.usdtUsdOn]); null = keiner (dann 1, in der Datei vermerkt) oder keine USDT-Umrechnung ([CutoffExport.convertsUsdt]).
 */
internal suspend fun PortfolioExportViewModel.cutoffUsdtUsd(currency: String, date: LocalDate): BigDecimal? {
    if (!CutoffExport.convertsUsdt(currency)) return null
    val today = LocalDate.now(ZoneId.systemDefault())
    if (!date.isBefore(today)) return depegReadings.liveUsdtUsd()?.let { PortfolioDecimal.of(it) }
    return com.cryptochecker.app.domain.portfolio.TaxExport.usdtUsdOn(usdtUsdCloses(currency, date), date)
}

/**
 * Tageskurse der Vorlage: FR [frPrices], Netzwerkgebühren als Veräusserung ([TaxTemplate.networkFeeIsDisposal]: UK,
 * US, CA, AU, JP) [networkFeePrices]; dazu für alle Vorlagen die Gebühr-Zeilen, Verschenkt/bezahlt ohne Marktwert und
 * Tausch ohne Wert zum Tageskurs ([feeDisposalPrices]). [until]: letzter Tag mit Bedarf ([TaxExport.priceUntil]; UK/CA
 * 30 Tage nach dem Steuerjahr — ein Zugang dort kann eine Veräusserung des Jahres decken).
 */
internal suspend fun PortfolioExportViewModel.taxPrices(
    tpl: TaxTemplate,
    method: TaxMethod,
    trades: List<PortfolioTrade>,
    period: TaxPeriod,
    zone: ZoneId = tpl.zone(),
    until: LocalDate = period.end,
): ((String, Long) -> BigDecimal?)? {
    val base = when {
        method == TaxMethod.FR_PTA -> frPrices(trades, period, zone, until)
        tpl.networkFeeIsDisposal -> networkFeePrices(trades, period, zone, until)
        else -> null
    }
    val fees = feeDisposalPrices(trades, period, zone, until) ?: return base
    return { coin, time -> fees(coin, time) ?: base?.invoke(coin, time) }
}

/**
 * Gebühr-Zeilen ([PortfolioLedger.isFeeDisposal], CSV «Fee»), Verschenkt/bezahlt ohne Marktwert, Tausch ohne Wert,
 * Belohnungen ohne Wert und Gebühren im dritten Coin ohne Preis ([PortfolioLedger.dayCloseNeeds]) bis Ende [until] (Standard: Ende [period]): zum Tageskurs — der Coin zum Tagesschluss SEINES UTC-Tags
 * ([TaxDayClose], gleicher Kurs wie Karte und Wertverlauf); nur für genau diese (Coin, Zeit), sonst null. Ohne solche
 * Transaktionen null.
 */
internal suspend fun PortfolioExportViewModel.feeDisposalPrices(
    trades: List<PortfolioTrade>,
    period: TaxPeriod,
    zone: ZoneId = ZoneId.systemDefault(),
    until: LocalDate = period.end,
): ((String, Long) -> BigDecimal?)? {
    val end = CutoffExport.endOfDayMillis(maxOf(until, period.end), zone)
    val keys = PortfolioLedger.dayCloseNeeds(trades.filter { it.time <= end && !AddressBalance.isAddressTrade(it.id) })
    if (keys.isEmpty()) return null
    val wanted = keys.filter { PortfolioStables.needsQuote(it.first) }.map { it.first to TaxDayClose.day(it.second) }.distinct()
    val closes = dailyCloses(wanted)
    return { coin, time ->
        when {
            (coin to time) !in keys -> null
            !PortfolioStables.needsQuote(coin) -> BigDecimal.ONE
            else -> closes(coin, time)
        }
    }
}

/**
 * Marktwert der Netzwerkgebühren in Krypto (eigene Veräusserung, [TaxEngines.applyRules]) bis Ende [period]: Gebühr
 * im Coin bzw. im dritten Coin ohne erfassten Gebühr-Preis einer Umbuchung, Auszahlung oder Schenkung
 * ([TaxDayClose.networkFeeNeeds]) — der Coin zum Tagesschluss seines UTC-Tags wie jede Gebühr-Zeile derselben Unterlage
 * ([TaxDayClose]). Fehlt ein Kurs, bleibt der Erlös dieser Zeile leer. Bis Ende [until] wie [feeDisposalPrices].
 */
internal suspend fun PortfolioExportViewModel.networkFeePrices(
    trades: List<PortfolioTrade>,
    period: TaxPeriod,
    zone: ZoneId = ZoneId.systemDefault(),
    until: LocalDate = period.end,
): (String, Long) -> BigDecimal? {
    return dailyCloses(TaxDayClose.networkFeeNeeds(trades, CutoffExport.endOfDayMillis(maxOf(until, period.end), zone)))
}

/**
 * FR: Kurse für den Gesamtwert des Portfolios vor jeder Abtretung bis Ende [period] — je Verkauf, Auszahlung und
 * Gebühr in Krypto (Umbuchung/Tausch mit Gebühr im Coin, Gebühr im dritten Coin) die gehaltenen Coins (ohne den
 * verkauften und ohne Stablecoins) zum Tagesschluss des UTC-Tags der Abtretung ([TaxDayClose], dieselbe Regel wie die
 * Gebühr-Zeilen). Gehalten = in
 * der Reihenfolge der Rechnung davor gebucht ([TaxEngines.frHeldBefore], auch dieselbe Millisekunde). Fehlt ein
 * Kurs, bleibt der Einstand dieser Abtretung leer («unvollständig»).
 */
internal suspend fun PortfolioExportViewModel.frPrices(
    trades: List<PortfolioTrade>,
    period: TaxPeriod,
    zone: ZoneId = ZoneId.systemDefault(),
    until: LocalDate = period.end,
): (String, Long) -> BigDecimal? {
    val end = CutoffExport.endOfDayMillis(maxOf(until, period.end), zone)
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
        val day = TaxDayClose.day(t.time)
        // Verkauf: Coin zum Verkaufspreis; sonst ohne erfassten Wert: auch der Coin selbst braucht einen Kurs
        val sold = PortfolioCalculator.normalizeCoin(t.coin).takeIf { t.type.isSaleLike || t.validPrice != null }
        val held = TaxEngines.frHeldBefore(own, t) +
            addressTrades.filter { it.time <= t.time }.map { PortfolioCalculator.normalizeCoin(it.coin) }
        held.filter { it != sold && PortfolioStables.needsQuote(it) }
            .forEach { wanted[it to day] = Unit }
    }
    return dailyCloses(wanted.keys)
}

/**
 * Tagesschlusskurse (USDT) je (Coin, Tag); Abfrage über (Coin, Zeit) → Schluss des UTC-Tags der Zeit ([TaxDayClose.lookup]);
 * fehlt = null.
 */
internal suspend fun PortfolioExportViewModel.dailyCloses(
    wanted: Collection<Pair<String, LocalDate>>,
): (String, Long) -> BigDecimal? {
    if (wanted.isEmpty()) return { _, _ -> null }
    val coins = wanted.map { it.first }.distinct()
    val current = try {
        priceSource.prices(coins).let { p -> CutoffExport.referencePrices(p.prices, p.unchecked, p.unverified) }
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
    return TaxDayClose.lookup(closes)
}
