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
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.ui.theme.Spacing

/**
 * Abschnittsüberschrift überall gleich: klein, fett, in Textfarbe, davor ein kurzer Strich in
 * der Themenfarbe ([sectionTitleMarker]) — Einstellungen («Allgemein», «Darstellung» …),
 * Portfolio («Gesamtwert», «Wertverlauf» …) und Markt («Jetzt», «Einordnung», «Daten» …).
 * Die Themenfarbe bleibt so Bedienbarem vorbehalten; der Strich gliedert, ohne zu färben.
 */
object SectionTitle {
    val style: TextStyle
        @Composable get() = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp)
    val color: Color
        @Composable get() = MaterialTheme.colorScheme.onSurface
}

/**
 * Kurzer senkrechter Strich in der Themenfarbe vor einer Abschnittsüberschrift (3 dp breit,
 * so hoch wie die Schrift, in Rechts-nach-links-Sprachen rechts). Als letztes Glied der
 * Modifier-Kette setzen, damit er sich an der Schrift ausrichtet und nicht am Aussenabstand.
 */
@Composable
fun Modifier.sectionTitleMarker(): Modifier {
    val color = MaterialTheme.colorScheme.primary
    return this
        .drawBehind {
            val w = 3.dp.toPx()
            val h = minOf(size.height, 14.sp.toPx())
            val x = if (layoutDirection == LayoutDirection.Ltr) 0f else size.width - w
            drawRoundRect(
                color = color,
                topLeft = Offset(x, (size.height - h) / 2f),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2f)
            )
        }
        .padding(start = 10.dp)
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
            // Überschrift wie überall: klein, fett, Strich in der Themenfarbe (keine Grossbuchstaben)
            Text(
                text = title,
                style = SectionTitle.style,
                color = SectionTitle.color,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp).sectionTitleMarker()
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

/**
 * Schalterzeile: die ganze Zeile ist antippbar, nicht nur der Schalter. [icon] (optional)
 * zeigt dasselbe Zeichen wie anderswo, z. B. das Auge der Kurs-Benachrichtigung in der Zeile.
 */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
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
        if (icon != null) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp).size(20.dp)
            )
        }
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
