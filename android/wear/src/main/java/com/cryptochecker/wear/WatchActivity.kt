package com.cryptochecker.wear

import android.content.ComponentName
import android.Manifest
import android.content.Context
import android.os.Build
import androidx.core.app.ActivityCompat
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import kotlinx.coroutines.flow.MutableStateFlow

/** Einstieg der Uhr-App: Liste der Favoriten mit Kurs und 24 h, Auswahl der Coins ([WatchApp]). */
class WatchActivity : ComponentActivity() {
    /** Sichtbar (zwischen onResume und onPause): nur dann laufen Abruf und Alterstakt der Liste. */
    private val visible = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // «Akku & Aktivität»: eigene Zähler, Summe ans Handy (höchstens alle 30 Min.)
        WatchUsage.attach(this)
        WatchUsage.publish(this)
        // «Auf der Uhr zeigen» am Handy eingeschaltet, Erlaubnis fehlt: einmal fragen
        if (WatchNotifications.wantsPermission(this) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            WatchNotifications.asked(this)
            // Ergebnis braucht keine Handlung (ohne Erlaubnis zeigt die Uhr nichts); ohne Fragment-Ergebnis-API
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
        val store = WatchStore(this)
        setContent { WatchApp(store, visible, onQuotesChanged = { requestComplicationUpdate(this) }) }
    }

    override fun onResume() {
        super.onResume()
        visible.value = true
        // Auch nach der Frage um Erlaubnis: dem Handy melden, ob die Uhr Mitteilungen zeigen darf
        WatchNotifications.publishState(this)
    }

    override fun onPause() {
        visible.value = false
        super.onPause()
    }
}

/** Anfrage-Nummer der Erlaubnis für Mitteilungen («Auf der Uhr zeigen»). */
private const val REQUEST_NOTIFICATIONS = 1

/** Komplikationen neu zeichnen lassen (neue Kurse oder anderer erster Favorit). */
fun requestComplicationUpdate(context: Context) = requestUpdate(context, PriceComplicationService::class.java)

/** Portfolio-Komplikation neu zeichnen lassen (neuer Wert vom Handy oder entfernt). */
fun requestPortfolioComplicationUpdate(context: Context) = requestUpdate(context, PortfolioComplicationService::class.java)

private fun requestUpdate(context: Context, service: Class<*>) {
    runCatching {
        ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, service)).requestUpdateAll()
    }
}
