package com.cryptochecker.app.util

/**
 * Protokoll der Abläufe in `:domain` ohne Timber (Schritt zu Kotlin Multiplatform, Build 146): gleiche Form wie
 * Timber (`%s`-Platzhalter, Ausnahme vorn). Die App setzt beim Start [sink] (Timber; nur in Debug-Builds mit Baum
 * sichtbar); ohne [sink] — Unit-Tests, sehr früh — geht nichts verloren, was vorher sichtbar war.
 */
object DomainLog {
    enum class Level { DEBUG, INFO, WARN }

    fun interface Sink {
        fun log(level: Level, error: Throwable?, message: String, args: Array<out Any?>)
    }

    @Volatile var sink: Sink? = null

    fun d(message: String, vararg args: Any?) = emit(Level.DEBUG, null, message, args)
    fun d(error: Throwable?, message: String, vararg args: Any?) = emit(Level.DEBUG, error, message, args)
    fun i(message: String, vararg args: Any?) = emit(Level.INFO, null, message, args)
    fun i(error: Throwable?, message: String, vararg args: Any?) = emit(Level.INFO, error, message, args)
    fun w(message: String, vararg args: Any?) = emit(Level.WARN, null, message, args)
    fun w(error: Throwable?, message: String, vararg args: Any?) = emit(Level.WARN, error, message, args)

    private fun emit(level: Level, error: Throwable?, message: String, args: Array<out Any?>) {
        sink?.log(level, error, message, args)
    }
}
