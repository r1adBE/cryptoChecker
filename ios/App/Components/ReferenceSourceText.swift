import SwiftUI

/// Quellzeile und Takt eines Devisen-Referenzkurses (EZB, NBU, NBP): in «Paar hinzufügen» und auf der Paar-Seite gleich.
/// Wie Android `ReferenceSourceText.kt`.
enum ReferenceSourceText {
    /// «Quelle: EZB» / «Quelle: Nationalbank der Ukraine» / «Quelle: Polnische Nationalbank».
    static func name(_ source: ReferenceSource) -> String {
        switch source {
        case .ecb: return L("explorer_source_ecb")
        case .nbu: return L("explorer_source_nbu")
        case .nbp: return L("explorer_source_nbp")
        }
    }

    /// `name` mit «· umgerechnet», wenn der Wert von `base` ein Kreuzkurs oder eine berechnete Veränderung ist.
    static func line(_ source: ReferenceSource, base: String, hasChange: Bool) -> String {
        source.converted(base: base, hasChange: hasChange)
            ? name(source) + " · " + L("explorer_source_converted") : name(source)
    }

    /// «Referenzkurs, täglich» bzw. «Referenzkurs, wöchentlich».
    static func period(_ source: ReferenceSource) -> String {
        L(source.weekly ? "watch_price_reference_weekly" : "watch_price_reference")
    }

    /// Kurz «täglich» bzw. «wöchentlich» (Zeile der Merkliste).
    static func periodShort(_ source: ReferenceSource) -> String {
        L(source.weekly ? "watch_price_weekly" : "watch_price_daily")
    }
}

/// Quellzeile klein unter einem Referenzkurs (`ReferenceSourceText.line`). Wie Android `ReferenceSourceLine`.
struct ReferenceSourceLine: View {
    let source: ReferenceSource
    let base: String
    let hasChange: Bool

    var body: some View {
        Text(ReferenceSourceText.line(source, base: base, hasChange: hasChange))
            .font(.caption)
            .foregroundStyle(AppColors.onSurfaceVariant)
            .fixedSize(horizontal: false, vertical: true)
    }
}
