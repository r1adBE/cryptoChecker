package com.cryptochecker.app.testing

import android.Manifest
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.cryptochecker.app.notification.NotificationChannels
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Was sonst `CryptoCheckerApp.onCreate` erledigt (die Tests laufen mit `HiltTestApplication`):
 * WorkManager (Test-Fassung, ohne Hintergrundarbeit) und Mitteilungskanäle. Dazu die
 * Mitteilungs-Erlaubnis (Android 13+), damit kein System-Dialog die Oberfläche verdeckt,
 * und feste Startkurse. Muss vor dem Start der Activity laufen (Regel-Reihenfolge).
 */
class AppTestEnvironmentRule : TestRule {

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                instrumentation.uiAutomation.grantRuntimePermission(
                    context.packageName, Manifest.permission.POST_NOTIFICATIONS
                )
            }
            WorkManagerTestInitHelper.initializeTestWorkManager(
                context,
                Configuration.Builder().setExecutor(SynchronousExecutor()).build()
            )
            NotificationChannels.createAll(context)
            FakeMarket.reset()
            base.evaluate()
        }
    }

    companion object {
        /** Shell-Befehl als Shell-Benutzer (z. B. `locksettings`); wartet auf das Ende, gibt die Ausgabe zurück. */
        fun shell(command: String): String {
            val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
        }
    }
}
