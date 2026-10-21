package com.cryptochecker.app.domain.portfolio

/**
 * «Adresse entfernen» mit «Rückgängig» (seit Build 94): Was geht mit der Adresse weg (für den Text der Rückfrage), und
 * darf die entfernte Adresse mit ihrer alten Id zurück? Rein, gleich wie `WatchedAddressUndo.swift` (iOS).
 */
object WatchedAddressUndo {

    /** Was mit der Adresse verloren ginge: Zahl der Eröffnungsposten und ob ein Guthaben-Verlauf (auch Token) da ist. */
    data class Removal(val lots: Int, val history: Boolean) {
        val isEmpty: Boolean get() = lots == 0 && !history
    }

    fun removal(address: WatchedAddress): Removal = Removal(
        lots = address.costLots.size,
        history = address.observations.isNotEmpty() || address.tokenObservations.values.any { it.isNotEmpty() },
    )

    /**
     * Zurück mit derselben Id nur, wenn die Id frei ist, dieselbe Adresse (Netz + Adresse) nicht inzwischen neu
     * hinzugefügt wurde und die Liste nicht voll ist ([WatchedAddressRules.MAX_ADDRESSES]).
     */
    fun canRestore(existing: List<WatchedAddress>, removed: WatchedAddress): Boolean =
        removed.id > 0 &&
            existing.none { it.id == removed.id } &&
            !WatchedAddressRules.contains(existing, removed.chain, removed.address) &&
            WatchedAddressRules.canAdd(existing)

    /** Warum «Rückgängig» nicht ging (seit Build 95: Banner statt still nichts). */
    enum class RestoreFailure { DUPLICATE, LIMIT, NOT_SAVED }

    /**
     * Grund nach einem gescheiterten Zurückholen: dieselbe Adresse inzwischen neu hinzugefügt, Liste voll — sonst nicht
     * gespeichert (Id belegt, Datei bzw. Datenbank gerade nicht beschreibbar).
     */
    fun failure(existing: List<WatchedAddress>, removed: WatchedAddress): RestoreFailure = when {
        WatchedAddressRules.contains(existing, removed.chain, removed.address) -> RestoreFailure.DUPLICATE
        !WatchedAddressRules.canAdd(existing) -> RestoreFailure.LIMIT
        else -> RestoreFailure.NOT_SAVED
    }
}
