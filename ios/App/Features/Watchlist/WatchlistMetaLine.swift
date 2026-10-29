import SwiftUI

/// Zeile «Börse ◇ 🔔 🔔 1» der Merkliste: Passen Börsentext (erstes Kind, samt ⚠) und Symbole (zweites Kind)
/// nicht nebeneinander, stehen die Symbole in einer eigenen Zeile unter der Börse
/// (`DenseRowText.metaWraps`) — statt die Börse zu kürzen («Bi…») oder das Mini-Chart auszublenden.
/// Der Börsentext bekommt dann die ganze Breite; gekürzt wird er nur noch als letzter Ausweg (eigene
/// Zeilengrenze). Gleiche Regel wie Android `RowMetaLine`.
struct WatchlistMetaLine: Layout {
    /// Abstand zwischen Text und Symbolen in einer Zeile (wie bisher `Spacing.xs`).
    var gap: CGFloat
    /// Abstand zwischen Börsenzeile und umgebrochenen Symbolen.
    var lineGap: CGFloat = 2

    private func iconsSize(_ subviews: Subviews) -> CGSize {
        subviews.count >= 2 ? subviews[1].sizeThatFits(.unspecified) : .zero
    }

    private func wraps(_ width: CGFloat?, _ subviews: Subviews) -> Bool {
        guard subviews.count >= 2, let lead = subviews.first else { return false }
        return DenseRowText.metaWraps(available: width.map { Double($0) },
                                      lead: Double(lead.sizeThatFits(.unspecified).width),
                                      icons: Double(iconsSize(subviews).width), gap: Double(gap))
    }

    /// Breite des Börsentexts: ganze Breite (umgebrochen) bzw. Breite ohne Symbole (eine Zeile).
    private func leadWidth(_ width: CGFloat?, wrapped: Bool, _ subviews: Subviews) -> CGFloat? {
        guard !wrapped, subviews.count >= 2 else { return width }
        return width.map { max($0 - iconsSize(subviews).width - gap, 0) }
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard let lead = subviews.first else { return .zero }
        let wrapped = wraps(proposal.width, subviews)
        let icons = iconsSize(subviews)
        let leadSize = lead.sizeThatFits(ProposedViewSize(width: leadWidth(proposal.width, wrapped: wrapped, subviews),
                                                          height: nil))
        if wrapped {
            return CGSize(width: max(leadSize.width, icons.width), height: leadSize.height + lineGap + icons.height)
        }
        let iconsRoom = subviews.count >= 2 ? icons.width + gap : 0
        return CGSize(width: leadSize.width + iconsRoom, height: max(leadSize.height, icons.height))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard let lead = subviews.first else { return }
        let wrapped = wraps(bounds.width, subviews)
        let width = leadWidth(bounds.width, wrapped: wrapped, subviews)
        let leadSize = lead.sizeThatFits(ProposedViewSize(width: width, height: nil))
        if wrapped {
            lead.place(at: CGPoint(x: bounds.minX, y: bounds.minY), anchor: .topLeading,
                       proposal: ProposedViewSize(width: width, height: leadSize.height))
            subviews[1].place(at: CGPoint(x: bounds.minX, y: bounds.minY + leadSize.height + lineGap),
                              anchor: .topLeading, proposal: .unspecified)
            return
        }
        // Eine Zeile: Mitte an Mitte
        lead.place(at: CGPoint(x: bounds.minX, y: bounds.midY), anchor: .leading,
                   proposal: ProposedViewSize(width: width, height: leadSize.height))
        if subviews.count >= 2 {
            subviews[1].place(at: CGPoint(x: bounds.minX + leadSize.width + gap, y: bounds.midY), anchor: .leading,
                              proposal: .unspecified)
        }
    }
}
