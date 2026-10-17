package com.cryptochecker.app.widget

/**
 * Widgets im Live-Modus (WebSocket) bei ausgeschaltetem Bildschirm nicht bei jedem
 * Takt neu zeichnen: nur merken, dass etwas fehlt, und einmal alle nachziehen, sobald der
 * Bildschirm wieder an ist (ACTION_SCREEN_ON, siehe [LiveWidgetGate]) bzw. beim nächsten Takt
 * mit eingeschaltetem Bildschirm. Daten (Kurse, Portfolio-Verlauf), Alarme und Meldungen laufen
 * davon unberührt weiter.
 *
 * Reine Logik ohne Android (getestet in WidgetScreenGateTest). Threadsicher.
 */
class WidgetScreenGate {
    @Volatile private var dirty = false

    /** Steht ein Neuzeichnen aus? */
    val isDirty: Boolean get() = dirty

    /**
     * Ein Takt mit neuen Kursen.
     * @param deferWhenOff nur der Live-Strom schiebt auf (volle Durchläufe zeichnen immer)
     * @return true = jetzt zeichnen; false = aufgeschoben (als ausstehend gemerkt)
     */
    @Synchronized
    fun onTick(deferWhenOff: Boolean, interactive: Boolean): Boolean {
        if (deferWhenOff && !interactive) {
            dirty = true
            return false
        }
        dirty = false
        return true
    }

    /** Bildschirm wieder an: true = jetzt einmal nachzeichnen (es stand etwas aus). */
    @Synchronized
    fun onScreenOn(): Boolean {
        if (!dirty) return false
        dirty = false
        return true
    }
}
