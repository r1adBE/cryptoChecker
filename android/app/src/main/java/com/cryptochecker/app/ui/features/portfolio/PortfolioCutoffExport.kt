// Stichtag-Export und CH-Vermögenswert (Teil von `PortfolioExportViewModel`, wie `PortfolioCutoffExport.swift`): Bestand an
// einem Tagesende samt beobachteter Adressen, geprüfte Tagesschlüsse und CSV des Stichtags.
// Eigene Datei, weil diese Abrufe vom Ablauf der Exporte getrennt lesbar sein sollen; Erweiterungsfunktionen, der
// Zustand bleibt in der Klasse.
package com.cryptochecker.app.ui.features.portfolio

import com.cryptochecker.app.data.portfolio.HistoricFx
import com.cryptochecker.app.data.portfolio.HistoricPriceSource
import com.cryptochecker.app.domain.portfolio.AddressBalanceHistory
import com.cryptochecker.app.domain.portfolio.AddressCostLots
import com.cryptochecker.app.domain.portfolio.AddressBalancesAt
import com.cryptochecker.app.domain.portfolio.CostMethod
import com.cryptochecker.app.domain.portfolio.CutoffCloses
import com.cryptochecker.app.domain.portfolio.CutoffCsvFormat
import com.cryptochecker.app.domain.portfolio.CutoffCsvTexts
import com.cryptochecker.app.domain.portfolio.CutoffExport
import com.cryptochecker.app.domain.portfolio.PortfolioDecimal
import com.cryptochecker.app.domain.portfolio.PortfolioStables
import com.cryptochecker.app.domain.portfolio.PortfolioTrade
import com.cryptochecker.app.domain.portfolio.TaxMethod
import com.cryptochecker.app.settings.deviceRegionLocale
import com.cryptochecker.app.settings.inputDecimalSeparator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId

/**
 * CH: Tagesschlüsse des 31.12. ([end]) für den Bestand an dessen Ende (Zeitzone der Vorlage) samt beobachteter
 * Adressen — dieselben Quellen wie der Stichtag-Export ([build]: [HistoricPriceSource.dailyClosesUsdt] mit
 * Identitätsprüfung gegen die aktuellen Kurse). Erst wenn die UTC-Tageskerze des 31.12. geschlossen ist (UTC-Datum
 * danach), sonst null: kein laufender oder heutiger Kurs, der Posten bleibt «nicht gerechnet». Ebenso null, wenn die
 * beobachteten Adressen nicht gelesen werden können — sonst stünde still eine zu tiefe Summe da.
 */
internal suspend fun PortfolioExportViewModel.chYearEnd(
    trades: List<PortfolioTrade>,
    method: TaxMethod,
    end: LocalDate,
    zone: ZoneId,
): Pair<CutoffCloses, AddressBalancesAt>? {
    if (!end.isBefore(LocalDate.now(java.time.ZoneOffset.UTC))) return null
    val cutoff = CutoffExport.endOfDayMillis(end, zone)
    val holdings = CutoffExport.holdingsAt(trades, cutoff, method.costMethod ?: CostMethod.DEFAULT)
    val addressAt = try {
        AddressBalanceHistory.balancesAt(AddressBalanceHistory.of(repository.getWatchedAddresses()), cutoff)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "CH-Vermögenswert: Adressen nicht geladen, nicht gerechnet")
        return null
    }
    val coins = (holdings.map { it.coin } + addressAt.balances.keys).distinct().filter { PortfolioStables.needsQuote(it) }
    val current = try {
        if (coins.isEmpty()) emptyMap() else priceSource.prices(coins).let { p ->
            CutoffExport.referencePrices(p.prices, p.unchecked)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "CH-Vermögenswert: aktuelle Kurse fehlen, alle Kurse ungeprüft")
        emptyMap()
    }
    // Fehler beim Laden: keine Kurse → jeder Coin «nicht gerechnet», die Summe unvollständig
    val closes = try {
        historic.dailyClosesUsdt(coins, end, current)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "CH-Vermögenswert: Tagesschlüsse fehlen")
        CutoffCloses()
    }
    return closes to addressAt
}

