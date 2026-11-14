package com.cryptochecker.wear

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester

/** Einstieg der Uhr-App: Liste der Favoriten mit Kurs und 24 h, Auswahl der Coins ([WatchApp]). */
class WatchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // «Akku & Aktivität»: eigene Zähler, Summe ans Handy (höchstens alle 30 Min.)
        WatchUsage.attach(this)
        WatchUsage.publish(this)
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
