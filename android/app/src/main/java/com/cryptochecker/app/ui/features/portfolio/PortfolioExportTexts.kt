// Texte der Export-Dateien und der Steuer-Vorlagen: Spaltenköpfe und Notizen der CSV (Steuer- und
// Stichtag-Export), Namen von Vorlage, Methode und Zeilen, Hinweise und Quellen der Vorlage.
// Eigene Datei, weil es reine Textzuordnungen sind – `PortfolioExportDialog` und `TaxWorksheetScreen` bleiben so kurz
// (wie `PortfolioExportTexts.swift`).
package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.CutoffCsvTexts
import com.cryptochecker.app.domain.portfolio.EstvKursliste
import com.cryptochecker.app.domain.portfolio.TAX_NOTE_DATE
import com.cryptochecker.app.domain.portfolio.TAX_WEALTH_AMOUNT
import com.cryptochecker.app.domain.portfolio.TAX_WEALTH_COIN
import com.cryptochecker.app.domain.portfolio.TaxCsvTexts
import com.cryptochecker.app.domain.portfolio.TaxLine
import com.cryptochecker.app.domain.portfolio.TaxMethod
import com.cryptochecker.app.domain.portfolio.TaxSummaryNote
import com.cryptochecker.app.domain.portfolio.TaxSummaryTexts
import com.cryptochecker.app.domain.portfolio.TaxTemplate
import java.time.ZoneId

/**
 * Texte der CSV «Realisierte Veräusserungen» und «Einkünfte» ([TaxCsvTexts]); Währungsspalten mit [currency]. [template]
 * AT: Einkünfte getrennt nach Zins und Staking (Vermerke, zwei Summen); sonst eine Summe.
 */
