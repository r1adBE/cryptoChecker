// Schmale Schnittstellen der Auswertung nach einem neuen Kurs ([RefreshEffects], [RefreshResultProcessor],
// [LivePriceApplier]): genau die Aufrufe, die diese Klassen an Speicher, Mitteilungen, Sprachausgabe und
// Datenquellen richten. Die Hüllen um die App-Klassen stehen in der App (`RefreshPortAdapters.kt`, je ein
// Weiterreichen ohne eigene Logik); Unit-Tests geben kleine Fakes in den primären Konstruktor — ohne
// Android-Kontext, Room oder DataStore. Die Signaturen entsprechen denen der App-Klassen.
package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.data.ErrorWrite
import com.cryptochecker.app.data.PriceWrite
import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.remote.VolumeSpike
import com.cryptochecker.app.data.remote.WindowRanges
import com.cryptochecker.app.domain.alarm.AlarmTextNote
import com.cryptochecker.app.domain.alarm.DerivativesValues
import com.cryptochecker.app.domain.alarm.MoveWindow
import com.cryptochecker.app.domain.alarm.NearExtreme
import com.cryptochecker.app.domain.convert.FiatAlarmGuard
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.domain.watch.ChangeStamp
import com.cryptochecker.app.settings.AppSettings
import com.cryptochecker.marketdata.model.Ticker
import kotlinx.coroutines.flow.StateFlow

/** Kurse, Fehler und Alarm-Zustände speichern ([WatchRepository]). */
interface RefreshStore {
    suspend fun getWatches(): List<WatchEntity>
    suspend fun applyRefresh(prices: List<PriceWrite>, errors: List<ErrorWrite>): Set<Long>
    suspend fun setNotifiedPrices(prices: List<Pair<Long, Double>>, time: Long)
    suspend fun getAllEnabledAlarms(): List<AlarmEntity>
    suspend fun markAlarmTriggered(
        alarm: AlarmEntity,
        price: Double,
        time: Long,
        candleOpenTime: Long? = null,
        nearLevel: Double? = null,
    ): Boolean
    suspend fun setAlarmReferenceIfUnchanged(alarm: AlarmEntity, price: Double, time: Long)
    suspend fun rearmAlarmIfUnchanged(alarm: AlarmEntity)
}

/** Alarm- und Kurs-Mitteilungen ([AppNotifier]). */
interface RefreshNotifier {
    fun canShowAlarm(alarm: AlarmEntity): Boolean
    suspend fun showAlarm(
        watch: WatchEntity,
        alarm: AlarmEntity,
        price: Double,
        volumeRatio: Double? = null,
        nearFire: NearExtreme.Decision.Fire? = null,
        derivativesValue: Double? = null,
        derivativesReference: String? = null,
        priceCurrency: String? = null,
        /** Zusatz am Alarmtext: Börse fremder Kerzen bzw. «USDT = 1 angenommen» ([AlarmTextNote]). */
        textNote: AlarmTextNote? = null,
    ): Boolean
    fun cancelAlarms(alarmIds: Collection<Long>)
    fun activeNotificationIds(): Set<Int>?
    fun cancelPrices(watchIds: Collection<Long>, shown: Set<Int>?)
    suspend fun showPrices(watches: List<WatchEntity>, ongoing: Boolean): Set<Long>
    /** Ende einer laufenden «1 h später»-Pause des Alarms ([com.cryptochecker.app.domain.alarm.AlarmSnooze]); null = keine. */
    fun alarmSnoozeUntil(alarmId: Long): Long? = null
    /** Meldezeit der offenen Mitteilung des Alarms; null = keine offen bzw. nicht abfragbar. */
    fun alarmPostedAt(alarmId: Long): Long? = null
}

/** Warteschlange der Sprachausgabe ([TtsSpeaker]). */
interface RefreshSpeaker {
    fun enqueue(text: String, speechRate: Float = 1.0f, flush: Boolean = false, key: Long? = null)
}

/** Vorgelesene Sätze ([SpokenText]). */
interface RefreshSpokenText {
    fun price(watch: WatchEntity, price: Double): String
    fun alarm(watch: WatchEntity, condition: AlarmCondition, price: Double, currency: String? = null): String
}

