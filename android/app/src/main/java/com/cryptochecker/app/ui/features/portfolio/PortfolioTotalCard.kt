@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.alarm.DepegAlarm
import com.cryptochecker.app.domain.portfolio.PortfolioSummary
import com.cryptochecker.app.domain.portfolio.TodayChange
import com.cryptochecker.app.ui.components.AppCard
import com.cryptochecker.app.ui.components.CardPadding
import com.cryptochecker.app.ui.components.ExpandToggleButton
import com.cryptochecker.app.ui.components.SectionTitle
import com.cryptochecker.app.ui.components.sectionTitleMarker
import com.cryptochecker.app.ui.components.withSmallQuote
import com.cryptochecker.app.ui.theme.AppColors
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.ui.theme.label
import com.cryptochecker.app.util.PriceFormat

/**
 * Kompakter Kopf wie in Portfolio-Trackern (Build 46 entlastet): Gesamtwert (gross, rollende Ziffern)
 * und die Umrechnung, darunter EINE Veränderung «Heute» ([TotalChangeLine], %-Basis der App), dann
 * zwei Kennzahlen nebeneinander — «7 T» (Gewinn seit dem Tagesschluss vor 7 Tagen, Käufe/Verkäufe
 * herausgerechnet, [PortfolioViewModel.weekChange]) und «Seit Kauf» (± unrealisiert seit Kauf).
 * Hinter «Details» (zugeklappt): 1 J, Belohnungen, Einstand, Realisiert, Ausgebucht.
 * L-15: Hinweise (Kursquellen, Durchschnittskosten, fehlende Coins, Börsen, fehlende Kurse) und Stand
 * der Kurse stehen UNTER der Karte, zugeklappt als eine Zeile «Hinweise (n)» ([TotalCardHints]);
 * weitere Hinweiszeilen gehören dorthin.
 * Beträge in der Anzeigewährung (ohne Devisenkurs in USDT; verborgen als «•••»), Prozente bleiben sichtbar; unbekannt = «—».
 * Gestaltung wie auf iOS: ruhiger Verlauf in der Akzentfarbe (8 % → 2 %) über surfaceContainer,
 * Ecken 20 dp, Rand in der Akzentfarbe (25 %).
 */
