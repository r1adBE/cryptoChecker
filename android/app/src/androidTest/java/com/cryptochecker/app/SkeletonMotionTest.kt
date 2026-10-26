package com.cryptochecker.app

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.cryptochecker.app.testing.AppTestEnvironmentRule
import com.cryptochecker.app.ui.MainActivity
import com.cryptochecker.app.ui.components.SkeletonList
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Lade-Platzhalter [SkeletonList]: bei reduzierter Bewegung («Animationen entfernen») steht der Puls (gleiche Pixel
 * über die Zeit), und Screenreader finden einen Knoten «Wird geladen…» mit unbestimmtem Fortschritt. Braucht ein
 * Gerät (Compose-Semantik und Bildaufnahme ohne Robolectric).
 */
@HiltAndroidTest
class SkeletonMotionTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val environment = AppTestEnvironmentRule()

    @get:Rule(order = 2)
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun reduzierteBewegungStehtStill() {
        val loading = compose.activity.getString(R.string.loading_hint)
        compose.mainClock.autoAdvance = false
        show { SkeletonList(reduceMotion = true) }
        val node = compose.onNode(hasContentDescription(loading), useUnmergedTree = true)
        // Erst Übergänge der Aktivität und des Themas ausklingen lassen; danach darf sich nichts mehr bewegen
        compose.mainClock.advanceTimeBy(3_000L)
        compose.waitForIdle()
        val first = node.captureToImage().toPixelMap()
        // Halbe Pulsdauer (900 ms) und eine ganze später: ohne Puls dieselben Pixel
        for (step in listOf(450L, 450L, 900L)) {
            compose.mainClock.advanceTimeBy(step)
            val later = node.captureToImage().toPixelMap()
            assertTrue("gleiche Grösse", later.width == first.width && later.height == first.height)
            val same = (0 until first.width step 4).all { x ->
                (0 until first.height step 4).all { y -> first[x, y] == later[x, y] }
            }
            assertTrue("Platzhalter pulsiert trotz reduzierter Bewegung (nach $step ms)", same)
        }
    }

    @Test
    fun ladeKnotenFuerScreenreader() {
        val loading = compose.activity.getString(R.string.loading_hint)
        show { SkeletonList(reduceMotion = true) }
        val indeterminate = SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate
        )
        compose.onNode(hasContentDescription(loading) and indeterminate, useUnmergedTree = true).assertExists()
    }

    private fun show(content: @Composable () -> Unit) {
        compose.runOnUiThread { compose.activity.setContent { MaterialTheme { content() } } }
        compose.waitForIdle()
    }
}
