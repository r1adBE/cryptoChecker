package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.layout.Column
import com.cryptochecker.app.ui.theme.label
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.PortfolioInsights
import com.cryptochecker.app.ui.components.ChangePill
import com.cryptochecker.app.ui.components.ChangePillSize
import com.cryptochecker.app.ui.components.PillTone
import com.cryptochecker.app.ui.components.TonePill
import com.cryptochecker.app.ui.components.pillArrow
import com.cryptochecker.app.ui.components.withSmallQuote
import com.cryptochecker.app.ui.theme.PriceColors
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.util.BidiText
import com.cryptochecker.app.util.PriceFormat
import java.math.BigDecimal
import java.text.DateFormat
import java.text.DecimalFormat
import java.util.Date
import kotlin.math.abs
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.text.style.TextAlign

/** Gemeinsame Bausteine und Formate für das Portfolio. */
internal object PortfolioFormat {
    const val USDT = "USDT"

    /** Beträge unter einem halben Cent gelten als 0 (grau, ohne Vorzeichen). */
    fun isZero(value: Double): Boolean = abs(value) < 0.005

    fun usdt(value: Double): String = PriceFormat.valueWithCurrency(value, USDT)

    fun price(value: Double?): String = PriceFormat.priceWithCurrency(value, USDT)

    /** «+1’234.56 USDT» / «−12.00 USDT» / «0.00 USDT». */
    fun signedUsdt(value: Double): String {
        val sign = when {
            isZero(value) -> ""
            value > 0 -> "+"
            else -> "−"
        }
        // Vorzeichen und Zahl als links-nach-rechts-Insel (RTL: «+» bleibt vor der Zahl) — wie iOS
        return BidiText.ltr(sign + DecimalFormat("#,##0.00").format(abs(value))) + " " + USDT
    }

    /**
     * Realisiert mit unbekanntem Teil ([incomplete], Verkäufe ohne Kaufpreis/Verkaufspreis):
     * nur unbekannt «—», sonst «+12.00 USDT (unvollständig)» ([incompleteLabel]). Vollständig: wie [signedUsdt]. Wie iOS.
     */
    fun realizedText(value: Double, incomplete: Boolean, incompleteLabel: String): String = when {
        !incomplete -> signedUsdt(value)
        isZero(value) -> "—"
        else -> signedUsdt(value) + " (" + incompleteLabel + ")"
    }

    /** Realisiert anzeigen: Betrag ungleich 0 oder ein Teil unbekannt. */
    fun showRealized(value: Double, incomplete: Boolean): Boolean = incomplete || !isZero(value)

    /** «+12.34%», bei praktisch 0 «0.00%». */
    fun signedPercent(value: Double): String = PriceFormat.changePercent(value) ?: PriceFormat.zeroPercent()

    fun amount(value: Double, coin: String): String = "${PriceFormat.amount(value)} $coin"

    /** Exakte Menge ohne Rundung («0.00000001 BTC»), Tausendertrennung wie [amount]. */
    fun amount(value: BigDecimal, coin: String): String = "${PriceFormat.amount(value)} $coin"

