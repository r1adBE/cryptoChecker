package com.cryptochecker.app.ui.features.settings

import com.cryptochecker.app.widget.PortfolioSnapshotUpdater
import com.cryptochecker.app.widget.WidgetUpdater
import javax.inject.Inject

/**
 * Was die Einstellungen an den Widgets anstossen: alle neu zeichnen ([widgetUpdater]) und die Momentaufnahme des
 * Portfolio-Widgets neu rechnen ([portfolioSnapshotUpdater]). Ohne eigenen Zustand.
 */
class SettingsWidgets @Inject constructor(
    val widgetUpdater: WidgetUpdater,
    val portfolioSnapshotUpdater: PortfolioSnapshotUpdater,
)
