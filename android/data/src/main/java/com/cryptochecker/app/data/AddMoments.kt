package com.cryptochecker.app.data

import com.cryptochecker.app.domain.starter.AddMoment
import com.cryptochecker.app.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Übergibt einen «Erst-Moment» ([AddMoment]) an die Merkliste, die ihn abspielt,
 * sobald sie sichtbar ist und die neuen Zeilen geladen hat. Nur im Speicher:
 * ein einmaliger Zustandswechsel, nichts Dauerhaftes.
 */
@Singleton
class AddMoments @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val watchRepository: WatchRepository,
) {
    private val _pending = MutableStateFlow<AddMoment?>(null)

    /** Noch nicht abgespielter Moment; null = keiner. */
    val pending: StateFlow<AddMoment?> = _pending.asStateFlow()

    /**
     * Start-Merkliste (eines oder alle fünf): immer abspielen. Vor dem Speichern
     * aufrufen, damit die Zeilen schon beim ersten Erscheinen einblenden.
     */
    suspend fun postStarter(pairs: List<AddMoment.Added>) {
        if (pairs.isEmpty()) return
        settingsRepository.setFirstPairAdded(true)
        _pending.value = AddMoment(System.nanoTime(), pairs)
    }

    /**
     * Ein Paar wurde im Hinzufügen-Tab angelegt. Nur das allererste Paar bekommt
     * den Moment; stand schon etwas in der Merkliste (z. B. nach einem Update),
     * gilt das erste Paar als erledigt.
     */
    suspend fun afterExplorerAdd(watchId: Long) {
        if (settingsRepository.current().firstPairAdded) return
        settingsRepository.setFirstPairAdded(true)
        if (watchRepository.getWatches().size != 1) return
        val watch = watchRepository.getWatch(watchId) ?: return
        _pending.value = AddMoment(
            System.nanoTime(),
            listOf(AddMoment.Added(watch.marketKey, watch.baseAsset, watch.quoteAsset, watch.displayName)),
        )
    }

    /** Mehrere Paare auf einmal aus dem Hinzufügen-Tab: kein Moment, aber erledigt. */
    suspend fun markFirstPairAdded() {
        if (!settingsRepository.current().firstPairAdded) settingsRepository.setFirstPairAdded(true)
    }

    /** Die Merkliste hat den Moment übernommen. */
    fun consume(moment: AddMoment) {
        _pending.compareAndSet(moment, null)
    }
}
