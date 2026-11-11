package com.cryptochecker.app.domain.util
import javax.inject.Qualifier

/**
 * Dispatcher für blockierende Ein-/Ausgabe (Datei, Datenbank, Netz) — die App liefert `Dispatchers.IO`
 * (`DispatcherModule`). Per Hilt erzeugte Klassen nehmen ihn im Konstruktor statt `Dispatchers.IO` fest zu
 * verdrahten; Tests reichen den Dispatcher, den sie brauchen.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** Dispatcher für Rechenarbeit abseits des Main-Threads — die App liefert `Dispatchers.Default` (`DispatcherModule`). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher
