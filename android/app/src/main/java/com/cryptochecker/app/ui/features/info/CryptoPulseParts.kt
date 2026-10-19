// Bausteine der Karte «Crypto Pulse»: Lade-Platzhalter, Kopfzeile, Faktor- und Faktenzeilen, Coin-Änderungen,
// Kennzahl-Chips, Abschnittstitel und Textzeilen.
// Eigene Datei, weil die Karte selbst nur Zustand und Anordnung regelt (wie `CryptoPulseParts.swift`).
package com.cryptochecker.app.ui.features.info

import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.market.PulseBreadthNote
import com.cryptochecker.app.domain.market.PulseFactor
import com.cryptochecker.app.domain.market.PulseFactorKind
import com.cryptochecker.app.domain.market.PulseReport
import com.cryptochecker.app.notification.ActivityTexts
import com.cryptochecker.app.ui.components.ChangePillSize
import com.cryptochecker.app.ui.components.FactorRow
import com.cryptochecker.app.ui.components.PillTone
import com.cryptochecker.app.ui.components.SectionTitle
import com.cryptochecker.app.ui.components.SkeletonLine
import com.cryptochecker.app.ui.components.SkeletonPulse
import com.cryptochecker.app.ui.components.pillArrow
import com.cryptochecker.app.ui.components.pillContent
import com.cryptochecker.app.ui.components.pillTextStyle
import com.cryptochecker.app.ui.components.sectionTitleMarker
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.ui.theme.displayCompact
import com.cryptochecker.app.ui.theme.label
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.PriceFormat
import kotlin.math.abs

/**
 * Platzhalter in der Form von [PulseContent] (Überzeile steht schon darüber):
 * Schlagzeile (Heldengrösse [displayCompact]), Leitsatz über zwei Zeilen, Zeile mit drei Coin-Änderungen, Zeile mit
 * Stand und «Was bewegt sich mit?». Höhen aus der echten Typografie, damit beim Laden nichts springt.
 */
@Composable
internal fun PulseSkeleton() {
    SkeletonPulse(modifier = Modifier.fillMaxWidth()) {
        SkeletonLine(MaterialTheme.typography.displayCompact, Modifier.fillMaxWidth(0.55f))
        SkeletonLine(MaterialTheme.typography.bodyLarge, Modifier.padding(top = Spacing.xs).fillMaxWidth())
        SkeletonLine(MaterialTheme.typography.bodyLarge, Modifier.fillMaxWidth(0.7f))
        // Zeile «24h  BTC ↘ −0.3%  ETH ↗ +1.2%  SOL ↗ +0.8%»: Zeitraum und drei Coin-Angaben
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        ) {
            SkeletonLine(MaterialTheme.typography.label, Modifier.width(28.dp))
            repeat(3) {
                // Gleiche Höhe wie [PulseCoinChange], nur ohne Inhalt
                SkeletonLine(MaterialTheme.typography.titleSmall.amountNumbers(), Modifier.width(84.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            SkeletonLine(MaterialTheme.typography.label, Modifier.width(96.dp))
            // Platz des «Was bewegt sich mit?»-Knopfs (Textknopf samt Mindest-Tippfläche 48 dp)
            Spacer(modifier = Modifier.weight(1f).height(48.dp))
        }
    }
}

/**
 * Eine Zeile der Checkliste, z. B. «! Funding … +0.045%». Volumen, Fear & Greed
 * und Gas stehen in eigenen Karten des Tabs (siehe [CryptoPulse.factors]).
 */
@Composable
internal fun PulseFactorRow(report: PulseReport, factor: PulseFactor) {
    when (factor.kind) {
        PulseFactorKind.FUNDING -> report.fundingPercent?.let { percent ->
            FactorRow(
                mark = factor.mark,
                title = stringResource(R.string.factor_funding),
                value = ActivityTexts.percent(percent, 3)
            )
        }
    }
}

/** Kleine Überzeile «Was gerade auffällt». */
@Composable
internal fun PulseOverline(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.pulse_now_title),
        style = SectionTitle.style,
        color = SectionTitle.color,
        modifier = modifier.sectionTitleMarker()
    )
}

/** Zeile «Bezeichnung … Wert» unter den Coin-Änderungen. */
@Composable
internal fun PulseFactRow(label: String, value: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        value()
    }
}

internal fun breadthNoteRes(note: PulseBreadthNote): Int = when (note) {
    PulseBreadthNote.BROAD_UP -> R.string.pulse_breadth_note_broad_up
    PulseBreadthNote.BROAD_DOWN -> R.string.pulse_breadth_note_broad_down
    PulseBreadthNote.NARROW_UP -> R.string.pulse_breadth_note_narrow_up
    PulseBreadthNote.NARROW_DOWN -> R.string.pulse_breadth_note_narrow_down
}

/** «BTC ↗ +2.8%»: Symbol in Textfarbe, Änderung farbig ohne Pille (Pfeil und Vorzeichen wie die Widgets). */
@Composable
internal fun PulseCoinChange(symbol: String, change: Double) {
    val tone = PillTone.forValue(change) { abs(it) < 0.05 }
    val arrow = pillArrow(tone)?.let { "$it " }.orEmpty()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            symbol,
            style = pillTextStyle(ChangePillSize.MEDIUM),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
        Text(
            arrow + coinChangeText(change),
            style = pillTextStyle(ChangePillSize.MEDIUM),
            fontWeight = FontWeight.SemiBold,
            color = pillContent(tone),
            maxLines = 1,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

/** Neutraler Chip (grauer Rand, keine Bedeutungsfarbe). */
@Composable
internal fun PulseMetricChip(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
            .padding(horizontal = Spacing.sm, vertical = 4.dp)
    )
}

/**
 * «+3.2%» bzw. «−1.4%» (echtes Minus U+2212) oder «0.0%» bei praktisch unverändert (unter 0.05 %),
 * wie `PulseAssetChip` (iOS) über [PriceFormat.signedPercent].
 */
private fun coinChangeText(change: Double): String = PriceFormat.signedPercent(change, 1)

/** «gestiegen um 2.8%» mit einer Nachkommastelle wie angezeigt; flach = «unverändert». */
internal fun coinSpoken(context: Context, change: Double): String =
    A11yText.change(context, if (abs(change) < 0.05) 0.0 else change, decimals = 1)

@Composable
internal fun PulseSectionTitle(text: String) {
    Text(
        text,
        style = SectionTitle.style,
        color = SectionTitle.color,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).semantics { heading() }.sectionTitleMarker()
    )
}

@Composable
internal fun PulseLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))
}
