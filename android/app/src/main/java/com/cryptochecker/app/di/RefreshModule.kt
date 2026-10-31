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
import com.cryptochecker.app.domain.alarm.DerivativesAlarmData
import com.cryptochecker.app.domain.live.LiveCoverage
import com.cryptochecker.app.domain.refresh.AlarmLogStore
import com.cryptochecker.app.domain.refresh.ChartRefresher
import com.cryptochecker.app.domain.refresh.CheckRunStore
import com.cryptochecker.app.domain.refresh.DayReferences
import com.cryptochecker.app.domain.refresh.LivePriceApplier
import com.cryptochecker.app.domain.refresh.ManualRefresh
import com.cryptochecker.app.domain.refresh.PriceFetcher
import com.cryptochecker.app.domain.refresh.PriceRefresher
import com.cryptochecker.app.domain.refresh.RefreshEffects
import com.cryptochecker.app.domain.refresh.RefreshLock
import com.cryptochecker.app.domain.refresh.RefreshResultProcessor
import com.cryptochecker.app.domain.refresh.RefreshSideChecks
import com.cryptochecker.app.domain.refresh.asAlarmDerivativesSource
import com.cryptochecker.app.domain.refresh.asAlarmMoveHistory
import com.cryptochecker.app.domain.refresh.asAlarmRangeSource
import com.cryptochecker.app.domain.refresh.asAlarmRateSource
import com.cryptochecker.app.domain.refresh.asAlarmVolumeSource
import com.cryptochecker.app.domain.refresh.asChartWidgets
import com.cryptochecker.app.domain.refresh.asManualRefreshNet
import com.cryptochecker.app.domain.refresh.asManualRefreshStats
import com.cryptochecker.app.domain.refresh.asRefreshAlarmLog
import com.cryptochecker.app.domain.refresh.asRefreshCharts
import com.cryptochecker.app.domain.refresh.asRefreshDayReferences
import com.cryptochecker.app.domain.refresh.asRefreshDexLiquidity
import com.cryptochecker.app.domain.refresh.asRefreshHourlyClose
import com.cryptochecker.app.domain.refresh.asRefreshMarkFallback
import com.cryptochecker.app.domain.refresh.asRefreshNotifier
import com.cryptochecker.app.domain.refresh.asRefreshSettings
import com.cryptochecker.app.domain.refresh.asRefreshSpeaker
import com.cryptochecker.app.domain.refresh.asRefreshSpokenText
import com.cryptochecker.app.domain.refresh.asRefreshStatsPort
import com.cryptochecker.app.domain.refresh.asRefreshStore
import com.cryptochecker.app.domain.refresh.asRefreshWidgets
import com.cryptochecker.app.domain.refresh.asRefresherCharts
import com.cryptochecker.app.domain.refresh.asRefresherDayReferences
import com.cryptochecker.app.domain.refresh.asRefresherOnline
import com.cryptochecker.app.domain.refresh.asRefresherSideChecks
import com.cryptochecker.app.domain.refresh.asRefresherSparklines
import com.cryptochecker.app.domain.refresh.asRefresherStats
import com.cryptochecker.app.domain.refresh.asRefresherStore
import com.cryptochecker.app.domain.refresh.asRefresherWatchWidgets
import com.cryptochecker.app.notification.AlarmDeliveryMarkers
import com.cryptochecker.app.notification.AppNotifier
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.tts.SpokenText
import com.cryptochecker.app.tts.TtsSpeaker
import com.cryptochecker.app.util.ConnectivityMonitor
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
    ): LivePriceApplier = LivePriceApplier(
        refreshLock,
        watchRepository.asRefreshStore(),
        settingsRepository.asRefreshSettings(),
        refreshStats.asRefreshStatsPort(),
        liveWidgetGate.asRefreshWidgets(),
        chartRefresher.asRefreshCharts(),
        resultProcessor,
    )

    @Provides
    @Singleton
    fun priceRefresher(
        watchRepository: WatchRepository,
        settingsRepository: SettingsRepository,
        widgetUpdater: WidgetUpdater,
        refreshStats: RefreshStats,
        sparklineRepository: SparklineRepository,
        connectivity: ConnectivityMonitor,
        liveCoverage: LiveCoverage,
        liveWidgetGate: LiveWidgetGate,
        priceFetcher: PriceFetcher,
        dayReferences: DayReferences,
        effects: RefreshEffects,
        refreshLock: RefreshLock,
        resultProcessor: RefreshResultProcessor,
        chartRefresher: ChartRefresher,
        sideChecks: RefreshSideChecks,
    ): PriceRefresher = PriceRefresher(
        watchRepository.asRefresherStore(),
        settingsRepository.asRefreshSettings(),
        widgetUpdater.asRefresherWatchWidgets(),
        refreshStats.asRefresherStats(),
        sparklineRepository.asRefresherSparklines(),
        connectivity.asRefresherOnline(),
        liveCoverage,
        liveWidgetGate.asRefreshWidgets(),
        priceFetcher,
        dayReferences.asRefresherDayReferences(),
        effects,
        refreshLock,
        resultProcessor,
        chartRefresher.asRefresherCharts(),
        sideChecks.asRefresherSideChecks(),
    )

    @Provides
    @Singleton
    fun chartRefresher(widgetUpdater: WidgetUpdater): ChartRefresher = ChartRefresher(widgetUpdater.asChartWidgets())

    @Provides
    @Singleton
    fun manualRefresh(
        priceRefresher: PriceRefresher,
        refreshStats: RefreshStats,
        connectivity: ConnectivityMonitor,
        checkRuns: CheckRunStore,
    ): ManualRefresh = ManualRefresh(
        priceRefresher,
        refreshStats.asManualRefreshStats(),
        connectivity.asManualRefreshNet(),
        checkRuns,
    )
}
