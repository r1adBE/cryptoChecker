package com.cryptochecker.wear

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.cryptochecker.marketdata.watch.WatchInterval
import com.cryptochecker.marketdata.watch.WatchPortfolio

/**
 * Komplikation «Portfolio»: der Wert, den das Handy schickt (nur mit «Portfolio-Wert auf der Uhr»), ohne eigene
 * Abfrage — kurz «12.3K» mit der Währung als Titel, lang «CHF 12’345.67  +1.20 %» mit «Portfolio» als Titel; verborgen
 * auf dem Handy «•••». Ohne Wert (Schalter aus oder noch nie einer): kurz «Portfolio / aus», lang «Portfolio · am Handy
 * einschalten» ([WatchPortfolioText.offLine]), nie leer. Veraltet ([WatchFreshness], 3 × Intervall des Handys): lang mit Alter, kurz mit dem
 * Alter im Titel (vom Zifferblatt fortgezählt), wie [PriceComplicationService]. Neu gezeichnet bei jedem neuen Wert
 * ([WatchDataListener]). Tippen öffnet die Uhr-App. Wie die Apple Watch (`WatchPortfolioWidget`).
 */
class PortfolioComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        PortfolioComplicationData.data(
            this,
            type,
            WatchPortfolio(12_345.67, 1.2, "USD", System.currentTimeMillis(), hidden = false),
            WatchInterval.DEFAULT_MINUTES,
            null
        )

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val tap = PendingIntent.getActivity(
            this,
            1,
            Intent(this, WatchActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val store = WatchStore(this)
        val p = store.portfolioFlow.value ?: return PortfolioComplicationData.off(this, request.complicationType, tap)
        return PortfolioComplicationData.data(this, request.complicationType, p, store.settings.interval, tap)
    }
}
