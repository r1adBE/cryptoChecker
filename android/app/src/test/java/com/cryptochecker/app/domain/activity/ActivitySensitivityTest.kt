package com.cryptochecker.app.domain.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivitySensitivityTest {

    private val now = 1_700_000_000_000L

    private fun signal(kind: SignalKind, value: Double, factor: Double? = null) =
        ActivitySignal(kind, SignalSeverity.NOTABLE, value, factor, seenAt = now)

    @Test
    fun normal_isTodaysThresholds() {
        assertSame(SignalThresholds.NORMAL, SignalThresholds.of(ActivitySensitivity.NORMAL))
        assertEquals(ActivityAnalyzer.SIGNAL_VOLUME_RATIO, SignalThresholds.NORMAL.volumeRatio, 0.0)
        assertEquals(ActivityAnalyzer.SIGNAL_PRICE_Z, SignalThresholds.NORMAL.priceZ, 0.0)
    }

    @Test
    fun less_scalesAllThresholdsByOneAndAHalf() {
        val t = SignalThresholds.of(ActivitySensitivity.LESS)
        val n = SignalThresholds.NORMAL
        assertEquals(n.priceZ * 1.5, t.priceZ, 1e-9)
        assertEquals(n.priceMinMovePercent * 1.5, t.priceMinMovePercent, 1e-9)
        assertEquals(n.priceStrongZ * 1.5, t.priceStrongZ, 1e-9)
        assertEquals(n.priceStrongMovePercent * 1.5, t.priceStrongMovePercent, 1e-9)
        assertEquals(n.volumeRatio * 1.5, t.volumeRatio, 1e-9)
        assertEquals(n.volumeStrongRatio * 1.5, t.volumeStrongRatio, 1e-9)
        assertEquals(n.fundingPercent * 1.5, t.fundingPercent, 1e-9)
        assertEquals(n.fundingStrongPercent * 1.5, t.fundingStrongPercent, 1e-9)
        assertEquals(n.oiPercent * 1.5, t.oiPercent, 1e-9)
        assertEquals(n.oiStrongPercent * 1.5, t.oiStrongPercent, 1e-9)
        assertEquals(3, ActivitySensitivity.LESS.maxCardCoins)
    }

    @Test
    fun more_scalesAllThresholdsByThreeQuarters() {
        val t = SignalThresholds.of(ActivitySensitivity.MORE)
        assertEquals(SignalThresholds.NORMAL.volumeRatio * 0.75, t.volumeRatio, 1e-9)
        assertEquals(SignalThresholds.NORMAL.oiStrongPercent * 0.75, t.oiStrongPercent, 1e-9)
        assertNull(ActivitySensitivity.MORE.maxCardCoins)
        assertNull(ActivitySensitivity.NORMAL.maxCardCoins)
    }

    @Test
    fun volume_dependsOnSensitivity() {
        // Normal: ab 5×, stark ab 10×
        val spike = signal(SignalKind.VOLUME_SPIKE, 6.0)
        assertEquals(SignalSeverity.NOTABLE, SignalThresholds.of(ActivitySensitivity.NORMAL).severityOf(spike))
        assertNull(SignalThresholds.of(ActivitySensitivity.LESS).severityOf(spike)) // ab 7.5×
        assertEquals(
            SignalSeverity.NOTABLE,
            SignalThresholds.of(ActivitySensitivity.MORE).severityOf(signal(SignalKind.VOLUME_SPIKE, 4.0)) // ab 3.75×
        )
        assertEquals(
            SignalSeverity.STRONG,
            SignalThresholds.of(ActivitySensitivity.MORE).severityOf(signal(SignalKind.VOLUME_SPIKE, 8.0)) // ab 7.5×
        )
    }

    @Test
    fun priceMove_needsZAndMove() {
        val less = SignalThresholds.of(ActivitySensitivity.LESS)
        // z 4 reicht für Normal (3.5), nicht für Weniger (5.25)
        val move = signal(SignalKind.PRICE_MOVE, 3.0, factor = 4.0)
        assertEquals(SignalSeverity.NOTABLE, SignalThresholds.NORMAL.severityOf(move))
        assertNull(less.severityOf(move))
        // Mehr: 2 % reichen (Mindestbewegung 1.875 %)
        assertEquals(
            SignalSeverity.NOTABLE,
            SignalThresholds.of(ActivitySensitivity.MORE).severityOf(signal(SignalKind.PRICE_MOVE, -2.0, factor = 3.0))
        )
    }

    @Test
    fun fundingAndOi_bothSigns() {
        val more = SignalThresholds.of(ActivitySensitivity.MORE)
        assertEquals(SignalSeverity.NOTABLE, more.severityOf(signal(SignalKind.FUNDING_EXTREME, -0.08)))
        assertNull(SignalThresholds.NORMAL.severityOf(signal(SignalKind.FUNDING_EXTREME, -0.08)))
        assertEquals(SignalSeverity.STRONG, more.severityOf(signal(SignalKind.OPEN_INTEREST_JUMP, -19.0, 45.0)))
        assertNull(SignalThresholds.of(ActivitySensitivity.LESS).severityOf(signal(SignalKind.OPEN_INTEREST_JUMP, 20.0)))
    }

    @Test
    fun signals_useSensitivity_sameForCardAndNotifications() {
        // Volumen 6×: Normal und Mehr melden, Weniger nicht
        val stats = HourStats(movePercent = 0.1, zScore = 0.2, volumeRatio = 6.0, candleOpenTime = now)
        assertEquals(1, ActivityAnalyzer.signals(stats, null, null, null, now).size)
        assertTrue(ActivityAnalyzer.signals(stats, null, null, null, now, ActivitySensitivity.LESS).isEmpty())
        assertEquals(
            SignalSeverity.NOTABLE,
            ActivityAnalyzer.signals(stats, null, null, null, now, ActivitySensitivity.MORE).single().severity
        )
    }

    @Test
    fun applySensitivity_refiltersStoredSignals() {
        val stored = listOf(
            signal(SignalKind.VOLUME_SPIKE, 6.0),
            signal(SignalKind.FUNDING_EXTREME, 0.25),
        )
        val less = ActivityAnalyzer.applySensitivity(stored, ActivitySensitivity.LESS)
        // Volumen fällt weg; Funding 0.25 % bleibt, aber nur noch auffällig (stark erst ab 0.3 %)
        assertEquals(SignalKind.FUNDING_EXTREME, less.single().kind)
        assertEquals(SignalSeverity.NOTABLE, less.single().severity)
        assertEquals(2, ActivityAnalyzer.applySensitivity(stored, ActivitySensitivity.NORMAL).size)
    }

    @Test
    fun merge_dropsOldSignalsBelowStricterThresholds() {
        val previous = ActivityReport(listOf(signal(SignalKind.VOLUME_SPIKE, 6.0)), computedAt = now)
        val merge = ActivityAnalyzer.merge(previous, emptyList(), now + 60_000, ActivitySensitivity.LESS)
        assertTrue(merge.report.signals.isEmpty())
        assertTrue(merge.newKinds.isEmpty())
    }

    @Test
    fun fromName_unknownOrMissingIsNormal() {
        assertEquals(ActivitySensitivity.NORMAL, ActivitySensitivity.fromName(null))
        assertEquals(ActivitySensitivity.NORMAL, ActivitySensitivity.fromName("SOMETHING"))
        assertEquals(ActivitySensitivity.LESS, ActivitySensitivity.fromName("LESS"))
        assertEquals(ActivitySensitivity.MORE, ActivitySensitivity.fromName("MORE"))
    }
}
