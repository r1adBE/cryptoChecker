// Bausteine der Gesamtkarte: Kopf «Gewinn/Verlust» mit Methode, passend verkleinerter Gesamtwert,
// Änderungszeile, Kennzahlen, Hinweis auf Staking-Erträge und die Anzeigewährung der Karte (`CardUnit`).
// Eigene Datei, weil `TotalCard` nur die Anordnung bestimmt; die Bausteine sind einzeln lesbar
// (wie `PortfolioTotalFigures.swift`).
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.CostMethod
import com.cryptochecker.app.ui.components.RollingNumberText
import com.cryptochecker.app.ui.components.pillArrow
import com.cryptochecker.app.ui.components.pillContent
import com.cryptochecker.app.ui.components.withSmallQuote
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.ui.theme.display
import com.cryptochecker.app.ui.theme.label
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.PriceFormat

/**
 * Kleine Überschrift «Gewinn/Verlust» über Heute · 7 T · Seit Kauf, rechts ein (i): erklärt in
 * wenigen Sätzen, wie die Zahlen gerechnet sind (Käufe/Verkäufe im Zeitraum herausgerechnet,
 * Einstand nach der gewählten Methode [method] (FIFO oder Ø), Gebühren, Bewertung mit Binance-Kursen, USDT zum Marktkurs).
 */
@Composable
internal fun GainHeader(method: CostMethod, modifier: Modifier = Modifier) {
    var showInfo by remember { mutableStateOf(false) }
    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(stringResource(R.string.ledger_gain_title)) },
            text = {
                Text(
                    stringResource(if (method.isFifo) R.string.ledger_gain_info_fifo else R.string.ledger_gain_info),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) { Text(stringResource(R.string.action_close)) }
            }
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.ledger_gain_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false)
        )
        // Trefferfläche 48 dp (Symbol bleibt 16 dp)
        IconButton(onClick = { showInfo = true }, modifier = Modifier.size(48.dp)) {
            Icon(
                painterResource(R.drawable.ic_info),
                contentDescription = stringResource(R.string.ledger_gain_info_a11y),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * Gesamtwert gross (displaySmall, Betragsschrift) mit rollenden Ziffern, dahinter die Währung
 * [quote] klein und grau wie in der Merkliste (bodyMedium, onSurfaceVariant; Grundlinie wie die
 * Zahl, immer rechts davon). Passt beides nicht in die Breite, wird nur die Zahl verkleinert
 * (höchstens auf die Hälfte, wie iOS `minimumScaleFactor(0.5)`); die Währung bleibt gleich gross.
 */
@Composable
internal fun FittedTotal(
    text: String,
    quote: String?,
    value: Double,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val base = MaterialTheme.typography.display.amountNumbers().copy(fontWeight = FontWeight.SemiBold)
        val quoteStyle = MaterialTheme.typography.bodyMedium
        val gapPx = with(LocalDensity.current) { 6.dp.roundToPx() }
        val measurer = rememberTextMeasurer()
        val maxPx = constraints.maxWidth
        val style = remember(text, quote, maxPx, base, quoteStyle) {
            val width = measurer.measure(text, base, maxLines = 1, softWrap = false).size.width
            val quoteWidth = quote?.let { measurer.measure(it, quoteStyle, maxLines = 1, softWrap = false).size.width + gapPx } ?: 0
            val room = maxPx - quoteWidth
            if (!constraints.hasBoundedWidth || width <= room || width == 0 || room <= 0) base
            else base.copy(fontSize = base.fontSize * (room.toFloat() / width * 0.98f).coerceAtLeast(0.5f))
        }
        val spoken = if (quote != null) "$text $quote" else text
        // Immer «Zahl Währung» von links nach rechts (wie PriceWithQuote), ein Element für den Screenreader
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(modifier = Modifier.clearAndSetSemantics { contentDescription = spoken }) {
                RollingNumberText(text = text, value = value, style = style, color = color, modifier = Modifier.alignByBaseline())
                if (quote != null) {
                    Text(
                        quote,
                        style = quoteStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.alignByBaseline().padding(start = 6.dp)
                    )
                }
            }
        }
    }
}