@Composable
internal fun taxCsvTexts(currency: String, zone: ZoneId = ZoneId.systemDefault(), template: TaxTemplate = TaxTemplate.NEUTRAL): TaxCsvTexts = TaxCsvTexts(
    coin = stringResource(R.string.portfolio_export_col_coin),
    acquired = stringResource(R.string.tax_export_col_acquired),
    sold = stringResource(R.string.tax_export_col_sold),
    holdingDays = stringResource(R.string.tax_export_col_days),
    longTerm = stringResource(R.string.tax_export_col_long_term),
    amount = stringResource(R.string.portfolio_export_col_amount),
    costUsdt = stringResource(R.string.tax_export_col_cost, "USDT"),
    proceedsUsdt = stringResource(R.string.tax_export_col_proceeds, "USDT"),
    gainUsdt = stringResource(R.string.tax_export_col_gain, "USDT"),
    costTarget = stringResource(R.string.tax_export_col_cost, currency),
    proceedsTarget = stringResource(R.string.tax_export_col_proceeds, currency),
    gainTarget = stringResource(R.string.tax_export_col_gain, currency),
    date = stringResource(R.string.tax_export_col_date),
    valueUsdt = stringResource(R.string.portfolio_export_col_value),
    valueTarget = stringResource(R.string.portfolio_export_col_value_cur, currency),
    note = stringResource(R.string.portfolio_export_col_note),
    total = stringResource(R.string.portfolio_export_total),
    yes = stringResource(R.string.tax_export_yes),
    no = stringResource(R.string.tax_export_no),
    costMissing = stringResource(R.string.tax_export_cost_missing),
    proceedsMissing = stringResource(R.string.tax_export_proceeds_missing),
    feeLeg = stringResource(R.string.tax_export_fee_leg),
    feeValueMissing = stringResource(R.string.tax_export_fee_value_missing),
    avgCostRates = stringResource(R.string.tax_export_avg_cost_rates),
    valueMissing = stringResource(R.string.tax_export_value_missing),
    noFx = stringResource(R.string.portfolio_export_no_fx),
    incomplete = stringResource(R.string.portfolio_export_incomplete),
    oversold = stringResource(R.string.tax_export_oversold),
    proceedsWithoutCost = stringResource(R.string.tax_export_proceeds_without_cost),
    // Jahr und Daten in der Zeitzone der Vorlage (Neutral: Gerät) — in der CSV genannt («Daten in Ortszeit (Zone)»)
    timeZone = stringResource(R.string.tax_export_year_local_zone, zone.id),
    // Tausch und FIFO je Wallet (Platzhalter «%s» setzt der Export mit dem Namen der Wallet)
    swap = stringResource(R.string.tax_export_swap),
    wallet = stringResource(R.string.tax_export_wallet, "%s"),
    walletUnspecified = stringResource(R.string.tax_export_wallet_unspecified),
    lostFeeCost = stringResource(R.string.tax_export_lost_fee_cost),
    // Vorlagen: Zuordnung (UK), nicht abziehbarer Verlust (CA), Alt-/Neubestand (AT), Gesamtwert (FR)
    matchSameDay = stringResource(R.string.tax_export_match_same_day),
    matchThirtyDay = stringResource(R.string.tax_export_match_thirty_day),
    matchPool = stringResource(R.string.tax_export_match_pool),
    adjustmentUsdt = stringResource(R.string.tax_export_col_adjustment, "USDT"),
    adjustmentTarget = stringResource(R.string.tax_export_col_adjustment, currency),
    adjustment = stringResource(R.string.tax_export_adjustment),
    oldHolding = stringResource(R.string.tax_export_old_holding),
    newHolding = stringResource(R.string.tax_export_new_holding),
    costIncomplete = stringResource(R.string.tax_export_cost_incomplete),
    portfolioValueUsdt = stringResource(R.string.tax_export_col_portfolio_value, "USDT"),
    portfolioValueTarget = stringResource(R.string.tax_export_col_portfolio_value, currency),
    grossUsdt = stringResource(R.string.tax_export_col_gross, "USDT"),
    grossTarget = stringResource(R.string.tax_export_col_gross, currency),
    // Gebühr als Aufwand (PortfolioFeeExpenses)
    feeConsumed = stringResource(R.string.tax_export_fee_consumed),
    withdrawalFee = stringResource(R.string.tax_export_withdrawal_fee),
    // CSV-Zeile «Fee»: Veräusserung ohne Erlös
    feeRow = stringResource(R.string.tax_export_fee_row),
    // Verschenkt/bezahlt und geschätzter Wert (Tagesschluss)
    gift = stringResource(R.string.tax_export_gift),
    payment = stringResource(R.string.tax_export_payment),
    valueEstimated = stringResource(R.string.tax_export_value_estimated),
    // Schenkung an Ehe-/Lebenspartner (UK s. 58, CA 73(1))
    spouseGift = stringResource(R.string.tax_export_spouse_gift),
    // Auszahlungsgebühr USDT/Fiat nur Information (UK, US, CA, AU, JP: TaxTemplate.quoteFeeIsInfo)
    withdrawalFeeInfo = stringResource(R.string.tax_export_withdrawal_fee_info),
    // Umbuchungsgebühr USDT/Fiat nur Information (nicht im Einstand)
    transferFeeInfo = stringResource(R.string.tax_export_transfer_fee_info),
    // Umbuchungsgebühr auf Bestand ohne Kaufpreis (Info-Zeile, kein Gewinn/Verlust)
    unassignedFee = stringResource(R.string.tax_export_unassigned_fee),
    // USDT zum Marktkurs: Tag ohne USDT/USD-Tagesschluss rechnet mit 1 — je Zeile vermerkt
    usdtAssumed = stringResource(R.string.portfolio_usdt_assumed),
    // AT: Zinsen beim Zufluss steuerpflichtig, Staking (Neubestand) nur Information — getrennt wie die Jahresübersicht
    incomeInterest = if (template == TaxTemplate.AT) stringResource(R.string.tax_export_at_income_interest) else "",
    incomeStaking = if (template == TaxTemplate.AT) stringResource(R.string.tax_export_at_income_staking) else "",
    totalInterest = if (template == TaxTemplate.AT) stringResource(R.string.tax_export_at_total_interest) else "",
    totalStaking = if (template == TaxTemplate.AT) stringResource(R.string.tax_export_at_total_staking) else "",
    // AT: Zufluss vor dem 1.3.2022 (vor § 27b EStG) — eigener Vermerk statt Zins/Staking
    incomeBeforeNewLaw = if (template == TaxTemplate.AT) stringResource(R.string.tax_export_at_income_before_new_law) else "",
    // DE/AT: Auszahlungsgebühren als eigene Summenzeile, nicht in der Summe der Gewinne — wie der Posten der Jahresübersicht
    withdrawalFeesTotal = if (template.withdrawalFeeApart) stringResource(R.string.tax_line_withdrawal_fees) else "",
)