/** Stundenvolumen für den Volumen-Alarm ([VolumeDataSource]). */
interface AlarmVolumeSource {
    suspend fun hourlySpike(watch: WatchEntity, price: Double? = watch.lastPrice): VolumeSpike?
}

/** Devisenkurs für Alarme in anderer Währung ([CurrencyConverter]). */
interface AlarmRateSource {
    suspend fun rate(quote: String, target: String): Double?

    /** Fiat-Alarm diesmal auslassen? Devisenkurs zu alt bzw. USDT/USD unsicher ([FiatAlarmGuard.skip]); null = prüfen. */
    fun skip(quote: String, target: String, now: Long): FiatAlarmGuard.Skip? = null

    /** Rechnet der Faktor mit USDT = 1, weil USDT/USD nicht gemessen ist ([FiatAlarmGuard.UsdtState.ASSUMED])? */
    fun usdtAssumed(quote: String, target: String, now: Long): Boolean = false
}

/** Hoch/Tief der Zeiträume für «Nahe am Hoch/Tief» ([NearExtremeDataSource]). */
interface AlarmRangeSource {
    suspend fun ranges(baseAsset: String, quoteAsset: String): WindowRanges?
    fun seriesAllowed(ranges: WindowRanges, exchangeKey: String, baseAsset: String, price: Double?, quoteToSeries: Double?): Boolean
}

/** Funding und Open Interest der Futures-Alarme ([DerivativesAlarmData]). */
interface AlarmDerivativesSource {
    suspend fun prefetch(watch: WatchEntity, now: Long)
    suspend fun values(watch: WatchEntity, price: Double, now: Long): DerivativesValues?
    fun oiChange(watch: WatchEntity, values: DerivativesValues, hours: Int, now: Long): Double?
}

/** Kursverlauf je Paar für den Bewegungs-Alarm ([MoveHistoryStore]). */
interface AlarmMoveHistory {
    fun history(watchId: Long): List<MoveWindow.PricePoint>
    fun append(watchId: Long, point: MoveWindow.PricePoint, now: Long)
}

/** 24-h- und Tages-Bezüge ([DayReferences]). */
interface RefreshDayReferences {
    fun notePillSource(watchId: Long, provider: String?)
    fun change24h(watch: WatchEntity, price: Double, ticker: Ticker, basis: ChangeBasis, dayStart: Long?): Double?
}

/** Liquidität der DEX-Pools ([DexLiquidityStore]). */
interface RefreshDexLiquidity {
    fun put(pairId: String?, liquidityUsd: Double?)
    fun blocksAlarms(exchangeKey: String, pairId: String?): Boolean
}

/** Paare mit Mark-Preis statt Mittelkurs ([MarkFallbackStore]) — für «Mark» in Zeile und Blatt. */
fun interface RefreshMarkFallback {
    fun put(watchId: Long, markFallback: Boolean)

    companion object {
        /** Merkt nichts (Tests ohne Anzeige). */
        val NONE = RefreshMarkFallback { _, _ -> }
    }
}

/** Alarm-Protokoll ([AlarmLogStore]): Einträge eines Durchlaufs übernehmen; wirft nie. */
fun interface RefreshAlarmLog {
    fun record(entries: List<com.cryptochecker.app.domain.alarm.AlarmLogEntry>, live: Boolean)

    companion object {
        /** Merkt nichts (Tests ohne Protokoll). */
        val NONE = RefreshAlarmLog { _, _ -> }
    }
}

/** Einstellungen im Moment des Speicherns ([SettingsRepository]). */
interface RefreshSettings {
    suspend fun current(): AppSettings
}

/** Zeit und Dauer des letzten Durchlaufs, Stempel der Veränderungen ([RefreshStats]). */
interface RefreshStatsPort {
    val changeStamp: StateFlow<ChangeStamp?>
    val lastDurationMillis: StateFlow<Long>
    fun setLastRefresh(durationMillis: Long)
}

/** Widgets nach Live-Kursen zeichnen ([LiveWidgetGate]). */
interface RefreshWidgets {
    suspend fun drawAll(deferWhenOff: Boolean, fetch: Boolean = true): Boolean
}

/** Charts mit fehlenden Kerzen ausserhalb der Sperre ([ChartRefresher]). */
interface RefreshCharts {
    fun markIfStale()
    fun launchIfPending()
}
