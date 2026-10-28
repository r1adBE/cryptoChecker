package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.domain.alarm.AlarmDelivery
import com.cryptochecker.app.domain.alarm.AlarmDeliveryMarker
import com.cryptochecker.app.domain.alarm.PortfolioAlarmDecision
import com.cryptochecker.app.domain.alarm.PortfolioAlarmKind
import com.cryptochecker.app.domain.alarm.PortfolioAlarmLogic
import com.cryptochecker.app.domain.alarm.PortfolioReading
import com.cryptochecker.app.domain.alarm.QuietHours
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshot
import com.cryptochecker.app.notification.AppNotifier
import com.cryptochecker.app.notification.PortfolioAlarmDeliveryMarkers
import com.cryptochecker.app.notification.activePostTimes
import com.cryptochecker.app.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import com.cryptochecker.app.notification.canShowPortfolioAlarm
import com.cryptochecker.app.notification.showPortfolioAlarm

/** Portfolio-Alarme lesen und ändern (Portfolio-Tab, Alarm-Übersicht, Sicherung). */
@Singleton
class PortfolioAlarmRepository @Inject constructor(
    private val dao: PortfolioAlarmDao,
    /** Vermerke «wird gemeldet» gelöschter bzw. ersetzter Alarme aufräumen. */
    private val deliveryMarkers: PortfolioAlarmDeliveryMarkers,
) {
    fun observeAll(): Flow<List<PortfolioAlarmEntity>> = dao.observeAll()

    suspend fun getAll(): List<PortfolioAlarmEntity> = dao.getAll()

    /** Neu anlegen: scharf, ohne letzte Meldung. */
    suspend fun add(kind: PortfolioAlarmKind, threshold: Double, currency: String?, repeating: Boolean): Long =
        dao.insert(
            PortfolioAlarmEntity(
                kind = kind,
                threshold = threshold,
                currency = currency?.takeIf { kind.isValue },
                repeating = repeating,
            )
        )

    suspend fun delete(id: Long) {
        dao.delete(id)
        // Nicht bei Kopie im Speicher (der Alarm kommt mit dem nächsten Prozess aus der Datei zurück)
        if (PortfolioAlarmDeliveryMarkers.prunable()) withContext(Dispatchers.IO) { deliveryMarkers.remove(listOf(id)) }
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        // FUN3-01: «fällt um ≥ 100 %» löst nie aus — nicht einschalten (erst anpassen)
        if (enabled && dao.getAll().any { it.id == id && PortfolioAlarmLogic.exceedsFall(it.kind, it.threshold) }) return
        dao.setEnabled(id, enabled)
    }

    /** Wiederherstellen: alles ersetzen, jeweils scharf (wie Paar-Alarme aus einer Sicherung). */
    suspend fun replaceAll(alarms: List<PortfolioAlarmEntity>) {
        dao.deleteAll()
        alarms.forEach { dao.insert(it.copy(referenceAt = 0, lastTriggeredAt = 0, lastTriggeredValue = null)) }
        // Alle Alarme ersetzt (Ids können sich decken) — alte Vermerke gelten nicht mehr
        withContext(Dispatchers.IO) { deliveryMarkers.retain(emptySet()) }
    }
}

/**
 * Prüft die Portfolio-Alarme nach jeder neuen Momentaufnahme (PortfolioSnapshotUpdater: in der
 * App und bei jeder Hintergrund-Aktualisierung, sobald ein Portfolio-Alarm scharf ist). Nur mit
 * eingeschaltetem Portfolio. Meldet über den Kanal des «Alarm-Signals» ([com.cryptochecker.app.notification.showPortfolioAlarm]);
 * in der Nachtruhe und ohne zustellbare Meldung wird nicht ausgelöst ([QuietHours.mayFire]). Regeln: [PortfolioAlarmLogic].
 */
