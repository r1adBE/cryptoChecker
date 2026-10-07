package com.cryptochecker.app.ui.features.alarms

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.portfolio.CurrencyConverter
import com.cryptochecker.app.domain.alarm.NearExtreme
import com.cryptochecker.app.domain.alarm.ThresholdParser
import com.cryptochecker.app.domain.convert.CurrencyConversion
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.ui.navigation.ScreenRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

    fun save(draft: AlarmDraft) {
        viewModelScope.launch {
            val threshold = draft.threshold ?: return@launch

            val existing = alarms.value.firstOrNull { it.id == draft.id }

            // Allererster Alarm? Gab es schon Alarme (z. B. vor dem Update), gilt die Bestätigung als gezeigt.
            val shownBefore = existing != null || settingsRepository.current().firstAlarmShown
            val firstEver = !shownBefore && watchRepository.observeAllAlarms().first().isEmpty()
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

            if (!shownBefore) {
                settingsRepository.setFirstAlarmShown(true)
                if (firstEver) {
                    _firstAlarm.value = (watch.value ?: watchRepository.getWatch(watchId))?.baseAsset
                }
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
) {
    /** Gelesener Schwellwert (Tausendertrennung, Dezimalzeichen der Region; siehe [ThresholdParser]). */
    val threshold: Double?
        get() = ThresholdParser.parse(
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
        return when {
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
            else -> copy(condition = newCondition)
        }
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

        fun from(alarm: AlarmEntity): AlarmDraft = AlarmDraft(
            id = alarm.id,
            condition = alarm.condition,
            thresholdText = formatThreshold(alarm.threshold),
            repeating = alarm.repeating,
            sound = alarm.sound,
            vibrate = alarm.vibrate,
            speak = alarm.speak,
            windowHours = if (alarm.condition.isNearExtreme) NearExtreme.windowDays(alarm.windowHours)
            else alarm.windowHours,
            currency = alarm.currency,
        )
    }
}