/**
 * Zeile unter dem Gesamtwert wie im Portfolio-Widget: «↘ −38’412.95 USDT −27.85% · 1J» — Pfeil,
 * Betrag (verborgen «•••») und Prozent als farbige Schrift ohne Pille in der Kursfarbe (Richtung
 * nach dem Betrag, ohne Betrag nach dem Prozent; Null-Regel des Portfolios), die Währung klein und
 * grau, der Zeitraum [period] klein und grau dahinter. Build 46: für «Heute» (unter dem Gesamtwert)
 * und «1 J» (in den Details); ohne Zahl keine Zeile, mit [requireAmount] auch ohne Betrag keine.
 * Vorgelesen als ein Satz («1 year: down 27.85%, −38’412.95 USDT»).
 */
@Composable
internal fun TotalChangeLine(
    amountUsd: Double?,
    percent: Double?,
    unit: CardUnit,
    period: String,
    spokenLabel: String,
    requireAmount: Boolean = false,
) {
    val context = LocalContext.current
    val amount = amountUsd?.takeIf { it.isFinite() }?.let(unit::of)
    val validPercent = percent?.takeIf { it.isFinite() }
    if (amount == null && (requireAmount || validPercent == null)) return
    val tone = plTone(amount ?: validPercent)
    val arrow = pillArrow(tone)
    val amountSpoken = amount?.let { spokenAmount(unit.signed(it)) }
    val spoken = "$spokenLabel: " + listOfNotNull(validPercent?.let { A11yText.change(context, it) }, amountSpoken).joinToString(", ")
    val style = MaterialTheme.typography.labelLarge.amountNumbers()
    val color = pillContent(tone)
    val text = buildAnnotatedString {
        if (arrow != null) {
            append("$arrow ")
        }
        if (amount != null) {
            append(withSmallQuote(maskAmount(unit.signed(amount))))
        }
        if (validPercent != null) {
            append((if (amount != null) "  " else "") + PortfolioFormat.signedPercent(validPercent))
        }
        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Normal,
            fontSize = MaterialTheme.typography.label.fontSize)) {
            append(" · $period")
        }
    }
    Text(
        text,
        style = style,
        fontWeight = FontWeight.SemiBold,
        color = color,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 2.dp).clearAndSetSemantics { contentDescription = spoken }
    )
}

/**
 * Kleine Kennzahl im Kopf: Beschriftung, Betrag mit Vorzeichen (USDT ohne Einheit, verborgen
 * «•••», neutrale Schrift wie der Kurs einer Zeile) und darunter die Prozent-Änderung «↗ +1.23%»
 * ([PlPill], farbige Schrift in der Kursfarbe); ohne Wert «—». Vorgelesen als ein Satz
 * («7 days: up 2.10%, +123.45 USDT»; verborgen «Betrag verborgen»).
 */
