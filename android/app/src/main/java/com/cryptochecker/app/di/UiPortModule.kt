package com.cryptochecker.app.di

import android.net.Uri
import com.cryptochecker.app.data.ActivityRepository
import com.cryptochecker.app.data.AddMoments
import com.cryptochecker.app.data.DexLiquidityStore
import com.cryptochecker.app.data.DexTokenAddressStore
import com.cryptochecker.app.data.ExchangeRankingStore
import com.cryptochecker.app.data.GuardedWrites
import com.cryptochecker.app.data.HourlyCloseStore
import com.cryptochecker.app.data.MarkFallbackStore
import com.cryptochecker.app.data.RefreshStats
import com.cryptochecker.app.data.RetiredNoticeStore
import com.cryptochecker.app.data.StarterCoinsRepository
import com.cryptochecker.app.data.TokenIdentityStore
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.backup.AutoBackupRunner
import com.cryptochecker.app.data.backup.AutoBackupStore
import com.cryptochecker.app.data.backup.BackupManager
import com.cryptochecker.app.data.live.LivePriceStream
import com.cryptochecker.app.data.local.StoredNoticeFiles
import com.cryptochecker.app.data.logos.CoinLogoRepository
import com.cryptochecker.app.data.portfolio.CurrencyConverter
import com.cryptochecker.app.data.portfolio.FxRateSource
import com.cryptochecker.app.data.portfolio.HistoricPriceSource
import com.cryptochecker.app.data.portfolio.PortfolioAlarmRepository
import com.cryptochecker.app.data.portfolio.PortfolioCostGapStore
import com.cryptochecker.app.data.portfolio.PortfolioHistorySource
import com.cryptochecker.app.data.portfolio.PortfolioPriceFeedAdapter
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.data.portfolio.WatchedAddressRefresher
import com.cryptochecker.app.data.portfolio.WatchedAddressServerProbe
import com.cryptochecker.app.data.refresh.AlarmLogStore
import com.cryptochecker.app.data.remote.FuturesDataSource
import com.cryptochecker.app.data.remote.NearExtremeDataSource
import com.cryptochecker.app.data.remote.SpotDataSource
import com.cryptochecker.app.data.remote.VolumeDataSource
import com.cryptochecker.app.data.repositoryBulkAddRunner
import com.cryptochecker.app.data.util.ConnectivityMonitor
import com.cryptochecker.app.domain.BulkAddRunner
import com.cryptochecker.app.domain.activity.ActivityReports
import com.cryptochecker.app.domain.alarm.AlarmLogView
import com.cryptochecker.app.domain.alarm.PortfolioAlarmEditor
import com.cryptochecker.app.domain.backup.AutoBackupJobs
import com.cryptochecker.app.domain.backup.AutoBackupSettings
import com.cryptochecker.app.domain.backup.BackupFiles
import com.cryptochecker.app.domain.convert.QuoteConversion
import com.cryptochecker.app.domain.live.LivePrices
import com.cryptochecker.app.domain.local.StoredNotices
import com.cryptochecker.app.domain.local.WriteGuard
import com.cryptochecker.app.domain.logos.CoinLogoCatalog
import com.cryptochecker.app.domain.market.ExchangeVolumes
import com.cryptochecker.app.domain.portfolio.AddressRefresh
import com.cryptochecker.app.domain.portfolio.AddressServerProbe
import com.cryptochecker.app.domain.portfolio.CostGapAcks
import com.cryptochecker.app.domain.portfolio.FxRates
import com.cryptochecker.app.domain.portfolio.HistoricPrices
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryFeed
import com.cryptochecker.app.domain.portfolio.PortfolioPriceFeed
import com.cryptochecker.app.domain.portfolio.PortfolioReader
import com.cryptochecker.app.domain.portfolio.PortfolioStore
import com.cryptochecker.app.domain.refresh.AlarmRangeSource
import com.cryptochecker.app.domain.refresh.AlarmVolumeSource
import com.cryptochecker.app.domain.refresh.RefreshStatsView
import com.cryptochecker.app.domain.remote.FuturesInfoSource
import com.cryptochecker.app.domain.remote.SpotInfoSource
import com.cryptochecker.app.domain.starter.AddMomentStore
import com.cryptochecker.app.domain.starter.StarterCoinSource
import com.cryptochecker.app.domain.util.DefaultDispatcher
import com.cryptochecker.app.domain.util.NetworkStatus
import com.cryptochecker.app.domain.watch.DexLiquidityValues
import com.cryptochecker.app.domain.watch.DexTokenAddresses
import com.cryptochecker.app.domain.watch.HourlyCloseIds
import com.cryptochecker.app.domain.watch.MarkFallbackIds
import com.cryptochecker.app.domain.watch.RetiredNotices
import com.cryptochecker.app.domain.watch.TokenNames
import com.cryptochecker.app.logos.RepositoryLogoSource
import com.cryptochecker.app.refresh.asAlarmRangeSource
import com.cryptochecker.app.refresh.asAlarmVolumeSource
import com.cryptochecker.app.ui.components.CoinLogoSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher

