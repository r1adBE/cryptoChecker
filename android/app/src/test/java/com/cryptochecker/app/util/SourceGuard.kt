package com.cryptochecker.app.util

import org.junit.AssumptionViolatedException

/**
 * Pfade der Quelltext-Wächtertests: Fehlt eine Datei, darf der Test nicht still grün werden.
 * - [android]: eigene Android-Quellen und Ressourcen sind im Checkout immer da → Fehler.
 * - [ios]: iOS-Quellen fehlen in einem reinen Android-Checkout → «übersprungen» (sichtbar statt grün); in der CI
 *   (`REQUIRE_IOS_SOURCES=true`, android.yml checkt das ganze Repo aus) → Fehler.
 */
object SourceGuard {

    const val REQUIRE_IOS_ENV = "REQUIRE_IOS_SOURCES"

    fun <T : Any> android(value: T?, what: String): T =
        value ?: throw AssertionError("$what nicht gefunden (Arbeitsverzeichnis? umbenannt oder verschoben?)")

    fun <T : Any> ios(value: T?, what: String, requireIos: Boolean = iosRequired()): T {
        if (value != null) return value
        if (requireIos) throw AssertionError("$what nicht gefunden, obwohl $REQUIRE_IOS_ENV=true (umbenannt oder verschoben?)")
        throw AssumptionViolatedException("$what nicht im Baum (reiner Android-Checkout)")
    }

    fun iosRequired(): Boolean = System.getenv(REQUIRE_IOS_ENV) == "true"
}
