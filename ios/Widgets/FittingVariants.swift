import SwiftUI

/// `ViewThatFits` über eine Liste von Fassungen (lang → kurz): nimmt die erste, die in `axes` ganz passt, sonst die
/// letzte. Abgesichert gegen die Fälle, in denen SwiftUI beim Messen abbricht (Absturz der Widget-Erweiterung in
/// `SizeFittingState.applyChildren`): genau eine flache Liste ohne verschachteltes `ForEach`, Kennungen nach Position
/// (gleiche Texte sind keine doppelten Kennungen) und nie ohne Kind — eine leere Liste zeigt nichts.
struct FittingVariants<Item, Content: View>: View {
    let axes: Axis.Set
    let items: [Item]
    let content: (Item) -> Content

    init(in axes: Axis.Set, _ items: [Item], @ViewBuilder content: @escaping (Item) -> Content) {
        self.axes = axes
        self.items = items
        self.content = content
    }

    var body: some View {
        if items.isEmpty {
            Color.clear.frame(width: 0, height: 0)
        } else {
            ViewThatFits(in: axes) {
                ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                    content(item)
                }
            }
        }
    }
}
