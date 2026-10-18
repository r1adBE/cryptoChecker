// Zusatzdaten vorab und Prüfungen je Alarmart (Teil von [RefreshEffects]): Funding/Open Interest, «Nahe am Hoch/Tief» und
// Volumen-Spike. Die Zusatzdaten lädt [AlarmCheckRun] je Paar und Durchlauf höchstens einmal; gemeldet wird über
// denselben Weg wie die Kursalarme ([AlarmCheckRun.report]).
package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.remote.VolumeSpike
import com.cryptochecker.app.data.remote.WindowRanges
import com.cryptochecker.app.domain.alarm.AlarmCheckStatus
import com.cryptochecker.app.domain.alarm.DerivativesAlarm
import com.cryptochecker.app.domain.alarm.DerivativesAlarmData
import com.cryptochecker.app.domain.alarm.NearExtreme
import com.cryptochecker.app.settings.AppSettings
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import com.cryptochecker.app.data.local.model.convertCurrency
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Zusatzdaten der Alarme (Devisenkurs, Hoch/Tief, Stundenvolumen, Funding/Open Interest) schon
 * PARALLEL zur Kursabfrage holen — nur für Paare mit solchen Alarmen. Im Doze-Modus
 * ist das Netz-Fenster nach den Kursen sonst oft vorbei. Füllt nur die Zwischenspeicher der
 * Quellen; [checkAlarms] liest sie danach. Der Aufrufer wartet höchstens bis zu seiner Frist.
 */
internal fun RefreshEffects.startAlarmPrefetch(watches: List<WatchEntity>, alarms: List<AlarmEntity>, now: Long): List<Job> {
    val byWatch = alarms.filter { it.enabled }.groupBy { it.watchId }
    val jobs = ArrayList<Job>()
    // Je Ziel einmal (mehrere Paare teilen Hoch/Tief bzw. Devisenkurs)
    val started = HashSet<String>()
    fun launch(key: String, block: suspend () -> Unit) {
        if (!started.add(key)) return
        jobs += prefetchScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d(e, "Alarm-Zusatzdaten (%s) vorab nicht geholt", key)
            }
        }
    }
    for (watch in watches) {
        val list = byWatch[watch.id] ?: continue
        for (alarm in list) {
            when {
                alarm.condition.isDerivatives ->
                    launch("D|${watch.id}") { derivativesAlarmData.prefetch(watch, now) }
                alarm.condition.isNearExtreme ->
                    launch("N|${watch.baseAsset.uppercase()}|${watch.quoteAsset.uppercase()}") {
                        nearExtremeDataSource.ranges(watch.baseAsset, watch.quoteAsset)
                    }
                alarm.condition == AlarmCondition.VOLUME_SPIKE ->
                    launch("V|${watch.id}") { volumeDataSource.hourlySpike(watch) }
                else -> alarm.convertCurrency?.let { target ->
                    launch("F|${watch.quoteAsset.uppercase()}|${target.uppercase()}") {
                        currencyConverter.rate(watch.quoteAsset, target)
                    }
                }
            }
        }
    }
    return jobs
}

