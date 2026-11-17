import Foundation

/// Was die Seite «Über die App» nach 7 Tipps auf die Version zusätzlich zeigt: die Diagnose in jedem Build, «Entwickler»
/// (HTTP-Mitschnitt) nur im Debug-Build — der Store-Build hat keine Entwickleroptionen. Wie Android `AboutExtras`.
struct AboutExtras: Equatable {
    let diagnostics: Bool
    let developer: Bool

    static func of(unlocked: Bool, debugBuild: Bool) -> AboutExtras {
        AboutExtras(diagnostics: unlocked, developer: unlocked && debugBuild)
    }
}