/** Texte des Stichtag-Exports ([CutoffCsvTexts]) in [currency]. */
@Composable
internal fun cutoffTexts(currency: String): CutoffCsvTexts = CutoffCsvTexts(
    coin = stringResource(R.string.portfolio_export_col_coin),
    amount = stringResource(R.string.portfolio_export_col_amount),
    priceUsdt = stringResource(R.string.portfolio_export_col_price),
    valueUsdt = stringResource(R.string.portfolio_export_col_value),
    rate = stringResource(R.string.portfolio_export_col_rate, currency),
    valueTarget = stringResource(R.string.portfolio_export_col_value_cur, currency),
    note = stringResource(R.string.portfolio_export_col_note),
    total = stringResource(R.string.portfolio_export_total),
    noPrice = stringResource(R.string.portfolio_export_no_price),
    noFx = stringResource(R.string.portfolio_export_no_fx),
    unchecked = stringResource(R.string.portfolio_export_unchecked),
    incomplete = stringResource(R.string.portfolio_export_incomplete),
    // Ohne Argumente bleibt %1$s … stehen und wird erst beim Bauen ersetzt
    commentFormat = stringResource(R.string.portfolio_export_comment),
    estvNoteFormat = stringResource(R.string.portfolio_export_estv_note),
    estvLanguage = LocalConfiguration.current.locales[0].language,
    fromAddress = stringResource(R.string.portfolio_export_from_address),
    addressUnobserved = stringResource(R.string.portfolio_export_address_unobserved),
    usdtAssumed = stringResource(R.string.portfolio_usdt_assumed),
    addressesUnreadable = stringResource(R.string.portfolio_export_addresses_unreadable),
    addressLots = stringResource(R.string.portfolio_export_address_lots_note),
)

/** Name einer Vorlage. */
internal fun taxTemplateLabel(t: TaxTemplate): Int = when (t) {
    TaxTemplate.NEUTRAL -> R.string.tax_template_neutral
    TaxTemplate.DE -> R.string.tax_template_de
    TaxTemplate.AT -> R.string.tax_template_at
    TaxTemplate.CH -> R.string.tax_template_ch
    TaxTemplate.UK -> R.string.tax_template_uk
    TaxTemplate.US -> R.string.tax_template_us
    TaxTemplate.CA -> R.string.tax_template_ca
    TaxTemplate.AU -> R.string.tax_template_au
    TaxTemplate.FR -> R.string.tax_template_fr
    TaxTemplate.JP -> R.string.tax_template_jp
}

/** Name einer Methode der Arbeitsunterlage. */
@Composable
internal fun taxMethodName(m: TaxMethod): String = when (m) {
    TaxMethod.FIFO -> "FIFO"
    TaxMethod.FIFO_WALLET -> stringResource(R.string.settings_cost_method_fifo_wallet)
    TaxMethod.AVERAGE -> stringResource(R.string.settings_cost_method_average)
    TaxMethod.UK_POOL -> stringResource(R.string.tax_method_uk_pool)
    TaxMethod.CA_ACB -> stringResource(R.string.tax_method_ca_acb)
    TaxMethod.FR_PTA -> stringResource(R.string.tax_method_fr_pta)
    TaxMethod.JP_TOTAL_AVERAGE -> stringResource(R.string.tax_method_jp_total)
    TaxMethod.AT_MOVING_AVERAGE -> stringResource(R.string.tax_method_at)
}

/**
 * Hinweise einer Vorlage im Dialog (was die App nicht kann bzw. wie sie zählt). Netzwerkgebühr und Schenkung an Partner
 * nennen die Quelle der Vorlage ([TaxTemplate.networkFeeIsDisposal], [TaxTemplate.spouseGiftNeutral]); Quellen sind
 * Eigennamen und bleiben unübersetzt.
 */
