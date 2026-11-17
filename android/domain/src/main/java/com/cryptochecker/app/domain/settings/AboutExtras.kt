package com.cryptochecker.app.domain.settings

/**
 * Was die Seite «Über die App» nach 7 Tipps auf die Version zusätzlich zeigt: die Diagnose in jedem Build, «Entwickler»
 * (HTTP-Mitschnitt) nur im Debug-Build — der Store-Build hat keine Entwickleroptionen. Wie iOS `AboutExtras`.
 */
data class AboutExtras(val diagnostics: Boolean, val developer: Boolean) {
    companion object {
        fun of(unlocked: Boolean, debugBuild: Boolean) = AboutExtras(diagnostics = unlocked, developer = unlocked && debugBuild)
    }
}
