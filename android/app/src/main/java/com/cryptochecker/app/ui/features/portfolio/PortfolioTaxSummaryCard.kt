// Karte «Jahresübersicht» der Arbeitsunterlage (Summen je Zeile, Freigrenzen-Status, Hinweise).
// Eigene Datei, weil sie eine eigenständige Anzeige ist, die der Export-Dialog nur einbettet
// (wie `PortfolioTaxSummaryCard.swift`).
package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.TaxLimitStatus
import com.cryptochecker.app.domain.portfolio.TaxLine
import com.cryptochecker.app.domain.portfolio.TaxSummaryLine
import com.cryptochecker.app.domain.portfolio.TaxSummaryTexts
import com.cryptochecker.app.domain.portfolio.TaxTemplate
import com.cryptochecker.app.domain.portfolio.TaxYearSummary
import com.cryptochecker.app.domain.portfolio.noteText
import com.cryptochecker.app.domain.portfolio.taxOrientationLabel
import com.cryptochecker.app.domain.portfolio.wealthCoinLabel
import com.cryptochecker.app.domain.portfolio.wealthCoinNote
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.util.PriceFormat
import java.math.BigDecimal

/**
 * Karte «Jahresübersicht» der Vorlage: Tatsachen-Posten (Summen in der Währung der Vorlage, ohne Devisenkurse USDT), dann
 * der Block «Orientierung – keine Steuerberatung, keine Veranlagung» mit jedem Posten als «Orientierung: …» und Erklärung
 * der Grenzen — Sätze, Grenzen und Freibeträge stehen nur dort.
 * [summary] null = wird gerechnet.
 */
@Composable
internal fun TaxSummaryCard(summary: TaxYearSummary?, texts: TaxSummaryTexts, methodNote: String? = null) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(texts.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(
                texts.disclaimer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = if (methodNote == null) 4.dp else 0.dp)
            )
            // Vorlage rechnet mit einer anderen Methode als die App («Vorlage rechnet FIFO, nicht Ø»)
            if (methodNote != null) {
                Text(
                    methodNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            if (summary == null) {
                Text(stringResource(R.string.tax_de_loading), style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            val fx = summary.fxAvailable
            val unit = if (fx) summary.currency else "USDT"
            fun money(l: TaxSummaryLine): String? {
                val v = if (fx) l.target else l.usdt
                if (v != null) return PriceFormat.valueWithCurrency(v.toDouble(), unit)
                // Grenzen und Freibeträge gibt es nur in der Währung der Vorlage
                return l.target?.let { PriceFormat.valueWithCurrency(it.toDouble(), summary.currency) }
            }
            var orientationShown = false
            for (l in summary.lines) {
                val plain = texts.labels[l.line].orEmpty()
                val label = if (l.line.orientation) taxOrientationLabel(texts.orientationItem, texts.orientation, plain) else plain
                if (l.line.orientation && !orientationShown) {
                    orientationShown = true
                    Text(
                        texts.orientationHeading.ifEmpty { texts.orientation },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                val notes = listOfNotNull(
                    texts.incomplete.takeIf { l.incomplete },
                    texts.noFx.takeIf { l.noFx },
                    texts.notCalculated.takeIf { l.notCalculated && it.isNotEmpty() },
                    l.noteText(texts),
                ).joinToString(", ")
                val status = l.status
                if (status != null) {
                    TaxStatusLine(label, status, l.target, summary.currency, texts)
                } else {
                    SummaryLine(label, money(l), bold = l.line == TaxLine.NET || l.line == TaxLine.SHORT_NET || l.line == TaxLine.NEW_NET)
                }
                if (notes.isNotEmpty()) {
                    Text(notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // CH Vermögenswert: je Coin Bestand und Wert, ohne Wert «nicht gerechnet – Grund» (wie die CSV)
                if (l.line == TaxLine.CH_WEALTH_VALUE) {
                    summary.wealth?.rows?.forEach { r ->
                        val v = if (fx) r.valueTarget else r.valueUsdt
                        SummaryLine(
                            texts.wealthCoinLabel(r.coin, PriceFormat.amount(r.amount)),
                            v?.let { PriceFormat.valueWithCurrency(it.toDouble(), unit) } ?: texts.wealthCoinNote(r),
                            indent = true,
                        )
                    }
                }
            }
            if (summary.template == TaxTemplate.DE) {
                Text(
                    stringResource(R.string.tax_de_limit_scope),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String?, bold: Boolean = false, indent: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth().padding(start = if (indent) 12.dp else 0.dp, top = 1.dp, bottom = 1.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(end = 8.dp))
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

/** Grenze: «Grenze …: unter der Grenze» und darunter die Erklärung (mit dem Betrag der Grenze). */
@Composable
private fun TaxStatusLine(label: String, status: TaxLimitStatus, limit: BigDecimal?, currency: String, texts: TaxSummaryTexts) {
    val amount = limit?.let { PriceFormat.valueWithCurrency(it.toDouble(), currency) }.orEmpty()
    val (statusText, explain) = when (status) {
        TaxLimitStatus.BELOW -> texts.below to stringResource(R.string.tax_de_explain_below, amount)
        TaxLimitStatus.REACHED -> texts.reached to stringResource(R.string.tax_de_explain_reached, amount)
        TaxLimitStatus.UNKNOWN -> texts.unknown to stringResource(R.string.tax_de_explain_unknown)
        TaxLimitStatus.LOSS -> texts.loss.ifEmpty { texts.below } to stringResource(R.string.tax_de_explain_loss)
    }
    Text(
        "$label: $statusText",
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
        color = if (status == TaxLimitStatus.REACHED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 4.dp)
    )
    Text(explain, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
