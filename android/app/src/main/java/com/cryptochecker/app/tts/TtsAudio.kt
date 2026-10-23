// Audio-Fokus und Ruhe-Regeln der Sprachausgabe (Teil von `TtsSpeaker`): Fokus «kurz, andere leiser» während
// einer Ansage, Klingelmodus, Telefonat und «Bitte nicht stören». Eigene Datei, eigener Zustand unter [focusLock].
package com.cryptochecker.app.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import kotlinx.coroutines.CompletableDeferred

/** Audio-Fokus und Ruhe-Regeln für [TtsSpeaker]. */
internal class TtsAudio(private val context: Context) {

    private val focusLock = Any()

    /** Laufende Ansagen mit Audio-Fokus; nur unter [focusLock]. */
    private var focusUsers = 0

    private val focusRequest: AudioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(ANNOUNCEMENT_ATTRIBUTES)
            .build()
    }

    /**
     * Audio-Fokus «kurz, andere leiser» bis [done]; abgegeben, wenn keine Ansage mehr läuft.
     * @return false, wenn Android den Fokus verweigert (ab Android 15 im Hintergrund üblich) —
     *   dann ohne Fokus sprechen; [done] gibt dann nichts frei. [onReleased] nach dem Ende von [done].
     */
    fun holdFocusUntil(done: CompletableDeferred<Unit>, onReleased: () -> Unit): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return true
        synchronized(focusLock) {
            if (focusUsers == 0) {
                val result = runCatching { audio.requestAudioFocus(focusRequest) }
                    .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
            }
            focusUsers++
        }
        done.invokeOnCompletion {
            synchronized(focusLock) {
                if (--focusUsers == 0) runCatching { audio.abandonAudioFocusRequest(focusRequest) }
            }
            onReleased()
        }
        return true
    }

    fun ringerAllowsSpeech(): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return true
        return audio.ringerMode == AudioManager.RINGER_MODE_NORMAL
    }

    /** Kein Telefonat (Audio-Modus normal) und «Bitte nicht stören» aus (alle Unterbrechungen erlaubt). */
    fun callAndDndAllowSpeech(): Boolean {
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio != null && audio.mode != AudioManager.MODE_NORMAL) return false
        val filter = runCatching {
            context.getSystemService(android.app.NotificationManager::class.java)?.currentInterruptionFilter
        }.getOrNull() ?: return true
        return filter == android.app.NotificationManager.INTERRUPTION_FILTER_ALL ||
            filter == android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    companion object {
        /**
         * Automatische Ansagen (Alarme, Kurse): Strom der Mitteilungen wie der Alarm-Kanal (USAGE_NOTIFICATION) —
         * Lautstärke «Benachrichtigungen», stumm, wenn Mitteilungen stumm sind. Ein stummes Telefon bleibt stumm;
         * Lautlos/Vibration, Telefonat und «Bitte nicht stören» prüft [TtsSpeaker] zusätzlich vor jeder Ansage.
         * Früher lief das über den Wecker-Strom (USAGE_ALARM) und war bei stummen Mitteilungen trotzdem hörbar.
         * Eine Einstellung «Dringend», die den Wecker-Strom rechtfertigen würde, gibt es nicht. iOS: Kategorie `.ambient`.
         */
        val ANNOUNCEMENT_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        /**
         * Vom Nutzer angestossen (Stimme testen, Alarm testen): immer hörbar, Medien-Lautstärke — wie iOS `.playback`.
         */
        val MANUAL_SPEECH_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
