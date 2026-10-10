package com.cryptochecker.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R

/**
 * Knopf, der etwas auf- bzw. zuklappt («Warum?», «Details anzeigen», «Indikatoren einblenden»,
 * «Liste zeigen»): Text in der Themenfarbe mit Pfeil nach unten (zu) bzw. oben (offen) — wie die
 * Abschnitte «Einordnung»/«Daten». Kein «→»: das stünde für einen Seitenwechsel.
 * Wie `ExpandToggleLabel` (iOS).
 */
@Composable
fun ExpandToggleButton(text: String, expanded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // Rechts-nach-links ist der Pfeil gespiegelt (zeigt nach links): Drehung umkehren
    val turn = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    TextButton(onClick = onClick, modifier = modifier) {
        Text(text)
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            modifier = Modifier
                .padding(start = 2.dp)
                .size(18.dp)
                .rotate((if (expanded) -90f else 90f) * turn)
        )
    }
}
