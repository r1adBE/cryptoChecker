// Block «Hinweise» unter der Gesamtkarte (Kursquellen, fehlende Coins, ungeprüfte Kurse …).
// Eigene Datei, weil die Karte selbst nur Beträge zeigt (Regel «Gesamtkarte ist ein Bericht») und
// alle Hinweise hier gesammelt werden.
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.components.ExpandChevronIcon
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.body

internal data class TotalHintItem(val text: String, val error: Boolean = false, val txId: Long? = null)

/**
 * Bereich «Hinweise» unter der Gesamtkarte (L-15), Build 46 zugeklappt: eine Zeile «Hinweise (n)» mit
 * Pfeil (48 dp hoch, Fehlerfarbe, wenn eine Zeile ein Fehler ist — veraltet, Wallet-Bestand); offen die
 * Zeilen ([TotalHint]) eng untereinander, eingerückt wie der Inhalt der Karte. Wie iOS `hints`.
 */
@Composable
internal fun TotalCardHints(hints: List<TotalHintItem>, expanded: Boolean, onToggle: () -> Unit, onShortfall: (Long) -> Unit) {
    if (hints.isEmpty()) return
    val anyError = hints.any { it.error }
    val expandedText = stringResource(R.string.a11y_expanded)
    val collapsedText = stringResource(R.string.a11y_collapsed)
    Column(modifier = Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.lg)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics { stateDescription = if (expanded) expandedText else collapsedText }
        ) {
            Text(
                stringResource(R.string.portfolio_card_hints_count, hints.size),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = if (anyError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f, fill = false)
            )
            ExpandChevronIcon(expanded = expanded, size = 16.dp, modifier = Modifier.padding(start = 2.dp))
        }
        if (expanded) {
            hints.forEach { hint ->
                TotalHint(
                    hint.text,
                    color = if (hint.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = hint.txId?.let { id -> Modifier.clickable { onShortfall(id) } } ?: Modifier,
                )
            }
        }
    }
}

/**
 * Eine Zeile im Bereich «Hinweise»: Lesegrösse ([body], 14 sp — ganze Sätze, aufgeklappt, mit Platz),
 * onSurfaceVariant bzw. [color]. Wie iOS `hint` (`.subheadline`).
 */
@Composable
private fun TotalHint(
    text: String,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.body,
        color = color,
        modifier = Modifier.padding(top = 2.dp).then(modifier)
    )
}
