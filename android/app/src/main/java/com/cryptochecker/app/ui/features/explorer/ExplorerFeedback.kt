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
import com.cryptochecker.app.domain.watch.WatchlistLimits
import com.cryptochecker.app.util.LocaleNumbers
import com.cryptochecker.app.ui.components.HapticEvent
import com.cryptochecker.app.ui.components.rememberHaptics
import com.cryptochecker.app.ui.components.rememberOneShotGate
import com.cryptochecker.app.ui.components.showAppSnackbar
import com.cryptochecker.app.ui.theme.SnackbarKind

/**
 * Rückmeldungen als Snackbar statt Text, der das Layout verschiebt: Paar übernommen bzw. schon
 * da, DEX-Pool, mehrere Paare. Mit «Ansehen» zur Merkliste. Neue Paare haben Meldungen an —
 * die Erlaubnis wird jetzt gefragt, nicht beim Start. Übernommen = Erfolg, gesperrt, voll oder DEX-Suche
 * gescheitert = Fehler, sonst Info ([SnackbarKind]).
 */
@Composable
internal fun ExplorerFeedback(
    snackbar: SnackbarHostState,
    addResult: AddResult?,
    dex: DexUi,
    bulkAddResult: BulkAddResult?,
    onOpenWatchlist: () -> Unit,
    onAddResultShown: () -> Unit,
    onBulkAddResultShown: () -> Unit,
    /** «Rückgängig» nach dem Hinzufügen mehrerer Paare (die neu angelegten Ids). */
    onUndoBulk: (List<Long>) -> Unit = {},
) {
    // Texte über LocalResources (Lint: kein getString über LocalContext in Compose)
    val resources = LocalResources.current
    // Neue Paare: erst kurz erklären, dann die System-Abfrage (nur wenn das System wirklich fragen würde)
    val requestNotifications = com.cryptochecker.app.ui.components.rememberNotificationPermissionRequest(
        com.cryptochecker.app.ui.components.NotificationAsk.PAIRS_ADDED
    )
    val viewLabel = stringResource(R.string.action_view)
    val undoLabel = stringResource(R.string.action_undo)
    val addedText = stringResource(R.string.explorer_added_to_watchlist)
    val alreadyText = stringResource(R.string.explorer_already_in_watchlist)
    val noPairText = stringResource(R.string.explorer_no_pair_selected)
    val fullText = watchlistFullText()
    // Haptik wie iOS: übernommen = Erfolg; schon da, kein Paar gewählt oder Merkliste voll = Warnung
    val haptics = rememberHaptics()
    // Nach Drehen/Dunkelmodus hält das ViewModel die Meldung evtl. noch: dann nicht nochmals zeigen, nur abräumen
    val addGate = rememberOneShotGate()
    val dexGate = rememberOneShotGate()
    val bulkGate = rememberOneShotGate()
    // Paar nicht übernommen, weil die Merkliste gesperrt ist (WatchlistStorageLock): kurzer Hinweis mit Warn-Haptik
    com.cryptochecker.app.ui.components.WatchlistLockedHint {
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showAppSnackbar(it, SnackbarKind.ERROR)
    }
    LaunchedEffect(addResult) {
        if (!addGate.claim(addResult)) {
            if (addResult != null) onAddResultShown()
            return@LaunchedEffect
        }
        val state = addResult ?: return@LaunchedEffect
        haptics.play(if (state == AddResult.ADDED) HapticEvent.SUCCESS else HapticEvent.WARNING)
        if (state == AddResult.ADDED) requestNotifications()
        // Erst nach dem Schliessen zurücksetzen — sonst bricht der neue
        // Schlüssel die laufende Snackbar sofort ab.
        try {
            val result = snackbar.showAppSnackbar(
                text = when (state) {
                    AddResult.ADDED -> addedText
                    AddResult.ALREADY_IN_LIST -> alreadyText
                    AddResult.NO_PAIR_SELECTED -> noPairText
                    AddResult.WATCHLIST_FULL -> fullText
                },
                kind = when (state) {
                    AddResult.ADDED -> SnackbarKind.SUCCESS
                    AddResult.WATCHLIST_FULL -> SnackbarKind.ERROR
                    else -> SnackbarKind.INFO
                },
                actionLabel = if (state == AddResult.NO_PAIR_SELECTED) null else viewLabel,
                // Volle Merkliste: Zeit zum Lesen (auch mit Screenreader), «Ansehen» führt zum Aufräumen
                duration = if (state == AddResult.WATCHLIST_FULL) SnackbarDuration.Long else SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) onOpenWatchlist()
        } finally {
            onAddResultShown()
        }
    }
    // DEX-Rückmeldungen ebenfalls als Snackbar
    val dexMessage = dex.message
    val dexAddedText = (dexMessage as? DexMessage.Added)?.let { stringResource(R.string.dex_added, it.symbol) }
    val dexAlreadyText = (dexMessage as? DexMessage.AlreadyInList)?.let { stringResource(R.string.dex_already, it.symbol) }
    val dexFailedText = (dexMessage as? DexMessage.Failed)?.let {
        stringResource(
            when (it.kind) {
                DexFailure.OFFLINE -> R.string.watch_error_offline
                DexFailure.UNREACHABLE -> R.string.error_market_unreachable_short
                DexFailure.OTHER -> R.string.something_went_wrong
            }
        )
    }
    val retryLabel = stringResource(R.string.action_retry)
    LaunchedEffect(dexMessage) {
        val text = when (dexMessage) {
            is DexMessage.Added -> dexAddedText
            is DexMessage.AlreadyInList -> dexAlreadyText
            is DexMessage.Failed -> dexFailedText
            else -> null
        } ?: run {
            // Ohne Snackbar (z. B. keine Treffer, steht im Bildschirm): nicht abräumen
            dexGate.claim(null)
            return@LaunchedEffect
        }
        if (!dexGate.claim(dexMessage)) {
            dex.onMessageShown()
            return@LaunchedEffect
        }
        if (dexMessage is DexMessage.Added) requestNotifications()
        // Gescheiterte Suche ohne Haptik (wie iOS) — nur Aufnehmen gibt Rückmeldung
        if (dexMessage !is DexMessage.Failed) {
            haptics.play(if (dexMessage is DexMessage.Added) HapticEvent.SUCCESS else HapticEvent.WARNING)
        }
        try {
            val result = snackbar.showAppSnackbar(
                text = text,
                kind = when (dexMessage) {
                    is DexMessage.Added -> SnackbarKind.SUCCESS
                    is DexMessage.Failed -> SnackbarKind.ERROR
                    else -> SnackbarKind.INFO
                },
                actionLabel = if (dexMessage is DexMessage.Failed) retryLabel else viewLabel,
                // Mit «Erneut versuchen» länger stehen lassen (Zeit zum Antippen, auch mit Screenreader)
                duration = if (dexMessage is DexMessage.Failed) SnackbarDuration.Long else SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                // «Erneut versuchen»: denselben Begriff noch einmal suchen
                if (dexMessage is DexMessage.Failed) dex.onSearch(dexMessage.query) else onOpenWatchlist()
            }
        } finally {
            dex.onMessageShown()
        }
    }

    LaunchedEffect(bulkAddResult) {
        val done = bulkAddResult?.takeIf { !it.running }
        if (!bulkGate.claim(done)) {
            if (done != null) onBulkAddResultShown()
            return@LaunchedEffect
        }
        val state = done ?: return@LaunchedEffect
        if (state.added > 0) requestNotifications()
        haptics.play(if (state.added > 0) HapticEvent.SUCCESS else HapticEvent.WARNING)
        // Neue Paare angelegt: «Rückgängig» (genau diese wieder entfernen); sonst «Ansehen»
        val undoable = state.addedIds.isNotEmpty()
        try {
            val result = snackbar.showAppSnackbar(
                text = bulkAddedText(resources, state),
                kind = bulkAddedKind(state),
                actionLabel = if (undoable) undoLabel else viewLabel,
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) {
                if (undoable) onUndoBulk(state.addedIds) else onOpenWatchlist()
            }
        } finally {
            onBulkAddResultShown()
        }
    }
}

