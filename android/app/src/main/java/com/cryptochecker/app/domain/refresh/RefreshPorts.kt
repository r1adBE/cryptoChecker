// Schmale Schnittstellen der Auswertung nach einem neuen Kurs ([RefreshEffects], [RefreshResultProcessor],
// [LivePriceApplier]): genau die Aufrufe, die diese Klassen an Speicher, Mitteilungen, Sprachausgabe und
// Datenquellen richten. Hilt gibt die App-Klassen über die Hüllen unten hinein (je ein @Inject-Konstruktor);
// Unit-Tests geben kleine Fakes in den primären Konstruktor — ohne Android-Kontext, Room oder DataStore.
// Die Signaturen entsprechen denen der App-Klassen, damit die Klassen selbst unverändert bleiben.
package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.domain.alarm.AlarmTextNote
import com.cryptochecker.app.domain.convert.FiatAlarmGuard
import com.cryptochecker.app.data.ErrorWrite
import com.cryptochecker.app.data.DexLiquidityStore
import com.cryptochecker.app.data.MarkFallbackStore
import com.cryptochecker.app.data.MoveHistoryStore
import com.cryptochecker.app.data.PriceWrite
import com.cryptochecker.app.data.RefreshStats
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.portfolio.CurrencyConverter
import com.cryptochecker.app.data.remote.NearExtremeDataSource
import com.cryptochecker.app.data.remote.VolumeDataSource
import com.cryptochecker.app.data.remote.VolumeSpike
import com.cryptochecker.app.data.remote.WindowRanges
import com.cryptochecker.app.domain.alarm.DerivativesAlarmData
import com.cryptochecker.app.domain.alarm.MoveWindow
import com.cryptochecker.app.domain.alarm.NearExtreme
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.domain.watch.ChangeStamp
import com.cryptochecker.app.notification.AppNotifier
import com.cryptochecker.app.settings.AppSettings
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.tts.SpokenText
import com.cryptochecker.app.tts.TtsSpeaker
import com.cryptochecker.app.widget.LiveWidgetGate
import com.cryptochecker.marketdata.model.Ticker
import kotlinx.coroutines.flow.StateFlow
import com.cryptochecker.app.notification.alarmSnoozeUntil
import com.cryptochecker.app.notification.canShowAlarm
import com.cryptochecker.app.notification.showAlarm
import com.cryptochecker.app.data.applyRefresh
import com.cryptochecker.app.data.markAlarmTriggered
import com.cryptochecker.app.data.rearmAlarmIfUnchanged
import com.cryptochecker.app.data.setAlarmReferenceIfUnchanged
import com.cryptochecker.app.data.setNotifiedPrices

/** Kurse, Fehler und Alarm-Zustände speichern ([WatchRepository]). */
internal interface RefreshStore {
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
internal interface RefreshNotifier {
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
}

/** Warteschlange der Sprachausgabe ([TtsSpeaker]). */
internal interface RefreshSpeaker {
    fun enqueue(text: String, speechRate: Float = 1.0f, flush: Boolean = false, key: Long? = null)
}

/** Vorgelesene Sätze ([SpokenText]). */
internal interface RefreshSpokenText {
    fun price(watch: WatchEntity, price: Double): String
    fun alarm(watch: WatchEntity, condition: AlarmCondition, price: Double, currency: String? = null): String
}

/** Stundenvolumen für den Volumen-Alarm ([VolumeDataSource]). */
internal interface AlarmVolumeSource {
    suspend fun hourlySpike(watch: WatchEntity, price: Double? = watch.lastPrice): VolumeSpike?
}

/** Devisenkurs für Alarme in anderer Währung ([CurrencyConverter]). */
internal interface AlarmRateSource {
    suspend fun rate(quote: String, target: String): Double?

    /** Fiat-Alarm diesmal auslassen? Devisenkurs zu alt bzw. USDT/USD unsicher ([FiatAlarmGuard.skip]); null = prüfen. */
    fun skip(quote: String, target: String, now: Long): FiatAlarmGuard.Skip? = null

