package com.cryptochecker.app.ui.components

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Die eine Erklärung vor der System-Abfrage der App ([NotificationPrePrompt.slot], gezeigt von
 * [NotificationPrePromptHost]) — wie `NotificationPrePrompt.shared` (iOS). Ohne Android, damit ein Unit-Test die
 * Regel samt Zeitpunkt des Speicherns prüft.
 *
 * Die Stufe geht schon beim Zeigen weiter, als wäre «Später» getippt: Verschwindet die Erklärung ohne Antwort
 * (Zurück, Tippen daneben, Prozess beendet), zählt das als «Später». Solange sie steht, kommt keine zweite.
 */
class PrePromptSlot(
    private val readStage: () -> Int,
    private val writeStage: (Int) -> Unit,
) {
    private val _showing = MutableStateFlow(false)

    /** Erklärung steht gerade. */
    val showing: StateFlow<Boolean> = _showing.asStateFlow()

    /**
     * Anfrage aus [reason]; [notDetermined]: das System würde jetzt fragen. [NotificationPrePrompt.Action.SYSTEM]
     * startet der Aufrufer selbst; bei [NotificationPrePrompt.Action.EXPLAIN] steht die Erklärung jetzt.
     */
    @Synchronized
    fun request(notDetermined: Boolean, reason: NotificationAsk): NotificationPrePrompt.Action {
        // Schon offen: kein zweites Mal, auch keine System-Abfrage darüber
        if (_showing.value) return NotificationPrePrompt.Action.NOTHING
        val stage = readStage()
        val action = NotificationPrePrompt.action(notDetermined, stage, reason)
        if (action == NotificationPrePrompt.Action.EXPLAIN) {
            writeStage(NotificationPrePrompt.afterLater(stage))
            _showing.value = true
        }
        return action
    }

    /**
     * Antwort: «Weiter» ([proceed]) merkt [NotificationPrePrompt.STAGE_CONTINUED] — der Aufrufer startet dann die
     * System-Abfrage. «Später» und Wegtippen ändern nichts mehr. true = die Erklärung stand noch (erste Antwort).
     */
    @Synchronized
    fun answer(proceed: Boolean): Boolean {
        if (!_showing.value) return false
        _showing.value = false
        if (proceed) writeStage(NotificationPrePrompt.STAGE_CONTINUED)
        return true
    }
}