/** Schnittstellen der Oberfläche aus :domain, Umsetzungen aus :data. */
@Suppress("unused")
@Module
@InstallIn(SingletonComponent::class)
abstract class UiPortModule {
    @Binds
    abstract fun networkStatus(impl: ConnectivityMonitor): NetworkStatus

    @Binds
    abstract fun markFallbackIds(impl: MarkFallbackStore): MarkFallbackIds

    @Binds
    abstract fun hourlyCloseIds(impl: HourlyCloseStore): HourlyCloseIds

    @Binds
    abstract fun addMomentStore(impl: AddMoments): AddMomentStore

    @Binds
    abstract fun refreshStatus(impl: RefreshStats): RefreshStatsView

    @Binds
    abstract fun writeGuard(impl: GuardedWrites): WriteGuard

    @Binds
    abstract fun alarmLogView(impl: AlarmLogStore): AlarmLogView

    @Binds
    abstract fun tokenNames(impl: TokenIdentityStore): TokenNames

    @Binds
    abstract fun dexLiquidityValues(impl: DexLiquidityStore): DexLiquidityValues

    @Binds
    abstract fun dexTokenAddresses(impl: DexTokenAddressStore): DexTokenAddresses

    @Binds
    abstract fun starterCoinSource(impl: StarterCoinsRepository): StarterCoinSource

    @Binds
    abstract fun exchangeVolumes(impl: ExchangeRankingStore): ExchangeVolumes

    @Binds
    abstract fun activityReports(impl: ActivityRepository): ActivityReports

    @Binds
    abstract fun portfolioAlarmEditor(impl: PortfolioAlarmRepository): PortfolioAlarmEditor

    @Binds
    abstract fun autoBackupJobs(impl: AutoBackupRunner): AutoBackupJobs

    @Binds
    abstract fun marketCache(impl: com.cryptochecker.app.data.market.MarketCacheAdapter): com.cryptochecker.app.domain.market.MarketCache

    @Binds
    abstract fun marketSources(
        impl: com.cryptochecker.app.data.market.MarketSourcesAdapter
    ): com.cryptochecker.app.domain.market.MarketSources

    @Binds
    abstract fun autoBackupSettings(impl: AutoBackupStore): AutoBackupSettings

    @Binds
    abstract fun retiredNotices(impl: RetiredNoticeStore): RetiredNotices

    @Binds
    abstract fun coinLogoCatalog(impl: CoinLogoRepository): CoinLogoCatalog

    @Binds
    abstract fun coinLogoSource(impl: RepositoryLogoSource): CoinLogoSource

    @Binds
    abstract fun quoteConversion(impl: CurrencyConverter): QuoteConversion

    @Binds
    abstract fun futuresInfoSource(impl: FuturesDataSource): FuturesInfoSource

    @Binds
    abstract fun spotInfoSource(impl: SpotDataSource): SpotInfoSource

    @Binds
    abstract fun livePrices(impl: LivePriceStream): LivePrices

    @Binds
    abstract fun storedNotices(impl: StoredNoticeFiles): StoredNotices

    @Binds
    abstract fun backupFiles(impl: BackupManager): BackupFiles<Uri>

    @Binds
    abstract fun portfolioReader(impl: PortfolioRepository): PortfolioReader

    @Binds
    abstract fun addressServerProbe(impl: WatchedAddressServerProbe): AddressServerProbe

    @Binds
    abstract fun portfolioStore(impl: PortfolioRepository): PortfolioStore

    @Binds
    abstract fun portfolioPriceFeed(impl: PortfolioPriceFeedAdapter): PortfolioPriceFeed

    @Binds
    abstract fun fxRates(impl: FxRateSource): FxRates

    @Binds
    abstract fun historicPrices(impl: HistoricPriceSource): HistoricPrices

    @Binds
    abstract fun portfolioHistoryFeed(impl: PortfolioHistorySource): PortfolioHistoryFeed

    @Binds
    abstract fun addressRefresh(impl: WatchedAddressRefresher): AddressRefresh

    @Binds
    abstract fun costGapAcks(impl: PortfolioCostGapStore): CostGapAcks

    companion object {
        /** App-weite Sammelaufnahme «Mehrere Paare auf einmal» (eine je Prozess). */
        @Provides
        @Singleton
        fun bulkAddRunner(
            watchRepository: WatchRepository,
            addMoments: AddMoments,
            @DefaultDispatcher defaultDispatcher: CoroutineDispatcher,
        ): BulkAddRunner = repositoryBulkAddRunner(watchRepository, addMoments, defaultDispatcher)

        /** Hoch/Tief der Tageskerzen (Schnell-Alarme im Editor), gleiche Quelle wie die Alarmprüfung. */
        @Provides
        fun alarmRangeSource(source: NearExtremeDataSource): AlarmRangeSource = source.asAlarmRangeSource()

        /** Stundenvolumen (Schnell-Alarme im Editor), gleiche Quelle wie die Alarmprüfung. */
        @Provides
        fun alarmVolumeSource(source: VolumeDataSource): AlarmVolumeSource = source.asAlarmVolumeSource()
    }
}