@Composable
internal fun taxTemplateNotes(t: TaxTemplate): List<String> {
    val notes = when (t) {
        TaxTemplate.US -> listOf(R.string.tax_note_specific_id, R.string.tax_note_us_wallet, R.string.tax_note_us_transition)
        TaxTemplate.AU -> listOf(R.string.tax_note_specific_id)
        // FR/AT: Verkauf gegen einen Stablecoin, den man behält, ist Krypto→Krypto — als Tausch erfassen;
        // AT: Staking/Airdrop mit Anschaffungskosten 0 (§ 27b Abs. 2 EStG), der Zuflusswert zählt nicht
        TaxTemplate.FR -> listOf(R.string.tax_note_fr, R.string.tax_note_stable_swap)
        TaxTemplate.AT -> listOf(R.string.tax_note_at, R.string.tax_note_stable_swap, R.string.tax_note_at_staking)
        TaxTemplate.UK -> listOf(R.string.tax_note_uk)
        TaxTemplate.NEUTRAL, TaxTemplate.DE, TaxTemplate.CH, TaxTemplate.CA, TaxTemplate.JP -> emptyList()
    }.map { stringResource(it) }
    val networkFee = taxNetworkFeeSource(t)?.let { stringResource(R.string.tax_note_network_fee, it) }
    val spouse = taxSpouseGiftSource(t)?.let { stringResource(R.string.tax_note_spouse_gift, it) }
    val withdrawalFee = taxWithdrawalFeeSource(t)?.let { stringResource(R.string.tax_note_withdrawal_fee, it) }
    // Gebühr USDT/Fiat einer Umbuchung ebenso (gleiche Vorlagen, [TaxTemplate.quoteFeeIsInfo])
    val transferFee = stringResource(R.string.tax_note_transfer_fee).takeIf { t.quoteFeeIsInfo }
    return notes + listOfNotNull(networkFee, withdrawalFee, transferFee, spouse)
}

/**
 * Quelle der Regel «Gebühr USDT/Fiat einer Auszahlung oder Umbuchung = kein Verlust, nur Information»
 * ([TaxTemplate.quoteFeeIsInfo]); UK ausdrücklich (CRYPTO22150), sonst die Behörde ohne eigene Regel dazu (Begründung
 * bei [TaxTemplate.quoteFeeIsInfo]).
 */
private fun taxWithdrawalFeeSource(t: TaxTemplate): String? = when (t) {
    TaxTemplate.UK -> "HMRC CRYPTO22150"
    TaxTemplate.US -> "IRS digital asset FAQ"
    TaxTemplate.CA -> "CRA"
    TaxTemplate.AU -> "ATO"
    TaxTemplate.JP -> "国税庁 FAQ"
    TaxTemplate.NEUTRAL, TaxTemplate.DE, TaxTemplate.AT, TaxTemplate.CH, TaxTemplate.FR -> null
}

/** Quelle der Regel «Netzwerkgebühr = Veräusserung zum Marktwert» ([TaxTemplate.networkFeeIsDisposal]); sonst null. */
private fun taxNetworkFeeSource(t: TaxTemplate): String? = when (t) {
    TaxTemplate.UK -> "HMRC CRYPTO22280"
    TaxTemplate.US -> "IRS Notice 2014-21, IRS digital asset FAQ"
    TaxTemplate.CA -> "CRA, Guide for cryptocurrency users"
    TaxTemplate.AU -> "ATO, CGT event A1"
    TaxTemplate.JP -> "国税庁 FAQ"
    TaxTemplate.NEUTRAL, TaxTemplate.DE, TaxTemplate.AT, TaxTemplate.CH, TaxTemplate.FR -> null
}

/** Quelle der Regel «Schenkung an Ehe-/Lebenspartner ohne Gewinn/Verlust» ([TaxTemplate.spouseGiftNeutral]); sonst null. */
private fun taxSpouseGiftSource(t: TaxTemplate): String? = when (t) {
    TaxTemplate.UK -> "TCGA 1992 s. 58"
    TaxTemplate.CA -> "ITA 73(1)"
    TaxTemplate.NEUTRAL, TaxTemplate.DE, TaxTemplate.AT, TaxTemplate.CH, TaxTemplate.US, TaxTemplate.AU, TaxTemplate.FR,
    TaxTemplate.JP -> null
}

