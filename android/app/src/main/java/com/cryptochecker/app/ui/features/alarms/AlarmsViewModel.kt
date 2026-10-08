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
import com.cryptochecker.app.domain.alarm.AlarmTemplates
import com.cryptochecker.app.domain.alarm.DerivativesAlarm
import com.cryptochecker.app.domain.alarm.NearExtreme
import com.cryptochecker.app.domain.alarm.ThresholdParser
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
import java.math.BigDecimal
import java.math.MathContext
import java.text.DecimalFormatSymbols
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
) : ViewModel() {

    /** Basis-Symbol (z. B. «BTC») für die Bestätigung nach dem allerersten Alarm; null = keine. */
    private val _firstAlarm = MutableStateFlow<String?>(null)
    val firstAlarm: StateFlow<String?> = _firstAlarm.asStateFlow()

    fun dismissFirstAlarm() { _firstAlarm.value = null }

    /** «Alarm testen» aus der Bestätigung heraus. */
    suspend fun testAlarm(): com.cryptochecker.app.notification.AlarmTestResult = alarmTester.run()

    private val watchId: Long = savedStateHandle.get<Long>(ScreenRoute.AlarmsArgWatchId) ?: 0L

    val watch: StateFlow<WatchEntity?> = watchRepository.observeWatch(watchId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val alarms: StateFlow<List<AlarmEntity>> = watchRepository.observeAlarms(watchId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
                val hourly = orFalse { volumeDataSource.hourlySpike(w.baseAsset, w.quoteAsset) != null }
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
            if (firstEver) _firstAlarm.value = w.baseAsset
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

    fun save(draft: AlarmDraft) {
        viewModelScope.launch {
            val threshold = draft.threshold ?: return@launch

            val existing = alarms.value.firstOrNull { it.id == draft.id }

            // Allererster Alarm? Gab es schon Alarme (z. B. vor dem Update), gilt die Bestätigung als gezeigt.
            val firstEver = markFirstAlarm(existing = existing != null)
            val keepReference = existing != null && existing.condition == draft.condition &&
                existing.windowHours == draft.windowHours
            val referencePrice = when {
                !draft.condition.isPercent -> null
                // Prozentalarme messen ab dem Kurs, der beim Anlegen galt.
                keepReference -> existing.referencePrice
                else -> watch.value?.lastPrice
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

            watchRepository.saveAlarm(
                AlarmEntity(
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
                    // Eigene Währung nur bei Kursalarmen und nur, wenn sie von der Quote abweicht
                    currency = draft.currency
                        ?.takeIf { draft.condition.isPriceThreshold }
                        ?.let { CurrencyConversion.normalize(it) }
                        ?.takeIf { it.isNotEmpty() && !CurrencyConversion.sameCurrency(it, watch.value?.quoteAsset) },
                )
            )

            if (firstEver) {
                _firstAlarm.value = (watch.value ?: watchRepository.getWatch(watchId))?.baseAsset
            }
        }
    }

    fun delete(alarmId: Long) {
        viewModelScope.launch { watchRepository.deleteAlarm(alarmId) }
    }

    fun setEnabled(alarmId: Long, enabled: Boolean) {
        viewModelScope.launch { watchRepository.setAlarmEnabled(alarmId, enabled) }
    }
}

/** Eingabezustand des Alarm-Dialogs. */
data class AlarmDraft(
    val id: Long = 0,
    val condition: AlarmCondition = AlarmCondition.PRICE_ABOVE,
    val thresholdText: String = "",
    val repeating: Boolean = false,
    val sound: Boolean = true,
    val vibrate: Boolean = true,
    val speak: Boolean = false,
    /** Zeitfenster für «bewegt sich um x % in y Stunden»; bei «Nahe am Hoch/Tief» Tage. */
    val windowHours: Int = 4,
    /** Währung des Schwellwerts bei Kursalarmen; null = Quote-Währung des Paars. */
    val currency: String? = null,
    /**
     * Aktueller Kurs in der Währung des Schwellwerts (nur Kursalarme): entscheidet bei
     * mehrdeutiger Eingabe wie «60,000», siehe [ThresholdParser]. null = unbekannt.
     */
    val priceHint: Double? = null,
    /** Dezimalzeichen der Region (Punkt oder Komma). */
    val decimalSeparator: Char = localeDecimalSeparator(),
    /**
     * «Nahe am Hoch/Tief»: nur neue Hochs/Tiefs melden (Abstand 0, Vorlage «Neues 30-Tage-Hoch»).
     * Das Abstandsfeld behält seinen Wert für den Fall, dass wieder ausgeschaltet wird.
     */
    val newExtremeOnly: Boolean = false,
) {
    /**
     * Gelesener Schwellwert (Tausendertrennung, Dezimalzeichen der Region; siehe [ThresholdParser]).
     * Funding mit Vorzeichen (auch 0), siehe [DerivativesAlarm.parseFunding].
     */
    val threshold: Double?
        get() = if (condition.isNearExtreme && newExtremeOnly) NearExtreme.NEW_ONLY_DISTANCE
        else if (condition.isFunding) DerivativesAlarm.parseFunding(thresholdText, decimalSeparator)
        else if (condition.isOpenInterest) ThresholdParser.parse(thresholdText, decimalSeparator)
            ?.takeIf { DerivativesAlarm.isValidThreshold(condition, it) }
        else ThresholdParser.parse(
            thresholdText,
            decimalSeparator,
            priceHint.takeIf { condition.isPriceThreshold },
        )

    val isValid: Boolean get() = threshold != null

    /**
     * Bedingung wechseln. Beim Volumen-Spike ist der Wert ein Faktor (×2…×10),
     * deshalb beim Wechsel von/zu dieser Bedingung den Wert neu setzen.
     */
    fun withCondition(newCondition: AlarmCondition): AlarmDraft {
        if (newCondition == condition) return this
        val switched = switchCondition(newCondition)
        // Zwischen Kursmarke und Prozent wechseln: ein Kurs ist kein Prozentwert (und umgekehrt).
        // Zurück zur Kursmarke wieder mit dem aktuellen Kurs als Vorschlag (wie beim Anlegen).
        return when {
            condition.isPriceThreshold == newCondition.isPriceThreshold -> switched
            newCondition.isPriceThreshold ->
                switched.copy(thresholdText = AlarmTemplates.suggestedThresholdText(priceHint, decimalSeparator))
            newCondition.isPercent -> switched.copy(thresholdText = "")
            else -> switched
        }
    }

    private fun switchCondition(newCondition: AlarmCondition): AlarmDraft {
        return when {
            // Funding: Schwelle in % mit Vorzeichen (Vorschlag 0,05 %)
            newCondition.isFunding -> copy(
                condition = newCondition,
                thresholdText = if (condition.isFunding) thresholdText
                else formatThreshold(DerivativesAlarm.DEFAULT_FUNDING_PERCENT, decimalSeparator),
                windowHours = if (condition.isNearExtreme) DEFAULT_WINDOW_HOURS else windowHours,
            )
            // Open Interest: Veränderung in % (Vorschlag 10 %), Fenster 1, 4 oder 24 Stunden
            newCondition.isOpenInterest -> copy(
                condition = newCondition,
                thresholdText = if (condition.isOpenInterest) thresholdText
                else formatThreshold(DerivativesAlarm.DEFAULT_OI_PERCENT, decimalSeparator),
                windowHours = DerivativesAlarm.oiWindowHours(
                    if (condition.isNearExtreme) DerivativesAlarm.DEFAULT_OI_WINDOW_HOURS else windowHours
                ),
            )
            newCondition == AlarmCondition.VOLUME_SPIKE ->
                copy(condition = newCondition, thresholdText = formatFactor(DEFAULT_VOLUME_FACTOR))
            // Nahe am Hoch/Tief: Abstand in % (Standard 2 %), Zeitraum in Tagen (Standard 30)
            newCondition.isNearExtreme -> copy(
                condition = newCondition,
                thresholdText = if (condition.isPercent || condition.isNearExtreme) thresholdText
                else formatFactor(NearExtreme.DEFAULT_DISTANCE_PERCENT),
                windowHours = if (condition.isNearExtreme) windowHours else NearExtreme.DEFAULT_WINDOW_DAYS,
            )
            condition.isNearExtreme -> copy(
                condition = newCondition,
                thresholdText = if (newCondition.isPercent) thresholdText else "",
                windowHours = DEFAULT_WINDOW_HOURS,
            )
            condition == AlarmCondition.VOLUME_SPIKE -> copy(condition = newCondition, thresholdText = "")
            // Funding/Open Interest zurück auf eine andere Bedingung: deren Wert passt nicht
            condition.isDerivatives -> copy(condition = newCondition, thresholdText = "")
            else -> copy(condition = newCondition)
        }
    }

    /** Funding: Vorzeichen der Eingabe wechseln («0,01» ↔ «-0,01»), da Zifferntastaturen oft kein Minus haben. */
    fun withToggledSign(): AlarmDraft {
        val text = thresholdText.trim()
        val flipped = when {
            text.startsWith('-') || text.startsWith('\u2212') -> text.substring(1).trimStart()
            text.startsWith('+') -> "-" + text.substring(1).trimStart()
            else -> "-$text"
        }
        return copy(thresholdText = flipped)
    }

    /**
     * Währung des Schwellwerts wechseln (null = Quote). Ist der Faktor Quote →
     * [other] bekannt, wird ein bereits getippter Wert mit umgerechnet.
     * @param other die Nicht-Quote-Währung der Auswahl (z. B. «CHF»)
     * @param rate Faktor Quote → [other]
     */
    fun withCurrency(newCurrency: String?, other: String, rate: Double?): AlarmDraft {
        if (newCurrency == currency) return this
        val value = threshold
        val converted = when {
            value == null || rate == null || rate <= 0.0 -> null
            currency == null && newCurrency == other -> value * rate
            currency == other && newCurrency == null -> value / rate
            else -> null
        }
        return copy(
            currency = newCurrency,
            thresholdText = converted?.let { formatConverted(it, decimalSeparator) } ?: thresholdText,
        )
    }

    companion object {
        /**
         * Umgerechneten Wert fürs Eingabefeld runden: sechs gültige Stellen, ohne Exponent und
         * Tausendertrennung, mit dem Dezimalzeichen der Region (liest sich so eindeutig zurück).
         */
        fun formatConverted(value: Double, decimalSeparator: Char = '.'): String =
            BigDecimal.valueOf(value).round(MathContext(6)).stripTrailingZeros().toPlainString()
                .replace('.', ThresholdParser.normalized(decimalSeparator))

        /** Dezimalzeichen der Region, auf Punkt oder Komma abgebildet. */
        fun localeDecimalSeparator(): Char =
            ThresholdParser.normalized(DecimalFormatSymbols.getInstance().decimalSeparator)

        /** Schwellwert fürs Eingabefeld: ohne Exponent und Tausendertrennung, Dezimalzeichen der Region. */
        fun formatThreshold(value: Double, decimalSeparator: Char = localeDecimalSeparator()): String =
            BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
                .replace('.', ThresholdParser.normalized(decimalSeparator))

        /**
         * Kurs, an dem eine mehrdeutige Eingabe gemessen wird: [lastPrice] in der Währung des
         * Schwellwerts ([currency] = null → Quote; sonst mit dem Faktor aus [rates]).
         */
        fun priceHint(lastPrice: Double?, currency: String?, rates: Map<String, Double>): Double? {
            val price = lastPrice?.takeIf { it.isFinite() && it > 0.0 } ?: return null
            if (currency == null) return price
            val rate = rates[currency.uppercase()]?.takeIf { it.isFinite() && it > 0.0 } ?: return null
            return price * rate
        }

        /** Wählbare Faktoren für den Volumen-Spike. */
        val VOLUME_FACTORS = listOf(2.0, 3.0, 5.0, 10.0)
        const val DEFAULT_VOLUME_FACTOR = 3.0

        /** Standard-Zeitfenster für «bewegt sich um x % in y Stunden». */
        const val DEFAULT_WINDOW_HOURS = 4

        fun formatFactor(value: Double): String =
            if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

        /**
         * Neuer Alarm im einfachen Modus: «Wenn BTC über [Kurs] geht», Betrag = aktueller Kurs
         * auf drei gültige Stellen ([AlarmTemplates.suggestedThresholdText]).
         */
        fun newFor(lastPrice: Double?): AlarmDraft =
            AlarmDraft(thresholdText = AlarmTemplates.suggestedThresholdText(lastPrice, localeDecimalSeparator()))

        fun from(alarm: AlarmEntity): AlarmDraft = AlarmDraft(
            id = alarm.id,
            condition = alarm.condition,
            // Nur neue Hochs/Tiefs: Abstand 0 → Schalter an, Feld mit dem Standardabstand
            newExtremeOnly = alarm.condition.isNearExtreme && NearExtreme.isNewOnly(alarm.threshold),
            thresholdText = if (alarm.condition.isNearExtreme && NearExtreme.isNewOnly(alarm.threshold))
                formatFactor(NearExtreme.DEFAULT_DISTANCE_PERCENT)
            else formatThreshold(alarm.threshold),
            repeating = alarm.repeating,
            sound = alarm.sound,
            vibrate = alarm.vibrate,
            speak = alarm.speak,
            windowHours = when {
                alarm.condition.isNearExtreme -> NearExtreme.windowDays(alarm.windowHours)
                alarm.condition.isOpenInterest -> DerivativesAlarm.oiWindowHours(alarm.windowHours)
                else -> alarm.windowHours
            },
            currency = alarm.currency,
        )
    }
}
