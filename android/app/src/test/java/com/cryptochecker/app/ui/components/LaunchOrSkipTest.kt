package com.cryptochecker.app.ui.components

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Erlaubnis-Abfrage nach dem Neuerstellen der Activity: Ein abgemeldeter Launcher der alten Komposition
 * wirft `IllegalStateException`. [launchOrSkip] stürzt dann nicht ab und merkt nichts als «gefragt».
 */
class LaunchOrSkipTest {

    /** Fake-Launcher: zählt Starts, wirft auf Wunsch wie ein abgemeldeter. */
    private class FakeLauncher(private val unregistered: Boolean) : ActivityResultLauncher<String>() {
        val launched = mutableListOf<String>()

        override fun launch(input: String, options: ActivityOptionsCompat?) {
            if (unregistered) throw IllegalStateException("Attempting to launch an unregistered ActivityResultLauncher")
            launched += input
        }

        override fun unregister() = Unit

        override val contract: ActivityResultContract<String, *> = ActivityResultContracts.RequestPermission()
    }

    @Test
    fun unregisteredLauncherIsSkippedWithoutMarkingAsAsked() {
        var asked = 0
        val started = launchOrSkip(FakeLauncher(unregistered = true)) { asked++ }
        assertFalse(started)
        assertEquals(0, asked)
    }

    @Test
    fun activeLauncherAsksOnceAndMarks() {
        var asked = 0
        val launcher = FakeLauncher(unregistered = false)
        assertTrue(launchOrSkip(launcher) { asked++ })
        assertEquals(listOf(android.Manifest.permission.POST_NOTIFICATIONS), launcher.launched)
        assertEquals(1, asked)
    }
}
