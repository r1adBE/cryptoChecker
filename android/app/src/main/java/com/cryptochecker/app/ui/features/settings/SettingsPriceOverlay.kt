package com.cryptochecker.app.ui.features.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.components.SwitchRow

// «Aktualisierung» › Preis-Overlay (nur Android): Schalter unter «dauerhafte Kurs-Mitteilung».

/**
 * Schalter «Preis-Overlay» (Standard aus): wirkt nur mit der dauerhaften Kurs-Mitteilung ([ongoing]); ohne die
 * Berechtigung «Über anderen Apps einblenden» ([permitted]) führt Einschalten zur Android-Einstellung und darunter steht
 * ein Knopf dorthin. Das Overlay selbst: `PriceOverlay`.
 */
@Composable
internal fun PriceOverlayRow(enabled: Boolean, ongoing: Boolean, permitted: Boolean, onToggle: (Boolean) -> Unit) {
    val context = LocalContext.current
    val ongoingTitle = stringResource(R.string.settings_ongoing_notifications)
    SwitchRow(
        title = stringResource(R.string.settings_price_overlay),
        subtitle = if (ongoing) stringResource(R.string.settings_price_overlay_hint)
        else stringResource(R.string.settings_price_overlay_needs, ongoingTitle),
        checked = enabled && ongoing,
        enabled = ongoing,
        onCheckedChange = { on ->
            onToggle(on)
            if (on && !permitted) openOverlayPermission(context)
        },
    )
    if (enabled && ongoing && !permitted) {
        FilledTonalButton(onClick = { openOverlayPermission(context) }, modifier = Modifier.padding(bottom = 8.dp)) {
            Text(stringResource(R.string.settings_price_overlay_grant))
        }
    }
}

/** Android-Einstellung «Über anderen Apps einblenden» für diese App. */
internal fun openOverlayPermission(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, ("package:" + context.packageName).toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
