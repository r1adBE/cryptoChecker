package com.cryptochecker.app.testing

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource
import java.io.File

/**
 * Ersetzt die Dateiauswahl des Systems (SAF) in den instrumentierten Tests: Jeder Start von
 * «Dokument öffnen» bzw. «Dokument anlegen» wird abgefangen und sofort mit [uri] beantwortet —
 * über einen blockierenden `Instrumentation.ActivityMonitor` (Plattform-API ab Android 8, keine
 * Zusatzbibliothek). Die App selbst bleibt unverändert: Sie erhält das Ergebnis über denselben
 * `rememberLauncherForActivityResult` wie nach einer echten Auswahl. [uri] ist eine `file://`-Adresse
 * im Cache der App (Test und App laufen im selben Prozess, `ContentResolver` liest und schreibt sie).
 */
class DocumentPickerStub : ExternalResource() {

    /** Antwort der nächsten Auswahl; null = abgebrochen (RESULT_CANCELED). */
    @Volatile
    var uri: Uri? = null

    /** Wie oft eine Auswahl abgefangen wurde (zum Prüfen, dass der Ablauf sie wirklich geöffnet hat). */
    @Volatile
    var launches: Int = 0
        private set

    private val monitor = object : Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.action != Intent.ACTION_OPEN_DOCUMENT && intent.action != Intent.ACTION_CREATE_DOCUMENT) return null
            launches++
            val answer = uri ?: return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            return Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(answer))
        }
    }

    override fun before() {
        InstrumentationRegistry.getInstrumentation().addMonitor(monitor)
    }

    override fun after() {
        InstrumentationRegistry.getInstrumentation().removeMonitor(monitor)
    }

    companion object {
        /** Datei im Cache der App (leer angelegt bzw. geleert) als `file://`-Adresse. */
        fun cacheFile(name: String): File {
            val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, name)
            file.parentFile?.mkdirs()
            file.writeBytes(ByteArray(0))
            return file
        }

        /**
         * Gemeinsame Testdatei der UI-Tests (android/testdata/uitest, als Assets des Test-APK; dieselbe
         * Datei kopiert ios/tools/gen_xcodeproj.py nach ios/UITests/Fixtures) in den Cache der App.
         */
        fun fixture(name: String): File {
            val file = cacheFile(name)
            InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            return file
        }
    }
}