/** Zustand eines Prüf-Durchlaufs je Paar ([RefreshEffects.checkAlarms]): einmal geladene Zusatzdaten und die Melde-Wege. */
internal class AlarmCheckRun(
    private val reportBlock: suspend (AlarmEntity, suspend () -> Boolean, suspend () -> Boolean) -> Unit,
    private val uncheckedBlock: (AlarmEntity, AlarmCheckStatus.Reason) -> Unit,
    private val rateBlock: suspend (String) -> Double?,
) {
    // Volumendaten nur holen, wenn dieses Paar einen Volumen-Alarm hat — und dann nur einmal.
    var volume: VolumeSpike? = null
    var volumeLoaded = false

    // «Nahe am Hoch/Tief»: Hoch/Tief der Zeiträume nur bei Bedarf, je Paar einmal (6 h zwischengespeichert)
    var nearRanges: WindowRanges? = null
    var nearLoaded = false

    // Funding/Open Interest: nur für Paare mit solchen Alarmen, je Paar höchstens alle 5 Min. abgefragt
    var derivatives: DerivativesAlarmData.Values? = null
    var derivativesLoaded = false

    /** Ausgelösten Alarm melden (wie die Kursalarme, siehe `report` in [RefreshEffects.checkAlarms]). */
    suspend fun report(alarm: AlarmEntity, mark: suspend () -> Boolean, show: suspend () -> Boolean) = reportBlock(alarm, mark, show)

    /** Ausgelassen (keine Daten/Kerzen/Devisenkurs): für den Hinweis in der Alarmliste. */
    fun unchecked(alarm: AlarmEntity, reason: AlarmCheckStatus.Reason) = uncheckedBlock(alarm, reason)

    /** Umrechnungsfaktor Quote → [currency], je Währung einmal pro Durchlauf. */
    suspend fun rateFor(currency: String): Double? = rateBlock(currency)
}

/** Funding- bzw. Open-Interest-Alarm (nicht bei Live-Kursen; das prüft der Aufrufer). */
internal suspend fun RefreshEffects.checkDerivativesAlarm(
    alarm: AlarmEntity,
    watch: WatchEntity,
    price: Double,
    settings: AppSettings,
    now: Long,
    run: AlarmCheckRun,
) {
    if (!run.derivativesLoaded) {
        run.derivatives = derivativesAlarmData.values(watch, price, now)
        run.derivativesLoaded = true
    }
    // null = Paar ohne solche Daten (kein Perpetual einer unterstützten Börse)
    val values = run.derivatives ?: return
    // Nur als geprüft vermerken, wenn der benötigte Wert da ist (Funding bzw. Open Interest);
    // sonst «nicht geprüft» statt stiller Lücke
    val raw = if (alarm.condition.isFunding) values.fundingPercent else values.oiUnits
    if (raw == null) {
        run.unchecked(alarm, AlarmCheckStatus.Reason.NO_DATA)
        return
    }
    // Kein Hinweis «nicht geprüft» (z. B. von einer früheren Kursmarke) stehen lassen
    alarmCheckStatus.markChecked(alarm.id)
    // Open Interest: ohne alte genug Messung (Verlauf im Aufbau) null — geprüft, aber noch keine Entscheidung
    val value = if (alarm.condition.isFunding) values.fundingPercent
    else derivativesAlarmData.oiChange(watch, values, DerivativesAlarm.oiWindowHours(alarm.windowHours), now)
    when (val decision = DerivativesAlarm.decide(
        condition = alarm.condition,
        threshold = alarm.threshold,
        value = value,
        armed = alarm.referenceAt <= 0L,
        enabled = alarm.enabled,
        lastTriggeredAt = alarm.lastTriggeredAt,
        now = now,
        cooldownMinutes = settings.alarmCooldownMinutes,
    )) {
        DerivativesAlarm.Decision.None -> Unit
        DerivativesAlarm.Decision.Rearm -> watchRepository.rearmAlarmIfUnchanged(alarm)
        is DerivativesAlarm.Decision.Fire -> run.report(
            alarm,
            mark = { watchRepository.markAlarmTriggered(alarm, price, now) },
            show = {
                notifier.showAlarm(
                    watch, alarm, price,
                    derivativesValue = decision.value,
                    derivativesReference = values.referenceSource,
                )
            },
        )
    }
}

