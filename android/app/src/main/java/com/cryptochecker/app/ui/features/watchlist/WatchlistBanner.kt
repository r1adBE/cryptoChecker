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
import com.cryptochecker.app.ui.components.HapticEvent
import com.cryptochecker.app.ui.components.HapticPlayer
import com.cryptochecker.app.ui.components.rememberHaptics
import com.cryptochecker.app.ui.components.showAppSnackbar
import com.cryptochecker.app.ui.theme.SnackbarKind

/**
 * Banner unten in der Merkliste («BTC/USDT entfernt» mit «Rückgängig», «… wird jetzt
 * überwacht» mit «Alarm setzen», Favorit an/aus). Jedes Banner hat eine Art ([SnackbarKind], Standard Info: kein
 * farbiger Rahmen), gezeichnet von `AppSnackbarHost`. Ein neues Banner ersetzt das alte; weitere einzelne
 * Löschungen fasst der Bildschirm zu einem Sammel-Banner zusammen ([RemovedBatch]). Mit Screenreader bleibt ein Banner mit Aktion ggf.
 * länger stehen (Systemeinstellung «Zeit für Aktionen»).
 */
@Stable
internal class WatchlistBanner(
    /** Für den `AppSnackbarHost` des Bildschirms. */
    val host: SnackbarHostState,
    private val scope: CoroutineScope,
) {
    /** Jede Komposition neu gesetzt (Sprache, Screenreader können wechseln). */
    internal var accessibilityManager: androidx.compose.ui.platform.AccessibilityManager? = null
    internal var undoLabel: String = ""
    /** «Rückgängig» getippt: Erfolgs-Haptik (wie iOS); null in Tests ohne Bildschirm. */
    internal var haptics: HapticPlayer? = null

    private var job: Job? = null

    /** Dauerhinweis ([showResting]) als Text und Aktion, oder null. */
    private var resting: Pair<String, String>? = null

    /**
     * Dauerhinweis (Merkliste nur leer im Arbeitsspeicher): steht ohne Ablauf, bis [clearResting]. Ein anderes Banner
     * verdrängt ihn nur; ist jenes abgelaufen, weggewischt oder seine Aktion getippt, kommt er wieder. Die Aktion
     * [label] («Schliessen») blendet ihn bis zum nächsten Banner aus.
     */
    fun showResting(text: String, label: String) {
        resting = text to label
        job?.cancel()
        job = scope.launch {
            host.currentSnackbarData?.dismiss()
            // Die Aktion ist selbst «Schliessen»: kein zusätzliches ✕
            host.showAppSnackbar(
                text, actionLabel = label, duration = SnackbarDuration.Indefinite, withDismissAction = false,
            )
        }
    }

    /** Dauerhinweis aufheben; steht er gerade, verschwindet er. */
    fun clearResting() {
        val text = resting?.first ?: return
        resting = null
        host.currentSnackbarData?.takeIf { it.visuals.message == text }?.dismiss()
    }

    /** Nach einem anderen Banner: Dauerhinweis zurück — nur, wenn inzwischen kein neueres Banner dran ist. */
    private fun restoreRestingAfter(finished: Job?) {
        val (text, label) = resting ?: return
        if (job === finished) showResting(text, label)
    }

    /**
     * Banner mit Aktion [label] (z. B. «Alarm setzen») für [millis]; [onAction] beim Tippen darauf.
     * [onClosed] einmal, sobald das Banner weg ist (abgelaufen, weggewischt, ersetzt oder Aktion getippt) —
     * mit `true`, wenn die Aktion getippt wurde. [kind]: Art der Meldung (Rahmen, Symbol).
     */
    fun showAction(
        text: String,
        label: String,
        millis: Long,
        kind: SnackbarKind = SnackbarKind.INFO,
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
                    host.showAppSnackbar(text, kind, actionLabel = label, duration = SnackbarDuration.Indefinite)
                }
                performed = result == SnackbarResult.ActionPerformed
                if (performed) onAction()
            } finally {
                // Auch beim Ersetzen durch ein neues Banner (Job abgebrochen)
                onClosed?.invoke(performed)
            }
            // Nur bei regulärem Ende (abgebrochen = ersetzt, dann kommt der Code hier nicht an)
            restoreRestingAfter(coroutineContext[Job])
        }
    }

    /**
     * Kurzes Banner der Art [kind]; mit [onUndo] steht «Rückgängig» daneben ([undoMillis], Standard [UNDO_MILLIS]).
     * [undoHaptic]: Erfolgs-Haptik beim Tippen auf «Rückgängig» — aus, wenn [onUndo] erst nachfragt (die Haptik kommt
     * dann beim Bestätigen). [onClosed] wie bei [showAction].
     */
    fun show(
        text: String,
        kind: SnackbarKind = SnackbarKind.INFO,
        onClosed: ((actionPerformed: Boolean) -> Unit)? = null,
        undoMillis: Long = UNDO_MILLIS,
        undoHaptic: Boolean = true,
        onUndo: (() -> Unit)? = null,
    ) {
        if (onUndo == null) {
            job?.cancel()
            job = scope.launch {
                try {
                    host.currentSnackbarData?.dismiss()
                    host.showAppSnackbar(text, kind, duration = SnackbarDuration.Short)
                } finally {
                    onClosed?.invoke(false)
                }
                restoreRestingAfter(coroutineContext[Job])
            }
        } else {
            showAction(text, undoLabel, undoMillis, kind, onClosed = onClosed) {
                if (undoHaptic) haptics?.play(HapticEvent.SUCCESS)
                onUndo()
            }
        }
    }
}

/** [WatchlistBanner] für diesen Bildschirm; läuft in [scope]. */
@Composable
internal fun rememberWatchlistBanner(scope: CoroutineScope): WatchlistBanner {
    val banner = remember { WatchlistBanner(SnackbarHostState(), scope) }
    banner.accessibilityManager = LocalAccessibilityManager.current
    banner.undoLabel = stringResource(R.string.action_undo)
    banner.haptics = rememberHaptics()
    return banner
}

/** So lange bleibt «Rückgängig» nach dem Löschen per Wischen stehen (ohne Screenreader). */
private const val UNDO_MILLIS = 5_000L

/**
 * So lange steht «Rückgängig» nach «Mehrere Paare auf einmal» (ohne Screenreader) — wie die Meldung im Explorer
 * (`SnackbarDuration.Long`) und iOS `ExplorerSnackbar.undoSeconds`.
 */
internal const val BULK_UNDO_MILLIS = 10_000L
