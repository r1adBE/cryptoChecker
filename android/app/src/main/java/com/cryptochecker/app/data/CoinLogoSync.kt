package com.cryptochecker.app.data

import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.widget.WidgetUpdater
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Startet den Abgleich aller Coin-Logos ([CoinLogoRepository.startSync]), sofern Logos irgendwo
 * gebraucht werden ([CoinLogoUse.needed]) — beim Öffnen der App und beim Einschalten. Kamen neue Logos
 * dazu und sind sie für Widgets an, werden die Widgets neu gezeichnet.
 */
@Singleton
class CoinLogoSync @Inject constructor(
    private val repository: CoinLogoRepository,
    private val settingsRepository: SettingsRepository,
    private val widgetUpdater: WidgetUpdater,
) {
    suspend fun startIfEnabled() {
        val settings = settingsRepository.current()
        if (!CoinLogoUse.needed(settings)) return
        repository.startSync { added ->
            if (added > 0 && settingsRepository.current().widgetCoinLogos) widgetUpdater.updateAll()
        }
    }
}

/** Wo Logos gezeigt werden (Schalter unter Darstellung › Coin-Logos). */
object CoinLogoUse {
    /** Portfolio-Logos zählen nur mit eingeschaltetem Portfolio-Tab. */
    fun portfolio(settings: com.cryptochecker.app.settings.AppSettings): Boolean =
        settings.portfolioCoinLogos && settings.portfolioEnabled

    fun needed(settings: com.cryptochecker.app.settings.AppSettings): Boolean =
        settings.coinLogos || settings.widgetCoinLogos || portfolio(settings)
}
