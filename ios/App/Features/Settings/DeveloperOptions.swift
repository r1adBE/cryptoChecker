import SwiftUI

/// Entwickleroptionen (HTTP-Protokoll) gibt es nur im Debug-Build, wie auf Android (BuildConfig.DEBUG):
/// im Release bleiben Schalter, Suchtreffer und Protokollfeld verborgen.
enum DeveloperOptions {
    #if DEBUG
    static let available = true
    #else
    static let available = false
    #endif
}
