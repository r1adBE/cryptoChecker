package com.cryptochecker.app.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Einziger Weg, auf dem Live-Takte die Widgets neu zeichnen: Live-Dienst (PriceService),
 * WebSocket-Sammelpunkt (PriceRefresher.applyLive), nachgetragene Tages-Bezüge und die
 * Portfolio-Momentaufnahme nach einem Live-Durchlauf. Bei ausgeschaltetem Bildschirm wird nur
 * gemerkt, dass etwas fehlt ([WidgetScreenGate]); beim Einschalten (ACTION_SCREEN_ON) werden
 * alle Widgets einmal nachgezeichnet. Die Daten (Kurse, Portfolio-Verlauf) werden trotzdem
 * gespeichert.
 *
 * Der Hintergrund-Job (WorkManager) und Aktionen in der App rufen mit `deferWhenOff = false`
 * bzw. gar nicht hierüber — sie zeichnen immer (bei ausgeschaltetem Bildschirm ist der Job die
 * einzige Aktualisierung).
 *
 * Der Empfänger für ACTION_SCREEN_ON ist nur angemeldet, solange etwas aussteht.
 */
@Singleton
class LiveWidgetGate @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val widgetUpdater: WidgetUpdater,
) {
    private val gate = WidgetScreenGate()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val receiverLock = Any()
    private var receiverRegistered = false

    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_ON) return
            redrawLater()
        }
    }

    /**
     * Ein Takt mit neuen Kursen: alle Widgets zeichnen oder (Bildschirm aus, [deferWhenOff])
     * aufschieben. @return true = gezeichnet
     */
    suspend fun drawAll(deferWhenOff: Boolean): Boolean {
        val draw = gate.onTick(deferWhenOff, widgetUpdater.isScreenInteractive())
        if (draw) widgetUpdater.updateAll() else listenForScreenOn()
        return draw
    }

    /**
     * Nur ein Teil der Widgets (z. B. Portfolio) soll neu gezeichnet werden.
     * @return true = jetzt zeichnen; false = aufgeschoben, beim Einschalten zeichnet [drawAll] alles
     */
    fun allowPartial(deferWhenOff: Boolean): Boolean {
        val draw = gate.onPartial(deferWhenOff, widgetUpdater.isScreenInteractive())
        if (!draw) listenForScreenOn()
        return draw
    }

    /** Bildschirm wieder an: aufgeschobene Widgets einmal nachzeichnen. */
    suspend fun redrawIfDeferred() {
        stopListening()
        if (gate.onScreenOn()) widgetUpdater.updateAll()
    }

    private fun listenForScreenOn() {
        synchronized(receiverLock) {
            if (receiverRegistered) return
            receiverRegistered = runCatching {
                // Systemmeldung: kommt auch mit NOT_EXPORTED an
                ContextCompat.registerReceiver(
                    context, screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED
                )
            }.onFailure { Timber.w(it, "Widgets: Empfänger für «Bildschirm an» nicht angemeldet") }.isSuccess
        }
        // Inzwischen schon wieder an (Meldung vor der Anmeldung verpasst): gleich nachziehen
        if (widgetUpdater.isScreenInteractive()) redrawLater()
    }

    private fun redrawLater() {
        scope.launch {
            runCatching { redrawIfDeferred() }
                .onFailure { Timber.w(it, "Widgets nach dem Einschalten nicht gezeichnet") }
        }
    }

    private fun stopListening() {
        synchronized(receiverLock) {
            if (!receiverRegistered) return
            runCatching { context.unregisterReceiver(screenOnReceiver) }
            receiverRegistered = false
        }
    }
}
