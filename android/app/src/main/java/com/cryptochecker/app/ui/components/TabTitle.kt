package com.cryptochecker.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified

/**
 * Titel einer Tab-Seite (Markt, Portfolio, Einstellungen) in der Kopfzeile: davor dasselbe
 * Symbol wie im Tab ([iconRes], gleiche Ressource wie in `AppNavHost`), in der Titelfarbe und so
 * gross wie die Titelschrift (wächst mit der Schriftgrösse). Das Symbol ist für den Screenreader
 * nur dekorativ. Wie `TabTitle` (iOS).
 *
 * Die Schriftstärke ist fest halbfett (wie iOS `.headline`): Sie gehört zur Komponente, nicht zur
 * Seite.
 */
@Composable
fun TabTitle(text: String, @DrawableRes iconRes: Int) {
    val fontSize = LocalTextStyle.current.fontSize
    val iconSize = if (fontSize.isSpecified) with(LocalDensity.current) { fontSize.toDp() } else 24.dp
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Farbe: LocalContentColor der Kopfzeile = Titelfarbe
        Icon(painterResource(iconRes), contentDescription = null, modifier = Modifier.size(iconSize))
        Text(text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