@Singleton
class PortfolioAlarmChecker @Inject constructor(
    private val dao: PortfolioAlarmDao,
    private val settingsRepository: SettingsRepository,
    private val notifier: AppNotifier,
    private val fxSource: FxRateSource,
    private val depegReadings: com.cryptochecker.app.domain.alarm.DepegReadingStore,
    /** Vermerk «wird gemeldet» der Portfolio-Alarme. */
    deliveryMarkers: PortfolioAlarmDeliveryMarkers,
) {
    private val mutex = Mutex()

    /** Melden-dann-Speichern; Vermerk dauerhaft und mit eigenen Ids ([PortfolioAlarmDeliveryMarkers]). */
    private val delivery = AlarmDelivery(deliveryMarkers)

    /** Braucht die Hintergrund-Aktualisierung eine Momentaufnahme für die Alarme? */
    suspend fun hasActiveAlarms(): Boolean {
        if (!settingsRepository.current().portfolioEnabled) return false
        return dao.countEnabled() > 0
    }

    suspend fun check(snapshot: PortfolioSnapshot) = mutex.withLock {
        val settings = settingsRepository.current()
        if (!settings.portfolioEnabled) return@withLock
        val alarms = dao.getAll().filter { it.enabled }
        if (alarms.isEmpty()) return@withLock
        // Alarme in einer anderen Währung als der Stand (z. B. nach Wechsel CHF → EUR): mit Devisenkurs umrechnen
        val needed = PortfolioAlarmLogic.currenciesNeedingRate(
            alarms.filter { it.kind.isValue }.map { it.currency }, snapshot.currency
        )
        // Devisenkurse zusammen höchstens FX_TIMEOUT_MILLIS (je Währung bis zu zwei Quellen mit langen Timeouts);
        // was nicht kommt, gilt aus dem Zwischenspeicher (höchstens 24 h alt) — wie iOS im Hintergrund
        withTimeoutOrNull(FX_TIMEOUT_MILLIS) { needed.forEach { fxSource.usdTo(it) } }
        val usdTo = needed.mapNotNull { code -> fxSource.cached(code)?.let { code to it } }.toMap()
        // USDT zum Marktkurs wie der Portfolio-Kopf (Alarm in einer anderen Währung als der Stand)
        val reading = snapshot.toAlarmReading(usdTo, depegReadings.liveUsdtUsd())
        val now = System.currentTimeMillis()
        // Nachtruhe: Alarme warten bis zum Ende und melden sich dann mit Ton (wie Paar-Alarme)
        val holdAlarms = QuietHours.holdsAlarms(
            settings.quietHoursEnabled, settings.quietHoursStart, settings.quietHoursEnd, QuietHours.minuteOfDay()
        )
        for (alarm in alarms) {
            // Ein scheiternder Alarm hält die übrigen nicht auf
            try {
                when (val decision = PortfolioAlarmLogic.decide(alarm.toInput(), reading, now, settings.alarmCooldownMinutes)) {
                    PortfolioAlarmDecision.None -> Unit
                    PortfolioAlarmDecision.Rearm -> dao.rearm(alarm.id)
                    // Nachtruhe oder keine zustellbare Meldung (keine Erlaubnis, Kanal stumm): nichts speichern,
                    // nichts melden — der Alarm bleibt scharf, auch ein wiederholender (QuietHours.mayFire).
                    // Wie die Paar-Alarme: ZUERST melden, erst speichern (einmalige: abschalten), wenn die Meldung
                    // unter den offenen Mitteilungen steht — verwirft Android sie, bleibt der Alarm scharf und meldet
                    // frühestens nach der Abklingzeit erneut. Hat der Nutzer ihn inzwischen geändert/ausgeschaltet,
                    // speichert markTriggered nichts und die Mitteilung wird wieder entfernt. Läuft über
                    // [AlarmDelivery] mit dauerhaftem Vermerk (wie iOS `AlarmDelivery.portfolio`): ein Prozess-Ende
                    // zwischen Melden und Speichern klingelt nicht ein zweites Mal.
                    is PortfolioAlarmDecision.Fire -> {
                        // Alarm in anderer Währung ohne Messung USDT/USD: «USDT = 1 angenommen» im Text
                        val usdtAssumed = PortfolioAlarmLogic.usdtAssumed(alarm.toInput(), reading)
                        val notificationId = AppNotifier.portfolioAlarmNotificationId(alarm.id)
                        delivery.deliver(
                            alarm.id, now, settings.alarmCooldownMinutes, holdAlarms,
                            canShow = { notifier.canShowPortfolioAlarm() },
                            show = {
                                notifier.showPortfolioAlarm(alarm, decision.measured, usdtAssumed).also {
                                    if (!it) Timber.w("Portfolio-Alarm %d: Meldung nicht bestätigt, bleibt scharf", alarm.id)
                                }
                            },
                            mark = {
                                dao.markTriggered(
                                    id = alarm.id,
                                    time = now,
                                    value = decision.measured,
                                    enabled = PortfolioAlarmLogic.enabledAfterFire(alarm.repeating),
                                    expectedReferenceAt = alarm.referenceAt,
                                    kind = alarm.kind.name,
                                    threshold = alarm.threshold,
                                    repeating = alarm.repeating,
                                    currency = alarm.currency,
                                ) > 0
                            },
                            retract = { notifier.cancelAlarms(emptyList(), listOf(alarm.id)) },
                            isShown = { markerAt ->
                                AlarmDeliveryMarker.shownSinceMarker(notifier.activePostTimes()?.get(notificationId), markerAt)
                            },
                            // Vermerk einer schon gespeicherten Auslösung zählt nicht
                            savedSince = { markerAt -> AlarmDeliveryMarker.savedSinceMarker(alarm.lastTriggeredAt, markerAt) },
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Portfolio-Alarm %d nicht geprüft", alarm.id)
            }
        }
    }

    companion object {
        /** Devisenkurse für die Prüfung zusammen höchstens so lange; danach gilt der Zwischenspeicher. */
        const val FX_TIMEOUT_MILLIS = 10_000L
    }
}

/**
 * Stand für die Portfolio-Alarme aus einer Momentaufnahme — Prüfung ([PortfolioAlarmChecker]) und Hinweis
 * «pausiert» der Alarm-Übersicht ([PortfolioAlarmLogic.pause]) lesen ihn gleich. Teilweise alter Kurs (wesentlich),
 * fehlende Kurse und nie geladene Adressen: diesmal gar nicht prüfen (kein Melden, kein Wieder-scharf-Stellen).
 */
fun PortfolioSnapshot.toAlarmReading(usdTo: Map<String, Double> = emptyMap(), usdtUsdLive: Double? = null): PortfolioReading =
    PortfolioReading(
        total = total,
        currency = currency,
        totalUsdt = totalUsdt,
        changePercent = changePercent,
        empty = empty,
        usdTo = usdTo,
        missingPrices = missingPrices,
        todaySkipped = todaySkipped.size,
        stalePrices = stalePrices,
        staleValueUsdt = staleValueUsdt,
        addressesPending = addressesPending,
        addressesFailing = addressesFailing,
        staleTokenAddresses = staleTokenAddresses,
        staleTokenValueUsdt = staleTokenValueUsdt,
        // «Kurs ungeprüft» zählt wie ein alter Kurs (wesentlich → Pause, unwesentlich nicht)
        unverifiedCoins = unverifiedCoins,
        unverifiedValueUsdt = unverifiedValueUsdt,
        usdtUsdLive = usdtUsdLive,
    )
