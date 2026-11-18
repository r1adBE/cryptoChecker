package com.cryptochecker.app.ui.fakes

import com.cryptochecker.app.domain.settings.AppSettings
import com.cryptochecker.app.domain.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Einstellungen im Speicher: [settings] und [cached] aus [state], Setter landen nur in [calls] (ändern [state] nicht).
 * [writeFails] = jeder `guarded`-Schreibweg scheitert wie bei vollem Speicher (Block läuft nicht, false).
 */
internal class FakeSettingsRepository private constructor(
    val calls: MutableList<String>,
    initial: AppSettings,
) : SettingsRepository by recorder(SettingsRepository::class.java, calls) {

    constructor(initial: AppSettings = AppSettings()) : this(mutableListOf(), initial)

    val state = MutableStateFlow(initial)

    var writeFails = false

    /** Zwischenspeicher getrennt von [state], wie vor dem ersten Lesen der Datei. */
    var cachedValue: AppSettings = initial

    override val settings: Flow<AppSettings> get() = state

    override val cached: AppSettings get() = cachedValue

    override val loaded: Boolean get() = true

    override suspend fun current(): AppSettings = state.value

    override suspend fun guarded(block: suspend SettingsRepository.() -> Unit): Boolean {
        if (writeFails) return false
        block()
        return true
    }
}
