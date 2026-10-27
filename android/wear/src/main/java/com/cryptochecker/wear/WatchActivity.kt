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
        val store = WatchStore(this)
        setContent { WatchApp(store, onQuotesChanged = { requestComplicationUpdate(this) }) }
    }
}

/** Komplikationen neu zeichnen lassen (neue Kurse oder anderer erster Favorit). */
fun requestComplicationUpdate(context: Context) {
    runCatching {
        ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, PriceComplicationService::class.java))
            .requestUpdateAll()
    }
}
