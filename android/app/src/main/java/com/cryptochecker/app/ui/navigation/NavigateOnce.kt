// Schutz gegen doppeltes Tippen beim Öffnen einer Seite (Teil von `AppNavHost`).
// Während der Überblendung (200 ms) bleibt die alte Seite tippbar; ein zweiter Tipp legte dieselbe Seite
// sonst ein zweites Mal auf den Stapel (Zurück führte auf dieselbe Seite, zwei ViewModels).
package com.cryptochecker.app.ui.navigation

import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController

internal object NavigateOnce {
    /**
     * Darf die Seite mit dem Zustand [state] noch weiter navigieren? Nur solange sie vorne steht (RESUMED):
     * Nach dem ersten Tipp ist sie während der Überblendung höchstens STARTED, der zweite Tipp verpufft.
     */
    fun allowed(state: Lifecycle.State): Boolean = state.isAtLeast(Lifecycle.State.RESUMED)
}

/**
 * Öffnet [route] von der Seite [from] aus – nur wenn [from] noch vorne steht, und mit `launchSingleTop`,
 * damit auch ein Tipp, der die Prüfung überholt, keinen zweiten gleichen Eintrag erzeugt.
 */
internal fun NavHostController.navigateOnce(from: NavBackStackEntry, route: ScreenRoute) {
    if (!NavigateOnce.allowed(from.lifecycle.currentState)) return
    navigate(route) { launchSingleTop = true }
}

/** Seite «Paar hinzufügen» von [from] aus, mit demselben Schutz wie [navigateOnce]. */
internal fun NavHostController.openExplorerOnce(from: NavBackStackEntry) {
    if (!NavigateOnce.allowed(from.lifecycle.currentState)) return
    openExplorer()
}
