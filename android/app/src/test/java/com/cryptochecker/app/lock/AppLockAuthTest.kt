package com.cryptochecker.app.lock

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import org.junit.Assert.assertEquals
import org.junit.Test

/** Erlaubte Verfahren der Portfolio-Sperre je API-Stufe. */
class AppLockAuthTest {

    @Test
    fun abApi30StarkeBiometrieOderGeraeteSperre() {
        assertEquals(BIOMETRIC_STRONG or DEVICE_CREDENTIAL, AppLockAuth.authenticatorsFor(30))
        assertEquals(BIOMETRIC_STRONG or DEVICE_CREDENTIAL, AppLockAuth.authenticatorsFor(36))
    }

    @Test
    fun darunterUnveraendertSchwacheBiometrie() {
        assertEquals(BIOMETRIC_WEAK or DEVICE_CREDENTIAL, AppLockAuth.authenticatorsFor(29))
        assertEquals(BIOMETRIC_WEAK or DEVICE_CREDENTIAL, AppLockAuth.authenticatorsFor(26))
    }
}
