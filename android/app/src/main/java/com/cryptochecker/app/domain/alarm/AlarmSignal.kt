package com.cryptochecker.app.domain.alarm

/**
 * «Alarm-Signal»: wie ein ausgelöster Kursalarm auf sich aufmerksam macht.
 * Gespeichert und gesichert unter seinem Namen (Schlüssel «alarmSignal», wie iOS).
 *
 * Android lässt Ton und Vibration eines Mitteilungskanals nach dem Anlegen nicht mehr
 * ändern — deshalb ein eigener Kanal je Signal ([channelId]). [SYSTEM] nutzt den
 * bisherigen Alarm-Kanal, den man in den Systemeinstellungen selbst einstellt.
 * iOS kennt nur [SYSTEM] und [SILENT] (Vibration lässt sich dort nicht getrennt steuern).
 * Reine Logik, wie iOS `AlarmSignal`.
 */
enum class AlarmSignal(val sound: Boolean, val vibrate: Boolean) {
    /** Wie in den Mitteilungseinstellungen des Telefons (bisheriger Alarm-Kanal). */
    SYSTEM(sound = true, vibrate = true),
    SOUND_VIBRATE(sound = true, vibrate = true),
    SOUND(sound = true, vibrate = false),
    VIBRATE(sound = false, vibrate = true),

    /** Nur die Mitteilung (erscheint trotzdem oben), ohne Ton und Vibration. */
    SILENT(sound = false, vibrate = false);

    companion object {
        val DEFAULT = SYSTEM

        /** Kanal-ID des bisherigen Alarm-Kanals ([SYSTEM]); Versionen «alarms_v<n>». */
        const val SYSTEM_BASE = "alarms"

        /** Gespeicherter Name → Signal; fehlend oder unbekannt (neuere Version) → [DEFAULT]. */
        fun fromName(name: String?): AlarmSignal = entries.firstOrNull { it.name == name } ?: DEFAULT

        /**
         * Signal für einen einzelnen Alarm: dessen Schalter «Ton»/«Vibration» können nur
         * wegnehmen, nie hinzufügen. Bei [SYSTEM] entscheidet der Kanal — nur wenn der Alarm
         * beides aus hat, kommt er lautlos ([SILENT]).
         */
        fun effective(mode: AlarmSignal, alarmSound: Boolean, alarmVibrate: Boolean): AlarmSignal {
            if (mode == SYSTEM) return if (!alarmSound && !alarmVibrate) SILENT else SYSTEM
            return of(sound = mode.sound && alarmSound, vibrate = mode.vibrate && alarmVibrate)
        }

        /** Signal mit genau diesem Ton/dieser Vibration (nie [SYSTEM]). */
        fun of(sound: Boolean, vibrate: Boolean): AlarmSignal = when {
            sound && vibrate -> SOUND_VIBRATE
            sound -> SOUND
            vibrate -> VIBRATE
            else -> SILENT
        }

        /** Stamm der Kanal-ID je Signal (stabil). */
        fun channelBase(mode: AlarmSignal): String = when (mode) {
            SYSTEM -> SYSTEM_BASE
            SOUND_VIBRATE -> "alarms_sound_vibrate"
            SOUND -> "alarms_sound"
            VIBRATE -> "alarms_vibrate"
            SILENT -> "alarms_silent"
        }

        /**
         * Kanal-ID für Signal und Ton-Version: Kanäle mit Ton bekommen je gewähltem
         * Alarmton eine neue Version («alarms_sound_v3»); Kanäle ohne Ton bleiben stabil.
         */
        fun channelId(mode: AlarmSignal, soundVersion: Int): String {
            val base = channelBase(mode)
            return if (!mode.sound || soundVersion <= 0) base else "${base}_v$soundVersion"
        }

        /** Gehört [id] zum Kanal von [mode] (irgendeine Ton-Version)? «alarms_vibrate» ist keine Version von «alarms». */
        fun isChannelOf(mode: AlarmSignal, id: String): Boolean {
            val base = channelBase(mode)
            if (id == base) return true
            if (!mode.sound || !id.startsWith("${base}_v")) return false
            val version = id.removePrefix("${base}_v")
            return version.isNotEmpty() && version.all { it in '0'..'9' }
        }

        /**
         * Alte Ton-Versionen desselben Signals, die beim Anlegen von [channelId] wegkommen.
         * Kanäle anderer Signale bleiben (dort hat man vielleicht etwas eingestellt).
         */
        fun staleChannelIds(existing: Collection<String>, mode: AlarmSignal, soundVersion: Int): List<String> {
            val current = channelId(mode, soundVersion)
            return existing.filter { it != current && isChannelOf(mode, it) }
        }
    }
}
