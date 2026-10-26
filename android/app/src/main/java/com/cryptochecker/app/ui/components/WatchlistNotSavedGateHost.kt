package com.cryptochecker.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.WatchlistNotSavedGate

/**
 * F-08: Frage «Änderungen werden gerade nicht gespeichert …» vor der ersten Änderung der Merkliste je
 * App-Lauf ([WatchlistNotSavedGate]). Einmal in der MainActivity; sammelt nur, solange die App sichtbar ist — sonst fragt
 * der Schreibweg nicht (Hintergrund nie). Wie iOS `WatchlistNotSavedGatePresenter`.
 */
@Composable
fun WatchlistNotSavedGateHost() {
    val request by WatchlistNotSavedGate.request.collectAsStateWithLifecycle()
    if (request == null) return
    AlertDialog(
        onDismissRequest = { WatchlistNotSavedGate.answer(proceed = false) },
        text = { DialogText { Text(stringResource(R.string.watchlist_not_saved_confirm_message)) } },
        confirmButton = {
            TextButton(onClick = { WatchlistNotSavedGate.answer(proceed = true) }) {
                Text(stringResource(R.string.watchlist_not_saved_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = { WatchlistNotSavedGate.answer(proceed = false) }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
