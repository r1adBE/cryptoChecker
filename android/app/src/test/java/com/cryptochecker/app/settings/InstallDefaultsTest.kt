package com.cryptochecker.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Geänderte Standards von Build 43 (und Build 44: Portfolio-Tab aus) nur für neue Installationen — wie `InstallDefaultsTests` (iOS).
 * Die Kette bildet die Reihenfolge in SettingsRepository nach: OngoingNotificationsMigration →
 * InstallDefaultsMigration → UpdateModeMigration.
 */
class InstallDefaultsTest {

    private val ongoing = "ongoing_notifications"
    private val names = "watchlist_names"
    private val converted = "show_converted"
    private val alpha = "include_alpha_tokens"
    private val portfolio = "portfolio_enabled"
    private val updateMode = "update_mode"

    /** Wie die drei Migrationen: schreibt nur fehlende Schlüssel; Ergebnis plus «bestehend?» je Migration. */
    private data class Run(val prefs: Map<String, Any>, val installDefaultsExisting: Boolean, val updateModeExisting: Boolean)

    private fun migrate(start: Map<String, Any>): Run {
        val p = start.toMutableMap()
        if (ongoing !in p) p[ongoing] = AppSettings.ongoingForMigration(existingInstall = p.isNotEmpty())
        val existing = AppSettings.isExistingInstall(p.keys, ignored = setOf(ongoing))
        val d = AppSettings()
        p.putIfAbsent(names, AppSettings.defaultForMigration(existing, AppSettings.LEGACY_WATCHLIST_NAMES, d.watchlistNames))
        p.putIfAbsent(converted, AppSettings.defaultForMigration(existing, AppSettings.LEGACY_SHOW_CONVERTED, d.showConverted))
        p.putIfAbsent(alpha, AppSettings.defaultForMigration(existing, AppSettings.LEGACY_INCLUDE_ALPHA_TOKENS, d.includeAlphaTokens))
        p.putIfAbsent(portfolio, AppSettings.defaultForMigration(existing, AppSettings.LEGACY_PORTFOLIO_ENABLED, d.portfolioEnabled))
        val updateExisting = AppSettings.isExistingInstall(p.keys, ignored = SettingsRepository.MIGRATION_WRITTEN_KEYS)
        if (updateMode !in p) p[updateMode] = if (updateExisting) UpdateMode.SPARSAM.name else UpdateMode.DEFAULT.name
        return Run(p, existing, updateExisting)
    }

    @Test
    fun newDefaults() {
        val d = AppSettings()
        assertTrue(d.watchlistNames)
        assertTrue(d.showConverted)
        assertFalse(d.includeAlphaTokens)
        assertFalse(AppSettings.LEGACY_WATCHLIST_NAMES)
        assertFalse(AppSettings.LEGACY_SHOW_CONVERTED)
        assertTrue(AppSettings.LEGACY_INCLUDE_ALPHA_TOKENS)
        assertFalse(d.portfolioEnabled)
        assertTrue(AppSettings.LEGACY_PORTFOLIO_ENABLED)
    }

    /** Die Schlüssel der Migrationen stimmen mit den Namen in SettingsRepository überein. */
    @Test
    fun migrationKeys() {
        assertEquals(setOf(ongoing, names, converted, alpha, portfolio), SettingsRepository.MIGRATION_WRITTEN_KEYS)
        assertEquals(portfolio, SettingsRepository.KEY_PORTFOLIO_ENABLED)
    }

    @Test
    fun decisionFunctions() {
        assertTrue(AppSettings.defaultForMigration(existingInstall = true, legacy = true, current = false))
        assertFalse(AppSettings.defaultForMigration(existingInstall = false, legacy = true, current = false))
        assertFalse(AppSettings.isExistingInstall(emptyList(), setOf(ongoing)))
        assertFalse(AppSettings.isExistingInstall(listOf(ongoing), setOf(ongoing)))
        assertTrue(AppSettings.isExistingInstall(listOf(ongoing, "dark_mode"), setOf(ongoing)))
    }

