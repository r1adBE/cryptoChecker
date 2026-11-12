package com.cryptochecker.app.di

import com.cryptochecker.app.data.DexLiquidityStore
import com.cryptochecker.app.data.HourlyCloseStore
import com.cryptochecker.app.data.MarkFallbackStore
import com.cryptochecker.app.data.MoveHistoryStore
import com.cryptochecker.app.data.RefreshStats
import com.cryptochecker.app.data.SparklineRepository
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.portfolio.CurrencyConverter
import com.cryptochecker.app.data.remote.NearExtremeDataSource
import com.cryptochecker.app.data.remote.VolumeDataSource
import com.cryptochecker.app.domain.alarm.AlarmCheckStatus
import com.cryptochecker.app.domain.alarm.AlarmEvaluator
import com.cryptochecker.app.data.alarm.DerivativesAlarmData
import com.cryptochecker.app.domain.live.LiveCoverage
import com.cryptochecker.app.data.refresh.AlarmLogStore
import com.cryptochecker.app.domain.refresh.ChartRefresher
import com.cryptochecker.app.domain.refresh.CheckRunStore
import com.cryptochecker.app.refresh.DayReferences
import com.cryptochecker.app.domain.refresh.LivePriceApplier
import com.cryptochecker.app.domain.refresh.ManualRefresh
import com.cryptochecker.app.domain.refresh.PriceFetcher
import com.cryptochecker.app.domain.refresh.PriceRefresher
import com.cryptochecker.app.domain.refresh.RefreshEffects
import com.cryptochecker.app.domain.refresh.RefreshLock
import com.cryptochecker.app.domain.refresh.RefreshResultProcessor
import com.cryptochecker.app.domain.refresh.RefresherCandles
import com.cryptochecker.app.domain.refresh.RefresherDrawing
import com.cryptochecker.app.domain.refresh.RefresherState
import com.cryptochecker.app.domain.util.DefaultDispatcher
import com.cryptochecker.app.domain.util.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import com.cryptochecker.app.refresh.RefreshSideChecks
import com.cryptochecker.app.refresh.asAlarmDerivativesSource
import com.cryptochecker.app.refresh.asAlarmMoveHistory
import com.cryptochecker.app.refresh.asAlarmRangeSource
import com.cryptochecker.app.refresh.asAlarmRateSource
import com.cryptochecker.app.refresh.asAlarmVolumeSource
import com.cryptochecker.app.refresh.asChartWidgets
import com.cryptochecker.app.refresh.asManualRefreshNet
import com.cryptochecker.app.refresh.asManualRefreshStats
import com.cryptochecker.app.refresh.asRefreshAlarmLog
import com.cryptochecker.app.refresh.asRefreshCharts
import com.cryptochecker.app.refresh.asRefreshDayReferences
import com.cryptochecker.app.refresh.asRefreshDexLiquidity
import com.cryptochecker.app.refresh.asRefreshHourlyClose
import com.cryptochecker.app.refresh.asRefreshMarkFallback
import com.cryptochecker.app.refresh.asRefreshNotifier
import com.cryptochecker.app.refresh.asRefreshSettings
import com.cryptochecker.app.refresh.asRefreshSpeaker
import com.cryptochecker.app.refresh.asRefreshSpokenText
import com.cryptochecker.app.refresh.asRefreshStatsPort
import com.cryptochecker.app.refresh.asRefreshStore
import com.cryptochecker.app.refresh.asRefreshWidgets
import com.cryptochecker.app.refresh.asRefresherCharts
import com.cryptochecker.app.refresh.asRefresherDayReferences
import com.cryptochecker.app.refresh.asRefresherOnline
import com.cryptochecker.app.refresh.asRefresherSideChecks
import com.cryptochecker.app.refresh.asRefresherSparklines
import com.cryptochecker.app.refresh.asRefresherStats
import com.cryptochecker.app.refresh.asRefresherStore
import com.cryptochecker.app.refresh.asRefresherWatchWidgets
import com.cryptochecker.app.data.notification.AlarmDeliveryMarkers
import com.cryptochecker.app.notification.AppNotifier
import com.cryptochecker.app.data.settings.SettingsRepository
import com.cryptochecker.app.tts.SpokenText
import com.cryptochecker.app.tts.TtsSpeaker
import com.cryptochecker.app.data.util.ConnectivityMonitor
import com.cryptochecker.app.widget.LiveWidgetGate
import com.cryptochecker.app.widget.WidgetUpdater
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Aktualisierung ([PriceRefresher]) und Auswertung nach einem neuen Kurs ([RefreshEffects], [RefreshResultProcessor],
 * [LivePriceApplier]) — die Klassen liegen in :domain und kennen nur die schmalen Schnittstellen aus `RefreshPorts.kt`
 * und `PriceRefresherPorts.kt`. Hier reicht die App ihre Klassen
 * über die Hüllen aus `RefreshPortAdapters.kt` bzw. `PriceRefresherAdapters.kt` hinein (kein zweiter @Inject-Konstruktor in der Klasse).
 */
@Module
@InstallIn(SingletonComponent::class)
object RefreshModule {

