package com.cryptochecker.app.di

import com.cryptochecker.app.util.DefaultDispatcher
import com.cryptochecker.app.util.IoDispatcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Dispatcher der per Hilt erzeugten Klassen ([IoDispatcher], [DefaultDispatcher]). Eigenes Modul, damit UI-Tests, die
 * [RemoteDataModule] ersetzen, sie trotzdem bekommen. Ohne `@Singleton`: `Dispatchers.IO`/`Default` sind schon
 * prozessweit dieselben Objekte.
 */
@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {

    @Provides
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
}
