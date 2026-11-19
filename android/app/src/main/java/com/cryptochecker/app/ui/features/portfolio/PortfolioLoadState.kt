package com.cryptochecker.app.ui.features.portfolio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Zustand des Ladens im Portfolio ([refresh]): [busy] solange irgendein Laden läuft (Menü «Aktualisieren» gesperrt),
 * [manual] nur beim Ziehen bzw. Menü (Kreisel kurz, [com.cryptochecker.app.ui.components.rememberPullAck]), [progress]
 * der dünne Balken oben ([com.cryptochecker.app.ui.components.LoadProgressBar]), null = keiner.
 * Wie iOS `PortfolioModel.loadProgress`.
 */
class PortfolioLoadState {
    internal val busyFlow = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = busyFlow.asStateFlow()

    internal val manualFlow = MutableStateFlow(false)
    val manual: StateFlow<Boolean> = manualFlow.asStateFlow()

    internal val progressFlow = MutableStateFlow<Float?>(null)
    val progress: StateFlow<Float?> = progressFlow.asStateFlow()
}
