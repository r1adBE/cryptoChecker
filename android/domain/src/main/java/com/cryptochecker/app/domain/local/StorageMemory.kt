package com.cryptochecker.app.domain.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Zustand der Datenbanken in diesem Prozess: welche nur im Arbeitsspeicher liegen ([inMemory], davon mit gespeicherter
 * Datei [storedKept]) und ein Zähler der Änderungen des gespeicherten Vermerks ([changes]). Gesetzt von
 * `DatabaseEncryptionNotice` (:data), gelesen von der Oberfläche.
 */
object StorageMemory {
    private val changed = MutableStateFlow(0)

    /** Zähler der Änderungen in diesem Prozess: Die Seite prüft bei jeder Änderung erneut. */
    val changes: StateFlow<Int> = changed.asStateFlow()

    /** Gespeicherter Vermerk geändert ([changes] zählt hoch). */
    fun noteChanged() {
        changed.update { it + 1 }
    }

    private val inMemoryNames = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Datenbanken, die in diesem Prozess nur im Arbeitsspeicher liegen (kein Schlüssel, nie unverschlüsselt angelegt;
     * dazu [storedKept]). Nicht gespeichert: Jeder Start entscheidet neu.
     */
    val inMemory: StateFlow<Set<String>> = inMemoryNames.asStateFlow()

    /** Liegt [database] in diesem Prozess nur im Arbeitsspeicher? Erst nach dem ersten Öffnen verlässlich. */
    fun isInMemory(database: String): Boolean = database in inMemoryNames.value

    private val keptNames = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Teilmenge von [inMemory]: Die verschlüsselte Datei ist da, nur gab der Keystore ihren Schlüssel gerade nicht heraus.
     * Der gespeicherte Stand ist jetzt nicht zu sehen und kommt nach einem Neustart zurück.
     */
    val storedKept: StateFlow<Set<String>> = keptNames.asStateFlow()

    /** Liegt [database] nur im Arbeitsspeicher, obwohl die Datei mit gespeichertem Stand da ist ([storedKept])? */
    fun isStoredKept(database: String): Boolean = database in keptNames.value

    /** Erst die Variante, dann [inMemory]: Wer [inMemory] beobachtet, sieht die Variante schon. */
    fun markInMemory(database: String, storedKept: Boolean = false) {
        if (storedKept) keptNames.update { it + database }
        inMemoryNames.update { it + database }
    }

    /** Nur für Tests: Vermerke von [markInMemory] für [database] wieder entfernen. */
    fun clearInMemoryForTest(database: String) {
        keptNames.update { it - database }
        inMemoryNames.update { it - database }
    }
}
