package com.cryptochecker.app.ui.features.alarms

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.local.DatabaseEncryptionNotice
import com.cryptochecker.app.data.local.WatchlistStorageLock
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
import com.cryptochecker.app.domain.watch.WatchlistLimits
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import com.cryptochecker.app.notification.alarmsAllowed
import com.cryptochecker.app.notification.canShowAlarm

@HiltViewModel
class AlarmsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val watchRepository: WatchRepository,
    private val settingsRepository: SettingsRepository,
    private val currencyConverter: CurrencyConverter,
    private val alarmTester: com.cryptochecker.app.notification.AlarmTester,
    private val nearExtremeDataSource: NearExtremeDataSource,
    private val volumeDataSource: VolumeDataSource,
    /** Dürfen Alarme erscheinen (« · Mitteilungen aus» nach dem Speichern)? Entfernt auch Mitteilungen geänderter Alarme. */
    private val appNotifier: com.cryptochecker.app.notification.AppNotifier,
    alarmCheckStatus: AlarmCheckStatus,
    /** «Pünktlich prüfen» nach dem ersten Alarm einmal anbieten ([ExactCheckOffer]). */
    private val exactScheduler: com.cryptochecker.app.work.ExactCheckScheduler,
    /** Liquidität der DEX-Pools: zu wenig → kein Alarm ([com.cryptochecker.app.domain.watch.DexLiquidity]). */
    dexLiquidityStore: com.cryptochecker.app.data.DexLiquidityStore,
    /** Letzter Kurs nur der Mark-Preis ([com.cryptochecker.app.data.MarkFallbackStore]): Alarme warten auf einen Mittelkurs. */
    markFallbackStore: com.cryptochecker.app.data.MarkFallbackStore,
    /** Alarm-Protokoll: «Zuletzt geprüft» im Editor; Löschen eines Alarms entfernt seine letzte Prüfung. */
    private val alarmLogStore: com.cryptochecker.app.domain.refresh.AlarmLogStore,
) : ViewModel() {

    /** Letzte Prüfung je Alarm-Id ([com.cryptochecker.app.domain.alarm.AlarmLog]). */
    val lastChecks: StateFlow<Map<Long, com.cryptochecker.app.domain.alarm.AlarmLogEntry>> = alarmLogStore.state
        .map { it.lastChecks }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        viewModelScope.launch { alarmLogStore.load() }
    }

    /** Alarme, die zuletzt nicht geprüft werden konnten (kein Devisenkurs/keine Kerzen), für den Hinweis. */
    val unchecked: StateFlow<Map<Long, AlarmCheckStatus.Unchecked>> = alarmCheckStatus.state

    /** Bestätigung nach dem ersten Alarm, «Pünktlich prüfen» und «Wann Alarme geprüft werden» ([AlarmSaveNotices]). */
    private val notices = AlarmSaveNotices(viewModelScope, watchRepository, settingsRepository, exactScheduler)
    /** Doppeltipp beim Anlegen: ein zweites «Speichern»/eine Vorlage, solange das erste läuft, bleibt ohne Wirkung. */
    private val createGuard = AlarmCreateGuard()

    /**
     * Bestätigung nach dem allerersten Alarm zeigen. Ihr Text ist der Hinweis «Wann Alarme geprüft
     * werden» ([AlarmTimingHint]) und braucht kein Symbol (Build 55).
     */
    val firstAlarm: StateFlow<Boolean> = notices.firstAlarm

    fun dismissFirstAlarm() = notices.dismissFirstAlarm()
    /** «Rückgängig» nach einem Schnell-Alarm, auch nach «Prüfen» noch zu zeigen ([PendingUndoSnackbar]). */
    internal val undo = AlarmUndoOffer()

    /** Akku-Einstellungen aus der Bestätigung geöffnet: der eigene Akku-Dialog kommt nicht mehr. */
    fun batteryHintUsed() {
        viewModelScope.launch { settingsRepository.setBatteryPromptSeen(true) }
    }

    /** «Pünktlich prüfen» anbieten (einmal, im Dialog nach dem allerersten Alarm); null = nicht. */
    val exactOffer: StateFlow<com.cryptochecker.app.work.ExactCheckOffer.Kind?> = notices.exactOffer

    /** Angebot angenommen: einschalten; Wecker stellen, sobald erlaubt (sonst nach der Berechtigung, BootReceiver). */
    fun acceptExactOffer() = notices.acceptExactOffer()

    /** Hinweis «Wann Alarme geprüft werden» jetzt zeigen ([AlarmTimingHint]): einmal nach dem Speichern, nicht zur ersten Bestätigung. */
    val timingHint: StateFlow<Boolean> = notices.timingHint

    fun dismissTimingHint() = notices.dismissTimingHint()

    /** «Alarm testen» aus der Bestätigung heraus. */
    suspend fun testAlarm(): com.cryptochecker.app.notification.AlarmTestResult = alarmTester.run()

    private val watchId: Long = savedStateHandle.get<Long>(ScreenRoute.AlarmsArgWatchId) ?: 0L

    /** «Anpassen» aus der Alarm-Mitteilung: dieser Alarm wird gleich bearbeitet (0 = keiner). */
    val editAlarmId: Long = savedStateHandle.get<Long>(ScreenRoute.AlarmsArgEditAlarmId) ?: 0L

    val watch: StateFlow<WatchEntity?> = watchRepository.observeWatch(watchId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Paar gibt es (nach dem Laden) nicht mehr, z. B. «Anpassen» aus einer alten Alarm-Mitteilung eines gelöschten
     * Paars: kein Editor, der Bildschirm geht zurück. null = noch nicht geladen.
     */
    val watchMissing: StateFlow<Boolean?> = watchRepository.observeWatch(watchId)
        .map<WatchEntity?, Boolean?> { it == null }
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

    /** Letzter Kurs war der Mark-Preis statt des Mittelkurses: Statuszeile «Wartet auf aktuellen Kurs». */
    val markFallback: StateFlow<Boolean> = markFallbackStore.ids.map { watchId in it }
        .distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Veraltet-Grenze der abgefragten Paare wie in der Merkliste ([com.cryptochecker.app.domain.watch.LiveStatus]):
     * älter → Statuszeile «Wartet auf aktuellen Kurs» ([com.cryptochecker.app.domain.alarm.AlarmCardStatus]).
     */
    val staleAfterMillis: StateFlow<Long> = settingsRepository.settings
        .map { com.cryptochecker.app.domain.watch.LiveStatus.pollingStaleAfterMillis(it.backgroundIntervalMinutes) }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            com.cryptochecker.app.domain.watch.LiveStatus.pollingStaleAfterMillis(settingsRepository.cached.backgroundIntervalMinutes),
        )

    /**
     * Dürfen Alarme als Mitteilung erscheinen (Erlaubnis, App-Mitteilungen, Alarm-Kanal nicht stumm)? Für die
     * Statuszeile der Karten; im Zweifel ja, damit kein falscher Hinweis erscheint.
     */
    fun alarmsAllowed(): Boolean = runCatching { appNotifier.alarmsAllowed() }.getOrDefault(true)

    /** Paar hat (nach dem Laden) keinen Alarm: Der Bildschirm öffnet gleich den Editor statt der leeren Liste. null = offen. */
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
            val quote = (watch.value ?: watchRepository.getWatch(watchId))?.let { CurrencyConversion.conversionQuote(it.marketKey, it.quoteAsset) } ?: return@launch
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
     * Merkliste nur leer im Arbeitsspeicher ([WatchlistStorageLock]): Ein Alarm wäre nach dem Prozess-Ende weg. Der
     * Bildschirm fragt vorher und zeigt den Hinweis; [save] und [createFromTemplate] legen dann nichts an.
     */
    fun alarmSaveBlocked(): Boolean = WatchlistStorageLock.blocksAlarmSave(DatabaseEncryptionNotice.storedKept.value)

    /**
     * Schnell-Alarm sofort anlegen (einmalig, Ton/Vibration wie neue Alarme).
     * [onCreated] bekommt den gespeicherten Alarm (Id für «Rückgängig», Bedingung für die Bestätigung); [onLimit]: Paar voll.
     */
    fun createFromTemplate(template: AlarmTemplates.Template, onLimit: () -> Unit, onCreated: (AlarmEntity) -> Unit) {
        if (alarmSaveBlocked()) return
        createGuard.launch(viewModelScope, guarded = true) {
            val w = watch.value ?: watchRepository.getWatch(watchId) ?: return@launch
            val def = AlarmTemplates.definition(template, w.lastPrice) ?: return@launch
            val firstEver = notices.isFirstAlarm(existing = false)
            val alarm = AlarmEntity(
                watchId = watchId,
                condition = def.condition,
                threshold = def.threshold,
                enabled = true,
                repeating = def.repeating,
                referencePrice = def.referencePrice,
                windowHours = def.windowHours,
            )
            val id = saveIfWatchExists(alarm, onLimit) ?: return@launch
            val timingHint = notices.markSaved(firstEver)
            // Dialoge zuerst: Die Bestätigung mit «Rückgängig» wartet, bis sie geschlossen sind (sonst liefe sie dahinter ab)
            if (firstEver) notices.showFirstAlarm()
            if (timingHint) notices.showTimingHint()
            onCreated(alarm.copy(id = id))
        }
    }

    /**
     * Alarm speichern, solange das Paar noch besteht; sonst nichts (null). Der Fremdschlüssel würde das Einfügen
     * sonst mit einem Fehler abbrechen. Neuer Alarm an einem vollen Paar ([WatchlistLimits]): nichts, [onLimit] (Hinweis).
     */
    private suspend fun saveIfWatchExists(alarm: AlarmEntity, onLimit: () -> Unit): Long? {
        if (watchRepository.getWatch(watchId) == null) return null
        return try {
            watchRepository.saveAlarm(alarm).also { if (it == null) onLimit() }
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            Timber.w(e, "Alarm nicht gespeichert: Paar fehlt")
            null
        }
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
     * darf ([com.cryptochecker.app.notification.canShowAlarm]) und ob gleich die
     * Bestätigung des allerersten Alarms kommt (dann keine eigene Zeile) — für die Bestätigung. [onLimit]: Paar voll.
     */
    fun save(draft: AlarmDraft, onLimit: () -> Unit = {}, onSaved: (AlarmEntity, Boolean, Boolean) -> Unit = { _, _, _ -> }) {
        if (alarmSaveBlocked()) return
        createGuard.launch(viewModelScope, guarded = draft.id == 0L) {
            val threshold = draft.threshold ?: return@launch

            val existing = alarms.value.firstOrNull { it.id == draft.id }

            // Allererster Alarm? Gab es schon Alarme (z. B. vor dem Update), gilt die Bestätigung als gezeigt.
            val firstEver = notices.isFirstAlarm(existing = existing != null)
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
            // Bearbeitet: alte Mitteilung vor dem Speichern weg (eine neue, die gleich danach meldet, bleibt) — [AlarmUserChange]
            if (existing != null && AlarmUserChange.cancelsNotification(enabled = null)) appNotifier.retractAlarm(existing.id)
            if (saveIfWatchExists(alarm, onLimit) == null) return@launch
            val timingHint = notices.markSaved(firstEver)
            onSaved(alarm, runCatchingCancellable { appNotifier.canShowAlarm(alarm) }.getOrDefault(true), firstEver)
            if (firstEver) notices.showFirstAlarm()
            if (timingHint) notices.showTimingHint()
        }
    }

    fun delete(alarmId: Long) {
        viewModelScope.launch {
            watchRepository.deleteAlarm(alarmId)
            // Mitteilung weg (ihr «Anpassen» zeigte ins Leere), nur im Arbeitsspeicher nicht ([AlarmUserChange.afterDelete])
            val cleanup = AlarmUserChange.afterDelete()
            if (cleanup.cancelNotification) appNotifier.retractAlarm(alarmId)
            // Datei versiegelt schreiben: nicht auf dem Hauptfaden
            if (cleanup.removeLog) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { alarmLogStore.removeAlarm(alarmId) }
        }
    }

    fun setEnabled(alarmId: Long, enabled: Boolean) {
        viewModelScope.launch {
            watchRepository.setAlarmEnabled(alarmId, enabled)
            // Ausgeschaltet: Mitteilung weg, ihr «1 h später» schaltete ihn sonst wieder ein ([AlarmUserChange])
            if (AlarmUserChange.cancelsNotification(enabled)) appNotifier.retractAlarm(alarmId)
        }
    }
}
