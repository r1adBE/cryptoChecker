package com.cryptochecker.app.ui.features.alarms

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.WatchEntity

/**
 * «Neuer Alarm» ohne Paar: erst das Paar wählen, dann dessen Alarm-Seite (öffnet ohne Alarm gleich den Editor).
 * Eine Auswahl für die leere Alarm-Übersicht und die Glocke der Merkliste ohne Alarm
 * ([com.cryptochecker.app.domain.watch.AlarmBellRoute.PICK_PAIR]); iOS: `confirmationDialog` mit denselben Zeilen.
 */
@Composable
internal fun AlarmPairPicker(
    watches: List<WatchEntity>,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.alarms_add)) },
        text = {
            LazyColumn {
                items(watches, key = { it.id }) { watch ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(watch.id) }
                            .padding(vertical = 12.dp)
                    ) {
                        Text(watch.displayName, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            watch.marketName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
