package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.local.model.convertCurrency
import com.cryptochecker.app.domain.alarm.AlarmEvaluator
import com.cryptochecker.app.domain.alarm.AlarmFreshness
import com.cryptochecker.app.domain.alarm.AlarmLog
import com.cryptochecker.app.domain.alarm.AlarmLogEntry
import com.cryptochecker.app.domain.alarm.AlarmLogTrace
import timber.log.Timber

/** Nimmt beobachtete Alarm-Prüfungen entgegen ([RefreshEffects.checkAlarms]); darf nie werfen. */
fun interface AlarmTraceSink {
    fun note(alarm: AlarmEntity, trace: AlarmLogTrace, comparePrice: Double?)
}

/**
 * Sammelt die Einträge des Alarm-Protokolls eines Durchlaufs ([RefreshResultProcessor]); geschrieben wird am Ende einmal
 * ([RefreshAlarmLog]). Nur beobachten: keine Entscheidung hängt hieran, jeder Fehler bleibt hier (wird nur protokolliert).
 * Nur Kursmarken und Prozent-Alarme ([AlarmLog.covers]).
 */
class AlarmLogCollector(private val now: Long) {
    private val evaluator = AlarmEvaluator()
    private val collected = ArrayList<AlarmLogEntry>()

    val entries: List<AlarmLogEntry> get() = collected

    /** Prüfung eines Alarms auf [watch] (mit neuem Kurs bzw. ohne Kurs). */
    fun note(watch: WatchEntity, alarm: AlarmEntity, trace: AlarmLogTrace, comparePrice: Double? = null) {
        safely {
            if (!AlarmLog.covers(alarm.condition)) return@safely
            val outcome = AlarmLog.outcome(trace)
            val currency = alarm.convertCurrency
            collected += AlarmLogEntry(
                alarmId = alarm.id,
                at = now,
                pair = watch.displayName,
                exchange = watch.marketName,
                quote = watch.quoteAsset,
                price = if (trace.noPrice) null else watch.lastPrice,
                condition = alarm.condition,
                threshold = alarm.threshold,
                windowHours = alarm.windowHours,
                currency = currency,
                shownPrice = if (currency != null) comparePrice else null,
                outcome = outcome,
                exchangeTime = watch.exchangeTime,
                notable = AlarmLog.notable(outcome, trace),
            )
        }
    }

    /** Für [RefreshEffects.checkAlarms]: Prüfungen der Alarme dieses Paars. */
    fun sinkFor(watch: WatchEntity): AlarmTraceSink = AlarmTraceSink { alarm, trace, comparePrice ->
        note(watch, alarm, trace, comparePrice)
    }

    /**
     * Paar mit neuem Kurs, dessen Alarme gesperrt sind (DEX-Liquidität, Mark-Preis, Kurs laut Börse zu alt). Ob ein
     * Alarm sonst fällig gewesen wäre, sagt eine Vorschau mit denselben reinen Regeln ([AlarmEvaluator.shouldTrigger]) —
     * nur für Alarme ohne Umrechnung und ohne Kursverlauf (Kursmarke, Steigt/Fällt um); sonst nur «zuletzt geprüft».
     */
    fun noteBlocked(
        watch: WatchEntity,
        alarms: List<AlarmEntity>,
        lowLiquidity: Boolean,
        markFallback: Boolean,
        cooldownMinutes: Int,
    ) {
        safely {
            val stale = AlarmFreshness.exchangeTimeTooOld(watch.exchangeTime, now)
            val price = watch.lastPrice ?: return@safely
            for (alarm in alarms) {
                if (!AlarmLog.covers(alarm.condition)) continue
                val due = safelyBoolean {
                    alarm.convertCurrency == null &&
                        alarm.condition != com.cryptochecker.app.data.local.model.AlarmCondition.MOVE_PERCENT_WINDOW &&
                        !evaluator.needsReference(alarm, now) &&
                        evaluator.shouldTrigger(alarm, price, watch.previousPrice, now, cooldownMinutes)
                }
                note(
                    watch, alarm,
                    AlarmLogTrace(lowLiquidity = lowLiquidity, markPrice = markFallback, stale = stale, due = due),
                )
            }
        }
    }

    /** Paar ohne Kurs (Abfrage fehlgeschlagen): «Kein Kurs» als letzte Prüfung seiner Alarme. */
    fun noteNoPrice(watch: WatchEntity, alarms: List<AlarmEntity>) {
        safely { alarms.forEach { note(watch, it, AlarmLogTrace(noPrice = true)) } }
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Timber.d(e, "Alarm-Protokoll: Eintrag nicht erfasst")
        }
    }

    private inline fun safelyBoolean(block: () -> Boolean): Boolean = try {
        block()
    } catch (e: Exception) {
        false
    }
}