/** Name eines Postens der Jahresübersicht. */
private fun taxLineLabel(l: TaxLine): Int = when (l) {
    TaxLine.SHORT_GAINS -> R.string.tax_de_short_gains
    TaxLine.SHORT_LOSSES -> R.string.tax_de_short_losses
    TaxLine.SHORT_NET -> R.string.tax_line_short_net
    TaxLine.LONG_GAINS -> R.string.tax_de_long_gains
    TaxLine.LONG_LOSSES -> R.string.tax_de_long_losses
    TaxLine.LONG_NET -> R.string.tax_line_long_net
    TaxLine.UNCLASSIFIED -> R.string.tax_de_unclassified
    TaxLine.GAINS -> R.string.tax_line_gains
    TaxLine.LOSSES -> R.string.tax_line_losses
    TaxLine.NET -> R.string.tax_line_net
    TaxLine.NEW_NET -> R.string.tax_line_new_net
    TaxLine.OLD_SHORT_NET -> R.string.tax_line_old_short_net
    TaxLine.OLD_LONG_NET -> R.string.tax_line_old_long_net
    TaxLine.DENIED_LOSS -> R.string.tax_line_denied_loss
    TaxLine.PROCEEDS -> R.string.tax_line_proceeds
    TaxLine.INCOME -> R.string.tax_line_income
    TaxLine.AT_INTEREST -> R.string.tax_line_at_interest
    TaxLine.AT_INTEREST_OLD -> R.string.tax_line_at_interest_old
    TaxLine.FR_OUTFLOW -> R.string.tax_line_fr_outflow
    TaxLine.WITHDRAWAL_FEES -> R.string.tax_line_withdrawal_fees
    TaxLine.TRANSFER_FEES -> R.string.tax_line_transfer_fees
    TaxLine.CH_WEALTH_VALUE -> R.string.tax_line_ch_wealth_value
    TaxLine.DE_LONG -> R.string.tax_line_de_long
    TaxLine.DE_PRIVATE_LIMIT -> R.string.tax_de_private_limit
    TaxLine.DE_INCOME_LIMIT -> R.string.tax_de_income_limit
    TaxLine.AT_RATE -> R.string.tax_line_at_rate
    TaxLine.AT_OLD_LONG -> R.string.tax_line_at_old_long
    TaxLine.AT_OLD_SPECULATION_LIMIT -> R.string.tax_line_at_old_speculation_limit
    TaxLine.CH_PRIVATE -> R.string.tax_line_ch_private
    TaxLine.CH_INCOME -> R.string.tax_line_ch_income
    TaxLine.CH_WEALTH -> R.string.tax_line_ch_wealth
    TaxLine.UK_EXEMPT -> R.string.tax_line_uk_exempt
    TaxLine.UK_AFTER_EXEMPT -> R.string.tax_line_uk_after_exempt
    TaxLine.US_TERMS -> R.string.tax_line_us_terms
    TaxLine.CA_TAXABLE -> R.string.tax_line_ca_taxable
    TaxLine.AU_DISCOUNTED -> R.string.tax_line_au_discounted
    TaxLine.NET_CAPITAL_LOSS -> R.string.tax_line_net_capital_loss
    TaxLine.FR_THRESHOLD -> R.string.tax_line_fr_threshold
    TaxLine.FR_RATE -> R.string.tax_line_fr_rate
    TaxLine.JP_MISC -> R.string.tax_line_jp_misc
}

/**
 * Texte der Jahresübersicht ([TaxSummaryTexts]) der Vorlage [t] für das Steuerjahr [yearLabel] in [currency]; [year] =
 * Steuerjahr für den Link der ESTV-Kursliste beim Vermerk «nicht gerechnet» (null = ohne).
 */
