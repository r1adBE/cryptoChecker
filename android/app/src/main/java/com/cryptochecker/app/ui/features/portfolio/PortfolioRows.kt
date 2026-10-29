@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.portfolio

import com.cryptochecker.app.ui.components.EmptyState
import com.cryptochecker.app.ui.theme.label
import com.cryptochecker.app.ui.components.ListSegment
import com.cryptochecker.app.ui.components.segmentLines
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.platform.LocalDensity
import com.cryptochecker.app.util.DenseRowText
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.platform.testTag
import java.util.Locale
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.CoinPosition
import com.cryptochecker.app.domain.portfolio.PortfolioChange
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetMath
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.ui.components.CoinBadge
import com.cryptochecker.app.ui.components.withSmallQuote
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.util.BidiText
import com.cryptochecker.app.util.PriceFormat
import com.cryptochecker.app.ui.UiTestTags

/**
 * Zeile je Coin (wie die Zeilen im Portfolio-Widget): Plakette, Menge klein und grau vor dem Symbol
 * («1.3 BTC»), darunter der Anteil am Gesamtwert [sharePercent] («88.9%»), Ø/aktuell; rechts Wert, ± % seit
 * Kauf und unten MEIN Gewinn/Verlust auf der Position über die %-Basis [basis] ([pnl], USDT,
 * [com.cryptochecker.app.domain.portfolio.PortfolioPeriodChange.todayByCoin]): grau «24h», daneben
 * «+1’250.00 CHF · +1.50%» in der Kursfarbe nach dem Betrag (ein Kauf heute zählt zu seinem Preis, kein
 * Gewinn); unbekannt «—». Die Summe der Zeilen ist «Heute» der Gesamtkarte. Wert und Gewinn/Verlust in
 * der Einheit der Gesamtkarte [unit] (Anzeigewährung wie im Widget, ohne Devisenkurs USDT); Detail in USDT.
 */
