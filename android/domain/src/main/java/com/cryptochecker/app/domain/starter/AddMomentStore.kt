package com.cryptochecker.app.domain.starter

import kotlinx.coroutines.flow.StateFlow

/** Moment «erstes Paar hinzugefügt» für die Merkliste (Umsetzung: `AddMoments`). */
interface AddMomentStore {
    val pending: StateFlow<AddMoment?>

    /** Start-Merkliste: immer abspielen; vor dem Speichern aufrufen. */
    suspend fun postStarter(pairs: List<AddMoment.Added>)

    /** Ein Paar wurde im Hinzufügen-Tab angelegt; nur das allererste bekommt den Moment. */
    suspend fun afterExplorerAdd(watchId: Long)

    fun consume(moment: AddMoment)
}
