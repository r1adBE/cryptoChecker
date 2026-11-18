import SwiftUI

/// «+» in der Kopfzeile (Portfolio, Seite eines Coins): gefüllter Kreis in der Themenfarbe wie «+» der Merkliste,
/// 34 pt sichtbar, Tippfläche mindestens 44 pt. Steht oben statt schwebend unten, damit er nie Beträge verdeckt.
/// Wie Android `HeaderAddButton`.
struct HeaderAddButton: View {
    let label: String
    let action: () -> Void
    @Environment(\.appAccent) private var accent
    @Environment(\.onAccent) private var onAccent

    var body: some View {
        Button(action: action) {
            Image(systemName: "plus")
                .scaledFont(size: 16, weight: .bold, relativeTo: .body)
                .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                .foregroundStyle(onAccent)
                .frame(width: 34, height: 34)
                .background(accent.primary, in: Circle())
                .contentShape(Circle().inset(by: -5))
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(label)
        // Bei `.xxxLarge` gedeckelt: darüber Symbol und Name gross beim langen Drücken
        .largeContentLabel(label, systemImage: "plus")
    }
}
