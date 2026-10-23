package com.cryptochecker.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cryptochecker.app.util.TextLimit
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Notizlänge auf dem Gerät (ICU): ZWJ-Folgen, Flaggen und Hauttöne zählen je als ein Zeichen und werden nie
 * zerrissen — die JVM-Tests prüfen diese Fälle erst ab JDK 20 (`TextLimitParityTest`).
 */
@RunWith(AndroidJUnit4::class)
class TextLimitDeviceTest {

    @Test
    fun familieFlaggeUndHauttonAlsEinZeichen() {
        val family = "👨‍👩‍👧‍👦"
        val flag = "🇨🇭"
        val thumb = "👍🏽"
        assertEquals(1, TextLimit.graphemeCount(family))
        assertEquals(1, TextLimit.graphemeCount(flag))
        assertEquals(1, TextLimit.graphemeCount(thumb))
        assertEquals("ab$family", TextLimit.takeGraphemes("ab$family$flag", 3))
        assertEquals("a".repeat(119) + flag, TextLimit.takeGraphemes("a".repeat(119) + flag + thumb, 120))
    }
}
