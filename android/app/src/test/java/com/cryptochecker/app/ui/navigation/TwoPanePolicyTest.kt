package com.cryptochecker.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoPanePolicyTest {
    @Test
    fun phonePortraitHasOnePane() {
        assertFalse(TwoPanePolicy.wide(widthDp = 411, heightDp = 891))
    }

    @Test
    fun phoneLandscapeHasTwoPanes() {
        assertTrue(TwoPanePolicy.wide(widthDp = 891, heightDp = 411))
        assertTrue(TwoPanePolicy.wide(widthDp = 640, heightDp = 360))
    }

    @Test
    fun smallLandscapeStaysOnePane() {
        assertFalse(TwoPanePolicy.wide(widthDp = 599, heightDp = 360))
    }

    @Test
    fun tabletPortraitHasTwoPanesFrom840() {
        assertTrue(TwoPanePolicy.wide(widthDp = 840, heightDp = 1200))
        assertFalse(TwoPanePolicy.wide(widthDp = 800, heightDp = 1280))
    }

    @Test
    fun listWidthStaysWithinBounds() {
        assertEquals(340, TwoPanePolicy.listWidthDp(640))
        assertEquals(400, TwoPanePolicy.listWidthDp(1000))
        assertEquals(520, TwoPanePolicy.listWidthDp(1600))
    }
}
