package com.cryptochecker.app.di

import com.cryptochecker.app.alarm.PortfolioAlarmChecker
import com.cryptochecker.app.wearable.PhoneWatchFeed
import com.cryptochecker.app.widget.PortfolioAlarmCheck
import com.cryptochecker.app.widget.PortfolioWatchFeed
import com.cryptochecker.app.widget.WidgetScheduler
import com.cryptochecker.app.work.PriceUpdateScheduler
import com.cryptochecker.app.domain.refresh.CheckTrigger
import com.cryptochecker.app.widget.WidgetReconnectCheck
import com.cryptochecker.app.work.BackgroundCheck
import dagger.Binds
import dagger.Provides
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Was die Widgets (Modul :widget) von der App brauchen ([WidgetScheduler], [PortfolioAlarmCheck], [PortfolioWatchFeed], [WidgetReconnectCheck]). */
@Suppress("unused")
@Module
@InstallIn(SingletonComponent::class)
abstract class WidgetHostModule {

    @Binds
    abstract fun bindWidgetScheduler(impl: PriceUpdateScheduler): WidgetScheduler

    @Binds
    abstract fun bindPortfolioAlarmCheck(impl: PortfolioAlarmChecker): PortfolioAlarmCheck

    @Binds
    abstract fun bindPortfolioWatchFeed(impl: PhoneWatchFeed): PortfolioWatchFeed

    companion object {
        /** Prüfung nach Rückkehr des Netzes = Lauf der Hintergrund-Prüfung mit garantiertem Netz. */
        @Provides
        fun provideWidgetReconnectCheck(backgroundCheck: BackgroundCheck): WidgetReconnectCheck =
            WidgetReconnectCheck { tts -> backgroundCheck.run(CheckTrigger.WORKER, networkGuaranteed = true, ttsWaitMillis = tts) }
    }
}
