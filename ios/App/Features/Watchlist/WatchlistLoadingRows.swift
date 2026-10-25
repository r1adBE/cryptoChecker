import SwiftUI

/// Merkliste beim Start, solange der gespeicherte Stand noch gelesen wird (`AppData.snapshotLoaded`, Schlüsselbund,
/// Entschlüsseln, Dekodieren abseits des Haupt-Threads): Platzhalter-Zeilen in der Form der Zeilen (Logo, Paar und
/// Börse, Kurs und Änderung), pulsierend wie die übrigen Platzhalter (`SkeletonPulse`). VoiceOver: eine Zeile
/// «Wird geladen …», einmal angesagt.
struct WatchlistLoadingRows: View {
    /// So viele Zeilen füllen einen Bildschirm; die echte Liste ersetzt sie meist nach Sekundenbruchteilen.
    static let rowCount = 8

    var body: some View {
        SkeletonPulse(label: L("loading_hint")) {
            VStack(spacing: Spacing.sm) {
                ForEach(0..<Self.rowCount, id: \.self) { _ in row }
            }
            .padding(.horizontal, Spacing.lg)
            .padding(.top, Spacing.lg)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(AppColors.background.ignoresSafeArea())
        .onAppear { AccessibilityNotification.Announcement(L("loading_hint")).post() }
    }

    private var row: some View {
        HStack(spacing: Spacing.md) {
            Circle()
                .fill(AppColors.containerHighest)
                .frame(width: ListSegment.logo, height: ListSegment.logo)
            VStack(alignment: .leading, spacing: Spacing.xs) {
                SkeletonBlock(width: 96, height: 14)
                SkeletonBlock(width: 64, height: 12)
            }
            Spacer(minLength: Spacing.sm)
            VStack(alignment: .trailing, spacing: Spacing.xs) {
                SkeletonBlock(width: 80, height: 14)
                SkeletonBlock(width: 48, height: 12)
            }
        }
        .padding(.vertical, Spacing.md)
    }
}
