package com.cryptochecker.app.ui.features.settings

import android.content.Context
import androidx.core.content.edit
import com.cryptochecker.app.BuildConfig
import com.cryptochecker.marketdata.config.ExchangeRetirement

/**
 * Debug-Schalter «Stichtag vorziehen» (Entwickler-Einstellungen, nur im Debug-Build): stellt die Uhr der
 * Stilllegungs-Regel ([ExchangeRetirement.clock]) vor — nicht die des Geräts, damit TLS-Zertifikate und alle übrigen
 * Börsen normal weiterlaufen. Stufen: aus → Ankündigung (kurz nach dem 1. Januar 2027) → Stichtag (kurz nach dem
 * 1. Februar 2027) → aus. Gespeichert wird der Versatz zur Gerätezeit (die Uhr der Regel läuft also weiter) in einer
 * eigenen Einstellungsdatei; [apply] setzt ihn beim Start der App (Widgets laufen im selben Prozess). Die Uhr Wear OS
 * folgt nicht (eigenes Gerät); sie bekommt vom Handy nur «kein Kurs». Wie iOS `RetirementPreview`.
 */
internal object RetirementPreview {
    /** Stufen des Schalters mit dem Zeitpunkt, auf den die Uhr der Regel springt (null = Gerätezeit). */
    enum class Step(val target: Long?) {
        OFF(null),
        NOTICE(ExchangeRetirement.BITPANDA_NOTICE_MILLIS + MARGIN_MILLIS),
        END(ExchangeRetirement.BITPANDA_END_MILLIS + MARGIN_MILLIS),
    }

    /** Gespeicherten Versatz anwenden (CryptoCheckerApp.onCreate); im Release-Build nichts. */
    fun apply(context: Context) {
        if (!BuildConfig.DEBUG) return
        setClock(prefs(context).getLong(KEY_OFFSET, 0L))
    }

    /** Aktuelle Stufe. */
    fun current(context: Context): Step =
        Step.entries.getOrNull(prefs(context).getInt(KEY_STEP, 0)) ?: Step.OFF

    /** Nächste Stufe wählen und die Uhr der Regel gleich darauf stellen; liefert die neue Stufe. */
    fun next(context: Context): Step {
        if (!BuildConfig.DEBUG) return Step.OFF
        val step = Step.entries[(current(context).ordinal + 1) % Step.entries.size]
        val offset = step.target?.let { it - System.currentTimeMillis() } ?: 0L
        prefs(context).edit {
            putInt(KEY_STEP, step.ordinal)
            putLong(KEY_OFFSET, offset)
        }
        setClock(offset)
        return step
    }

    private fun setClock(offset: Long) {
        if (offset == 0L) {
            ExchangeRetirement.resetClock()
        } else {
            ExchangeRetirement.clock = { System.currentTimeMillis() + offset }
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "debug_retirement_preview"
    private const val KEY_STEP = "step"
    private const val KEY_OFFSET = "offset"
}

/** Kurz nach dem Zeitpunkt (eine Minute), damit die Regel sicher umgeschaltet hat. */
private const val MARGIN_MILLIS = 60_000L
