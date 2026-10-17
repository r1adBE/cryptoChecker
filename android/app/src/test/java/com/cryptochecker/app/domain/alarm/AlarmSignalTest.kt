package com.cryptochecker.app.domain.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmSignalTest {

    @Test
    fun fromName_unknownOrMissing_isSystem() {
        assertEquals(AlarmSignal.SYSTEM, AlarmSignal.fromName(null))
        assertEquals(AlarmSignal.SYSTEM, AlarmSignal.fromName("LOUD"))
        assertEquals(AlarmSignal.VIBRATE, AlarmSignal.fromName("VIBRATE"))
        AlarmSignal.entries.forEach { assertEquals(it, AlarmSignal.fromName(it.name)) }
    }

    @Test
    fun channelIds_stableAndDistinct() {
        assertEquals("alarms", AlarmSignal.channelId(AlarmSignal.SYSTEM, 0))
        assertEquals("alarms_v3", AlarmSignal.channelId(AlarmSignal.SYSTEM, 3))
        assertEquals("alarms_sound_vibrate", AlarmSignal.channelId(AlarmSignal.SOUND_VIBRATE, 0))
        assertEquals("alarms_sound_v2", AlarmSignal.channelId(AlarmSignal.SOUND, 2))
        // Ohne Ton keine Versionen
        assertEquals("alarms_vibrate", AlarmSignal.channelId(AlarmSignal.VIBRATE, 5))
        assertEquals("alarms_silent", AlarmSignal.channelId(AlarmSignal.SILENT, 5))
        val ids = AlarmSignal.entries.map { AlarmSignal.channelId(it, 4) }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.none { it == "alarms_quiet" })
    }

    @Test
    fun isChannelOf_vibrateIsNotAVersionOfSystem() {
        assertTrue(AlarmSignal.isChannelOf(AlarmSignal.SYSTEM, "alarms"))
        assertTrue(AlarmSignal.isChannelOf(AlarmSignal.SYSTEM, "alarms_v12"))
        assertFalse(AlarmSignal.isChannelOf(AlarmSignal.SYSTEM, "alarms_vibrate"))
        assertFalse(AlarmSignal.isChannelOf(AlarmSignal.SYSTEM, "alarms_v"))
        assertFalse(AlarmSignal.isChannelOf(AlarmSignal.SYSTEM, "alarms_quiet"))
        assertFalse(AlarmSignal.isChannelOf(AlarmSignal.SOUND, "alarms_sound_vibrate"))
        assertTrue(AlarmSignal.isChannelOf(AlarmSignal.SOUND, "alarms_sound_v1"))
        assertFalse(AlarmSignal.isChannelOf(AlarmSignal.VIBRATE, "alarms_vibrate_v1"))
    }

    @Test
    fun staleChannelIds_onlySameSignalOldVersions() {
        val existing = listOf(
            "prices", "market", "alarms_quiet", "alarms", "alarms_v1", "alarms_v2",
            "alarms_vibrate", "alarms_silent", "alarms_sound", "alarms_sound_v1", "alarms_sound_vibrate",
        )
        assertEquals(listOf("alarms", "alarms_v1"), AlarmSignal.staleChannelIds(existing, AlarmSignal.SYSTEM, 2))
        assertEquals(listOf("alarms_sound"), AlarmSignal.staleChannelIds(existing, AlarmSignal.SOUND, 1))
        assertEquals(emptyList<String>(), AlarmSignal.staleChannelIds(existing, AlarmSignal.VIBRATE, 2))
        assertEquals(emptyList<String>(), AlarmSignal.staleChannelIds(existing, AlarmSignal.SILENT, 2))
    }

    @Test
    fun effective_alarmSwitchesOnlyRemove() {
        // System: der Kanal entscheidet; nur beides aus = lautlos
        assertEquals(AlarmSignal.SYSTEM, AlarmSignal.effective(AlarmSignal.SYSTEM, alarmSound = true, alarmVibrate = true))
        assertEquals(AlarmSignal.SYSTEM, AlarmSignal.effective(AlarmSignal.SYSTEM, alarmSound = false, alarmVibrate = true))
        assertEquals(AlarmSignal.SILENT, AlarmSignal.effective(AlarmSignal.SYSTEM, alarmSound = false, alarmVibrate = false))
        // Gewähltes Signal, vom Alarm eingeschränkt
        assertEquals(AlarmSignal.SOUND_VIBRATE, AlarmSignal.effective(AlarmSignal.SOUND_VIBRATE, true, true))
        assertEquals(AlarmSignal.VIBRATE, AlarmSignal.effective(AlarmSignal.SOUND_VIBRATE, false, true))
        assertEquals(AlarmSignal.SOUND, AlarmSignal.effective(AlarmSignal.SOUND_VIBRATE, true, false))
        // Nie mehr als gewählt
        assertEquals(AlarmSignal.VIBRATE, AlarmSignal.effective(AlarmSignal.VIBRATE, true, true))
        assertEquals(AlarmSignal.SILENT, AlarmSignal.effective(AlarmSignal.VIBRATE, true, false))
        assertEquals(AlarmSignal.SILENT, AlarmSignal.effective(AlarmSignal.SILENT, true, true))
    }
}
