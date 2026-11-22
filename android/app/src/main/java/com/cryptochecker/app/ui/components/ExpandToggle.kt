package com.cryptochecker.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.cryptochecker.resources.R

/**
 * Knopf, der etwas auf- bzw. zuklappt («Was bewegt sich mit?», «Details anzeigen», «Indikatoren einblenden»,
 * «Liste zeigen»): Text in der Themenfarbe mit Pfeil nach unten (zu) bzw. oben (offen) — wie die
 * Abschnitte «Einordnung»/«Daten». Kein «→»: das stünde für einen Seitenwechsel. Sagt den Zustand
 * («aufgeklappt»/«zugeklappt») selbst an. Wie `ExpandToggleLabel` mit `expandToggleState` (iOS).
 */
@Composable
fun ExpandToggleButton(text: String, expanded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // Zustand für den Screenreader («aufgeklappt»/«zugeklappt»): der Pfeil selbst ist stumm, und nicht jeder Text
    // wechselt mit (z. B. «Gebühren» beim Erfassen). Wie iOS `expandToggleState`.
    val state = stringResource(if (expanded) R.string.a11y_expanded else R.string.a11y_collapsed)
    TextButton(onClick = onClick, modifier = modifier.semantics { stateDescription = state }) {
        Text(text)
        ExpandChevronIcon(expanded = expanded, size = 18.dp, modifier = Modifier.padding(start = 2.dp))
    }
}

/**
 * Der Auf-/Zuklapp-Pfeil aller aufklappbaren Bereiche (Wertverlauf, «Mehr», Erweitert, Explorer,
 * Markt-Abschnitte): nach unten (zu) bzw. oben (offen), immer in der Themenfarbe, dekorativ.
 * Rechts-nach-links ist das Symbol gespiegelt (zeigt nach links) — die Drehung wird umgekehrt,
 * damit es auch dort nach unten bzw. oben zeigt. Wie der `chevron.down` in iOS.
 */
@Composable
fun ExpandChevronIcon(expanded: Boolean, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    val turn = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    Icon(
        painterResource(R.drawable.ic_chevron_right),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .size(size)
            .rotate((if (expanded) -90f else 90f) * turn)
    )
}
