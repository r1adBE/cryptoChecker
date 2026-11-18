package com.cryptochecker.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.domain.watch.StatusPillTone
import com.cryptochecker.resources.R

/**
 * Zeichen vorne in einer Status-Pille: wie eine Ampel, aber auch ohne Farbe lesbar. Aktuell ✓, veraltet !,
 * Fehler ✕; neutral (offline, lädt, nichts gehandelt) bleibt der ruhige Punkt. Dieselben Zeichen wie im Bericht
 * «Letzte Aktualisierung». Nur Schmuck: der Satz daneben trägt die Aussage. Wie iOS `StatusMark`.
 */
@Composable
fun StatusMark(tone: StatusPillTone, color: Color, modifier: Modifier = Modifier) {
    val icon = when (tone) {
        StatusPillTone.NORMAL -> R.drawable.ic_status_ok
        StatusPillTone.WARNING -> R.drawable.ic_status_partial
        StatusPillTone.ERROR -> R.drawable.ic_status_failed
        StatusPillTone.NEUTRAL -> null
    }
    if (icon == null) {
        Box(modifier.size(8.dp).clip(RoundedCornerShape(50)).background(color))
    } else {
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = modifier.size(14.dp))
    }
}

/** Zeichen zum Ton der [DataStatusPill]: veraltet ist eine Warnung (!) wie «x veraltet» in der Merkliste. */
fun DataStatusTone.mark(): StatusPillTone = when (this) {
    DataStatusTone.FRESH -> StatusPillTone.NORMAL
    DataStatusTone.NEUTRAL -> StatusPillTone.NEUTRAL
    DataStatusTone.WARN -> StatusPillTone.WARNING
}
