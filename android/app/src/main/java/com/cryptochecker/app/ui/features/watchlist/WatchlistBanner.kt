@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import android.view.accessibility.AccessibilityManager
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.res.stringResource
import com.cryptochecker.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Banner unten in der Merkliste («BTC/USDT entfernt» mit «Rückgängig», «… wird jetzt
 * überwacht» mit «Alarm setzen», Favorit an/aus). Ein neues Banner ersetzt das alte; das
 * Löschen davor bleibt dann bestehen. Mit Screenreader bleibt ein Banner mit Aktion ggf.
 * länger stehen (Systemeinstellung «Zeit für Aktionen»).
 */
@Stable
internal class WatchlistBanner(
    /** Für den `SnackbarHost` des Bildschirms. */
    val host: SnackbarHostState,
    private val scope: CoroutineScope,
) {
    /** Jede Komposition neu gesetzt (Sprache, Screenreader können wechseln). */
    internal var accessibilityManager: androidx.compose.ui.platform.AccessibilityManager? = null
    internal var undoLabel: String = ""

    private var job: Job? = null

    /**
     * Banner mit Aktion [label] (z. B. «Alarm setzen») für [millis]; [onAction] beim Tippen darauf.
     * [onClosed] einmal, sobald das Banner weg ist (abgelaufen, weggewischt, ersetzt oder Aktion getippt) —
     * mit `true`, wenn die Aktion getippt wurde.
     */
    fun showAction(
        text: String,
        label: String,
        millis: Long,
        onClosed: ((actionPerformed: Boolean) -> Unit)? = null,
        onAction: () -> Unit,
    ) {
        job?.cancel()
        job = scope.launch {
            var performed = false
            try {
                host.currentSnackbarData?.dismiss()
                // Mit Screenreader ggf. länger (Systemeinstellung «Zeit für Aktionen»)
                val timeout = accessibilityManager?.calculateRecommendedTimeoutMillis(
                    millis, containsIcons = false, containsText = true, containsControls = true
                ) ?: millis
                val result = withTimeoutOrNull(timeout) {
                    host.showSnackbar(text, actionLabel = label, duration = SnackbarDuration.Indefinite)
                }
                performed = result == SnackbarResult.ActionPerformed
                if (performed) onAction()
            } finally {
                // Auch beim Ersetzen durch ein neues Banner (Job abgebrochen)
                onClosed?.invoke(performed)
            }
        }
    }

    /** Kurzes Banner; mit [onUndo] steht «Rückgängig» daneben ([UNDO_MILLIS]). [onClosed] wie bei [showAction]. */
    fun show(text: String, onClosed: ((actionPerformed: Boolean) -> Unit)? = null, onUndo: (() -> Unit)? = null) {
        if (onUndo == null) {
            job?.cancel()
            job = scope.launch {
                try {
                    host.currentSnackbarData?.dismiss()
                    host.showSnackbar(text, duration = SnackbarDuration.Short)
                } finally {
                    onClosed?.invoke(false)
                }
            }
        } else {
            showAction(text, undoLabel, UNDO_MILLIS, onClosed = onClosed, onAction = onUndo)
        }
    }
}

/** [WatchlistBanner] für diesen Bildschirm; läuft in [scope]. */
@Composable
internal fun rememberWatchlistBanner(scope: CoroutineScope): WatchlistBanner {
    val banner = remember { WatchlistBanner(SnackbarHostState(), scope) }
    banner.accessibilityManager = LocalAccessibilityManager.current
    banner.undoLabel = stringResource(R.string.action_undo)
    return banner
}

/** So lange bleibt «Rückgängig» nach dem Löschen per Wischen stehen (ohne Screenreader). */
private const val UNDO_MILLIS = 5_000L
