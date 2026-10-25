package com.cryptochecker.app.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription

/**
 * Punkt für Punkt durch einen Chart (Screenreader): «Nächster Punkt» beginnt ohne Auswahl beim ersten Punkt,
 * «Vorheriger Punkt» beim letzten; an den Enden bleibt der Punkt stehen. Auf iOS übernimmt das der Audio Graph
 * (`ChartAudioGraph`).
 */
object ChartPointStep {
    /** Index nach «Nächster Punkt»; null bei leerer Reihe. */
    fun next(current: Int?, count: Int): Int? = when {
        count <= 0 -> null
        current == null -> 0
        else -> (current + 1).coerceIn(0, count - 1)
    }

    /** Index nach «Vorheriger Punkt»; null bei leerer Reihe. */
    fun previous(current: Int?, count: Int): Int? = when {
        count <= 0 -> null
        current == null -> count - 1
        else -> (current - 1).coerceIn(0, count - 1)
    }
}

/**
 * Chart als ein Screenreader-Element: [summary] bleibt der Chart-Satz (Start, Ende, Hoch, Tief); mit den Aktionen
 * [nextLabel]/[previousLabel] wählt man einen Punkt ([onStep] mit `true` = vorwärts), dessen Text [pointText]
 * («Di 14:00: 98’450 USDT», `label_value`) als Zustand angesagt wird. Ohne Punkte (`pointCount` 0) nur der
 * Chart-Satz.
 */
fun Modifier.chartPointSemantics(
    summary: String,
    pointCount: Int,
    pointText: String?,
    nextLabel: String,
    previousLabel: String,
    onStep: (forward: Boolean) -> Unit,
): Modifier = clearAndSetSemantics {
    contentDescription = summary
    if (pointCount > 0) {
        if (pointText != null) stateDescription = pointText
        customActions = listOf(
            CustomAccessibilityAction(nextLabel) { onStep(true); true },
            CustomAccessibilityAction(previousLabel) { onStep(false); true },
        )
    }
}
