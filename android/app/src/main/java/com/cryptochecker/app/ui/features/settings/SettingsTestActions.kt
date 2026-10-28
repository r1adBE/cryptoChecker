package com.cryptochecker.app.ui.features.settings

import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.notification.AlarmTestResult
import com.cryptochecker.app.notification.AlarmTester
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.tts.SpokenText
import com.cryptochecker.app.tts.TtsSpeaker
import javax.inject.Inject

/**
 * Die Prüf-Knöpfe der Einstellungen: «Alarm testen» (echter Alarm über den Weg der Kursalarme, ohne Nachtruhe) und
 * «Stimme testen» (liest den zuletzt bekannten Kurs vor). Eigene Klasse, damit [SettingsViewModel] nur einen Baustein
 * dafür braucht statt vier.
 */
class SettingsTestActions @Inject constructor(
    private val alarmTester: AlarmTester,
    private val ttsSpeaker: TtsSpeaker,
    private val spokenText: SpokenText,
    private val settingsRepository: SettingsRepository,
    private val watchRepository: WatchRepository,
) {
    /** «Alarm testen». */
    suspend fun testAlarm(): AlarmTestResult = alarmTester.run()

    /** «Stimme testen»: erster Kurs der Merkliste, sonst der App-Name, mit der eingestellten Sprechgeschwindigkeit. */
    suspend fun testSpeech() {
        val current = settingsRepository.current()
        val watch = watchRepository.getWatches().firstOrNull { it.lastPrice != null }
        // Lokale Kopie: lastPrice liegt in :domain (kein Smart Cast über Modulgrenzen)
        val price = watch?.lastPrice
        val text = if (watch != null && price != null) spokenText.price(watch, price) else "Crypto Checker"
        ttsSpeaker.speak(text, speechRate = current.ttsSpeechRate, flush = true)
    }

    /** Sprachausgabe ausgeschaltet: Text-to-Speech-Engine freigeben. */
    fun stopSpeech() = ttsSpeaker.shutdown()
}