@Composable
internal fun InsightFigure(
    label: String,
    spokenLabel: String,
    amount: Double?,
    percent: Double?,
    /** Einheit der Karte (Anzeigewährung oder USDT); [amount] und [rewardsUsd] kommen in USDT. */
    unit: CardUnit,
    modifier: Modifier = Modifier,
    /** Belohnungen im Zeitraum ([TodayChange.rewardsUsd]); > 0: «Belohnungen +X (nicht im Gewinn)» darunter. */
    rewardsUsd: Double = 0.0,
    /** [amount] schon in der Einheit der Karte ([PortfolioViewModel.costFx]), nicht in USDT. */
    amountInUnit: Boolean = false,
) {
    val context = LocalContext.current
    val validAmount = amount?.takeIf { it.isFinite() }?.let { if (amountInUnit) it else unit.of(it) }
    val validPercent = percent?.takeIf { it.isFinite() }
    val amountText = validAmount?.let { maskAmount(signedPlain(it)) } ?: "—"
    val spokenValue = validAmount?.let { spokenAmount(unit.signed(it)) }
    val figureSpoken = when {
        validPercent == null && spokenValue == null -> stringResource(R.string.portfolio_card_figure_none_a11y, spokenLabel)
        validPercent == null -> "$spokenLabel: $spokenValue"
        spokenValue == null -> "$spokenLabel: " + A11yText.change(context, validPercent)
        else -> "$spokenLabel: " + A11yText.change(context, validPercent) + ", " + spokenValue
    }
    // Belohnungen nur zu einer bekannten Zahl
    val shownRewards = rewardsUsd.takeIf { validAmount != null } ?: 0.0
    val rewardsLine = rewardsText(shownRewards, unit)
    val spoken = if (rewardsLine != null) "$figureSpoken. " + rewardsText(shownRewards, unit, spoken = true) else figureSpoken
    Column(modifier = modifier.clearAndSetSemantics { contentDescription = spoken }) {
        Text(
            label,
            style = MaterialTheme.typography.label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            amountText,
            style = MaterialTheme.typography.titleSmall.amountNumbers(),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            // Grosse Schrift: umbrechen statt abschneiden
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        PlPill(validPercent, modifier = Modifier.padding(top = 2.dp))
        rewardsLine?.let { RewardsNote(it) }
    }
}

/**
 * «Belohnungen +12.34 (nicht im Gewinn)» unter einer Zeitraum-Zahl: Belohnungen zählen als Zufluss, nicht als
 * Gewinn ([com.cryptochecker.app.domain.portfolio.PortfolioPeriodChange]). Betrag ohne Vorzeichen und Einheit
 * (das «+» steht im Text), verborgen «•••»; nichts bei 0.
 */
@Composable
internal fun rewardsText(rewardsUsd: Double, unit: CardUnit, spoken: Boolean = false): String? {
    if (!rewardsUsd.isFinite() || rewardsUsd <= 0.0 || PortfolioFormat.isZero(rewardsUsd)) return null
    val plain = PriceFormat.valueWithCurrency(unit.of(rewardsUsd), unit.code)
    // Vorgelesen mit Einheit bzw. «Betrag verborgen»
    val amount = if (spoken) spokenAmount(plain) else maskAmount(plain.removeSuffix(" " + unit.code))
    return stringResource(R.string.portfolio_period_rewards, amount)
}

/** Zeile [rewardsText] klein und grau. */
@Composable
internal fun RewardsNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.amountNumbers(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp)
    )
}

/** «+1’234.56» / «−12.00» ohne Einheit (die Kennzahlen stehen unter dem Gesamtwert, Einheit wie dort). */
private fun signedPlain(value: Double): String =
    PortfolioFormat.signedUsdt(value).removeSuffix(" " + PortfolioFormat.USDT)

/**
 * Einheit der Gesamtkarte: die Anzeigewährung mit dem Faktor [rate] (USDT → Währung: Devisenkurs × USDT/USD,
 * [PortfolioViewModel.displayFxRate]), sonst USDT mit Kurs 1. Beträge kommen in USDT und werden mit [of] umgerechnet.
 */
internal class CardUnit(val code: String, private val rate: Double) {
    fun of(usdt: Double): Double = usdt * rate
    /** Gross umgerechnet (dann «≈ … USDT» darunter). */
    val converted: Boolean get() = code != PortfolioFormat.USDT
    /** «+1’234.56 CHF» / «−12.00 CHF» / «0.00 CHF». */
    fun signed(value: Double): String = signedPlain(value) + " " + code
}

/**
 * Anzeigewährung mit gültigem Faktor — sonst USDT. Auch USD: USDT gilt dort seinen Marktkurs USDT/USD (Faktor = 1 ×
 * USDT/USD, ohne Messung 1 mit «USDT = 1 angenommen»), nicht mehr still 1:1. Wie iOS `PortfolioCardUnit`.
 */
internal fun cardUnit(currency: String, fxRate: Double?): CardUnit =
    fxRate?.takeIf { currency != PortfolioFormat.USDT && it > 0.0 && it.isFinite() }
        ?.let { CardUnit(currency, it) } ?: CardUnit(PortfolioFormat.USDT, 1.0)
