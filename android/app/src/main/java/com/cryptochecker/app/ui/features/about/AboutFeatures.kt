package com.cryptochecker.app.ui.features.about

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.theme.Spacing

/**
 * Was die App kann (Runde 32: früher im Begrüßungsblatt, das es nicht mehr gibt — ein neuer
 * Nutzer landet direkt in der Starter-Auswahl): Kurztext und drei Zeilen (Beobachten,
 * Alarmieren, Verstehen — je mit kurzer Frage darüber). Steht in Einstellungen › Über,
 * wie `AboutFeatures` in `AboutContent.swift`.
 */
@Composable
fun AboutFeatures(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.welcome_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FeatureLine(R.string.welcome_watch_q, R.string.welcome_watch, Modifier.padding(top = Spacing.md))
        FeatureLine(R.string.welcome_alert_q, R.string.welcome_alert, Modifier.padding(top = Spacing.sm))
        FeatureLine(R.string.welcome_understand_q, R.string.welcome_understand, Modifier.padding(top = Spacing.sm))
    }
}

/** Kleine Frage obenauf («Was passiert?»), darunter der Text. Für den Screenreader ein Element. */
@Composable
private fun FeatureLine(@StringRes question: Int, @StringRes text: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier.semantics(mergeDescendants = true) { }) {
        Text(
            stringResource(question),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            stringResource(text),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}
