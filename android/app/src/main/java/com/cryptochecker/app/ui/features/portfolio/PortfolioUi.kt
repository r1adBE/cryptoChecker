package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.PortfolioInsights
import com.cryptochecker.app.ui.components.changePill
import com.cryptochecker.app.ui.theme.PriceColors
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.PriceFormat
import java.text.DateFormat
import java.text.DecimalFormat
import java.util.Date
import kotlin.math.abs

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
        return sign + DecimalFormat("#,##0.00").format(abs(value)) + " " + USDT
    }

    /** «+12.34%», bei praktisch 0 «0.00%». */
    fun signedPercent(value: Double): String = PriceFormat.changePercent(value) ?: PriceFormat.zeroPercent()

    fun amount(value: Double, coin: String): String = "${PriceFormat.amount(value)} $coin"

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

/** Prozent als Pille wie in der Merkliste. */
@Composable
internal fun PlPill(percent: Double?, modifier: Modifier = Modifier) {
    val color = plColor(percent?.takeUnless { abs(it) < 0.005 })
    // Screenreader: Richtungswort statt Vorzeichen
    val spoken = percent?.let { A11yText.change(LocalContext.current, it) }
    // Pfeil wie in der Merkliste (folgt dem Vorzeichen, nie dem Farbtausch)
    val arrow = percent?.takeUnless { PortfolioFormat.isZero(it) }?.let { PriceFormat.changeArrow(it) }.orEmpty()
    Text(
        text = percent?.let { p ->
            PortfolioFormat.signedPercent(p).let { if (arrow.isEmpty()) it else "$arrow $it" }
        } ?: "—",
        style = MaterialTheme.typography.labelMedium.amountNumbers(),
        fontWeight = FontWeight.SemiBold,
        color = color,
        maxLines = 1,
        modifier = modifier
            .then(if (spoken != null) Modifier.clearAndSetSemantics { contentDescription = spoken } else Modifier)
            .changePill(color)
    )
}

/** Kleine Kennzahl: Beschriftung oben, Wert darunter. */
@Composable
internal fun Metric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(modifier = modifier) {
        // Zwei Zeilen: Beschriftungen wie «Gewinn/Verlust, noch nicht verkauft» nicht abschneiden
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            value,
            style = MaterialTheme.typography.titleSmall.amountNumbers(),
            fontWeight = FontWeight.Medium,
            color = valueColor,
            // Grosse Schrift: Betrag bricht um statt abgeschnitten zu werden
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
