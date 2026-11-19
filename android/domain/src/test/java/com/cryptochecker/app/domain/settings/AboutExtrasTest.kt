package com.cryptochecker.app.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** Diagnose erst nach der Freischaltung, «Entwickler» nur im Debug-Build. Wie iOS `UiHonestyTests.testAboutExtras`. */
class AboutExtrasTest {
    @Test
    fun diagnosticsAfterUnlockDeveloperOnlyInDebug() {
        assertEquals(AboutExtras(diagnostics = false, developer = false), AboutExtras.of(unlocked = false, debugBuild = true))
        assertEquals(AboutExtras(diagnostics = true, developer = false), AboutExtras.of(unlocked = true, debugBuild = false))
        assertEquals(AboutExtras(diagnostics = true, developer = true), AboutExtras.of(unlocked = true, debugBuild = true))
    }
}