/** «Nahe am Hoch/Tief»: Hoch/Tief der Zeiträume in der Quote des Paars. */
internal suspend fun RefreshEffects.checkNearExtremeAlarm(
    alarm: AlarmEntity,
    watch: WatchEntity,
    price: Double,
    settings: AppSettings,
    now: Long,
    run: AlarmCheckRun,
) {
    if (!run.nearLoaded) {
        run.nearRanges = try {
            nearExtremeDataSource.ranges(watch.baseAsset, watch.quoteAsset)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "Alarm: Hoch/Tief für %s nicht verfügbar", watch.displayName)
            null
        }
        run.nearLoaded = true
    }
    val ranges = run.nearRanges
    val raw = ranges?.ranges?.get(NearExtreme.windowDays(alarm.windowHours))
    if (ranges == null || raw == null) {
        run.unchecked(alarm, AlarmCheckStatus.Reason.NO_CANDLES)
        return
    }
    // Nur USDT-Kerzen: Hoch/Tief in die Quote des Paars umrechnen; ohne Faktor diesmal auslassen.
    // rateFor = Faktor Quote → Kerzenwährung; Hoch/Tief also dadurch teilen
    val sameCurrency = ranges.currency.equals(watch.quoteAsset, ignoreCase = true)
    val rate = if (sameCurrency) 1.0 else run.rateFor(ranges.currency)?.takeIf { it > 0.0 }
    if (rate == null) {
        run.unchecked(alarm, AlarmCheckStatus.Reason.NO_RATE)
        return
    }
    // Reihe der Ausweich-Kette nur bei gleichem Namen und Kurs ±25 % (wie der Mini-Chart) — sonst
    // kein Hoch/Tief-Alarm statt der Werte eines anderen Tokens
    if (!nearExtremeDataSource.seriesAllowed(ranges, watch.marketKey, watch.baseAsset, price, rate)) {
        run.unchecked(alarm, AlarmCheckStatus.Reason.NO_CANDLES)
        return
    }
    val range = if (sameCurrency) raw else raw.scaled(1.0 / rate)
    alarmCheckStatus.markChecked(alarm.id)
    val decision = NearExtreme.decide(
        side = if (alarm.condition == AlarmCondition.NEAR_HIGH) NearExtreme.Side.HIGH else NearExtreme.Side.LOW,
        price = price,
        range = range,
        thresholdPercent = alarm.threshold,
        armed = alarm.referenceAt <= 0L,
        lastLevel = NearExtreme.reportedMark(alarm.referencePrice, alarm.lastTriggeredAt, alarm.windowHours, now),
        inCooldown = NearExtreme.inCooldown(alarm.lastTriggeredAt, now, settings.alarmCooldownMinutes),
        lastTriggeredAt = alarm.lastTriggeredAt,
        now = now,
    )
    when (decision) {
        NearExtreme.Decision.None -> Unit
        NearExtreme.Decision.Rearm -> watchRepository.rearmAlarmIfUnchanged(alarm)
        is NearExtreme.Decision.Fire -> run.report(
            alarm,
            mark = { watchRepository.markAlarmTriggered(alarm, price, now, nearLevel = decision.level) },
            show = { notifier.showAlarm(watch, alarm, price, nearFire = decision) },
        )
    }
}

/** Volumen-Spike der letzten Stunde (nicht bei Live-Kursen; das prüft der Aufrufer). */
internal suspend fun RefreshEffects.checkVolumeAlarm(
    alarm: AlarmEntity,
    watch: WatchEntity,
    price: Double,
    settings: AppSettings,
    now: Long,
    run: AlarmCheckRun,
) {
    if (!run.volumeLoaded) {
        run.volume = volumeDataSource.hourlySpike(watch, price)
        run.volumeLoaded = true
    }
    val spike = run.volume
    if (spike == null) {
        run.unchecked(alarm, AlarmCheckStatus.Reason.NO_CANDLES)
        return
    }
    alarmCheckStatus.markChecked(alarm.id)
    val spikeFires = alarmEvaluator.shouldTriggerVolumeSpike(
        alarm = alarm,
        ratio = spike.ratio,
        candleOpenTime = spike.candleOpenTime,
        now = now,
        cooldownMinutes = settings.alarmCooldownMinutes
    )
    if (!spikeFires) return

    run.report(
        alarm,
        mark = { watchRepository.markAlarmTriggered(alarm, price, now, candleOpenTime = spike.candleOpenTime) },
        show = { notifier.showAlarm(watch, alarm, price, volumeRatio = spike.ratio) },
    )
}
