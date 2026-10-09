package com.cryptochecker.app

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Instrumentierte Tests laufen mit [HiltTestApplication] statt `CryptoCheckerApp`, damit
 * Test-Module (z. B. [com.cryptochecker.app.testing.FakeRemoteDataModule]) die echten ersetzen.
 * Was `CryptoCheckerApp.onCreate` sonst erledigt (WorkManager, Mitteilungskanäle), richtet
 * [com.cryptochecker.app.testing.AppTestEnvironmentRule] ein.
 */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