@Composable
internal fun CoinRow(
    position: CoinPosition,
    onClick: () -> Unit,
    pnl: PortfolioChange? = null,
    basis: ChangeBasis? = null,
    sharePercent: Double? = null,
    onDelete: (() -> Unit)? = null,
    txCount: Int = 0,
    /** Form je nach Platz in der Liste ([ListSegment.shape]); Positionen wirken wie eine Einheit. */
    shape: Shape = MaterialTheme.shapes.medium,
    /** Kurs veraltet (gemerkter Stand): aktueller Kurs und Wert abgeblasst wie in der Merkliste. */
    stale: Boolean = false,
    /** Nur ein ungeprüfter Kerzenkurs (kein Bezug): kein Wert («—»), Hinweis «ungeprüft» ([PortfolioPrices.unchecked]). */
    unchecked: Boolean = false,
    /** Abfrage gescheitert (Netz, Sperre), kein gemerkter Kurs: «Kurs gerade nicht abrufbar» ([PortfolioPrices.unavailable]). */
    unavailable: Boolean = false,
    /** Kein Binance-Name und keine Quelle mit Kurs: kein Wert («—»), Hinweis «Kein Markt» ([PortfolioPrices.noMarket]). */
    noMarket: Boolean = false,
    /** Einheit von Wert und Gewinn/Verlust ([cardUnit]); Standard USDT. */
    unit: CardUnit = cardUnit(PortfolioFormat.USDT, null),
    /** Börse, deren Kurs gilt ([com.cryptochecker.app.data.portfolio.PortfolioPrices.sources]); null = Binance (kein Name). */
    exchange: String? = null,
    /**
     * Gekapselter Coin ohne eigenen Börsenkurs, bewertet mit dem Kurs dieser Basis
     * ([com.cryptochecker.app.data.portfolio.PortfolioPrices.viaBase]): Hinweis «≈ BTC-Kurs (gebrückt/verpackt)».
     */
    viaBase: String? = null,
    /**
     * Stablecoin, dessen Menge gebrückte Varianten enthält ([com.cryptochecker.app.domain.portfolio.AddressBalance.bridgedStables],
     * z. B. «USDC.e»): Hinweis «≈ inkl. USDC.e (gebrückt)» — eine Brücke kann abkoppeln, die Menge gilt nur ungefähr.
     */
    bridgedStable: String? = null,
) {
    // Screenreader: Löschen (sonst nur per Wischen) als eigene Aktion der Zeile
    val deleteLabel = stringResource(R.string.action_delete)
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    // Screenreader: die Zeile als ein Satz ([coinRowSpoken]); die Teile darunter sind ausgeblendet
    val spoken = coinRowSpoken(
        position, pnl, basis, sharePercent, txCount, stale, unchecked, unavailable, unit, exchange, viaBase, bridgedStable, locale,
        noMarket,
    )
    Card(
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        // Positionen als eine Karte: Trennlinie und Aussenrand statt eines Rands je Zeile
        modifier = Modifier
            .segmentLines(shape, divider = ListSegment.dividerColor(), outline = ListSegment.outlineColor())
            .fillMaxWidth()
            .testTag(UiTestTags.portfolioCoin(position.coin))
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = spoken
                if (onDelete != null) customActions = listOf(CustomAccessibilityAction(deleteLabel) { onDelete(); true })
            }
    ) {
        // Grösste Schrift (ab 1.5): Wert und Gewinn/Verlust unter Coin und Anteil statt daneben ([DenseRowText])
        val fontScale = LocalDensity.current.fontScale
        val stacked = DenseRowText.stacksTrailing(fontScale)
        // Grosse Schrift: das Symbol darf zwei Zeilen nehmen statt abgeschnitten zu werden
        val lines = DenseRowText.maxLines(fontScale)
        val trailing: @Composable (Modifier) -> Unit = { columnModifier ->
            Column(horizontalAlignment = Alignment.End, modifier = columnModifier) {
                Text(
                    withSmallQuote(position.value?.let { maskAmount(PriceFormat.valueWithCurrency(unit.of(it), unit.code)) } ?: "—"),
                    style = MaterialTheme.typography.titleSmall.amountNumbers(),
                    fontWeight = FontWeight.SemiBold,
                    // Veraltet: Sekundärfarbe wie in der Merkliste statt halber Deckkraft (Kontrast)
                    color = if (stale) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                if (unchecked) {
                    Text(
                        stringResource(R.string.portfolio_price_unchecked),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                } else if (noMarket && position.value == null) {
                    Text(
                        stringResource(R.string.portfolio_price_no_market),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                } else if (unavailable && position.value == null) {
                    // Fehler statt «kein Kurs»: die Quelle antwortet gerade nicht
                    Text(
                        stringResource(R.string.portfolio_price_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                } else if (viaBase != null) {
                    // Kein eigener Kurs: Wert mit dem Kurs der Basis — nie als eigener Kurs ausgeben
                    Text(
                        stringResource(R.string.portfolio_price_via_base, viaBase),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                } else if (bridgedStable != null) {
                    // Gebrückte Stablecoin-Variante mitgezählt: Menge nur ungefähr, wie WBTC markiert
                    Text(
                        stringResource(R.string.portfolio_amount_bridged_stable, bridgedStable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
                // «Seit Kauf» des Teils mit Kaufpreis; fehlt bei einem Teil der Kaufpreis, darunter «Preis fehlt»
                if (!position.priceMissing || position.unrealizedPartial) {
                    PlPill(position.unrealizedPercent, modifier = Modifier.padding(top = 3.dp))
                }
                if (position.priceMissing) {
                    Text(
                        stringResource(R.string.portfolio_price_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
                if (basis != null) {
                    RowPnl(pnl, basis, unit)
                }
            }
        }
        // Gleicher Innenabstand und gleich grosses Logo wie in der Merkliste; Teile für TalkBack ausgeblendet (ein Satz oben)
        Column(modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.md).clearAndSetSemantics { }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Abstand an der Plakette: Logos aus → keine Plakette, kein Einzug
                CoinBadge(position.coin, size = ListSegment.Logo, portfolio = true, modifier = Modifier.padding(end = 8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Menge klein und grau vor dem Symbol («1.3 BTC»); grosse Schrift: Menge wird schrittweise kleiner
                        // statt gekürzt (eine abgeschnittene Zahl wäre eine falsche Zahl, wie iOS), das Symbol bleibt
                        val amountStyle = MaterialTheme.typography.bodySmall.amountNumbers()
                        BasicText(
                            maskAmount(PriceFormat.amount(position.holdingsExact)),
                            style = amountStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                            maxLines = 1,
                            // Passt sie auch klein nicht: sichtbar gekürzt («…») wie iOS, nie still abgeschnitten
                            overflow = TextOverflow.Ellipsis,
                            autoSize = TextAutoSize.StepBased(
                                minFontSize = amountStyle.fontSize * 0.6f,
                                maxFontSize = amountStyle.fontSize,
                                stepSize = 0.5.sp,
                            ),
                            modifier = Modifier.weight(1f, fill = false).padding(end = 4.dp)
                        )
                        Text(
                            position.coin,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = lines
                        )
                        if (position.oversold) {
                            Icon(
                                painterResource(R.drawable.ic_error),
                                contentDescription = stringResource(R.string.portfolio_oversold),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(start = Spacing.xs).size(14.dp)
                            )
                        }
                    }
                    // Anteil am Gesamtwert, daneben die Anzahl Transaktionen als kleine Zahl («88.9%  3»);
                    // Dahinter die Börse des Kurses («· Kraken», nur mit eigener Börse) und nur bei
                    // veraltetem Kurs «veraltet» in Fehlerfarbe (wie die Merkliste). Fehlt Platz, kürzt
                    // zuerst die Börse, dann der Anteil; Zahl und «veraltet» bleiben ganz.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            sharePercent?.takeIf { it.isFinite() }?.let { PortfolioWidgetMath.shareText(it, locale) } ?: "—",
                            style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (txCount > 0) TxCountBadge(txCount)
                        exchange?.takeIf { it.isNotBlank() }?.let { name ->
                            Text(
                                " · " + BidiText.isolate(name, locale),
                                style = MaterialTheme.typography.label,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                        if (stale) {
                            Text(
                                " · " + stringResource(R.string.widget_outdated),
                                style = MaterialTheme.typography.label,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 1,
                            )
                        }
                    }
                    // «Ø … · aktuell …»; passt das nicht, fällt zuerst «aktuell …» weg (Ø bleibt, wie iOS ViewThatFits)
                    val avgText = stringResource(R.string.portfolio_avg_and_now, PriceFormat.price(position.avgCost), PriceFormat.price(position.currentPrice))
                    var avgOnly by remember(avgText) { mutableStateOf(false) }
                    Text(
                        if (avgOnly) stringResource(R.string.portfolio_avg_only, PriceFormat.price(position.avgCost)) else avgText,
                        style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { if (!avgOnly && it.hasVisualOverflow) avgOnly = true },
                    )
                    // Ø nur über den Teil mit Kaufpreis: benennen, über welche Menge («Ø von 0.5 BTC mit Kaufpreis»)
                    if (position.avgCostPartial) {
                        Text(
                            stringResource(
                                R.string.portfolio_avg_partial,
                                maskAmount(PortfolioFormat.amount(position.pricedHoldingsExact, position.coin))
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (!stacked) trailing(Modifier.padding(start = 8.dp))
            }
            if (stacked) trailing(Modifier.align(Alignment.End).padding(top = Spacing.xs))
        }
    }
}

/** Geschlossene Position: nur der realisierte Gewinn/Verlust. */
@Composable
internal fun ClosedRow(position: CoinPosition, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = 8.dp)
    ) {
        CoinBadge(position.coin, size = 32.dp, portfolio = true, modifier = Modifier.padding(end = 12.dp))
        Text(
            position.coin,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        PlValuePill(
            maskAmount(PortfolioFormat.realizedText(position.realized, position.realizedIncomplete, stringResource(R.string.portfolio_export_incomplete))),
            position.realized
        )
    }
}

@Composable
internal fun EmptyPortfolio(
    modifier: Modifier,
    currency: String,
    onAdd: () -> Unit,
    onAddAddress: (() -> Unit)? = null,
    onImportCsv: (() -> Unit)? = null,
) {
    // Reihenfolge wie überall: Symbol → Titel → Beispiel → Hauptknopf → Textknöpfe → Fusstext (wie iOS).
    // Nur ein Satz (das Beispiel) statt Text plus Beispiel, damit auf einem üblichen Telefon alles
    // samt Fusstext ohne Scrollen sichtbar ist. Alltagssprache vor dem ersten Eintrag: Beispiel ohne
    // Kaufkurs in USDT, Hauptweg «Ersten Kauf eintragen»; Börsen-Datei und Adresse nur als Nebenwege
    EmptyState(
        iconRes = R.drawable.ic_portfolio,
        title = stringResource(R.string.portfolio_empty_title),
        example = stringResource(R.string.portfolio_empty_example_plain, PortfolioEmptyExample.amountText()),
        actionLabel = stringResource(R.string.portfolio_empty_action),
        onAction = onAdd,
        actionIconRes = R.drawable.ic_add,
        modifier = modifier,
    ) {
        // Nebenweg: Datei einer Börse übernehmen (CSV-Import)
        if (onImportCsv != null) {
            androidx.compose.material3.TextButton(
                onClick = onImportCsv,
                modifier = Modifier.padding(top = Spacing.sm).testTag(UiTestTags.PORTFOLIO_IMPORT_CSV)
            ) {
                Text(stringResource(R.string.portfolio_empty_csv))
            }
        }
        // Nebenweg: öffentliche Adresse nur beobachten (Bestand ohne Kaufpreis)
        if (onAddAddress != null) {
            androidx.compose.material3.TextButton(onClick = onAddAddress) {
                Text(stringResource(R.string.portfolio_empty_address))
            }
        }
        PortfolioDisclaimer()
    }
}

/** Menge im Beispiel des leeren Portfolios («Beispiel: 0.01 BTC …»), im Zahlenformat der App. Wie iOS. */
internal object PortfolioEmptyExample {
    const val AMOUNT = 0.01

    /** Menge wie die Bestände formatiert ([PriceFormat.amount]), de-CH «0.01», de-DE «0,01». */
    fun amountText(): String = PriceFormat.amount(AMOUNT)
}