@Composable
internal fun TotalCard(
    summary: PortfolioSummary,
    updatedAt: Long,
    currency: String,
    fxRate: Double?,
    todayPercent: Double?,
    todayAmount: Double? = null,
    /** Coins, die in «Heute» fehlen (kein Vergleichskurs). */
    todaySkipped: List<String> = emptyList(),
    weekChange: TodayChange? = null,
    /** «1 J» für die Zeile unter dem Gesamtwert ([PortfolioViewModel.yearChange]); null = keine Zeile. */
    yearChange: TodayChange? = null,
    /**
     * Kurse älter als die Frische-Schwelle (gemerkter Stand, ohne Netz): Gesamtwert in
     * onSurfaceVariant wie ein veralteter Kurs der Merkliste (nicht mehr abgeblasst, L-13),
     * «Kurse von … · veraltet» in Warnfarbe.
     */
    stale: Boolean = false,
    /** Coin → Börse, deren Kurs gilt ([com.cryptochecker.app.data.portfolio.PortfolioPrices.sources]); leer = nur Binance. */
    priceSources: Map<String, String> = emptyMap(),
    /** Coin → eingetragene Börse ohne Kurs (dann Binance). */
    exchangeFailed: Map<String, String> = emptyMap(),
    /** Coins mit mehreren eingetragenen Börsen (Binance). */
    mixedExchange: Set<String> = emptySet(),
    /** Coins, deren «7 T»/«1 J» Binance auf den Börsenkurs skaliert nutzt ([PortfolioViewModel.periodScaled]). */
    periodScaled: List<String> = emptyList(),
    /** FIFO je Wallet: Abgänge über den Bestand ihrer Wallet ([PortfolioCalculator.walletShortfalls]). */
    walletShortfalls: List<com.cryptochecker.app.domain.portfolio.PortfolioWalletShortfall> = emptyList(),
    /** Hinweis «Bestand in … reicht nicht» angetippt: Transaktion mit dieser Id öffnen. */
    onShortfall: (Long) -> Unit = {},
    /** Letzte Messung USDT/USD und Schwelle (Zehntel-%): Zeile «USDT = 1 angenommen» über [DepegAlarm.cardNote]. */
    depegReading: DepegAlarm.Reading? = null,
    depegThresholdTenths: Int = DepegAlarm.DEFAULT_THRESHOLD_TENTHS,
    /** Uhr für die Frische der Messung (Bildschirm tickt alle 30 s). */
    now: Long = System.currentTimeMillis(),
) {
    // Build 46: Details und Hinweise aufklappbar (die Karte zeigt zuerst nur Gesamtwert, «Heute», 7 T, Seit Kauf)
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var showHints by rememberSaveable { mutableStateOf(false) }
    val expandedText = stringResource(R.string.a11y_expanded)
    val collapsedText = stringResource(R.string.a11y_collapsed)
    // Woher die Kurse stammen: «Kurse: Binance · USDT zum Marktkurs» bzw. «Kurse: Kraken (BTC, ETH), sonst Binance · …»
    val priceSourceText = if (priceSources.isEmpty()) {
        stringResource(R.string.portfolio_price_source)
    } else {
        stringResource(R.string.portfolio_price_source_routes, com.cryptochecker.app.domain.portfolio.PortfolioExchangeRoute.sourcesText(priceSources))
    }
    // USDT zum Marktkurs (Owner-Entscheid Okt. 2026): ohne frische Messung trägt Ersatz 1 die Zahlen — dann
    // «USDT = 1 angenommen» auf der Karte (unabhängig vom Depeg-Alarm); mit Messung keine Zeile
    val usdtNote = DepegAlarm.cardNote(
        holdingCoins = summary.open.map { it.coin },
        valuesViaUsdt = com.cryptochecker.app.domain.portfolio.PortfolioExchangeRoute.usdtParityShown(
            currency, cardUnit(currency, fxRate).converted, priceSources
        ),
        reading = depegReading,
        thresholdTenths = depegThresholdTenths,
        now = now,
        usdtMarket = true,
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        // Hervorgehoben nur durch den Verlauf, ohne Akzentrand: eine einzige Hervorhebung (wie iOS)
        AppCard(padding = CardPadding.None) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Von oben links nach unten rechts, wie LinearGradient(.topLeading → .bottomTrailing);
                    // L-15: ruhiger (8 % → 2 %, früher 16 % → 4 %), hell und dunkel gleich ([AppColors.accentTint])
                    .background(Brush.linearGradient(listOf(AppColors.accentTint(CARD_TINT_START), AppColors.accentTint(CARD_TINT_END))))
                    .padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.lg, bottom = Spacing.xs)
            ) {
                Text(
                    stringResource(R.string.portfolio_total_value),
                    style = SectionTitle.style,
                    color = SectionTitle.color,
                    modifier = Modifier.sectionTitleMarker()
                )
                // Gross in der Anzeigewährung (wie das Widget), USDT klein darunter; ohne Devisenkurs gross USDT
                val unit = cardUnit(currency, fxRate)
                FittedTotal(
                    text = maskAmount(PriceFormat.valueWithCurrency(unit.of(summary.totalValue), unit.code).removeSuffix(" " + unit.code)),
                    // Verborgen («•••») ohne Währung, wie zuvor
                    quote = if (LocalHidePortfolioAmounts.current) null else unit.code,
                    value = unit.of(summary.totalValue),
                    // Veraltet: onSurfaceVariant statt halber Deckkraft (50 % lag unter 4.5:1, L-13)
                    color = if (stale) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 2.dp)
                )
                // Teilsumme: Coins ohne Kurs zählen als 0 — direkt an der Zahl «ohne 2 Coins» (antippen: Hinweise)
                if (summary.missingCurrentPrices.isNotEmpty()) {
                    PartialMarker(
                        text = pluralStringResource(
                            R.plurals.widget_portfolio_missing_coins,
                            summary.missingCurrentPrices.size,
                            summary.missingCurrentPrices.size,
                        ),
                        onClick = { showHints = true },
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
                // «≈ 117’345.12 USDT» — nur wenn gross umgerechnet steht (Faktor da; auch USD, USDT zum Marktkurs)
                if (unit.converted) {
                    Text(
                        withSmallQuote("≈ " + maskAmount(PortfolioFormat.usdt(summary.totalValue))),
                        style = MaterialTheme.typography.bodyMedium.amountNumbers(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // «USDT = 1 angenommen» auf der Karte selbst, sobald der Ersatz 1 die Zahlen trägt ([DepegAlarm.cardNote]
                // mit USDT zum Marktkurs: nur noch USDT_ASSUMED); die Kursquelle steht unter «Hinweise»
                when (usdtNote?.kind) {
                    // Mit usdtMarket nicht mehr geliefert (Abweichung = Bewertung zum Marktkurs, keine Warnzeile)
                    DepegAlarm.CardNoteKind.DEVIATION, null -> Unit
                    DepegAlarm.CardNoteKind.PRICE_SOURCE -> Text(
                        priceSourceText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    DepegAlarm.CardNoteKind.USDT_ASSUMED -> Text(
                        stringResource(R.string.portfolio_usdt_assumed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    // Bestätigter Einbruch: Werte rechnen mit dem Marktkurs — deutlich in Warnfarbe statt «angenommen»
                    DepegAlarm.CardNoteKind.USDT_BREAK -> Text(
                        stringResource(
                            R.string.portfolio_usdt_break,
                            com.cryptochecker.app.util.LocaleNumbers.decimal(usdtNote.reading?.price ?: 0.0, 4),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    // Kurs nur aus USDC/USDT: Herkunft nennen (USDC = 1 angenommen)
                    DepegAlarm.CardNoteKind.VIA_USDC -> Text(
                        stringResource(R.string.portfolio_usdt_via_usdc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                // Build 46: eine Veränderung direkt unter dem Gesamtwert — «↗ +412.95 CHF +1.85% · Heute»
                // (Prozent ohne Betrag, z. B. ohne Devisenkurs, bleibt sichtbar); ohne Daten keine Zeile
                TotalChangeLine(
                    amountUsd = todayAmount?.takeIf { todayPercent != null },
                    percent = todayPercent,
                    unit = unit,
                    period = stringResource(R.string.portfolio_card_today),
                    spokenLabel = stringResource(R.string.portfolio_card_today),
                )
                // M4: Überschrift «Gewinn/Verlust» mit (i) — was die Zahlen bedeuten (und was nicht)
                // (i) mit 48 dp Trefferfläche: Abstände darum knapper, damit die Karte nicht wächst
                GainHeader(method = summary.method, modifier = Modifier.padding(top = Spacing.xs))
                // 7 T · Seit Kauf nebeneinander (gleich breit)
                Row(modifier = Modifier.fillMaxWidth()) {
                    InsightFigure(
                        label = stringResource(R.string.portfolio_history_range_7d),
                        spokenLabel = stringResource(R.string.portfolio_history_period_7d),
                        amount = weekChange?.amountUsd,
                        percent = weekChange?.percent,
                        unit = unit,
                        modifier = Modifier.weight(1f),
                        rewardsUsd = weekChange?.rewardsUsd ?: 0.0,
                    )
                    // «Seit Kauf (CHF)»: Gewinn/Verlust seit Kauf der Coins mit Kaufpreis (in der Einheit des Gesamtwerts)
                    Column(modifier = Modifier.weight(1f).padding(start = Spacing.sm)) {
                        InsightFigure(
                            label = stringResource(R.string.ledger_since_purchase_currency, unit.code),
                            spokenLabel = stringResource(R.string.ledger_since_purchase_spoken),
                            amount = summary.unrealized,
                            percent = summary.unrealizedPercent?.takeIf { summary.unrealized != null },
                            unit = unit,
                            modifier = Modifier.fillMaxWidth()
                        )
                        // Teilsumme (Coins/Teile ohne Kaufpreis oder ohne Kurs fehlen): «unvollständig» an der Zahl
                        if (summary.unrealized != null &&
                            (summary.missingCostCoins.isNotEmpty() || summary.partialCostParts.isNotEmpty() ||
                                summary.missingCurrentPrices.isNotEmpty())
                        ) {
                            PartialMarker(
                                text = stringResource(R.string.portfolio_export_incomplete),
                                onClick = { showHints = true },
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
                // «Details»: 1 J, Belohnungen, Einstand, Realisiert, Ausgebucht — zugeklappt
                ExpandToggleButton(
                    text = stringResource(R.string.portfolio_card_details),
                    expanded = showDetails,
                    onClick = { showDetails = !showDetails },
                    modifier = Modifier.semantics {
                        stateDescription = if (showDetails) expandedText else collapsedText
                    }
                )
                if (showDetails) {
                    // «↘ −38’412.95 CHF −27.85% · 1J» wie im Portfolio-Widget; ohne Daten keine Zeile
                    yearChange?.let {
                        TotalChangeLine(
                            amountUsd = it.amountUsd,
                            percent = it.percent,
                            unit = unit,
                            period = stringResource(R.string.portfolio_history_range_1y),
                            spokenLabel = stringResource(R.string.portfolio_history_period_1y),
                            requireAmount = true,
                        )
                    }
                    // Belohnungen im Jahr: Zufluss, nicht Gewinn — klein darunter
                    yearChange?.takeIf { it.amountUsd.isFinite() }?.let { change -> rewardsText(change.rewardsUsd, unit)?.let { RewardsNote(it) } }
                    Row(modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm)) {
                        Metric(
                            label = stringResource(R.string.portfolio_invested),
                            value = summary.invested?.let { maskAmount(PortfolioFormat.usdt(it)) } ?: "—",
                            modifier = Modifier.weight(1f)
                        )
                        if (PortfolioFormat.showRealized(summary.realized, summary.realizedIncomplete)) {
                            Metric(
                                label = stringResource(R.string.portfolio_realized),
                                value = maskAmount(PortfolioFormat.realizedText(summary.realized, summary.realizedIncomplete, stringResource(R.string.portfolio_export_incomplete))),
                                valueTone = plTone(summary.realized),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    // Steuer-CSV rechnet nach den Regeln des Landes (Fristen, Freigrenzen, Pools): kann abweichen
                    if (PortfolioFormat.showRealized(summary.realized, summary.realizedIncomplete)) {
                        Text(
                            stringResource(R.string.tax_export_realized_differs),
                            style = MaterialTheme.typography.label,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    // «Ausgebucht: +X (Wertänderung bis zur Auszahlung)»: steckt im Gewinn/Verlust des Wertverlaufs,
                    // nicht in «Seit Kauf»/«realisiert» — nur wenn ≠ 0
                    summary.withdrawnGainExact?.toDouble()?.takeIf { it.isFinite() && !PortfolioFormat.isZero(it) }?.let { gain ->
                        Text(
                            stringResource(
                                R.string.portfolio_withdrawn_gain,
                                maskAmount(PortfolioFormat.realizedText(gain, summary.withdrawnGainIncomplete, stringResource(R.string.portfolio_export_incomplete))),
                            ),
                            style = MaterialTheme.typography.bodySmall.amountNumbers(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Spacing.xs)
                        )
                    }
                    Spacer(Modifier.height(Spacing.sm))
                }
            }
        }
        // L-15: Hinweise klein unter der Karte; Build 46: eine Zeile «Hinweise (n)», aufklappbar
        val hints = buildList {
            // Ohne Devisenkurs: Beträge in USDT statt in der Anzeigewährung
            if (currency != "USD" && !cardUnit(currency, fxRate).converted) {
                add(TotalHintItem(stringResource(R.string.portfolio_total_no_fx, currency)))
            }
            // Kursquelle: steht sie schon auf der Karte, nicht noch einmal unter «Hinweise»
            if (usdtNote?.kind != DepegAlarm.CardNoteKind.PRICE_SOURCE) add(TotalHintItem(priceSourceText))
            // G-9: Modell nahe an der Zahl, nicht nur im (i) — sonst wirkt die Abweichung zur Börse wie ein Fehler
            // Ø ist eine bewusste Wahl in den Einstellungen: kein Hinweis «nicht FIFO» mehr
            if (summary.unrealized != null && summary.method.isFifo) {
                add(TotalHintItem(stringResource(R.string.ledger_fifo_cost_note)))
            }
            // Was im Gewinn seit Kauf fehlt (M3: nur diese Coins, nicht das ganze Portfolio)
            if (summary.missingCostCoins.isNotEmpty()) {
                add(TotalHintItem(
                    pluralStringResource(
                        R.plurals.ledger_without_cost,
                        summary.missingCostCoins.size,
                        summary.missingCostCoins.size,
                        summary.missingCostCoins.joinToString(", ")
                    )
                ))
            }
            // Nur ein Teil ohne Kaufpreis: «Seit Kauf» zählt den Teil mit Kaufpreis, der Rest fehlt
            if (summary.partialCostParts.isNotEmpty()) {
                add(TotalHintItem(
                    stringResource(
                        R.string.ledger_partial_cost,
                        summary.partialCostParts.map { maskAmount(PortfolioFormat.amount(it.amount, it.coin)) }.joinToString(", ")
                    )
                ))
            }
            // Heute / 7 T / 1 J: Coins ohne passenden Kurs am Anfang fehlen nur dort, die übrigen zählen
            val periodSkipped = (todaySkipped + weekChange?.skipped.orEmpty() + yearChange?.skipped.orEmpty()).distinct().sorted()
            if (periodSkipped.isNotEmpty()) {
                add(TotalHintItem(stringResource(R.string.ledger_period_without, periodSkipped.joinToString(", "))))
            }
            // Eingetragene Börse ohne Kurs bzw. mehrere Börsen bei einem Coin: mit Binance bewertet
            exchangeFailed.entries.groupBy({ it.value }, { it.key }).toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (exchange, coins) ->
                add(TotalHintItem(stringResource(R.string.ledger_exchange_failed, coins.sorted().joinToString(", "), exchange)))
            }
            if (mixedExchange.isNotEmpty()) {
                add(TotalHintItem(stringResource(R.string.ledger_exchange_mixed, mixedExchange.sorted().joinToString(", "))))
            }
            // Keine eigenen Tageskerzen der Börse: «7 T»/«1 J» aus Binance, auf den Börsenkurs skaliert
            if (periodScaled.isNotEmpty()) {
                add(TotalHintItem(stringResource(R.string.portfolio_period_scaled, periodScaled.joinToString(", "))))
            }
            // FIFO je Wallet: je Coin und Wallet der erste Abgang über den Bestand — antippen öffnet die Transaktion
            walletShortfalls.distinctBy { it.coin to it.wallet.trim().lowercase() }.forEach { s ->
                val wallet = s.wallet.ifBlank { stringResource(R.string.tax_export_wallet_unspecified) }
                add(TotalHintItem(s.coin + ": " + stringResource(R.string.portfolio_wallet_shortfall, wallet), error = true, txId = s.txId))
            }
            // Umbuchungsgebühren auf Bestand ohne Kaufpreis: offener Posten (je Coin auf der Detailseite)
            if (summary.unassignedFeeUsdt.signum() != 0) {
                add(TotalHintItem(stringResource(
                    R.string.portfolio_unassigned_fee,
                    maskAmount(PortfolioFormat.usdt(summary.unassignedFeeUsdt.toDouble())),
                )))
            }
            if (summary.missingCurrentPrices.isNotEmpty()) {
                add(TotalHintItem(stringResource(R.string.portfolio_current_missing, summary.missingCurrentPrices.joinToString(", "))))
            }
            if (updatedAt > 0) {
                val asOf = stringResource(R.string.portfolio_updated, if (stale) portfolioStampTime(updatedAt) else PriceFormat.time(updatedAt))
                add(TotalHintItem(if (stale) stringResource(R.string.watchlist_row_outdated, asOf) else asOf, error = stale))
            }
        }
        TotalCardHints(hints = hints, expanded = showHints, onToggle = { showHints = !showHints }, onShortfall = onShortfall)
    }
}

/**
 * Kleiner Hinweis direkt an einer Teilsumme («ohne 2 Coins», «unvollständig») — nicht nur in den zugeklappten
 * Hinweisen; antippen klappt «Hinweise» auf ([onClick]). Wie iOS `partialMarker`.
 */
@Composable
private fun PartialMarker(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

/** Verlauf der Gesamtkarte (L-15): ruhig, 8 % → 2 % der Akzentfarbe (früher 16 % → 4 %). */
private const val CARD_TINT_START = 0.08f

private const val CARD_TINT_END = 0.02f
