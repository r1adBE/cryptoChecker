package com.cryptochecker.app.notification

import android.content.Context
import com.cryptochecker.app.R
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.tts.TtsSpeaker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Ergebnis von «Alarm testen». */
enum class AlarmTestResult {
    /** Gesendet. */
    SENT,

    /** Gesendet, aber gerade ist Nachtruhe: echte Alarme kämen lautlos. */
    SENT_QUIET,

    /** Benachrichtigungen sind aus oder nicht erlaubt; nichts gesendet. */
    DENIED,
}

/**
 * «Alarm testen»: ein echter Alarm über denselben Weg wie ein Kursalarm
 * (Kanal, Ton, Vibration, Sprachausgabe), aber ohne Nachtruhe.
 */
@Singleton
class AlarmTester @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val notifier: AppNotifier,
    private val settingsRepository: SettingsRepository,
    private val ttsSpeaker: TtsSpeaker,
) {
    suspend fun run(): AlarmTestResult {
        // Frisch lesen: Kanal, Ton und Nachtruhe nutzen den Zwischenspeicher
        val settings = settingsRepository.current()
        if (!notifier.alarmsAllowed()) return AlarmTestResult.DENIED
        val quiet = notifier.isQuietNow()
        notifier.showTestAlarm()
        if (settings.ttsEnabled) {
            ttsSpeaker.speak(
                context.getString(R.string.alarm_test_text),
                speechRate = settings.ttsSpeechRate,
                flush = true
            )
        }
        return if (quiet) AlarmTestResult.SENT_QUIET else AlarmTestResult.SENT
    }
}
