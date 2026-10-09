package com.cryptochecker.app.ui.components

import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.ui.theme.Spacing

/**
 * Abschnittsüberschrift überall gleich: klein, fett, in der Themenfarbe — Einstellungen
 * («Allgemein», «Darstellung» …), Portfolio («Gesamtwert», «Wertverlauf» …) und Markt
 * («Jetzt», «Einordnung», «Daten» …). Die einzige Ausnahme von «Themenfarbe nur für
 * Bedienbares»: klein und fett, deshalb nicht mit Knöpfen zu verwechseln.
 */
object SectionTitle {
    val style: TextStyle
        @Composable get() = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp)
    val color: Color
        @Composable get() = MaterialTheme.colorScheme.primary
}

/**
 * Abschnitt mit kleiner Überschrift und Inhalt auf einer abgerundeten Fläche.
 * Einheitlich für Einstellungen, Aktionen und Übersichten.
 */
@Composable
fun SectionCard(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth().padding(bottom = Spacing.lg)) {
        if (!title.isNullOrEmpty()) {
            // Überschrift wie überall: klein, fett, Themenfarbe (keine Grossbuchstaben)
            Text(
                text = title,
                style = SectionTitle.style,
                color = SectionTitle.color,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )
        }
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), content = content)
        }
    }
}

/** Schalterzeile: die ganze Zeile ist antippbar, nicht nur der Schalter. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .padding(vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // Klick läuft über die Zeile (toggleable), sonst würde doppelt geschaltet.
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
