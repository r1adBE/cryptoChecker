package com.cryptochecker.app.ui.features.alarms

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.portfolio.CurrencyConverter
import com.cryptochecker.app.data.remote.NearExtremeDataSource
import com.cryptochecker.app.data.remote.VolumeDataSource
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.alarm.AlarmCheckStatus
import com.cryptochecker.app.domain.alarm.AlarmEdit
import com.cryptochecker.app.domain.alarm.AlarmTemplates
import com.cryptochecker.app.domain.alarm.AlarmTimingHint
import com.cryptochecker.app.domain.convert.CurrencyConversion
import com.cryptochecker.app.domain.watch.SheetChart
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.ui.navigation.ScreenRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class AlarmsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val watchRepository: WatchRepository,
    private val settingsRepository: SettingsRepository,
    private val currencyConverter: CurrencyConverter,
    private val alarmTester: com.cryptochecker.app.notification.AlarmTester,
    private val nearExtremeDataSource: NearExtremeDataSource,
    private val volumeDataSource: VolumeDataSource,
    /** Dürfen Alarme erscheinen? Für « · Mitteilungen aus» in der Bestätigung nach dem Speichern. */
    private val appNotifier: com.cryptochecker.app.notification.AppNotifier,
    alarmCheckStatus: AlarmCheckStatus,
    /** «Pünktlich prüfen» nach dem ersten Alarm einmal anbieten ([ExactCheckOffer]). */
    private val exactScheduler: com.cryptochecker.app.work.ExactCheckScheduler,
    /** Liquidität der DEX-Pools: zu wenig → kein Alarm ([com.cryptochecker.app.domain.watch.DexLiquidity]). */
    dexLiquidityStore: com.cryptochecker.app.data.DexLiquidityStore,
) : ViewModel() {

    /** Alarme, die zuletzt nicht geprüft werden konnten (kein Devisenkurs/keine Kerzen), für den Hinweis. */
    val unchecked: StateFlow<Map<Long, AlarmCheckStatus.Unchecked>> = alarmCheckStatus.state

    /**
     * Bestätigung nach dem allerersten Alarm zeigen. Ihr Text ist der Hinweis «Wann Alarme geprüft
     * werden» ([AlarmTimingHint]) und braucht kein Symbol (Build 55).
     */
    private val _firstAlarm = MutableStateFlow(false)
    val firstAlarm: StateFlow<Boolean> = _firstAlarm.asStateFlow()

    fun dismissFirstAlarm() {
        _firstAlarm.value = false
        _exactOffer.value = null
    }

    /** Bestätigung des allerersten Alarms zeigen — mit «Pünktlich prüfen» im selben Dialog (höchstens ein Dialog nach dem ersten Alarm). */
    private suspend fun showFirstAlarm() {
        offerExactChecksOnce()
        _firstAlarm.value = true
    }

    /** Akku-Einstellungen aus der Bestätigung geöffnet: der eigene Akku-Dialog kommt nicht mehr. */
    fun batteryHintUsed() {
        viewModelScope.launch { settingsRepository.setBatteryPromptSeen(true) }
    }

    /** «Pünktlich prüfen» anbieten (einmal, im Dialog nach dem allerersten Alarm); null = nicht. */
    private val _exactOffer = MutableStateFlow<com.cryptochecker.app.work.ExactCheckOffer.Kind?>(null)
    val exactOffer: StateFlow<com.cryptochecker.app.work.ExactCheckOffer.Kind?> = _exactOffer.asStateFlow()

    private suspend fun offerExactChecksOnce() {
        val kind = com.cryptochecker.app.work.ExactCheckOffer.decide(
            exactOn = settingsRepository.current().exactChecks,
            permitted = exactScheduler.canScheduleExact(),
            offeredBefore = exactScheduler.offerShown,
        ) ?: return
        // Gleich merken: auch «OK» oder Wegtippen fragt nie wieder
        exactScheduler.offerShown = true
        _exactOffer.value = kind
    }

    /** Angebot angenommen: einschalten; Wecker stellen, sobald erlaubt (sonst nach der Berechtigung, BootReceiver). */
    fun acceptExactOffer() {
        _exactOffer.value = null
        viewModelScope.launch {
            settingsRepository.setExactChecks(true)
            exactScheduler.sync(true, hasWork = true)
        }
    }

    /**
     * Hinweis «Wann Alarme geprüft werden» jetzt zeigen ([AlarmTimingHint]): einmal nach dem Speichern,
     * nicht zusätzlich zur Bestätigung des allerersten Alarms.
     */
    private val _timingHint = MutableStateFlow(false)
    val timingHint: StateFlow<Boolean> = _timingHint.asStateFlow()

    fun dismissTimingHint() { _timingHint.value = false }

    /**
     * Hinweis prüfen und merken; true = nach dem Speichern zeigen. Nach jedem Speichern gilt er als
     * gezeigt: entweder erscheint er jetzt oder die Bestätigung des ersten Alarms mit demselben Satz.
     */
    private suspend fun markTimingHint(firstAlarmDialog: Boolean): Boolean {
        val shown = settingsRepository.current().alarmTimingHintShown
        if (!shown) settingsRepository.setAlarmTimingHintShown(true)
        return AlarmTimingHint.shouldShow(alreadyShown = shown, firstAlarmDialog = firstAlarmDialog)
    }

    /** «Alarm testen» aus der Bestätigung heraus. */
    suspend fun testAlarm(): com.cryptochecker.app.notification.AlarmTestResult = alarmTester.run()

    private val watchId: Long = savedStateHandle.get<Long>(ScreenRoute.AlarmsArgWatchId) ?: 0L

    val watch: StateFlow<WatchEntity?> = watchRepository.observeWatch(watchId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val alarms: StateFlow<List<AlarmEntity>> = watchRepository.observeAlarms(watchId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * DEX-Pool mit zu wenig oder unbekannter Liquidität ([com.cryptochecker.app.domain.watch.DexLiquidity]): Editor zu,
     * Hinweis «Geringe Liquidität – kein Alarm»; bestehende Alarme bleiben, lösen aber nicht aus. null = noch nicht geladen
     * (der Editor öffnet sich erst danach von selbst).
     */
    val lowLiquidity: StateFlow<Boolean?> = combine(watchRepository.observeWatch(watchId), dexLiquidityStore.liquidity) { w, levels ->
        w != null && com.cryptochecker.app.domain.watch.DexLiquidity.blocksAlarms(
            w.marketKey, com.cryptochecker.app.domain.watch.DexLiquidity.key(w.pairId)?.let { levels[it] },
        )
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Paar hat (nach dem Laden) keinen Alarm: Der Bildschirm öffnet dann gleich den Editor
     * statt der leeren Liste. null = noch nicht geladen.
     */
    val noAlarms: StateFlow<Boolean?> = watchRepository.observeAlarms(watchId)
        .map { it.isEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Umrechnungswährung aus den Einstellungen, z. B. «CHF». */
    val targetCurrency: StateFlow<String> = settingsRepository.settings
        .map { it.portfolioCurrency }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), settingsRepository.cached.portfolioCurrency)

    private val _rates = MutableStateFlow<Map<String, Double>>(emptyMap())

    /** Faktor Quote → Währung, soweit bekannt (zum Umrechnen des getippten Schwellwerts). */
    val rates: StateFlow<Map<String, Double>> = _rates.asStateFlow()

    /** Faktor Quote → [currency] holen, falls noch nicht bekannt. */
    fun loadRate(currency: String) {
        val code = CurrencyConversion.normalize(currency)
        if (code.isEmpty() || code in _rates.value) return
        viewModelScope.launch {
            val quote = watch.value?.quoteAsset ?: watchRepository.getWatch(watchId)?.quoteAsset ?: return@launch
            val rate = currencyConverter.cachedRate(quote, code) ?: currencyConverter.rate(quote, code)
            if (rate != null) _rates.update { it + (code to rate) }
        }
    }

    /** Kerzen-Daten für die Schnell-Alarme: Tageskerzen (30-Tage-Hoch/-Tief) und Stundenvolumen. */
    private data class CandleData(val dailyRange: Boolean = false, val hourlyVolume: Boolean = false)

    private val candleData = MutableStateFlow(CandleData())
    private var candleDataLoading = false

    /** Sichtbare Schnell-Alarme; ohne Kurs bzw. ohne Kerzen (z. B. DEX) ausgeblendet. */
    val templates: StateFlow<List<AlarmTemplates.Template>> = combine(watch, candleData) { w, data ->
        AlarmTemplates.available(
            hasPrice = (w?.lastPrice ?: 0.0) > 0.0,
            hasDailyRange = data.dailyRange,
            hasHourlyVolume = data.hourlyVolume,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Beim Öffnen des Editors: prüfen, ob das Paar Tages-/Stundenkerzen hat (zwischengespeichert,
     * gleiche Quellen wie die Alarmprüfung). DEX-Paare ohne Kerzenquelle fragen gar nicht.
     */
    fun loadTemplateData() {
        if (candleDataLoading || (candleData.value.dailyRange && candleData.value.hourlyVolume)) return
        candleDataLoading = true
        viewModelScope.launch {
            try {
                val w = watch.value ?: watchRepository.getWatch(watchId) ?: return@launch
                if (!SheetChart.isSupported(w.marketKey, w.baseAsset, w.quoteAsset)) return@launch
                val daily = orFalse {
                    nearExtremeDataSource.ranges(w.baseAsset, w.quoteAsset)
                        ?.ranges?.containsKey(AlarmTemplates.NEW_EXTREME_WINDOW_DAYS) == true
                }
                candleData.update { it.copy(dailyRange = daily) }
                val hourly = orFalse { volumeDataSource.hourlySpike(w) != null }
                candleData.update { it.copy(hourlyVolume = hourly) }
            } finally {
                candleDataLoading = false
            }
        }
    }

    /** Netz-/Parserfehler zählen als «keine Daten»; Abbruch läuft durch. */
    private suspend fun orFalse(block: suspend () -> Boolean): Boolean = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.d(e, "Schnell-Alarme: Kerzen nicht verfügbar")
        false
    }

    /**
     * Schnell-Alarm sofort anlegen (einmalig, Ton/Vibration wie neue Alarme).
     * [onCreated] bekommt die Id für «Rückgängig».
     */
    fun createFromTemplate(template: AlarmTemplates.Template, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val w = watch.value ?: watchRepository.getWatch(watchId) ?: return@launch
            val def = AlarmTemplates.definition(template, w.lastPrice) ?: return@launch
            val firstEver = markFirstAlarm(existing = false)
            val timingHint = markTimingHint(firstAlarmDialog = firstEver)
            val id = watchRepository.saveAlarm(
                AlarmEntity(
                    watchId = watchId,
                    condition = def.condition,
                    threshold = def.threshold,
                    enabled = true,
                    repeating = def.repeating,
                    referencePrice = def.referencePrice,
                    windowHours = def.windowHours,
                )
            )
            onCreated(id)
            if (firstEver) showFirstAlarm()
            if (timingHint) _timingHint.value = true
        }
    }

    /** Bestätigung nach dem allerersten Alarm: true, wenn sie jetzt gezeigt werden soll. */
    private suspend fun markFirstAlarm(existing: Boolean): Boolean {
        // Gab es schon Alarme (z. B. vor dem Update), gilt die Bestätigung als gezeigt.
        val shownBefore = existing || settingsRepository.current().firstAlarmShown
        if (shownBefore) return false
        val firstEver = watchRepository.observeAllAlarms().first().isEmpty()
        settingsRepository.setFirstAlarmShown(true)
        return firstEver
    }

    /** Eigene Währung nur bei Kursalarmen und nur, wenn sie von der Quote abweicht. */
    private fun alarmCurrency(draft: AlarmDraft): String? = draft.currency
        ?.takeIf { draft.condition.isPriceThreshold }
        ?.let { CurrencyConversion.normalize(it) }
        ?.takeIf { it.isNotEmpty() && !CurrencyConversion.sameCurrency(it, watch.value?.quoteAsset) }

    /**
     * Würde der Alarm gleich nach dem Speichern melden? Kursmarke schon erreicht
     * ([AlarmEdit.firesNow], Kurs in der Währung des Schwellwerts = [AlarmDraft.priceHint]) —
     * und nur, wenn er dabei (neu) scharf wird ([AlarmEdit.rearms]); sonst behält er seinen Zustand.
     */
    fun firesOnSave(draft: AlarmDraft): Boolean {
        val threshold = draft.threshold ?: return false
        if (!AlarmEdit.firesNow(draft.condition, threshold, draft.priceHint)) return false
        val existing = alarms.value.firstOrNull { it.id == draft.id && draft.id != 0L } ?: return true
        val edited = existing.copy(
            condition = draft.condition,
            threshold = threshold,
            repeating = draft.repeating,
            windowHours = draft.windowHours,
            currency = alarmCurrency(draft),
        )
        return AlarmEdit.rearms(existing, edited)
    }

    /**
     * Alarm speichern. [onSaved] bekommt den gespeicherten Alarm, ob er als Mitteilung erscheinen
     * darf ([com.cryptochecker.app.notification.AppNotifier.canShowAlarm]) und ob gleich die
     * Bestätigung des allerersten Alarms kommt (dann keine eigene Zeile) — für die Bestätigung.
     */
    fun save(draft: AlarmDraft, onSaved: (AlarmEntity, Boolean, Boolean) -> Unit = { _, _, _ -> }) {
        viewModelScope.launch {
            val threshold = draft.threshold ?: return@launch

            val existing = alarms.value.firstOrNull { it.id == draft.id }

            // Allererster Alarm? Gab es schon Alarme (z. B. vor dem Update), gilt die Bestätigung als gezeigt.
            val firstEver = markFirstAlarm(existing = existing != null)
            val timingHint = markTimingHint(firstAlarmDialog = firstEver)
            val keepReference = existing != null && existing.condition == draft.condition &&
                existing.windowHours == draft.windowHours
            val referencePrice = when {
                !draft.condition.isPercent -> null
                // Prozentalarme messen ab dem Kurs, der beim Anlegen galt.
                keepReference -> existing.referencePrice
                // Nur ein frischer Kurs taugt als Bezug; sonst setzt ihn die nächste Abfrage
                else -> (watch.value ?: watchRepository.getWatch(watchId)).let { w ->
                    AlarmEdit.freshReference(
                        w?.lastPrice, w?.lastUpdate ?: 0L, System.currentTimeMillis(),
                        com.cryptochecker.app.domain.watch.LiveStatus.pollingStaleAfterMillis(
                            settingsRepository.current().backgroundIntervalMinutes,
                        ),
                    )
                }
            }
            // Bewegungs-Alarm: Fenster beginnt jetzt (oder läuft unverändert weiter).
            // Volumen-Spike: zuletzt gemeldete Kerze behalten, damit sie nicht nochmals meldet.
            val referenceAt = when {
                draft.condition == AlarmCondition.VOLUME_SPIKE ->
                    existing?.takeIf { it.condition == AlarmCondition.VOLUME_SPIKE }?.referenceAt ?: 0L
                draft.condition != AlarmCondition.MOVE_PERCENT_WINDOW -> 0L
                keepReference -> existing.referenceAt
                referencePrice != null -> System.currentTimeMillis()
                else -> 0L
            }

            val edited = AlarmEntity(
                id = draft.id,
                watchId = watchId,
                condition = draft.condition,
                threshold = threshold,
                enabled = true,
                repeating = draft.repeating,
                sound = draft.sound,
                vibrate = draft.vibrate,
                speak = draft.speak,
                referencePrice = referencePrice,
                lastTriggeredAt = 0,
                lastTriggeredPrice = null,
                windowHours = draft.windowHours,
                referenceAt = referenceAt,
                currency = alarmCurrency(draft),
            )
            // Nur Ton, Vibration, Ansage oder Wiederholung geändert: Zustand (ausgelöst/gemeldet,
            // Bezug, Ein/Aus) bleibt; neu scharf nur bei geänderter Bedingung ([AlarmEdit.merged])
            val alarm = AlarmEdit.merged(existing, edited)
            watchRepository.saveAlarm(alarm)
            onSaved(alarm, runCatchingCancellable { appNotifier.canShowAlarm(alarm) }.getOrDefault(true), firstEver)

            if (firstEver) showFirstAlarm()
            if (timingHint) _timingHint.value = true
        }
    }

    fun delete(alarmId: Long) {
        viewModelScope.launch { watchRepository.deleteAlarm(alarmId) }
    }

    fun setEnabled(alarmId: Long, enabled: Boolean) {
        viewModelScope.launch { watchRepository.setAlarmEnabled(alarmId, enabled) }
    }
}
