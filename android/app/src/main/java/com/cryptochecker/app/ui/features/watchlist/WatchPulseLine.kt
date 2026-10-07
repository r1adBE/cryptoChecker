package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.watch.WatchPulse
import com.cryptochecker.app.ui.components.RollingNumberText
import com.cryptochecker.app.ui.theme.PriceColors
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.PriceFormat

/**
 * Schmale Puls-Zeile ganz oben in der Merkliste: «▲ 7 steigen · ▼ 3 fallen · Ø ▲ +1.80% 24h»
 * als kleine Pillen im Stil der Prozent-Pille der Zeilen (Veränderung über 24 Stunden). Zahlen rollen bei Änderungen.
 * Leere Zähler (0) werden weggelassen. Haben gezeigte Paare mit Kurs keinen 24-h-Wert,
 * folgt klein «· 510 ohne 24h-Wert» ([WatchPulse.missing]). Der Screenreader liest einen Satz
 * ([R.string.a11y_watchlist_pulse]) mit dem Durchschnitt in Worten, ggf. plus die fehlenden.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WatchPulseLine(pulse: WatchPulse, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Ø wie die Pille: Pfeil nach dem Vorzeichen, Farbe nach Richtung, grau bei 0.00%
    val average = PriceFormat.changePercent(pulse.average)
        ?.let { "${PriceFormat.changeArrow(pulse.average)} $it" }
        ?: "0.00%"
    val averageColor = if (pulse.flat) MaterialTheme.colorScheme.onSurfaceVariant
    else PriceColors.forChange(pulse.average)
    val spokenPulse = stringResource(
        R.string.a11y_watchlist_pulse,
        pluralStringResource(R.plurals.a11y_watchlist_pulse_rising, pulse.up, pulse.up),
        pluralStringResource(R.plurals.a11y_watchlist_pulse_falling, pulse.down, pulse.down),
        A11yText.change24h(context, pulse.average),
    )
    // Ehrlich: Paare mit Kurs, aber ohne 24-h-Wert, werden mitgesagt
    val spoken = if (pulse.missing > 0) {
        stringResource(
            R.string.a11y_watchlist_pulse_combined,
            spokenPulse,
            pluralStringResource(R.plurals.a11y_watchlist_pulse_missing, pulse.missing, pulse.missing),
        )
    } else spokenPulse
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp)
            .clearAndSetSemantics { contentDescription = spoken }
    ) {
        if (pulse.up > 0) {
            PulsePill(
                text = "▲ " + pluralStringResource(R.plurals.watchlist_pulse_up, pulse.up, pulse.up),
                value = pulse.up.toDouble(),
                color = PriceColors.up,
            )
        }
        if (pulse.down > 0) {
            PulsePill(
                text = "▼ " + pluralStringResource(R.plurals.watchlist_pulse_down, pulse.down, pulse.down),
                value = pulse.down.toDouble(),
                color = PriceColors.down,
            )
        }
        PulsePill(
            // Zeitraum wie neben den Pillen der Zeilen: «Ø ▲ +1.80% 24h»
            text = stringResource(R.string.watchlist_pulse_avg, average) + " " +
                stringResource(R.string.widget_range_short_24h),
            value = pulse.average,
            color = averageColor,
        )
        // Klein und grau, ohne Pille: «· 510 ohne 24h-Wert»
        if (pulse.missing > 0) {
            Text(
                text = "· " + pluralStringResource(R.plurals.watchlist_pulse_missing, pulse.missing, pulse.missing),
                style = MaterialTheme.typography.labelSmall.amountNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.align(Alignment.CenterVertically).padding(vertical = 2.dp),
            )
        }
    }
}

/** Pille wie [ChangePill]: Kursfarbe auf 14 % Tönung, rund. */
@Composable
private fun PulsePill(text: String, value: Double, color: Color) {
    RollingNumberText(
        text = text,
        value = value,
        style = MaterialTheme.typography.labelMedium.amountNumbers(),
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}