/**
 * Stichtag-CSV; [method] = Einstandsmethode des Bestands: null = die der App (allgemeiner Stichtag-Export), beim
 * Bestand der Arbeitsunterlage die der Vorlage ([TaxMethod.holdingsCostMethod]) — wie im Kopf genannt.
 */
internal suspend fun PortfolioExportViewModel.build(
    date: LocalDate,
    currency: String,
    texts: CutoffCsvTexts,
    header: List<String>,
    zone: ZoneId,
    method: CostMethod? = null,
): String {
    val trades = repository.getTransactions().map { it.toTrade() }
    val cutoff = CutoffExport.endOfDayMillis(date, zone)
    // Bestand nach derselben Einstandsmethode wie Karte, Verlauf und Steuer-Export (FIFO je Wallet je Wallet gekappt)
    val holdings = CutoffExport.holdingsAt(trades, cutoff, method ?: settingsRepository.current().costMethod)
    // Beobachtete Adressen, die im Portfolio zählen: heute das aktuelle, früher das an dem Tag beobachtete Guthaben
    // (eigene Zeilen «aus Adresse – ohne Kaufpreis»). Nicht lesbar: ohne Adressen, aber in der Datei vermerkt und
    // das Total «unvollständig» — nie still eine zu tiefe Summe (wie iOS `intact`)
    var addressesUnreadable = false
    // Zählende Adresse mit Einstand am Stichtag: Hinweiszeile (Umbuchungen zur/von der Adresse evtl. doppelt/fehlend)
    var addressLots = false
    val addressAt = try {
        val watched = repository.getWatchedAddresses()
        addressLots = AddressCostLots.hasCountedLots(watched, cutoff)
        AddressBalanceHistory.balancesAt(AddressBalanceHistory.of(watched), cutoff)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "Stichtag: Adressen nicht geladen, Export als unvollständig vermerkt")
        addressesUnreadable = true
        AddressBalancesAt(emptyMap(), emptySet())
    }
    // USDT = 1 ohne Abfrage; andere Stablecoins wie jeder Coin (fehlt der Kurs: «ohne Kurs»)
    val coins = (holdings.map { it.coin } + addressAt.balances.keys).distinct().filter { PortfolioStables.needsQuote(it) }
    // Aktuelle Kurse als Bezug der Identitätsprüfung (jede Quelle); ohne passenden Bezug wird kein
    // Tagesschluss übernommen («ungeprüft») — auch nicht, wenn das Laden ganz scheitert
    val current = try {
        if (coins.isEmpty()) emptyMap() else priceSource.prices(coins).let { p ->
            CutoffExport.referencePrices(p.prices, p.unchecked)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "Stichtag: aktuelle Kurse fehlen, alle Kurse ungeprüft")
        emptyMap()
    }
    val closes = historic.dailyClosesUsdt(coins, date, current)
    val empty = holdings.isEmpty() && addressAt.balances.isEmpty()
    val fx: HistoricFx? = if (empty && currency != "USD") null else historic.usdTo(currency, date)
    // Devisenkurs exakt über die kürzeste Darstellung (= Text der Quelle, z. B. «0.79123»); USDT zum Marktkurs:
    // × USDT/USD des Stichtags (heute live, sonst Tagesschluss) — ohne Kurs 1, in der Datei vermerkt
    val usdt = cutoffUsdtUsd(currency, date)
    val rate = PortfolioDecimal.of(fx?.rate)?.let { r -> usdt?.let { r.multiply(it, PortfolioDecimal.MC) } ?: r }
    val rows = CutoffExport.rows(holdings, closes.prices, rate, closes.unchecked) +
        CutoffExport.addressRows(addressAt, closes.prices, rate, closes.unchecked)
    // Trenn-/Dezimalzeichen wie Excel der Region (de-CH «;» + «.», de-DE «;» + «,», en «,» + «.»)
    val format = CutoffCsvFormat.forLocale(deviceRegionLocale().country, inputDecimalSeparator())
    val usdtApproximate = rate != null && usdt == null && CutoffExport.convertsUsdt(currency)
    return CutoffExport.csv(date, currency, fx?.date, rows, texts, format, header, usdtApproximate, addressesUnreadable, addressLots)
}
