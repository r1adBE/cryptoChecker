package com.cryptochecker.app.widget

import kotlinx.coroutines.CancellationException

/**
 * Liest höchstens EINMAL ([read]) und gibt danach immer dasselbe Ergebnis — so lesen Kopf, Zeilen und Leer-Hinweis
 * aller Listen-Widgets eines Zeichnens dieselben Paare aus einer Abfrage statt je Widget drei. Schlägt das Lesen fehl,
 * wirft jeder Abruf dieselbe Ausnahme (wie jedes einzelne Lesen bisher); ein Abbruch wird nicht gemerkt. Nicht
 * threadsicher: ein Zeichnen nach dem anderen (Sperre des Zeichnens).
 */
internal class WidgetOnceRead<T : Any>(private val read: suspend () -> T) {
    private var value: T? = null
    private var failure: Throwable? = null

    /** Das gelesene Ergebnis (beim ersten Abruf gelesen). */
    suspend fun get(): T {
        value?.let { return it }
        failure?.let { throw it }
        return try {
            read().also { value = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e
            throw e
        }
    }

    /** Schon gelesen und gelungen: das Ergebnis; sonst null (nicht gelesen oder fehlgeschlagen). */
    fun peek(): T? = value
}
