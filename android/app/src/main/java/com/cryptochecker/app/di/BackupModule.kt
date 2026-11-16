package com.cryptochecker.app.di

import android.content.Context
import com.cryptochecker.app.data.backup.DeviceAuthCheck
import com.cryptochecker.app.lock.AppLockAuth
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

/** Sicherung (Modul `data`): Prüfung der Geräte-Sperre aus der App ([AppLockAuth]). */
@Module
@InstallIn(SingletonComponent::class)
object BackupModule {

    @Provides
    fun provideDeviceAuthCheck(@ApplicationContext context: Context): DeviceAuthCheck =
        DeviceAuthCheck { AppLockAuth.canAuthenticate(context) }
}
