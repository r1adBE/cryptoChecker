package com.cryptochecker.app.widget

/**
 * Widgets im Live-Modus (Live-Dienst, WebSocket) bei ausgeschaltetem Bildschirm nicht bei jedem
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
     * @param deferWhenOff nur der Live-Dienst schiebt auf (der Hintergrund-Job zeichnet immer)
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

    /**
     * Ein Teil der Widgets soll neu gezeichnet werden (z. B. nur Portfolio-Widgets nach einer
     * neuen Momentaufnahme). Anders als [onTick] löscht ein erlaubtes Zeichnen ein ausstehendes
     * Gesamt-Neuzeichnen nicht — es zeichnet ja nicht alles.
     * @return true = jetzt zeichnen; false = aufgeschoben (als ausstehend gemerkt)
     */
    @Synchronized
    fun onPartial(deferWhenOff: Boolean, interactive: Boolean): Boolean {
        if (deferWhenOff && !interactive) {
            dirty = true
            return false
        }
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
