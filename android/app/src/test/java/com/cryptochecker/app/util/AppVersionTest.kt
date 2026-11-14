package com.cryptochecker.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class AppVersionTest {
    @Test
    fun versionWithBuild() {
        assertEquals("16.2.2 (234)", AppVersion.format("16.2.2", 234))
    }

    @Test
    fun debugSuffixDropped() {
        assertEquals("16.2.2 (234)", AppVersion.format("16.2.2-debug", 234))
    }
}
