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
import com.cryptochecker.app.domain.alarm.AlarmEvaluator
import com.cryptochecker.app.domain.alarm.DerivativesAlarm
import com.cryptochecker.app.domain.alarm.DerivativesAlarmData
import com.cryptochecker.app.domain.alarm.NearExtreme
import com.cryptochecker.app.domain.alarm.QuietHours
import com.cryptochecker.app.notification.AppNotifier
import com.cryptochecker.app.settings.AppSettings
import com.cryptochecker.app.tts.SpokenText
import com.cryptochecker.app.tts.TtsSpeaker
import kotlinx.coroutines.CancellationException
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
) {
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

        // Nicht abwarten: Die Ansage läuft in der Warteschlange der Sprachausgabe (Reihenfolge bleibt)
        fun speakIfWanted(alarm: AlarmEntity) {
            // Nachtruhe: Alarm kommt lautlos, also auch ohne Sprachausgabe
            if (settings.ttsEnabled && alarm.speak && !isQuiet(settings)) {
                ttsSpeaker.enqueue(
                    spokenText.alarm(watch, alarm.condition, price),
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

        for (alarm in enabledAlarms) {
            if (alarm.condition.isDerivatives) {
                // Nicht bei Live-Kursen (WebSocket): die normale Aktualisierung prüft sie
                if (live) continue
                if (!derivativesLoaded) {
                    derivatives = derivativesAlarmData.values(watch, price, now)
                    derivativesLoaded = true
                }
                val values = derivatives ?: continue
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
                    DerivativesAlarm.Decision.Rearm -> watchRepository.rearmAlarm(alarm.id)
                    is DerivativesAlarm.Decision.Fire -> {
                        notifier.showAlarm(watch, alarm, price, derivativesValue = decision.value)
                        watchRepository.markAlarmTriggered(alarm, price, now)
                        count++
                        speakIfWanted(alarm)
                    }
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
                val ranges = nearRanges ?: continue
                val days = NearExtreme.windowDays(alarm.windowHours)
                val raw = ranges.ranges[days] ?: continue
                // Nur USDT-Kerzen: Hoch/Tief in die Quote des Paars umrechnen; ohne Faktor diesmal auslassen
                val range = if (ranges.currency.equals(watch.quoteAsset, ignoreCase = true)) raw else {
                    // rateFor = Faktor Quote → Kerzenwährung; Hoch/Tief also dadurch teilen
                    val rate = rateFor(ranges.currency)?.takeIf { it > 0.0 } ?: continue
                    raw.scaled(1.0 / rate)
                }
                val decision = NearExtreme.decide(
                    side = if (alarm.condition == AlarmCondition.NEAR_HIGH) NearExtreme.Side.HIGH else NearExtreme.Side.LOW,
                    price = price,
                    range = range,
                    thresholdPercent = alarm.threshold,
                    armed = alarm.referenceAt <= 0L,
                    lastLevel = alarm.referencePrice,
                    inCooldown = NearExtreme.inCooldown(alarm.lastTriggeredAt, now, settings.alarmCooldownMinutes),
                    lastTriggeredAt = alarm.lastTriggeredAt,
                    now = now,
                )
                when (decision) {
                    NearExtreme.Decision.None -> Unit
                    NearExtreme.Decision.Rearm -> watchRepository.rearmAlarm(alarm.id)
                    is NearExtreme.Decision.Fire -> {
                        notifier.showAlarm(watch, alarm, price, nearFire = decision)
                        watchRepository.markAlarmTriggered(alarm, price, now, nearLevel = decision.level)
                        count++
                        speakIfWanted(alarm)
                    }
                }
                continue
            }
            if (alarm.condition == AlarmCondition.VOLUME_SPIKE) {
                if (live) continue
                if (!volumeLoaded) {
                    volume = volumeDataSource.hourlySpike(watch.baseAsset, watch.quoteAsset)
                    volumeLoaded = true
                }
                val spike = volume ?: continue
                val spikeFires = alarmEvaluator.shouldTriggerVolumeSpike(
                    alarm = alarm,
                    ratio = spike.ratio,
                    candleOpenTime = spike.candleOpenTime,
                    now = now,
                    cooldownMinutes = settings.alarmCooldownMinutes
                )
                if (!spikeFires) continue

                notifier.showAlarm(watch, alarm, price, volumeRatio = spike.ratio)
                watchRepository.markAlarmTriggered(alarm, price, now, candleOpenTime = spike.candleOpenTime)
                count++
                speakIfWanted(alarm)
                continue
            }

            // Bewegungs-Alarm: abgelaufenes Fenster neu beginnen, ohne auszulösen
            if (alarmEvaluator.needsWindowReset(alarm, now)) {
                watchRepository.setAlarmReference(alarm.id, price, now)
                continue
            }
            // Schwellwert in anderer Währung: Kurs umrechnen; ohne Faktor diesmal auslassen
            val convertTo = alarm.convertCurrency
            val comparePrice = if (convertTo == null) price else {
                val rate = rateFor(convertTo) ?: continue
                price * rate
            }
            // Gemeldeter Kursalarm: erst wieder scharf, wenn der Kurs auf die andere Seite zurück ist
            if (alarmEvaluator.shouldRearmLevel(alarm, comparePrice)) {
                watchRepository.rearmAlarm(alarm.id)
                continue
            }
            val fires = alarmEvaluator.shouldTrigger(
                alarm = alarm,
                price = comparePrice,
                previousPrice = previousPrice,
                now = now,
                cooldownMinutes = settings.alarmCooldownMinutes
            )
            if (!fires) continue

            notifier.showAlarm(watch, alarm, price)
            watchRepository.markAlarmTriggered(alarm, price, now)
            count++
            speakIfWanted(alarm)
        }
        return count
    }

    /**
     * Zeigt die Kurs-Benachrichtigung — je nach Einstellung nur dann, wenn sich
     * der Kurs seit der letzten Meldung deutlich genug bewegt hat.
     * @return true, wenn gemeldet wurde; der Aufrufer setzt dann den neuen
     *   Bezugspunkt (gesammelt für alle Paare in einem Schreibvorgang).
     */
    internal fun updateNotification(watch: WatchEntity, settings: AppSettings, shown: Set<Int>?): Boolean {
        if (!settings.priceNotifications || !watch.notificationEnabled) {
            notifier.cancelPriceIfShown(watch.id, shown)
            return false
        }

        val price = watch.lastPrice
        if (price == null || price <= 0.0) {
            notifier.cancelPriceIfShown(watch.id, shown)
            return false
        }

        val threshold = settings.notificationChangePercent
        val reference = watch.notifiedPrice

        if (threshold > 0 && reference != null && reference > 0.0) {
            val change = abs((price - reference) / reference * 100.0)
            if (change < threshold) return false
        }

        // Erst melden — die Benachrichtigung zeigt den Vergleich zur vorigen —
        // danach wird der neue Bezugspunkt gesetzt.
        notifier.showPrice(watch, ongoing = settings.ongoingNotifications)
        return true
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

    /** Nachtruhe in diesem Moment (Ortszeit des Geräts)? */
    private fun isQuiet(settings: AppSettings): Boolean =
        QuietHours.isQuiet(
            settings.quietHoursEnabled,
            settings.quietHoursStart,
            settings.quietHoursEnd,
            QuietHours.minuteOfDay(),
        )
}