@Composable
internal fun taxSummaryTexts(t: TaxTemplate, yearLabel: String, currency: String, year: Int?): TaxSummaryTexts = TaxSummaryTexts(
    title = stringResource(R.string.tax_summary_title, stringResource(taxTemplateLabel(t)), yearLabel),
    disclaimer = stringResource(R.string.tax_de_disclaimer),
    item = stringResource(R.string.tax_de_col_item),
    usdt = "USDT",
    target = currency,
    note = stringResource(R.string.portfolio_export_col_note),
    orientation = stringResource(R.string.tax_summary_orientation),
    // CH Vermögenswert: «Vermögenswert 31.12.<Jahr> (Orientierung)» mit dem Steuerjahr
    labels = TaxLine.entries.associateWith {
        if (it == TaxLine.CH_WEALTH_VALUE) stringResource(taxLineLabel(it), yearLabel) else stringResource(taxLineLabel(it))
    },
    below = stringResource(R.string.tax_de_below),
    reached = stringResource(R.string.tax_de_reached),
    unknown = stringResource(R.string.tax_de_unknown),
    incomplete = stringResource(R.string.portfolio_export_incomplete),
    noFx = stringResource(R.string.portfolio_export_no_fx),
    orientationHeading = stringResource(R.string.tax_summary_orientation_block),
    orientationItem = stringResource(R.string.tax_summary_orientation_item, "%s"),
    // CH Vermögenssteuer: kein Betrag, sondern Link auf die ESTV-Kursliste des Steuerjahrs
    notCalculated = year?.let {
        stringResource(
            R.string.tax_summary_not_calculated_estv,
            EstvKursliste.listUrl(it, LocalConfiguration.current.locales[0].language),
        )
    }.orEmpty(),
    loss = stringResource(R.string.tax_de_loss),
    notes = mapOf(
        TaxSummaryNote.AT_REWARD_INFO to stringResource(R.string.tax_summary_at_reward_info),
        TaxSummaryNote.CH_EXCHANGE_VALUE to stringResource(R.string.tax_summary_ch_year_end_value),
        TaxSummaryNote.CH_FIFO_INFO to stringResource(R.string.tax_summary_ch_fifo_info),
        TaxSummaryNote.CH_WEALTH_ORIENTATION to stringResource(R.string.tax_summary_ch_wealth_orientation),
        TaxSummaryNote.DE_WITHDRAWAL_FEE to stringResource(R.string.tax_summary_de_withdrawal_fee),
        TaxSummaryNote.AT_WITHDRAWAL_FEE to stringResource(R.string.tax_summary_at_withdrawal_fee),
        TaxSummaryNote.WITHDRAWAL_FEE_INFO to stringResource(R.string.tax_summary_withdrawal_fee_info),
        TaxSummaryNote.TRANSFER_FEE_INFO to stringResource(R.string.tax_summary_transfer_fee_info),
        TaxSummaryNote.AT_INTEREST_INCOME to stringResource(R.string.tax_summary_at_interest_income),
        TaxSummaryNote.AT_INTEREST_OLD_LAW to stringResource(R.string.tax_summary_at_interest_old_law),
        TaxSummaryNote.AT_TRANSITION_2021 to stringResource(R.string.tax_summary_at_transition_2021),
        TaxSummaryNote.AT_WITHOUT_OPTION to stringResource(R.string.tax_summary_at_without_option),
        TaxSummaryNote.AT_STAKING_ZERO_COST to stringResource(R.string.tax_summary_at_staking_zero_cost),
        // Datum setzt die Übersicht ein ([noteText]): hier nur der Platzhalter
        TaxSummaryNote.WINDOW_OPEN to stringResource(R.string.tax_summary_window_open, TAX_NOTE_DATE),
        TaxSummaryNote.UK_HMRC_REPORT to stringResource(R.string.tax_summary_uk_hmrc_report),
        TaxSummaryNote.FR_ADDRESS_NO_COST to stringResource(R.string.tax_summary_fr_address_no_cost),
        TaxSummaryNote.FR_REWARDS_INCOME to stringResource(R.string.tax_summary_fr_rewards_income),
    ),
    // Coin und Menge setzt die Übersicht ein ([wealthCoinLabel]): hier nur die Platzhalter
    wealthCoin = stringResource(R.string.tax_ch_wealth_coin, TAX_WEALTH_COIN, TAX_WEALTH_AMOUNT),
    wealthMissing = stringResource(R.string.tax_ch_wealth_not_calculated),
    wealthNoPrice = stringResource(R.string.portfolio_export_no_price),
    wealthUnchecked = stringResource(R.string.portfolio_export_unchecked),
    wealthAddressUnobserved = stringResource(R.string.portfolio_export_address_unobserved),
    // USDT = 1 an einem Tag ohne USDT/USD-Schluss (z. B. vor dem Fenster der Quellen): eigene Zeile, nie still
    usdtAssumed = stringResource(R.string.portfolio_usdt_assumed),
    addressLots = stringResource(R.string.portfolio_export_address_lots_note),
    estimated = stringResource(R.string.tax_export_value_estimated),
)
