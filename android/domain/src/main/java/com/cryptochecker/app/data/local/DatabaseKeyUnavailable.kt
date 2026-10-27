package com.cryptochecker.app.data.local

/**
 * Keystore gibt den Schlüssel einer verschlüsselten Datenbank gerade nicht heraus (vorübergehend, nichts verändert).
 * Hintergrund-Einstiege (Worker, Empfänger, Widgets) überspringen den Lauf und versuchen es später erneut
 * ([isDatabaseKeyUnavailable]); die Oberfläche zeigt ihren üblichen Fehlerzustand.
 */
class DatabaseKeyUnavailableException(database: String, cause: Throwable) :
    IllegalStateException("$database: Schlüssel gerade nicht verfügbar", cause)

/** Liegt (auch verschachtelt, z. B. von Room umhüllt) ein [DatabaseKeyUnavailableException] vor? */
fun Throwable.isDatabaseKeyUnavailable(): Boolean =
    generateSequence(this) { it.cause.takeIf { c -> c !== it } }.take(MAX_CAUSE_DEPTH).any { it is DatabaseKeyUnavailableException }

private const val MAX_CAUSE_DEPTH = 16
