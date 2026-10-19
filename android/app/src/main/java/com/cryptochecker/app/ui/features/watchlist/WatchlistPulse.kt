package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.layout.Arrangement
import com.cryptochecker.app.ui.theme.label
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.domain.watch.WatchPulse
import com.cryptochecker.app.ui.components.PillTone
import com.cryptochecker.app.ui.components.TonePill
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.util.ChangeBasisText
import com.cryptochecker.app.util.PriceFormat

/**
 * Schmale Puls-Zeile ganz oben in der Merkliste: «↗ 7 steigen · ↘ 3 fallen · Ø +1.80% 24h»
 * als farbige Schrift ohne Pillen wie die Prozent-Änderung der Zeilen (Veränderung über 24 Stunden):
 * Zähler in Steigend- bzw. Fallend-Farbe mit schrägem Pfeil, der Durchschnitt in normaler Textfarbe
 * mit Vorzeichen (keine Richtung, wie im Markt-Puls), der Zeitraum klein und grau dahinter.
 * Zahlen rollen bei Änderungen.
 * Leere Zähler (0) werden weggelassen. Haben gezeigte Paare mit Kurs keinen 24-h-Wert,
 * folgt klein «· 510 ohne 24h-Wert» ([WatchPulse.missing]). Der Screenreader liest einen Satz
 * ([R.string.a11y_watchlist_pulse]) mit dem Durchschnitt in Worten, ggf. plus die fehlenden.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WatchPulseLine(pulse: WatchPulse, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Zeitraum wie die Änderungen der Zeilen (%-Basis): «24h», «heute», «heute UTC»
    val showPeriod = LocalChangeView.current.showPeriod
    val basis = LocalChangeView.current.basis
    // Ø mit Vorzeichen wie die Änderung der Zeilen, ohne Pfeil (0.00% ohne Vorzeichen)
    val average = PriceFormat.changePercent(pulse.average) ?: PriceFormat.zeroPercent()
    val spokenPulse = stringResource(
        R.string.a11y_watchlist_pulse,
        pluralStringResource(R.plurals.a11y_watchlist_pulse_rising, pulse.up, pulse.up),
        pluralStringResource(R.plurals.a11y_watchlist_pulse_falling, pulse.down, pulse.down),
        ChangeBasisText.spoken(context, basis, pulse.average),
    )
    // Ehrlich: Paare mit Kurs, aber ohne 24-h-Wert, werden mitgesagt
    val spoken = if (pulse.missing > 0) {
        stringResource(
            R.string.a11y_watchlist_pulse_combined,
            spokenPulse,
            pluralStringResource(
                if (basis == ChangeBasis.ROLLING_24H) R.plurals.a11y_watchlist_pulse_missing
                else R.plurals.a11y_watchlist_pulse_missing_day,
                pulse.missing, pulse.missing
            ),
        )
    } else spokenPulse
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp)
            .clearAndSetSemantics { contentDescription = spoken }
    ) {
        if (pulse.up > 0) {
            TonePill(
                text = PriceFormat.ARROW_UP + " " + pluralStringResource(R.plurals.watchlist_pulse_up, pulse.up, pulse.up),
                tone = PillTone.UP,
                rollingValue = pulse.up.toDouble(),
            )
        }
        if (pulse.down > 0) {
            TonePill(
                text = PriceFormat.ARROW_DOWN + " " + pluralStringResource(R.plurals.watchlist_pulse_down, pulse.down, pulse.down),
                tone = PillTone.DOWN,
                rollingValue = pulse.down.toDouble(),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TonePill(
                text = stringResource(R.string.watchlist_pulse_avg, average),
                tone = PillTone.AVERAGE,
                rollingValue = pulse.average,
            )
            // Zeitraum klein und grau wie neben den Änderungen der Zeilen: «Ø +1.80% 24h»
            if (showPeriod) {
                Text(
                    text = ChangeBasisText.shortLabel(basis),
                    style = MaterialTheme.typography.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        // Klein und grau: «· 510 ohne 24h-Wert»
        if (pulse.missing > 0) {
            Text(
                text = "· " + pluralStringResource(
                    if (basis == ChangeBasis.ROLLING_24H) R.plurals.watchlist_pulse_missing
                    else R.plurals.watchlist_pulse_missing_day,
                    pulse.missing, pulse.missing
                ),
                style = MaterialTheme.typography.label.amountNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.align(Alignment.CenterVertically).padding(vertical = 2.dp),
            )
        }
    }
}
