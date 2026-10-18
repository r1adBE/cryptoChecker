package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.local.model.convertCurrency
import com.cryptochecker.app.data.portfolio.CurrencyConverter
import com.cryptochecker.app.data.remote.NearExtremeDataSource
import com.cryptochecker.app.data.remote.VolumeDataSource
import com.cryptochecker.app.data.remote.VolumeSpike
import com.cryptochecker.app.data.remote.WindowRanges
import com.cryptochecker.app.domain.alarm.AlarmCheckStatus
import com.cryptochecker.app.domain.alarm.AlarmEvaluator
import com.cryptochecker.app.domain.alarm.DerivativesAlarm
import com.cryptochecker.app.domain.alarm.DerivativesAlarmData
import com.cryptochecker.app.domain.alarm.MoveWindow
import com.cryptochecker.app.domain.alarm.NearExtreme
import com.cryptochecker.app.domain.alarm.QuietHours
import com.cryptochecker.app.notification.AppNotifier
import com.cryptochecker.app.notification.NotificationBudget
import com.cryptochecker.app.settings.AppSettings
import com.cryptochecker.app.tts.SpokenText
import com.cryptochecker.app.tts.TtsSpeaker
import com.cryptochecker.app.data.MoveHistoryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Was nach einem neuen Kurs passiert: Alarme prüfen und melden (mit Ansage), die
 * Kurs-Benachrichtigung und die Kursansage — jeweils mit Nachtruhe. Teil von [PriceRefresher].
 */
