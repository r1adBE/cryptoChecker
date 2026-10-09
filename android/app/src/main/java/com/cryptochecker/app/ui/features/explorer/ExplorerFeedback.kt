package com.cryptochecker.app.ui.features.explorer

import androidx.compose.ui.platform.LocalResources
import androidx.compose.foundation.layout.*
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.cryptochecker.app.R

/**
 * Rückmeldungen als Snackbar statt Text, der das Layout verschiebt: Paar übernommen bzw. schon
 * da, DEX-Pool, mehrere Paare. Mit «Ansehen» zur Merkliste. Neue Paare haben Meldungen an —
 * die Erlaubnis wird jetzt gefragt, nicht beim Start.
 */
@Composable
internal fun ExplorerFeedback(
    snackbar: SnackbarHostState,
    addToWatchlistState: AddToWatchlistState?,
    dex: DexUi,
    bulkAddState: BulkAddState?,
    onOpenWatchlist: () -> Unit,
    onAddToWatchlistMessageShown: () -> Unit,
    onBulkAddMessageShown: () -> Unit,
) {
    // Texte über LocalResources (Lint: kein getString über LocalContext in Compose)
    val resources = LocalResources.current
    val requestNotifications = com.cryptochecker.app.ui.components.rememberNotificationPermissionRequest()
    val viewLabel = stringResource(R.string.action_view)
    val addedText = stringResource(R.string.explorer_added_to_watchlist)
    val alreadyText = stringResource(R.string.explorer_already_in_watchlist)
    val noPairText = stringResource(R.string.explorer_no_pair_selected)
    LaunchedEffect(addToWatchlistState) {
        val state = addToWatchlistState ?: return@LaunchedEffect
        if (state == AddToWatchlistState.ADDED) requestNotifications()
        // Erst nach dem Schliessen zurücksetzen — sonst bricht der neue
        // Schlüssel die laufende Snackbar sofort ab.
        try {
            val result = snackbar.showSnackbar(
                message = when (state) {
                    AddToWatchlistState.ADDED -> addedText
                    AddToWatchlistState.ALREADY_IN_LIST -> alreadyText
                    AddToWatchlistState.NO_PAIR_SELECTED -> noPairText
                },
                actionLabel = if (state == AddToWatchlistState.NO_PAIR_SELECTED) null else viewLabel,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) onOpenWatchlist()
        } finally {
            onAddToWatchlistMessageShown()
        }
    }
    // DEX-Rückmeldungen ebenfalls als Snackbar
    val dexMessage = dex.message
    val dexAddedText = (dexMessage as? DexMessage.Added)?.let { stringResource(R.string.dex_added, it.symbol) }
    val dexAlreadyText = (dexMessage as? DexMessage.AlreadyInList)?.let { stringResource(R.string.dex_already, it.symbol) }
    val dexFailedText = stringResource(R.string.something_went_wrong)
    LaunchedEffect(dexMessage) {
        val text = when (dexMessage) {
            is DexMessage.Added -> dexAddedText
            is DexMessage.AlreadyInList -> dexAlreadyText
            DexMessage.Failed -> dexFailedText
            else -> null
        } ?: return@LaunchedEffect
        if (dexMessage is DexMessage.Added) requestNotifications()
        try {
            val result = snackbar.showSnackbar(
                message = text,
                actionLabel = if (dexMessage is DexMessage.Failed) null else viewLabel,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) onOpenWatchlist()
        } finally {
            dex.onMessageShown()
        }
    }

    LaunchedEffect(bulkAddState) {
        val state = bulkAddState?.takeIf { !it.running } ?: return@LaunchedEffect
        if (state.added > 0) requestNotifications()
        try {
            val result = snackbar.showSnackbar(
                message = resources.getQuantityString(R.plurals.explorer_bulk_added, state.added, state.added, state.skipped),
                actionLabel = viewLabel,
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) onOpenWatchlist()
        } finally {
            onBulkAddMessageShown()
        }
    }
}