    /** Neue Installation: neue Standards, und «Wie aktuell?» bleibt beim Standard (nicht Sparsam). */
    @Test
    fun newInstallGetsNewDefaults() {
        val r = migrate(emptyMap())
        assertFalse(r.installDefaultsExisting)
        assertFalse(r.updateModeExisting)
        assertEquals(UpdateMode.DEFAULT.name, r.prefs[updateMode])
        assertEquals(false, r.prefs[ongoing])
        assertEquals(true, r.prefs[names])
        assertEquals(true, r.prefs[converted])
        assertEquals(false, r.prefs[alpha])
        assertEquals(false, r.prefs[portfolio])
    }

    /** Update von Build 43 (Migration schon gelaufen, nie umgeschaltet): Portfolio-Tab bleibt sichtbar. */
    @Test
    fun build43InstallKeepsPortfolioTab() {
        val r = migrate(mapOf(ongoing to false, names to true, converted to true, alpha to false, updateMode to "DEFAULT"))
        assertTrue(r.installDefaultsExisting)
        assertEquals(true, r.prefs[portfolio])
        assertEquals(true, r.prefs[names])
        assertEquals("DEFAULT", r.prefs[updateMode])
        // Selbst ausgeschaltet: bleibt aus
        val off = migrate(mapOf(ongoing to false, names to true, converted to true, alpha to false, updateMode to "DEFAULT", portfolio to false))
        assertEquals(false, off.prefs[portfolio])
    }

    /** Neue Installation, die nur den Portfolio-Schalter schreibt, gilt für «Wie aktuell?» weiter als neu. */
    @Test
    fun portfolioKeyIgnoredByUpdateMode() {
        val r = migrate(emptyMap())
        assertFalse(r.updateModeExisting)
        assertTrue(portfolio in r.prefs)
    }

    /** Update von Build 42: «ongoing_notifications» und «update_mode» stehen schon da → bestehend. */
    @Test
    fun build42InstallKeepsOldDefaults() {
        val r = migrate(mapOf(ongoing to false, updateMode to "LIVE_OFFEN"))
        assertTrue(r.installDefaultsExisting)
        assertEquals(false, r.prefs[names])
        assertEquals(false, r.prefs[converted])
        assertEquals(true, r.prefs[alpha])
        assertEquals("LIVE_OFFEN", r.prefs[updateMode])
        assertEquals(false, r.prefs[ongoing])
        assertEquals(true, r.prefs[portfolio])
    }

    /** Update von älteren Builds (ohne «ongoing»): alle bisherigen Standards. */
    @Test
    fun olderInstallKeepsOldDefaults() {
        val r = migrate(mapOf("dark_mode" to true))
        assertEquals(true, r.prefs[ongoing])
        assertEquals(false, r.prefs[names])
        assertEquals(false, r.prefs[converted])
        assertEquals(true, r.prefs[alpha])
        assertEquals(true, r.prefs[portfolio])
        assertEquals("SPARSAM", r.prefs[updateMode])
    }

    /** Gespeicherte Werte bleiben; ein selbst gesetzter Schalter macht die Installation zur bestehenden. */
    @Test
    fun storedValuesKept() {
        val r = migrate(mapOf(ongoing to true, updateMode to "SPARSAM", names to true, alpha to false))
        assertEquals(true, r.prefs[names])
        assertEquals(false, r.prefs[alpha])
        assertEquals(false, r.prefs[converted])
        val only = migrate(mapOf(converted to true))
        assertTrue(only.installDefaultsExisting)
        assertEquals(true, only.prefs[converted])
        assertEquals(false, only.prefs[names])
        assertEquals(true, only.prefs[alpha])
    }

    /** Zweiter Lauf ändert nichts (Schlüssel stehen alle da). */
    @Test
    fun idempotent() {
        val first = migrate(emptyMap()).prefs
        assertEquals(first, migrate(first).prefs)
    }
}
