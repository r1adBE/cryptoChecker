package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.local.model.convertCurrency
import com.cryptochecker.app.domain.alarm.AlarmCheckStatus
import com.cryptochecker.app.domain.alarm.AlarmDeliveryMarker
import com.cryptochecker.app.domain.alarm.AlarmDeliveryMarkerStore
import com.cryptochecker.app.domain.alarm.AlarmEvaluator
import com.cryptochecker.app.domain.alarm.AlarmLog
import com.cryptochecker.app.domain.alarm.AlarmLogTrace
import com.cryptochecker.app.domain.alarm.AlarmSnooze
import com.cryptochecker.app.domain.alarm.AlarmTextNote
import com.cryptochecker.app.domain.convert.FiatAlarmGuard
import com.cryptochecker.app.domain.alarm.MoveWindow
import com.cryptochecker.app.domain.alarm.QuietHours
import com.cryptochecker.app.settings.AppSettings
import com.cryptochecker.app.util.appScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import timber.log.Timber
import kotlin.math.abs

/**
 * Was nach einem neuen Kurs passiert: Alarme prüfen und melden (mit Ansage), die
 * Kurs-Benachrichtigung und die Kursansage — jeweils mit Nachtruhe. Teil von [PriceRefresher].
 */
class RefreshEffects constructor(
    val watchRepository: RefreshStore,
    val alarmEvaluator: AlarmEvaluator,
    val notifier: RefreshNotifier,
    private val ttsSpeaker: RefreshSpeaker,
    private val spokenText: RefreshSpokenText,
    val volumeDataSource: AlarmVolumeSource,
    val currencyConverter: AlarmRateSource,
    val nearExtremeDataSource: AlarmRangeSource,
    /** Funding/Open Interest für die Futures-Alarme (je Paar höchstens alle 5 Min.). */
    val derivativesAlarmData: AlarmDerivativesSource,
    /** Kursverlauf je Paar für den Bewegungs-Alarm (gleitendes Fenster, [MoveWindow]). */
    private val moveHistoryStore: AlarmMoveHistory,
    /** «Nicht geprüft seit …» für Alarme ohne Devisenkurs bzw. Kerzen (nur im Speicher). */
    val alarmCheckStatus: AlarmCheckStatus,
    /** Vermerk «wird gemeldet», in der App dauerhaft ([AlarmDeliveryMarkers]). */
    deliveryMarkers: AlarmDeliveryMarkerStore = AlarmDeliveryMarkerStore.InMemory(),
) {
    /** Melden-dann-Speichern samt unbestätigter Versuche (dauerhaft vermerkt). */
    private val delivery = com.cryptochecker.app.domain.alarm.AlarmDelivery(deliveryMarkers)

    /** Vorab-Abrufe der Alarm-Zusatzdaten ([startAlarmPrefetch]); laufen nach dem Warten fertig. */
    val prefetchScope = appScope(Dispatchers.IO, "RefreshEffects")

    suspend fun checkAlarms(
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
        /**
         * Alarm-Protokoll ([AlarmLogCollector]): beobachtet Kursmarken und Prozent-Alarme an den bestehenden
         * Entscheidungsstellen — ändert nichts daran; Fehler darin bleiben dort ([observe]).
         */
        trace: AlarmTraceSink? = null,
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

        // Umrechnungsfaktoren für Kursalarme in einer anderen Währung: je Währung einmal pro Durchlauf.
        val rates = HashMap<String, Double?>()
        // Aster «U» zählt als USD ([CurrencyConversion.conversionQuote])
        val conversionQuote = com.cryptochecker.app.domain.convert.CurrencyConversion.conversionQuote(watch.marketKey, watch.quoteAsset)
        suspend fun rateFor(currency: String): Double? {
            val key = currency.uppercase()
            if (rates.containsKey(key)) return rates[key]
            val rate = try {
                currencyConverter.rate(conversionQuote, key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d(e, "Alarm: Umrechnung %s → %s fehlgeschlagen", watch.quoteAsset, key)
                null
            }
            rates[key] = rate
            return rate
        }

        // Bewegungs-Alarm: Kursverlauf des Paars (nur wenn es so einen Alarm hat)
        val hasMove = enabledAlarms.any { it.condition == AlarmCondition.MOVE_PERCENT_WINDOW }
        val moveHistory = if (hasMove) moveHistoryStore.history(watch.id) else emptyList()

        /**
         * Ausgelösten Alarm melden: Alarme ohne zustellbare Benachrichtigung (keine Erlaubnis,
         * Kanal stumm; einmalige wie wiederholende) bleiben scharf und melden, sobald es wieder geht — statt sich
         * still abzuschalten. Sonst ZUERST melden und erst speichern (einmalige: abschalten), wenn die
         * Meldung unter den offenen Mitteilungen steht ([show] = true): Verwirft Android
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
            /** Alarm-Protokoll: Kurs in der Währung der Schwelle; null = dieser Alarm wird nicht protokolliert. */
            logPrice: Double? = null,
        ) {
            // Lauf abgebrochen (Worker gestoppt) → nicht mehr melden, Alarm bleibt scharf (wie iOS)
            currentCoroutineContext().ensureActive()
            // Fürs Protokoll nur mitlesen, was die Schritte liefern (null = Schritt nicht erreicht)
            var canShowSeen: Boolean? = null
            var shownSeen: Boolean? = null
            // «1 h später» aus der Mitteilung: nur das Melden wartet (wie die Nachtruhe), die Prüfung bleibt gleich
            val snoozed = AlarmSnooze.isSnoozed(notifier.alarmSnoozeUntil(alarm.id), now)
            // Nachtruhe oder keine zustellbare Meldung (auch bei wiederholenden): nicht auslösen,
            // Alarm bleibt unverändert scharf (QuietHours.mayFire)
            // Nachtruhe / keine zustellbare Meldung / unbestätigter Versuch: Alarm bleibt scharf. Sonst zuerst melden,
            // erst nach bestätigter Meldung speichern ([AlarmDelivery], auch im Ketten-Test geprüft)
            val saved = delivery.deliver(
                alarm.id, now, settings.alarmCooldownMinutes, holdAlarms,
                canShow = { notifier.canShowAlarm(alarm).also { canShowSeen = it } },
                show = { show().also { shownSeen = it } },
                mark = mark,
                retract = { notifier.cancelAlarms(listOf(alarm.id)) },
                snoozed = snoozed,
                // Nur eine NACH dem Vermerk gemeldete Mitteilung bestätigt (nicht eine ältere desselben Alarms)
                isShown = { markerAt -> AlarmDeliveryMarker.shownSinceMarker(notifier.alarmPostedAt(alarm.id), markerAt) },
                // Vermerk einer schon gespeicherten Auslösung zählt nicht (neue Auslösung wird gemeldet)
                savedSince = { markerAt -> AlarmDeliveryMarker.savedSinceMarker(alarm.lastTriggeredAt, markerAt) },
            )
            if (trace != null && logPrice != null) observe {
                AlarmLog.deliveryTrace(holdAlarms, canShowSeen, shownSeen, saved, snoozed)?.let { trace.note(alarm, it, logPrice) }
            }
            if (!saved) return
            count++
            speakIfWanted(alarm, spokenPrice, currency)
        }

        // Ausgelassen (kein Devisenkurs/keine Kerzen) bzw. wieder geprüft: für den Hinweis in der Alarmliste
        fun unchecked(alarm: AlarmEntity, reason: AlarmCheckStatus.Reason) =
            alarmCheckStatus.markUnchecked(alarm.id, reason, now)

        // Zusatzdaten des Paars (je Durchlauf einmal geladen) und die Melde-Wege für die Prüfungen je Alarmart
        // (RefreshEffectsAlarmKinds.kt)
        val run = AlarmCheckRun(
            { a, mark, show -> report(a, mark, show) },
            { a, reason -> unchecked(a, reason) },
            { currency -> rateFor(currency) },
        )

        for (alarm in enabledAlarms) {
            // Ein scheiternder Alarm (Datenbank, Daten einer Quelle) hält die übrigen des Paars nicht auf
            try {
                if (alarm.condition.isDerivatives) {
                    // Nicht bei Live-Kursen (WebSocket): die normale Aktualisierung prüft sie
                    if (live) continue
                    checkDerivativesAlarm(alarm, watch, price, settings, now, run)
                    continue
                }
                if (alarm.condition.isNearExtreme) {
                    checkNearExtremeAlarm(alarm, watch, price, settings, now, run)
                    continue
                }
                if (alarm.condition == AlarmCondition.VOLUME_SPIKE) {
                    // Nicht bei Live-Kursen (WebSocket): die normale Aktualisierung prüft sie
                    if (live) continue
                    checkVolumeAlarm(alarm, watch, price, settings, now, run)
                    continue
                }

                // Bewegungs- bzw. Prozentalarm ohne Bezug: aktuellen Kurs als Bezug setzen, ohne auszulösen
                if (alarmEvaluator.needsReference(alarm, now)) {
                    watchRepository.setAlarmReferenceIfUnchanged(alarm, price, now)
                    if (trace != null) observe { trace.note(alarm, AlarmLogTrace(), price) }
                    continue
                }
                // Schwellwert in anderer Währung: Kurs umrechnen; ohne Faktor diesmal auslassen.
                // Vorheriger Kurs mit demselben Faktor — verglichen wird durchgehend in der Alarm-Währung
                val convertTo = alarm.convertCurrency
                val rate = if (convertTo == null) 1.0 else rateFor(convertTo)?.takeIf { it.isFinite() && it > 0.0 }
                if (rate == null) {
                    unchecked(alarm, AlarmCheckStatus.Reason.NO_RATE)
                    if (trace != null) observe { trace.note(alarm, AlarmLogTrace(noRate = true), null) }
                    continue
                }
                val comparePrice = if (convertTo == null) price else price * rate
                val comparePrevious = if (convertTo == null) previousPrice else previousPrice?.let { it * rate }
                // Devisenkurs zu alt bzw. USDT/USD nur unsicher über USDC: diesmal nicht prüfen, Alarm bleibt scharf
                val skip = convertTo?.let { currencyConverter.skip(conversionQuote, it, now) }
                if (skip != null) {
                    unchecked(alarm, AlarmCheckStatus.Reason.NO_RATE)
                    if (trace != null) observe {
                        val due = alarmEvaluator.shouldTrigger(
                            alarm, comparePrice, comparePrevious, now, settings.alarmCooldownMinutes, moveHistory,
                        )
                        val fxStale = skip == FiatAlarmGuard.Skip.FX_STALE
                        trace.note(alarm, AlarmLogTrace(fxStale = fxStale, fxUnsure = !fxStale, due = due), comparePrice)
                    }
                    continue
                }
                alarmCheckStatus.markChecked(alarm.id)
                // Gemeldeter Kursalarm: erst wieder scharf, wenn der Kurs auf die andere Seite zurück ist
                if (alarmEvaluator.shouldRearmLevel(alarm, comparePrice, now)) {
                    watchRepository.rearmAlarmIfUnchanged(alarm)
                    if (trace != null) observe { trace.note(alarm, AlarmLogTrace(), comparePrice) }
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
                if (!fires) {
                    // Protokoll: erfüllt, aber in der Ruhezeit? (dieselbe reine Regel ohne Ruhezeit nachgefragt)
                    if (trace != null) observe {
                        val metWithoutCooldown = settings.alarmCooldownMinutes > 0 && alarmEvaluator.shouldTrigger(
                            alarm = alarm,
                            price = comparePrice,
                            previousPrice = comparePrevious,
                            now = now,
                            cooldownMinutes = 0,
                            moveHistory = moveHistory,
                        )
                        val waiting = alarm.condition.isPriceThreshold && !alarmEvaluator.isLevelArmed(alarm)
                        trace.note(alarm, AlarmLogTrace(coolingDown = metWithoutCooldown, waitingRearm = waiting), comparePrice)
                    }
                    continue
                }

                // Gespeichert wird der Kurs in der Quote (wie bisher); gemeldet und angesagt in der Währung
                // der Schwelle — sonst stünde «Über 60’000 CHF — jetzt 66’000 USDT» in der Meldung
                report(
                    alarm,
                    mark = { watchRepository.markAlarmTriggered(alarm, price, now) },
                    show = {
                        // Umgerechnet mit USDT = 1 (USDT/USD nicht gemessen): im Text vermerkt
                        val usdtAssumed = convertTo != null && currencyConverter.usdtAssumed(conversionQuote, convertTo, now)
                        notifier.showAlarm(watch, alarm, comparePrice, priceCurrency = convertTo, textNote = AlarmTextNote.of(usdtAssumed = usdtAssumed))
                    },
                    spokenPrice = comparePrice,
                    currency = convertTo,
                    logPrice = comparePrice,
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

    /** Alarm-Protokoll: nur beobachten — ein Fehler darin darf die Prüfung nie stören. */
    private inline fun observe(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Timber.d(e, "Alarm-Protokoll: Prüfung nicht erfasst")
        }
    }

    /** Was mit der Kurs-Benachrichtigung eines Paars geschieht ([priceUpdate]). */
    enum class PriceUpdate { NONE, SHOW, CANCEL }

    /**
     * Kurs-Benachrichtigung zeigen — je nach Einstellung nur dann, wenn sich der Kurs seit der
     * letzten Meldung deutlich genug bewegt hat — oder entfernen. Gemeldet wird gesammelt nach
     * den Alarmen (AppNotifier.showPrices: höchstens 20 einzeln, Alarme haben Vorrang); der
     * Aufrufer setzt danach den neuen Bezugspunkt der gezeigten Paare.
     */
    fun priceUpdate(watch: WatchEntity, settings: AppSettings): PriceUpdate {
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
    fun speakPriceIfWanted(
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