    fun date(millis: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
}

/**
 * «Beträge verbergen»: gesetzt von Portfolio-Tab und Detailansicht (Einstellung bzw. Auge im Kopf).
 * Beträge und Werte werden zu «•••», Prozente bleiben.
 */
internal val LocalHidePortfolioAmounts = compositionLocalOf { false }

/** [text] oder «•••», wenn Beträge verborgen sind. */
@Composable
@ReadOnlyComposable
internal fun maskAmount(text: String): String = PortfolioInsights.mask(text, LocalHidePortfolioAmounts.current)

/** Für den Screenreader: [text] oder «Betrag verborgen» statt «•••». */
@Composable
@ReadOnlyComposable
internal fun spokenAmount(text: String): String =
    if (LocalHidePortfolioAmounts.current) stringResource(R.string.a11y_amount_hidden) else text

/** Grün/Rot für ±, grau bei 0 oder unbekannt. */
@Composable
@ReadOnlyComposable
internal fun plColor(value: Double?): Color = when {
    value == null || PortfolioFormat.isZero(value) -> MaterialTheme.colorScheme.onSurfaceVariant
    value > 0 -> PriceColors.up
    else -> PriceColors.down
}

/**
 * Prozent wie überall ([ChangePill]): farbige Schrift ohne Pille in der Kursfarbe, Pfeil und
 * Vorzeichen («↘ −0.25%»); 0 grau «0.00%», ohne Wert «—».
 */
@Composable
internal fun PlPill(percent: Double?, modifier: Modifier = Modifier, size: ChangePillSize = ChangePillSize.SMALL) {
    ChangePill(change = percent, modifier = modifier, dashWhenMissing = true, size = size)
}

/** Richtung eines Betrags oder Prozents für [TonePill]: Null-Regel des Portfolios (halber Cent). */
internal fun plTone(value: Double?): PillTone = PillTone.forValue(value, PortfolioFormat::isZero)

/**
 * Betrag mit Vorzeichen («+12.40 USDT», «−3.00 CHF») als farbige Schrift ohne Pille in der
 * Kursfarbe mit Pfeil davor («↗ +12.40 USDT») — wie die Prozent-Änderung; [text] ist schon formatiert
 * (ggf. «•••»), [value] gibt Richtung und Pfeil (0/unbekannt grau ohne Pfeil).
 */
@Composable
internal fun PlValuePill(
    text: String,
    value: Double?,
    modifier: Modifier = Modifier,
    size: ChangePillSize = ChangePillSize.SMALL,
) {
    val tone = plTone(value)
    TonePill(text = text, tone = tone, modifier = modifier, size = size, arrow = pillArrow(tone))
}

/**
 * Prozent als farbige Schrift mit Pfeil und Vorzeichen («↗ +4.20%», «↘ −1.20%»), Farbe und Pfeil
 * nach dem Betrag [value] (Null-Regel des Portfolios) — für Stellen, die Betrag oder Prozent zeigen.
 * Praktisch 0: grau «0.00%» ohne Pfeil. Wie `PortfolioPercentPill` (iOS).
 */
@Composable
internal fun PlPercentPill(
    percent: Double,
    value: Double?,
    modifier: Modifier = Modifier,
    size: ChangePillSize = ChangePillSize.SMALL,
) {
    val tone = plTone(value)
    val text = PriceFormat.changePercent(percent)
    TonePill(
        text = text ?: PriceFormat.zeroPercent(),
        tone = tone,
        modifier = modifier,
        size = size,
        arrow = if (text != null) pillArrow(tone) else null,
    )
}

/** Kleine Kennzahl: Beschriftung oben, Wert darunter. */
@Composable
internal fun Metric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    /** Gesetzt: Wert als farbige Schrift in dieser Bedeutung (Gewinn/Verlust), statt Text in [valueColor]. */
    valueTone: PillTone? = null,
    /** Währung am Ende klein und grau wie in der Merkliste ([withSmallQuote]); aus für Mengen («0.5 BTC»). */
    smallQuote: Boolean = true,
) {
    Column(modifier = modifier) {
        // Zwei Zeilen: Beschriftungen wie «Gewinn/Verlust, noch nicht verkauft» nicht abschneiden
        Text(
            label,
            style = MaterialTheme.typography.label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (valueTone != null) {
            TonePill(
                text = value,
                tone = valueTone,
                size = ChangePillSize.MEDIUM,
                arrow = pillArrow(valueTone),
                modifier = Modifier.padding(top = 2.dp)
            )
        } else {
            Text(
                if (smallQuote) withSmallQuote(value) else AnnotatedString(value),
                style = MaterialTheme.typography.titleSmall.amountNumbers(),
                fontWeight = FontWeight.Medium,
                color = valueColor,
                // Grosse Schrift: Betrag bricht um statt abgeschnitten zu werden
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun PortfolioHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
internal fun PortfolioDisclaimer() {
    Text(
        stringResource(R.string.portfolio_disclaimer),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)
    )
}
