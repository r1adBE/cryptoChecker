package com.cryptochecker.app.domain.watch

import kotlinx.coroutines.flow.StateFlow

/** Geschlossene Hinweise zu stillgelegten Börsen (Umsetzung: `RetiredNoticeStore`). */
interface RetiredNotices {
    /** Geschlossene Einträge; null = noch nicht gelesen ([load]). */
    val dismissed: StateFlow<Set<String>?>

    /** Einmal lesen (nicht im Haupt-Thread). */
    suspend fun load()

    /** ✕ am Hinweis nach dem Ende: für [exchange] schliessen. */
    fun dismiss(exchange: String)

    /** ✕ an der Ankündigung: für [exchange] schliessen. */
    fun dismissAdvance(exchange: String)
}