/** «3 hinzugefügt, 2 bereits vorhanden.» — bei voller Merkliste dazu, wie viele nicht dazukamen und warum. */
internal fun bulkAddedText(resources: android.content.res.Resources, state: BulkAddResult): String {
    val added = resources.getQuantityString(R.plurals.explorer_bulk_added, state.added, state.added, state.skipped)
    if (state.refused <= 0) return added
    val refused = resources.getQuantityString(
        R.plurals.explorer_bulk_not_added_limit, state.refused,
        state.refused, LocaleNumbers.integer(WatchlistLimits.MAX_PAIRS, grouping = true),
    )
    return "$added $refused"
}

/** Art der Meldung nach «Mehrere Paare»: etwas angelegt = Erfolg, nichts angelegt weil voll = Fehler, sonst Info. */
internal fun bulkAddedKind(state: BulkAddResult): SnackbarKind = when {
    state.added > 0 -> SnackbarKind.SUCCESS
    state.refused > 0 -> SnackbarKind.ERROR
    else -> SnackbarKind.INFO
}

/** «Deine Merkliste kann höchstens 2’000 Paare enthalten.» (Zahl im Format der App-Sprache). */
@Composable
internal fun watchlistFullText(): String =
    stringResource(R.string.watchlist_limit_reached, LocaleNumbers.integer(WatchlistLimits.MAX_PAIRS, grouping = true))
