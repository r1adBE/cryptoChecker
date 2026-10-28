import Foundation

/// Abschnitt einer Börse in der Börsenauswahl: Spot, Futures, DEX-Pools, Forex (Referenzkurs). Reihenfolge der
/// Fälle = Reihenfolge der Abschnitte (`ExchangeOrder.sections`). `rawValue` steht in den Paritätsfällen
/// (`testdata/parity/picker_order.json`). Android: `ExchangeCategory.kt`.
enum ExchangeCategory: String, CaseIterable, Sendable {
    case spot = "SPOT"
    case futures = "FUTURES"
    case dex = "DEX"
    case forex = "FOREX"
}
