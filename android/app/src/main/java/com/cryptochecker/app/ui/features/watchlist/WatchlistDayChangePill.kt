package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.layout.Row
import com.cryptochecker.app.ui.theme.label
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.components.ChangePill
import com.cryptochecker.app.ui.components.ChangePillSize
import com.cryptochecker.app.util.ChangeBasisText

/**
 * Veränderung gemäss %-Basis (`WatchEntity.shownChange`, [LocalChangeView]): [ChangePill] und
 * davor klein und grau der Zeitraum «24h», «letzter Stand», «heute» oder «heute UTC». Ohne Bezug
 * ein graues «—»; die Veränderung seit der letzten Abfrage nur bei der Basis
 * «Seit letzter Aktualisierung».
 * Screenreader: «up 2.30% in 24 hours» / «… today»; mit [mark] «Mark», mit [note] danach der Hinweis.
 * [size]: klein in der Zeile, mittel im Aktionsblatt (wie iOS).
 */
@Composable
internal fun DayChangePill(
    change: Double?,
    modifier: Modifier = Modifier,
    note: String? = null,
    /** Grau ohne Kursfarbe (deaktiviertes Paar). */
    neutral: Boolean = false,
    size: ChangePillSize = ChangePillSize.SMALL,
    /** Änderung Mark gegen Mark ([com.cryptochecker.app.domain.watch.ChangeBasisMath.showsMark], Hyperliquid): «Mark» davor. */
    mark: Boolean = false,
    /**
     * Wert aus den Kerzen einer anderen Börse ([com.cryptochecker.app.domain.watch.DayChange.rowChangeMarker]):
     * kleines «*» in onSurfaceVariant dahinter, nur sichtbar — gesprochen wird die Quelle im Zeilensatz,
     * erklärt im Aktionsblatt (kein eigenes Tippziel).
     */
    marker: Boolean = false,
) {
    val value = change?.takeIf { it.isFinite() }
    val view = LocalChangeView.current
    val basis = view.basis
    // [note]: z. B. «Veränderung aus Binance-Kerzen» — der Screenreader nennt ihn mit
    val markText = if (mark) stringResource(R.string.watch_change_mark) else null
    val spoken = listOfNotNull(ChangeBasisText.spoken(LocalContext.current, basis, value), markText, note).joinToString(", ")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .padding(top = 3.dp)
            .clearAndSetSemantics { contentDescription = spoken }
    ) {
        // Zeitraum klein und grau vor der Änderung: «24h ↘ −0.25%» — die Zahlen stehen so bündig rechts
        if (view.showPeriod) {
            Text(
                text = ChangeBasisText.shortLabel(basis),
                style = MaterialTheme.typography.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(end = 4.dp)
            )
        }
        // Andere Reihe als der Kurs (Mittelkurs): «24h Mark ↗ +1.20%»
        if (markText != null) {
            Text(
                text = markText,
                style = MaterialTheme.typography.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(end = 4.dp)
            )
        }
        ChangePill(change = value, dashWhenMissing = true, neutral = neutral, size = size)
        if (marker) {
            Text(
                text = CHANGE_SOURCE_MARKER,
                style = MaterialTheme.typography.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                // Hochgestellt wie eine Fussnote, dicht an der Zahl
                modifier = Modifier.align(Alignment.Top).padding(start = 1.dp)
            )
        }
    }
}

/** Marker der %-Änderung aus fremden Kerzen (iOS `WatchlistDayChangePill.sourceMarker`). */
internal const val CHANGE_SOURCE_MARKER = "*"