    @Provides
    @Singleton
    fun refreshEffects(
        watchRepository: WatchRepository,
        alarmEvaluator: AlarmEvaluator,
        notifier: AppNotifier,
        ttsSpeaker: TtsSpeaker,
        spokenText: SpokenText,
        volumeDataSource: VolumeDataSource,
        currencyConverter: CurrencyConverter,
        nearExtremeDataSource: NearExtremeDataSource,
        derivativesAlarmData: DerivativesAlarmData,
        moveHistoryStore: MoveHistoryStore,
        alarmCheckStatus: AlarmCheckStatus,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        deliveryMarkers: AlarmDeliveryMarkers,
    ): RefreshEffects = RefreshEffects(
        watchRepository.asRefreshStore(),
        alarmEvaluator,
        notifier.asRefreshNotifier(),
        ttsSpeaker.asRefreshSpeaker(),
        spokenText.asRefreshSpokenText(),
        volumeDataSource.asAlarmVolumeSource(),
        currencyConverter.asAlarmRateSource(),
        nearExtremeDataSource.asAlarmRangeSource(),
        derivativesAlarmData.asAlarmDerivativesSource(),
        moveHistoryStore.asAlarmMoveHistory(),
        alarmCheckStatus,
        ioDispatcher,
        deliveryMarkers,
    )

    @Provides
    @Singleton
    fun refreshResultProcessor(
        watchRepository: WatchRepository,
        notifier: AppNotifier,
        dayReferences: DayReferences,
        effects: RefreshEffects,
        dexLiquidity: DexLiquidityStore,
        markFallback: MarkFallbackStore,
        alarmLog: AlarmLogStore,
        hourlyClose: HourlyCloseStore,
    ): RefreshResultProcessor = RefreshResultProcessor(
        watchRepository.asRefreshStore(),
        notifier.asRefreshNotifier(),
        dayReferences.asRefreshDayReferences(),
        effects,
        dexLiquidity.asRefreshDexLiquidity(),
        markFallback.asRefreshMarkFallback(),
        alarmLog.asRefreshAlarmLog(),
        hourlyClose.asRefreshHourlyClose(),
    )

    @Provides
    @Singleton
    fun livePriceApplier(
        refreshLock: RefreshLock,
        watchRepository: WatchRepository,
        settingsRepository: SettingsRepository,
        refreshStats: RefreshStats,
        liveWidgetGate: LiveWidgetGate,
        chartRefresher: ChartRefresher,
        resultProcessor: RefreshResultProcessor,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): LivePriceApplier = LivePriceApplier(
        refreshLock,
        watchRepository.asRefreshStore(),
        settingsRepository.asRefreshSettings(),
        refreshStats.asRefreshStatsPort(),
        liveWidgetGate.asRefreshWidgets(),
        chartRefresher.asRefreshCharts(),
        resultProcessor,
        ioDispatcher,
    )

    @Provides
    fun refresherState(
        watchRepository: WatchRepository,
        settingsRepository: SettingsRepository,
        refreshStats: RefreshStats,
        connectivity: ConnectivityMonitor,
    ): RefresherState = RefresherState(
        watchRepository.asRefresherStore(),
        settingsRepository.asRefreshSettings(),
        refreshStats.asRefresherStats(),
        connectivity.asRefresherOnline(),
    )

    @Provides
    fun refresherDrawing(
        widgetUpdater: WidgetUpdater,
        liveWidgetGate: LiveWidgetGate,
        chartRefresher: ChartRefresher,
    ): RefresherDrawing = RefresherDrawing(
        widgetUpdater.asRefresherWatchWidgets(),
        liveWidgetGate.asRefreshWidgets(),
        chartRefresher.asRefresherCharts(),
    )

    @Provides
    fun refresherCandles(sparklineRepository: SparklineRepository, dayReferences: DayReferences): RefresherCandles =
        RefresherCandles(sparklineRepository.asRefresherSparklines(), dayReferences.asRefresherDayReferences())

    /** Die Bündel ([RefresherState], [RefresherDrawing], [RefresherCandles]) baut Hilt nur hierfür, also einmal. */
    @Provides
    @Singleton
    fun priceRefresher(
        state: RefresherState,
        drawing: RefresherDrawing,
        candles: RefresherCandles,
        liveCoverage: LiveCoverage,
        priceFetcher: PriceFetcher,
        effects: RefreshEffects,
        refreshLock: RefreshLock,
        resultProcessor: RefreshResultProcessor,
        sideChecks: RefreshSideChecks,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): PriceRefresher = PriceRefresher(
        state,
        drawing,
        candles,
        liveCoverage,
        priceFetcher,
        effects,
        refreshLock,
        resultProcessor,
        sideChecks.asRefresherSideChecks(),
        ioDispatcher,
    )

    @Provides
    @Singleton
    fun chartRefresher(widgetUpdater: WidgetUpdater, @IoDispatcher ioDispatcher: CoroutineDispatcher): ChartRefresher =
        ChartRefresher(widgetUpdater.asChartWidgets(), ioDispatcher)

    @Provides
    @Singleton
    fun manualRefresh(
        priceRefresher: PriceRefresher,
        refreshStats: RefreshStats,
        connectivity: ConnectivityMonitor,
        checkRuns: CheckRunStore,
        @DefaultDispatcher defaultDispatcher: CoroutineDispatcher,
    ): ManualRefresh = ManualRefresh(
        priceRefresher,
        refreshStats.asManualRefreshStats(),
        connectivity.asManualRefreshNet(),
        checkRuns,
        defaultDispatcher,
    )
}
