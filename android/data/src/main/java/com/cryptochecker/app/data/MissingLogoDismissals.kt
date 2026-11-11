package com.cryptochecker.app.data

import android.content.Context
import androidx.core.content.edit
import com.cryptochecker.app.domain.logos.MissingLogoNotice
import com.cryptochecker.app.domain.util.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coins, deren Hinweis «2 Coins ohne Logo» mit ✕ geschlossen wurde ([MissingLogoNotice]): nur auf diesem Gerät, in
 * der Einstellungsdatei der Logos ([CoinLogoStorage.prefs], «coin_logos») — nicht in der Systemsicherung
 * (backup_rules/data_extraction_rules schliessen sie nicht ein), nie synchronisiert. Teil von [CoinLogoRepository].
 */
@Singleton
class MissingLogoDismissals @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val prefs by lazy { CoinLogoStorage.prefs(context) }
    private val _keys = MutableStateFlow<Set<String>?>(null)

    /** Gemerkter Satz; null, bis er gelesen ist ([load]) — bis dahin kein Hinweis. */
    val keys: StateFlow<Set<String>?> = _keys.asStateFlow()

    /** Satz einmal aus der Datei lesen (nicht im Haupt-Thread). */
    suspend fun load() {
        if (_keys.value != null) return
        val stored = withContext(ioDispatcher) { MissingLogoNotice.decode(prefs.getString(KEY, null)) }
        _keys.compareAndSet(null, stored)
    }

    /** ✕: genau diese Coins ohne Logo merken (ersetzt den vorigen Satz). */
    fun dismiss(keys: Set<String>) {
        _keys.value = keys
        prefs.edit { putString(KEY, MissingLogoNotice.encode(keys)) }
    }

    companion object {
        const val KEY = "missing_logos_dismissed"
    }
}
