@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R

/**
 * Kleines ⚡ in der Zeile eines Paars mit aktiven Signalen; Tipp öffnet «Warum». Sichtbar 24 dp;
 * die Tippfläche erweitert Compose für klickbare Elemente unter 48 dp selbst auf die
 * Mindestgrösse (ViewConfiguration.minimumTouchTargetSize, «near hit»), ohne die Zeile höher zu
 * machen — ein knapper Tipp neben das ⚡ trifft also noch «Warum», nicht das Aktionsblatt.
 */
@Composable
internal fun ActivityBolt(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
    ) {
        Icon(
            painterResource(R.drawable.ic_bolt),
            contentDescription = stringResource(R.string.activity_indicator),
            // In der Themenfarbe (der ⚡-Chip im Kopf ist neutral, gewählt ebenfalls in der Themenfarbe)
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
    }
}

/** Platzhalter → Inhalt und «Details»: Dauer der Überblendung bzw. Grössenänderung. */
internal const val SWAP_MILLIS = 220
