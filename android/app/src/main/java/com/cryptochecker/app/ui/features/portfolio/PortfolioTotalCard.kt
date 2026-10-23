@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.alarm.DepegAlarm
import com.cryptochecker.app.domain.portfolio.PortfolioCostFxFigures
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
 * Kompakter Kopf wie in Portfolio-Trackern: Gesamtwert (gross, rollende Ziffern)
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
    /**
     * «Seit Kauf» und Einstand in der Anzeigewährung zum Kurs der Erwerbstage ([PortfolioViewModel.costFx]); gilt nur,
     * wenn die Karte umrechnet — null dann = Kurse laden noch («—»). In USDT bleiben die Zahlen aus [summary].
     */
    costFx: PortfolioCostFxFigures? = null,
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
    // Details und Hinweise aufklappbar (die Karte zeigt zuerst nur Gesamtwert, «Heute», 7 T, Seit Kauf)
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
                UsdtNoteLine(usdtNote, priceSourceText)
                // Eine Veränderung direkt unter dem Gesamtwert — «↗ +412.95 CHF +1.85% · Heute»
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
                // 7 Tage · Seit Kauf nebeneinander (gleich breit); ausgeschrieben statt «7 T», weil das Kürzel
                // allein neben dem Gesamtwert unverständlich war — die Spalte ist so breit wie «Seit Kauf (CHF)»
                Row(modifier = Modifier.fillMaxWidth()) {
                    InsightFigure(
                        label = stringResource(R.string.portfolio_history_period_7d),
                        spokenLabel = stringResource(R.string.portfolio_history_period_7d),
                        amount = weekChange?.amountUsd,
                        percent = weekChange?.percent,
                        unit = unit,
                        modifier = Modifier.weight(1f),
                        rewardsUsd = weekChange?.rewardsUsd ?: 0.0,
                    )
                    // «Seit Kauf (CHF)»: Gewinn/Verlust seit Kauf der Coins mit Kaufpreis (in der Einheit des Gesamtwerts);
                    // umgerechnet: Wert zum heutigen Kurs − Einstand zum Kurs der Erwerbstage ([PortfolioViewModel.costFx]),
                    // nie der Einstand zum heutigen Kurs
                    val sinceAmount = if (unit.converted) costFx?.unrealized?.toDouble() else summary.unrealized
                    val sincePercent = if (unit.converted) costFx?.unrealizedPercent else summary.unrealizedPercent
                    Column(modifier = Modifier.weight(1f).padding(start = Spacing.sm)) {
                        InsightFigure(
                            label = stringResource(R.string.ledger_since_purchase_currency, unit.code),
                            spokenLabel = stringResource(R.string.ledger_since_purchase_spoken),
                            amount = sinceAmount,
                            percent = sincePercent?.takeIf { sinceAmount != null },
                            unit = unit,
                            modifier = Modifier.fillMaxWidth(),
                            amountInUnit = unit.converted,
                        )
                        // Teilsumme (Coins/Teile ohne Kaufpreis, ohne Kurs oder ohne Devisenkurs am Kauftag fehlen):
                        // «unvollständig» an der Zahl
                        val fxMissing = unit.converted && costFx?.unrealizedFxMissing == true
                        if (summary.unrealized != null && (fxMissing ||
                            (sinceAmount != null && (summary.missingCostCoins.isNotEmpty() || summary.partialCostParts.isNotEmpty() ||
                                summary.missingCurrentPrices.isNotEmpty())))
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
                    TotalCardDetails(
                        summary = summary,
                        unit = unit,
                        costFx = costFx,
                        yearChange = yearChange,
                        onShowHints = { showHints = true },
                    )
                }
            }
        }
        // L-15: Hinweise klein unter der Karte; eine Zeile «Hinweise (n)», aufklappbar
        val hints = totalHintItems(
            summary = summary,
            updatedAt = updatedAt,
            currency = currency,
            fxRate = fxRate,
            costFx = costFx,
            todaySkipped = todaySkipped,
            weekChange = weekChange,
            yearChange = yearChange,
            stale = stale,
            exchangeFailed = exchangeFailed,
            mixedExchange = mixedExchange,
            periodScaled = periodScaled,
            walletShortfalls = walletShortfalls,
            usdtNote = usdtNote,
            priceSourceText = priceSourceText,
        )
        TotalCardHints(hints = hints, expanded = showHints, onToggle = { showHints = !showHints }, onShortfall = onShortfall)
    }
}

/** Verlauf der Gesamtkarte (L-15): ruhig, 8 % → 2 % der Akzentfarbe (früher 16 % → 4 %). */
private const val CARD_TINT_START = 0.08f

private const val CARD_TINT_END = 0.02f