    /** Rechnet der Faktor mit USDT = 1, weil USDT/USD nicht gemessen ist ([FiatAlarmGuard.UsdtState.ASSUMED])? */
    fun usdtAssumed(quote: String, target: String, now: Long): Boolean = false
}

/** Hoch/Tief der Zeiträume für «Nahe am Hoch/Tief» ([NearExtremeDataSource]). */
internal interface AlarmRangeSource {
    suspend fun ranges(baseAsset: String, quoteAsset: String): WindowRanges?
    fun seriesAllowed(ranges: WindowRanges, marketKey: String, baseAsset: String, price: Double?, quoteToSeries: Double?): Boolean
}

/** Funding und Open Interest der Futures-Alarme ([DerivativesAlarmData]). */
internal interface AlarmDerivativesSource {
    suspend fun prefetch(watch: WatchEntity, now: Long)
    suspend fun values(watch: WatchEntity, price: Double, now: Long): DerivativesAlarmData.Values?
    fun oiChange(watch: WatchEntity, values: DerivativesAlarmData.Values, hours: Int, now: Long): Double?
}

/** Kursverlauf je Paar für den Bewegungs-Alarm ([MoveHistoryStore]). */
internal interface AlarmMoveHistory {
    fun history(watchId: Long): List<MoveWindow.PricePoint>
    fun append(watchId: Long, point: MoveWindow.PricePoint, now: Long)
}

/** 24-h- und Tages-Bezüge ([DayReferences]). */
internal interface RefreshDayReferences {
    fun notePillSource(watchId: Long, provider: String?)
    fun change24h(watch: WatchEntity, price: Double, ticker: Ticker, basis: ChangeBasis, dayStart: Long?): Double?
}

/** Liquidität der DEX-Pools ([DexLiquidityStore]). */
internal interface RefreshDexLiquidity {
    fun put(pairId: String?, liquidityUsd: Double?)
    fun blocksAlarms(marketKey: String, pairId: String?): Boolean
}

/** Paare mit Mark-Preis statt Mittelkurs ([MarkFallbackStore]) — für «Mark» in Zeile und Blatt. */
internal fun interface RefreshMarkFallback {
    fun put(watchId: Long, markFallback: Boolean)

    companion object {
        /** Merkt nichts (Tests ohne Anzeige). */
        val NONE = RefreshMarkFallback { _, _ -> }
    }
}

/** Alarm-Protokoll ([AlarmLogStore]): Einträge eines Durchlaufs übernehmen; wirft nie. */
internal fun interface RefreshAlarmLog {
    fun record(entries: List<com.cryptochecker.app.domain.alarm.AlarmLogEntry>, live: Boolean)

    companion object {
        /** Merkt nichts (Tests ohne Protokoll). */
        val NONE = RefreshAlarmLog { _, _ -> }
    }
}

/** Einstellungen im Moment des Speicherns ([SettingsRepository]). */
internal interface RefreshSettings {
    suspend fun current(): AppSettings
}

/** Zeit und Dauer des letzten Durchlaufs, Stempel der Veränderungen ([RefreshStats]). */
internal interface RefreshStatsPort {
    val changeStamp: StateFlow<ChangeStamp?>
    val lastDurationMillis: StateFlow<Long>
    fun setLastRefresh(durationMillis: Long)
}

/** Widgets nach Live-Kursen zeichnen ([LiveWidgetGate]). */
internal interface RefreshWidgets {
    suspend fun drawAll(deferWhenOff: Boolean, fetch: Boolean = true): Boolean
}

/** Charts mit fehlenden Kerzen ausserhalb der Sperre ([ChartRefresher]). */
internal interface RefreshCharts {
    fun markIfStale()
    fun launchIfPending()
}

// ---- Hüllen um die App-Klassen (nur weiterreichen, keine eigene Logik)

internal fun WatchRepository.asRefreshStore(): RefreshStore = WatchRepositoryStore(this)
internal fun AppNotifier.asRefreshNotifier(): RefreshNotifier = AppNotifierPort(this)
internal fun TtsSpeaker.asRefreshSpeaker(): RefreshSpeaker = TtsSpeakerPort(this)
internal fun SpokenText.asRefreshSpokenText(): RefreshSpokenText = SpokenTextPort(this)
internal fun VolumeDataSource.asAlarmVolumeSource(): AlarmVolumeSource = VolumePort(this)
internal fun CurrencyConverter.asAlarmRateSource(): AlarmRateSource = RatePort(this)
internal fun NearExtremeDataSource.asAlarmRangeSource(): AlarmRangeSource = RangePort(this)
internal fun DerivativesAlarmData.asAlarmDerivativesSource(): AlarmDerivativesSource = DerivativesPort(this)
internal fun MoveHistoryStore.asAlarmMoveHistory(): AlarmMoveHistory = MoveHistoryPort(this)
internal fun DayReferences.asRefreshDayReferences(): RefreshDayReferences = DayReferencesPort(this)
internal fun DexLiquidityStore.asRefreshDexLiquidity(): RefreshDexLiquidity = DexLiquidityPort(this)
internal fun MarkFallbackStore.asRefreshMarkFallback(): RefreshMarkFallback = RefreshMarkFallback { id, on -> put(id, on) }
internal fun AlarmLogStore.asRefreshAlarmLog(): RefreshAlarmLog = RefreshAlarmLog { entries, live -> record(entries, live) }
internal fun SettingsRepository.asRefreshSettings(): RefreshSettings = SettingsPort(this)
internal fun RefreshStats.asRefreshStatsPort(): RefreshStatsPort = StatsPort(this)
internal fun LiveWidgetGate.asRefreshWidgets(): RefreshWidgets = WidgetsPort(this)
internal fun ChartRefresher.asRefreshCharts(): RefreshCharts = ChartsPort(this)

private class WatchRepositoryStore(private val repository: WatchRepository) : RefreshStore {
    override suspend fun getWatches() = repository.getWatches()
    override suspend fun applyRefresh(prices: List<PriceWrite>, errors: List<ErrorWrite>) = repository.applyRefresh(prices, errors)
    override suspend fun setNotifiedPrices(prices: List<Pair<Long, Double>>, time: Long) = repository.setNotifiedPrices(prices, time)
    override suspend fun getAllEnabledAlarms() = repository.getAllEnabledAlarms()
    override suspend fun markAlarmTriggered(alarm: AlarmEntity, price: Double, time: Long, candleOpenTime: Long?, nearLevel: Double?) =
        repository.markAlarmTriggered(alarm, price, time, candleOpenTime, nearLevel)
    override suspend fun setAlarmReferenceIfUnchanged(alarm: AlarmEntity, price: Double, time: Long) =
        repository.setAlarmReferenceIfUnchanged(alarm, price, time)
    override suspend fun rearmAlarmIfUnchanged(alarm: AlarmEntity) = repository.rearmAlarmIfUnchanged(alarm)
}

private class AppNotifierPort(private val notifier: AppNotifier) : RefreshNotifier {
    override fun canShowAlarm(alarm: AlarmEntity) = notifier.canShowAlarm(alarm)
    override suspend fun showAlarm(
        watch: WatchEntity,
        alarm: AlarmEntity,
        price: Double,
        volumeRatio: Double?,
        nearFire: NearExtreme.Decision.Fire?,
        derivativesValue: Double?,
        derivativesReference: String?,
        priceCurrency: String?,
        textNote: AlarmTextNote?,
    ) = notifier.showAlarm(watch, alarm, price, volumeRatio, nearFire, derivativesValue, derivativesReference, priceCurrency, textNote)
    override fun cancelAlarms(alarmIds: Collection<Long>) = notifier.cancelAlarms(alarmIds)
    override fun activeNotificationIds() = notifier.activeNotificationIds()
    override fun cancelPrices(watchIds: Collection<Long>, shown: Set<Int>?) = notifier.cancelPrices(watchIds, shown)
    override suspend fun showPrices(watches: List<WatchEntity>, ongoing: Boolean) = notifier.showPrices(watches, ongoing)
    override fun alarmSnoozeUntil(alarmId: Long) = notifier.alarmSnoozeUntil(alarmId)
}

private class TtsSpeakerPort(private val speaker: TtsSpeaker) : RefreshSpeaker {
    override fun enqueue(text: String, speechRate: Float, flush: Boolean, key: Long?) =
        speaker.enqueue(text, speechRate = speechRate, flush = flush, key = key)
}

private class SpokenTextPort(private val texts: SpokenText) : RefreshSpokenText {
    override fun price(watch: WatchEntity, price: Double) = texts.price(watch, price)
    override fun alarm(watch: WatchEntity, condition: AlarmCondition, price: Double, currency: String?) =
        texts.alarm(watch, condition, price, currency)
}

private class VolumePort(private val source: VolumeDataSource) : AlarmVolumeSource {
    override suspend fun hourlySpike(watch: WatchEntity, price: Double?) = source.hourlySpike(watch, price)
}

private class RatePort(private val converter: CurrencyConverter) : AlarmRateSource {
    override suspend fun rate(quote: String, target: String) = converter.rate(quote, target)
    override fun skip(quote: String, target: String, now: Long) =
        FiatAlarmGuard.skip(converter.fxRateTime(quote, target), converter.usdtState(quote, target, now), now)
    override fun usdtAssumed(quote: String, target: String, now: Long) =
        converter.usdtState(quote, target, now) == FiatAlarmGuard.UsdtState.ASSUMED
}

private class RangePort(private val source: NearExtremeDataSource) : AlarmRangeSource {
    override suspend fun ranges(baseAsset: String, quoteAsset: String) = source.ranges(baseAsset, quoteAsset)
    override fun seriesAllowed(ranges: WindowRanges, marketKey: String, baseAsset: String, price: Double?, quoteToSeries: Double?) =
        source.seriesAllowed(ranges, marketKey, baseAsset, price, quoteToSeries)
}

private class DerivativesPort(private val data: DerivativesAlarmData) : AlarmDerivativesSource {
    override suspend fun prefetch(watch: WatchEntity, now: Long) = data.prefetch(watch, now)
    override suspend fun values(watch: WatchEntity, price: Double, now: Long) = data.values(watch, price, now)
    override fun oiChange(watch: WatchEntity, values: DerivativesAlarmData.Values, hours: Int, now: Long) =
        data.oiChange(watch, values, hours, now)
}

private class MoveHistoryPort(private val store: MoveHistoryStore) : AlarmMoveHistory {
    override fun history(watchId: Long) = store.history(watchId)
    override fun append(watchId: Long, point: MoveWindow.PricePoint, now: Long) {
        store.append(watchId, point, now)
    }
}

private class DayReferencesPort(private val references: DayReferences) : RefreshDayReferences {
    override fun notePillSource(watchId: Long, provider: String?) = references.notePillSource(watchId, provider)
    override fun change24h(watch: WatchEntity, price: Double, ticker: Ticker, basis: ChangeBasis, dayStart: Long?) =
        references.change24h(watch, price, ticker, basis, dayStart)
}

private class DexLiquidityPort(private val store: DexLiquidityStore) : RefreshDexLiquidity {
    override fun put(pairId: String?, liquidityUsd: Double?) = store.put(pairId, liquidityUsd)
    override fun blocksAlarms(marketKey: String, pairId: String?) = store.blocksAlarms(marketKey, pairId)
}

private class SettingsPort(private val repository: SettingsRepository) : RefreshSettings {
    override suspend fun current() = repository.current()
}

private class StatsPort(private val stats: RefreshStats) : RefreshStatsPort {
    override val changeStamp: StateFlow<ChangeStamp?> get() = stats.changeStamp
    override val lastDurationMillis: StateFlow<Long> get() = stats.lastDurationMillis
    override fun setLastRefresh(durationMillis: Long) = stats.setLastRefresh(durationMillis)
}

private class WidgetsPort(private val gate: LiveWidgetGate) : RefreshWidgets {
    override suspend fun drawAll(deferWhenOff: Boolean, fetch: Boolean) = gate.drawAll(deferWhenOff, fetch)
}

private class ChartsPort(private val charts: ChartRefresher) : RefreshCharts {
    override fun markIfStale() = charts.markIfStale()
    override fun launchIfPending() = charts.launchIfPending()
}
