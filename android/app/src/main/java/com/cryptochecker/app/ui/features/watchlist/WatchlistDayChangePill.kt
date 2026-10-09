package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.ui.components.ChangePill
import com.cryptochecker.app.util.ChangeBasisText

/**
 * Veränderung gemäss %-Basis (`WatchEntity.shownChange`, [LocalChangeView]): [ChangePill] und
 * daneben klein der Zeitraum «24h», «letzter Stand», «heute» oder «heute UTC». Ohne Bezug eine
 * graue Pille «—» ohne Pfeil; die Veränderung seit der letzten Abfrage nur bei der Basis
 * «Seit letzter Aktualisierung».
 * Screenreader: «up 2.30% in 24 hours» / «… today».
 */
@Composable
internal fun DayChangePill(change: Double?, modifier: Modifier = Modifier) {
    val value = change?.takeIf { it.isFinite() }
    val view = LocalChangeView.current
    val basis = view.basis
    val spoken = ChangeBasisText.spoken(LocalContext.current, basis, value)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .padding(top = 3.dp)
            .clearAndSetSemantics { contentDescription = spoken }
    ) {
        ChangePill(change = value, dashWhenMissing = true)
        if (view.showPeriod) Text(
            text = ChangeBasisText.shortLabel(basis),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}
