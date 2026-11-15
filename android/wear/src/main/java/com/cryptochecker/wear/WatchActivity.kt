package com.cryptochecker.wear

import android.content.ComponentName
import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester

/** Einstieg der Uhr-App: Liste der Favoriten mit Kurs und 24 h, Auswahl der Coins ([WatchApp]). */
class WatchActivity : ComponentActivity() {
    /** Erlaubnis für Mitteilungen («Auf der Uhr zeigen»); das Ergebnis braucht keine weitere Handlung. */
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // «Akku & Aktivität»: eigene Zähler, Summe ans Handy (höchstens alle 30 Min.)
        WatchUsage.attach(this)
        WatchUsage.publish(this)
        // «Auf der Uhr zeigen» am Handy eingeschaltet, Erlaubnis fehlt: einmal fragen
        if (WatchNotifications.wantsPermission(this) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            WatchNotifications.asked(this)
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val store = WatchStore(this)
        setContent { WatchApp(store, onQuotesChanged = { requestComplicationUpdate(this) }) }
    }
}

/** Komplikationen neu zeichnen lassen (neue Kurse oder anderer erster Favorit). */
fun requestComplicationUpdate(context: Context) = requestUpdate(context, PriceComplicationService::class.java)

/** Portfolio-Komplikation neu zeichnen lassen (neuer Wert vom Handy oder entfernt). */
fun requestPortfolioComplicationUpdate(context: Context) = requestUpdate(context, PortfolioComplicationService::class.java)

private fun requestUpdate(context: Context, service: Class<*>) {
    runCatching {
        ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, service)).requestUpdateAll()
    }
}
