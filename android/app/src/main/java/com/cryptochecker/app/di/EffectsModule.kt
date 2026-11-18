package com.cryptochecker.app.di

import android.net.Uri
import com.cryptochecker.app.effects.AlarmNotifications
import com.cryptochecker.app.effects.AndroidDiagnosticsDevice
import com.cryptochecker.app.effects.AndroidSettingsDevice
import com.cryptochecker.app.effects.AndroidWatchEditEffects
import com.cryptochecker.app.effects.AppNotifierAlarmNotifications
import com.cryptochecker.app.effects.BackupFolderAccess
import com.cryptochecker.app.effects.ContentResolverBackupFolderAccess
import com.cryptochecker.app.effects.ContentResolverDocumentReader
import com.cryptochecker.app.effects.DiagnosticsDevice
import com.cryptochecker.app.effects.DocumentReader
import com.cryptochecker.app.effects.SettingsDevice
import com.cryptochecker.app.ui.features.portfolio.ExportTarget
import com.cryptochecker.app.ui.features.portfolio.UriExportTarget
import com.cryptochecker.app.ui.features.watchlist.WatchEditEffects
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Nebenwirkungen der ViewModels am Gerät (Dateien, Freigaben, Mitteilungen, Sprache): Schnittstelle → Android-Umsetzung. */
@Suppress("unused")
@Module
@InstallIn(SingletonComponent::class)
abstract class EffectsModule {
    @Binds
    abstract fun backupFolderAccess(impl: ContentResolverBackupFolderAccess): BackupFolderAccess

    @Binds
    abstract fun documentReader(impl: ContentResolverDocumentReader): DocumentReader

    @Binds
    abstract fun exportTarget(impl: UriExportTarget): ExportTarget<Uri>

    @Binds
    abstract fun settingsDevice(impl: AndroidSettingsDevice): SettingsDevice

    @Binds
    abstract fun diagnosticsDevice(impl: AndroidDiagnosticsDevice): DiagnosticsDevice

    @Binds
    abstract fun alarmNotifications(impl: AppNotifierAlarmNotifications): AlarmNotifications

    @Binds
    abstract fun watchEditEffects(impl: AndroidWatchEditEffects): WatchEditEffects
}
