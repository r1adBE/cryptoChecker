@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.watchlist

import com.cryptochecker.app.ui.components.LocalRowHighlight
import androidx.compose.foundation.background
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.offset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.R

/**
 * ⚡-Chip in der Zeile eines Paars mit aktiven Signalen, beschriftet mit der Kurzform von «Was bewegt sich mit?»
 * ([R.string.watch_action_why_short]); Tipp öffnet das Blatt «Was bewegt sich mit?».
 * Build 46: Tippfläche 48 dp hoch — sichtbar bleibt der 28-dp-Chip; nach aussen meldet der Knopf nur die
 * Chip-Höhe (Zeile wächst nicht), die Tippfläche ragt je 10 dp darüber und darunter, nie seitlich über den
 * Chip hinaus. Reicht die Breite nicht für «Was bewegt sich mit?», bleibt nur der ⚡ (das Paar geht vor, [RowInfo]).
 */
@Composable
internal fun ActivityBolt(onClick: () -> Unit, modifier: Modifier = Modifier) {
    // Weiss (Textfarbe); in der Themenfarbe nur, solange der ⚡-Chip gewählt ist
    val tint = if (LocalRowHighlight.current.activity) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurface
    // Screenreader: «Ungewöhnliche Aktivität, Was bewegt sich mit?»
    val label = stringResource(R.string.activity_indicator) + ", " + stringResource(R.string.why_action)
    // Druck-Rückmeldung nur auf dem sichtbaren Chip (nicht auf der höheren Tippfläche)
    val interaction = remember { MutableInteractionSource() }
    val indication = LocalIndication.current
    Layout(
        content = {
            Icon(
                painterResource(R.drawable.ic_bolt),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(WHY_ICON_SIZE)
            )
            // Kurzform im Chip (eine Zeile neben dem Paar), die volle Frage liest der Screenreader
            Text(
                text = stringResource(R.string.watch_action_why_short),
                style = MaterialTheme.typography.labelMedium,
                color = tint,
                maxLines = 1,
            )
        },
        modifier = modifier
            // Gemeldete Höhe = Chip; die Tippfläche darüber/darunter liegt ausserhalb (Treffer zählen trotzdem)
            .layout { measurable, constraints ->
                val extra = WHY_TOUCH_EXTRA.roundToPx()
                val placeable = measurable.measure(constraints.offset(vertical = 2 * extra))
                layout(placeable.width, (placeable.height - 2 * extra).coerceAtLeast(0)) {
                    placeable.place(0, -extra)
                }
            }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                onClick { onClick(); true }
            }
            .padding(vertical = WHY_TOUCH_EXTRA)
            .heightIn(min = WHY_CHIP_HEIGHT)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.12f))
            .indication(interaction, indication)
            .padding(horizontal = Spacing.sm)
    ) { measurables, constraints ->
        // ⚡ immer; «Was bewegt sich mit?» nur, wenn es ganz in die angebotene Breite passt
        val icon = measurables[0].measure(constraints.copy(minWidth = 0))
        val text = measurables[1].measure(Constraints(maxHeight = constraints.maxHeight))
        val gap = WHY_GAP.roundToPx()
        val showText = icon.width + gap + text.width <= constraints.maxWidth
        val width = if (showText) icon.width + gap + text.width else icon.width
        val height = maxOf(icon.height, if (showText) text.height else 0, constraints.minHeight)
        layout(width.coerceAtLeast(constraints.minWidth), height) {
            icon.placeRelative(0, (height - icon.height) / 2)
            if (showText) text.placeRelative(icon.width + gap, (height - text.height) / 2)
        }
    }
}

/** Höhe des sichtbaren «Was bewegt sich mit?»-Chips. */
private val WHY_CHIP_HEIGHT = 28.dp

/** Tippfläche über und unter dem Chip: 28 + 2 × 10 = 48 dp. */
private val WHY_TOUCH_EXTRA = 10.dp
private val WHY_ICON_SIZE = 14.dp
private val WHY_GAP = 2.dp

/** Breite des Chips nur mit ⚡ (Platz, den das Paar in [RowInfo] mindestens frei lässt). */
internal val WHY_CHIP_ICON_ONLY_WIDTH = WHY_ICON_SIZE + Spacing.sm * 2

/** Platzhalter → Inhalt und «Details»: Dauer der Überblendung bzw. Grössenänderung. */
internal const val SWAP_MILLIS = 220
