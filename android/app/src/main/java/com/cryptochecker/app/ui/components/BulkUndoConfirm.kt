package com.cryptochecker.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.UiTestTags

/**
 * Rückfrage vor «Rückgängig» nach «Mehrere Paare auf einmal»: Antippen von «Rückgängig» merkt nur die neu angelegten
 * Paare ([request]); erst «Entfernen» ([remove]) gibt sie an den Undo-Weg weiter, «Behalten» ([keep]) — ebenso Zurück
 * oder Tippen neben den Dialog — verwirft sie. Wie iOS `BulkUndoConfirm`.
 */
@Stable
class BulkUndoConfirm(initial: List<Long>? = null) {
    /** Paare, für die der Dialog gerade fragt; null = kein Dialog. */
    var pending: List<Long>? by mutableStateOf(initial?.takeIf { it.isNotEmpty() })
        private set

    /** «Rückgängig» getippt: Dialog für [ids] öffnen (ohne Paare keiner). */
    fun request(ids: List<Long>) {
        if (ids.isNotEmpty()) pending = ids
    }

    /** «Behalten»: nichts entfernen. */
    fun keep() {
        pending = null
    }

    /** «Entfernen»: Dialog zu, dann [undo] mit den gemerkten Paaren (genau einmal). */
    fun remove(undo: (List<Long>) -> Unit) {
        val ids = pending ?: return
        pending = null
        undo(ids)
    }

    companion object {
        /** Offener Dialog übersteht Drehen und Prozessende (Ids als LongArray). */
        val Saver: Saver<BulkUndoConfirm, LongArray> = Saver(
            save = { it.pending?.toLongArray() },
            restore = { BulkUndoConfirm(it.toList()) },
        )
    }
}

/** [BulkUndoConfirm] dieses Bildschirms. */
@Composable
fun rememberBulkUndoConfirm(): BulkUndoConfirm = rememberSaveable(saver = BulkUndoConfirm.Saver) { BulkUndoConfirm() }

/**
 * Dialog «746 Paare wieder entfernen?» mit dem Hinweis, dass Paare mit inzwischen gesetztem Alarm bleiben. «Behalten»
 * schliesst ohne Folgen, «Entfernen» (Fehlerfarbe) spielt die Erfolgs-Haptik und ruft [onRemove] mit den neu
 * angelegten Paaren — die Haptik also erst beim Entfernen, nicht beim Öffnen (wie iOS `ExplorerScreen`).
 */
@Composable
fun BulkUndoConfirmDialog(confirm: BulkUndoConfirm, onRemove: (List<Long>) -> Unit) {
    val haptics = rememberHaptics()
    val ids = confirm.pending ?: return
    AlertDialog(
        onDismissRequest = confirm::keep,
        title = { Text(pluralStringResource(R.plurals.bulk_undo_confirm_title, ids.size, ids.size)) },
        text = { DialogText { Text(stringResource(R.string.bulk_undo_confirm_text)) } },
        confirmButton = {
            TextButton(
                onClick = {
                    haptics.play(HapticEvent.SUCCESS)
                    confirm.remove(onRemove)
                },
                modifier = Modifier.testTag(UiTestTags.BULK_UNDO_REMOVE),
            ) {
                Text(
                    stringResource(R.string.watchlist_remove_not_traded_action),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = confirm::keep, modifier = Modifier.testTag(UiTestTags.BULK_UNDO_KEEP)) {
                Text(stringResource(R.string.action_keep))
            }
        },
    )
}