@Singleton
class RefreshEffects @Inject constructor(
    private val watchRepository: WatchRepository,
    private val alarmEvaluator: AlarmEvaluator,
    private val notifier: AppNotifier,
    private val ttsSpeaker: TtsSpeaker,
    private val spokenText: SpokenText,
    private val volumeDataSource: VolumeDataSource,
    private val currencyConverter: CurrencyConverter,
    private val nearExtremeDataSource: NearExtremeDataSource,
    /** Funding/Open Interest für die Futures-Alarme (je Paar höchstens alle 5 Min.). */
    private val derivativesAlarmData: DerivativesAlarmData,
    /** Kursverlauf je Paar für den Bewegungs-Alarm (gleitendes Fenster, [MoveWindow]). */
    private val moveHistoryStore: MoveHistoryStore,
    /** «Nicht geprüft seit …» für Alarme ohne Devisenkurs bzw. Kerzen (nur im Speicher). */
    private val alarmCheckStatus: AlarmCheckStatus,
) {
    /** Melden-dann-Speichern samt unbestätigter Versuche (nur im Speicher). */
    private val delivery = com.cryptochecker.app.domain.alarm.AlarmDelivery()

    /** Vorab-Abrufe der Alarm-Zusatzdaten ([startAlarmPrefetch]); laufen nach dem Warten fertig. */
    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Zusatzdaten der Alarme (Devisenkurs, Hoch/Tief, Stundenvolumen, Funding/Open Interest) schon
     * PARALLEL zur Kursabfrage holen (Review 13) — nur für Paare mit solchen Alarmen. Im Doze-Modus
     * ist das Netz-Fenster nach den Kursen sonst oft vorbei. Füllt nur die Zwischenspeicher der
     * Quellen; [checkAlarms] liest sie danach. Der Aufrufer wartet höchstens bis zu seiner Frist.
     */
    internal fun startAlarmPrefetch(watches: List<WatchEntity>, alarms: List<AlarmEntity>, now: Long): List<Job> {
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

    internal suspend fun checkAlarms(
        watch: WatchEntity,
        price: Double,
        previousPrice: Double?,
        enabledAlarms: List<AlarmEntity>,
        settings: AppSettings,
        now: Long,
        /**
         * Live-Kurse: Volumen-, Funding- und Open-Interest-Alarme auslassen (die REST-Abfrage prüft
         * sie mit Stundenkerzen bzw. Futures-Daten).
         */
        live: Boolean = false,
    ): Int {
        var count = 0
        // Nachtruhe: Alarme warten bis zum Ende und melden sich dann mit Ton (einmal je Durchlauf bestimmt)
        val holdAlarms = holdsAlarms(settings)

        // Nicht abwarten: Die Ansage läuft in der Warteschlange der Sprachausgabe (Reihenfolge bleibt)
        // [spokenPrice]/[currency]: wie in der Benachrichtigung (Kursmarke mit Alarm-Währung: umgerechnet)
        fun speakIfWanted(alarm: AlarmEntity, spokenPrice: Double, currency: String?) {
            // Nachtruhe: keine Sprachausgabe (Alarme lösen dann ohnehin nicht aus, siehe holdAlarms)
            if (settings.ttsEnabled && alarm.speak && !isQuiet(settings)) {
                ttsSpeaker.enqueue(
                    spokenText.alarm(watch, alarm.condition, spokenPrice, currency),
                    speechRate = settings.ttsSpeechRate,
                    flush = true
                )
            }
        }

        // Volumendaten nur holen, wenn dieses Paar einen Volumen-Alarm hat — und dann nur einmal.
        var volume: VolumeSpike? = null
        var volumeLoaded = false

        // Umrechnungsfaktoren für Kursalarme in einer anderen Währung: je Währung einmal pro Durchlauf.
        val rates = HashMap<String, Double?>()
        suspend fun rateFor(currency: String): Double? {
            val key = currency.uppercase()
            if (rates.containsKey(key)) return rates[key]
            val rate = try {
                currencyConverter.rate(watch.quoteAsset, key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d(e, "Alarm: Umrechnung %s → %s fehlgeschlagen", watch.quoteAsset, key)
                null
            }
            rates[key] = rate
            return rate
        }

        // «Nahe am Hoch/Tief»: Hoch/Tief der Zeiträume nur bei Bedarf, je Paar einmal (6 h zwischengespeichert)
        var nearRanges: WindowRanges? = null
        var nearLoaded = false

        // Funding/Open Interest: nur für Paare mit solchen Alarmen, je Paar höchstens alle 5 Min. abgefragt
        var derivatives: DerivativesAlarmData.Values? = null
        var derivativesLoaded = false

        // Bewegungs-Alarm: Kursverlauf des Paars (nur wenn es so einen Alarm hat)
        val hasMove = enabledAlarms.any { it.condition == AlarmCondition.MOVE_PERCENT_WINDOW }
        val moveHistory = if (hasMove) moveHistoryStore.history(watch.id) else emptyList()

        /**
         * Ausgelösten Alarm melden: Alarme ohne zustellbare Benachrichtigung (keine Erlaubnis,
         * Kanal stumm; einmalige wie wiederholende) bleiben scharf und melden, sobald es wieder geht — statt sich
         * still abzuschalten. Sonst ZUERST melden und erst speichern (einmalige: abschalten), wenn die
         * Meldung unter den offenen Mitteilungen steht ([show] = true, Review 12): Verwirft Android
         * sie (z. B. zu viele offene Mitteilungen), bleibt der Alarm scharf und meldet beim nächsten
         * Durchlauf erneut — frühestens nach der Abklingzeit ([NotificationBudget.retryBlocked]).
         * Hat der Nutzer den Alarm inzwischen geändert oder abgeschaltet, speichert [mark] nichts.
         */
        suspend fun report(
            alarm: AlarmEntity,
            mark: suspend () -> Boolean,
            show: suspend () -> Boolean,
            spokenPrice: Double = price,
            currency: String? = null,
        ) {
            // Nachtruhe oder keine zustellbare Meldung (auch bei wiederholenden): nicht auslösen,
            // Alarm bleibt unverändert scharf (QuietHours.mayFire)
            // Nachtruhe / keine zustellbare Meldung / unbestätigter Versuch: Alarm bleibt scharf. Sonst zuerst melden,
            // erst nach bestätigter Meldung speichern ([AlarmDelivery], auch im Ketten-Test geprüft)
            val saved = delivery.deliver(
                alarm.id, now, settings.alarmCooldownMinutes, holdAlarms,
                canShow = { notifier.canShowAlarm(alarm) }, show = show, mark = mark,
                retract = { notifier.cancelAlarms(listOf(alarm.id)) },
            )
            if (!saved) return
            count++
            speakIfWanted(alarm, spokenPrice, currency)
        }

        // Ausgelassen (kein Devisenkurs/keine Kerzen) bzw. wieder geprüft: für den Hinweis in der Alarmliste
        fun unchecked(alarm: AlarmEntity, reason: AlarmCheckStatus.Reason) =
            alarmCheckStatus.markUnchecked(alarm.id, reason, now)

        for (alarm in enabledAlarms) {
            // Ein scheiternder Alarm (Datenbank, Daten einer Quelle) hält die übrigen des Paars nicht auf
            try {
                if (alarm.condition.isDerivatives) {
                    // Nicht bei Live-Kursen (WebSocket): die normale Aktualisierung prüft sie
                    if (live) continue
                    if (!derivativesLoaded) {
                        derivatives = derivativesAlarmData.values(watch, price, now)
                        derivativesLoaded = true
                    }
                    // null = Paar ohne solche Daten (kein Perpetual einer unterstützten Börse)
                    val values = derivatives ?: continue
                    // Nur als geprüft vermerken, wenn der benötigte Wert da ist (Funding bzw. Open Interest);
                    // sonst «nicht geprüft» statt stiller Lücke
                    val raw = if (alarm.condition.isFunding) values.fundingPercent else values.oiUnits
                    if (raw == null) {
                        unchecked(alarm, AlarmCheckStatus.Reason.NO_DATA)
                        continue
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
                        is DerivativesAlarm.Decision.Fire -> report(
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
                    continue
                }
                if (alarm.condition.isNearExtreme) {
                    if (!nearLoaded) {
                        nearRanges = try {
                            nearExtremeDataSource.ranges(watch.baseAsset, watch.quoteAsset)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.d(e, "Alarm: Hoch/Tief für %s nicht verfügbar", watch.displayName)
                            null
                        }
                        nearLoaded = true
                    }
                    val ranges = nearRanges
                    val raw = ranges?.ranges?.get(NearExtreme.windowDays(alarm.windowHours))
                    if (ranges == null || raw == null) {
                        unchecked(alarm, AlarmCheckStatus.Reason.NO_CANDLES)
                        continue
                    }
                    // Nur USDT-Kerzen: Hoch/Tief in die Quote des Paars umrechnen; ohne Faktor diesmal auslassen.
                    // rateFor = Faktor Quote → Kerzenwährung; Hoch/Tief also dadurch teilen
                    val sameCurrency = ranges.currency.equals(watch.quoteAsset, ignoreCase = true)
                    val rate = if (sameCurrency) 1.0 else rateFor(ranges.currency)?.takeIf { it > 0.0 }
                    if (rate == null) {
                        unchecked(alarm, AlarmCheckStatus.Reason.NO_RATE)
                        continue
                    }
                    // Reihe der Ausweich-Kette nur bei gleichem Namen und Kurs ±25 % (wie der Mini-Chart) — sonst
                    // kein Hoch/Tief-Alarm statt der Werte eines anderen Tokens
                    if (!nearExtremeDataSource.seriesAllowed(ranges, watch.marketKey, watch.baseAsset, price, rate)) {
                        unchecked(alarm, AlarmCheckStatus.Reason.NO_CANDLES)
                        continue
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
                        is NearExtreme.Decision.Fire -> report(
                            alarm,
                            mark = { watchRepository.markAlarmTriggered(alarm, price, now, nearLevel = decision.level) },
                            show = { notifier.showAlarm(watch, alarm, price, nearFire = decision) },
                        )
                    }
                    continue
                }
                if (alarm.condition == AlarmCondition.VOLUME_SPIKE) {
                    if (live) continue
                    if (!volumeLoaded) {
                        volume = volumeDataSource.hourlySpike(watch, price)
                        volumeLoaded = true
                    }
                    val spike = volume
                    if (spike == null) {
                        unchecked(alarm, AlarmCheckStatus.Reason.NO_CANDLES)
                        continue
                    }
                    alarmCheckStatus.markChecked(alarm.id)
                    val spikeFires = alarmEvaluator.shouldTriggerVolumeSpike(
                        alarm = alarm,
                        ratio = spike.ratio,
                        candleOpenTime = spike.candleOpenTime,
                        now = now,
                        cooldownMinutes = settings.alarmCooldownMinutes
                    )
                    if (!spikeFires) continue

                    report(
                        alarm,
                        mark = { watchRepository.markAlarmTriggered(alarm, price, now, candleOpenTime = spike.candleOpenTime) },
                        show = { notifier.showAlarm(watch, alarm, price, volumeRatio = spike.ratio) },
                    )
                    continue
                }

                // Bewegungs- bzw. Prozentalarm ohne Bezug: aktuellen Kurs als Bezug setzen, ohne auszulösen
                if (alarmEvaluator.needsReference(alarm, now)) {
                    watchRepository.setAlarmReferenceIfUnchanged(alarm, price, now)
                    continue
                }
                // Schwellwert in anderer Währung: Kurs umrechnen; ohne Faktor diesmal auslassen.
                // Vorheriger Kurs mit demselben Faktor — verglichen wird durchgehend in der Alarm-Währung
                val convertTo = alarm.convertCurrency
                val rate = if (convertTo == null) 1.0 else rateFor(convertTo)?.takeIf { it.isFinite() && it > 0.0 }
                if (rate == null) {
                    unchecked(alarm, AlarmCheckStatus.Reason.NO_RATE)
                    continue
                }
                val comparePrice = if (convertTo == null) price else price * rate
                val comparePrevious = if (convertTo == null) previousPrice else previousPrice?.let { it * rate }
                alarmCheckStatus.markChecked(alarm.id)
                // Gemeldeter Kursalarm: erst wieder scharf, wenn der Kurs auf die andere Seite zurück ist
                if (alarmEvaluator.shouldRearmLevel(alarm, comparePrice, now)) {
                    watchRepository.rearmAlarmIfUnchanged(alarm)
                    continue
                }
                val fires = alarmEvaluator.shouldTrigger(
                    alarm = alarm,
                    price = comparePrice,
                    previousPrice = comparePrevious,
                    now = now,
                    cooldownMinutes = settings.alarmCooldownMinutes,
                    moveHistory = moveHistory,
                )
                if (!fires) continue

                // Gespeichert wird der Kurs in der Quote (wie bisher); gemeldet und angesagt in der Währung
                // der Schwelle — sonst stünde «Über 60’000 CHF — jetzt 66’000 USDT» in der Meldung
                report(
                    alarm,
                    mark = { watchRepository.markAlarmTriggered(alarm, price, now) },
                    show = { notifier.showAlarm(watch, alarm, comparePrice, priceCurrency = convertTo) },
                    spokenPrice = comparePrice,
                    currency = convertTo,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Alarm %d für %s nicht geprüft", alarm.id, watch.displayName)
            }
        }
        // Erst nach der Prüfung: der aktuelle Kurs ist ohnehin der Vergleichswert
        if (hasMove) moveHistoryStore.append(watch.id, MoveWindow.PricePoint(price, now), now)
        return count
    }

    /** Was mit der Kurs-Benachrichtigung eines Paars geschieht ([priceUpdate]). */
    internal enum class PriceUpdate { NONE, SHOW, CANCEL }

    /**
     * Kurs-Benachrichtigung zeigen — je nach Einstellung nur dann, wenn sich der Kurs seit der
     * letzten Meldung deutlich genug bewegt hat — oder entfernen. Gemeldet wird gesammelt nach
     * den Alarmen (AppNotifier.showPrices: höchstens 20 einzeln, Alarme haben Vorrang); der
     * Aufrufer setzt danach den neuen Bezugspunkt der gezeigten Paare.
     */
    internal fun priceUpdate(watch: WatchEntity, settings: AppSettings): PriceUpdate {
        if (!settings.priceNotifications || !watch.notificationEnabled) return PriceUpdate.CANCEL

        val price = watch.lastPrice
        if (price == null || price <= 0.0) return PriceUpdate.CANCEL

        val threshold = settings.notificationChangePercent
        val reference = watch.notifiedPrice

        if (threshold > 0 && reference != null && reference > 0.0) {
            val change = abs((price - reference) / reference * 100.0)
            if (change < threshold) return PriceUpdate.NONE
        }
        return PriceUpdate.SHOW
    }

    /** Kursansage in die Warteschlange der Sprachausgabe — die Aktualisierung wartet nicht darauf. */
    internal fun speakPriceIfWanted(
        watch: WatchEntity,
        price: Double,
        settings: AppSettings,
        spokenAlready: Boolean,
    ) {
        if (spokenAlready) return
        if (!settings.ttsEnabled || settings.ttsAlarmsOnly) return
        if (!watch.ttsEnabled) return
        // Nachtruhe: auch normale Kursansagen schweigen
        if (isQuiet(settings)) return

        ttsSpeaker.enqueue(spokenText.price(watch, price), speechRate = settings.ttsSpeechRate, key = watch.id)
    }

    /** Nachtruhe: Alarme jetzt zurückhalten (gemeinsame Regel [QuietHours.holdsAlarms])? */
    private fun holdsAlarms(settings: AppSettings): Boolean =
        QuietHours.holdsAlarms(
            settings.quietHoursEnabled,
            settings.quietHoursStart,
            settings.quietHoursEnd,
            QuietHours.minuteOfDay(),
        )

    /** Nachtruhe in diesem Moment (Ortszeit des Geräts)? */
    private fun isQuiet(settings: AppSettings): Boolean =
        QuietHours.isQuiet(
            settings.quietHoursEnabled,
            settings.quietHoursStart,
            settings.quietHoursEnd,
            QuietHours.minuteOfDay(),
        )
}
