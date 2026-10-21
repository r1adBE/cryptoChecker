import SwiftUI

// MARK: Kursblock

/// Letzter Kurs gross, darunter Hoch/Tief, Bid/Ask und Volumen — wie `Ticker.kt`.
struct TickerView: View {
    let ticker: Ticker
    let base: String
    let quote: String

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Gegenwährung klein und grau wie der Börsenname der Merkliste
            PriceWithQuote(price: PriceFormat.formatDouble(ticker.last), quote: quote, display: .compact)
            // Zeit laut Börse (z. B. letzter Handel), sonst Abruf — `timestamp` ist immer der Abruf (`Market.readTicker`)
            Text(L("ticker_timestamp") + " " + Self.sameDayTimeOrDate(ticker.exchangeTime ?? ticker.timestamp))
                .font(.footnote)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .padding(.bottom, 12)
            if ticker.high > Ticker.missing {
                statRow("ticker_high", amount(ticker.high), quote, "ticker_low", amount(ticker.low), quote)
            }
            if ticker.ask > Ticker.missing {
                statRow("ticker_bid", amount(ticker.bid), quote, "ticker_ask", amount(ticker.ask), quote)
                // Ein Satz, was Geld und Brief heissen (wie Android)
                Text(L("explain_bid_ask"))
                    .font(.footnote)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.bottom, Spacing.sm)
                // Grosser Abstand Kauf/Verkauf (über 2 % der Mitte): dünner Markt, Hinweis nur hier im Blatt
                if let percent = BidAskSpread.widePercent(bid: ticker.bid, ask: ticker.ask) {
                    Text(L("ticker_wide_spread", PercentText.unsigned(percent, decimals: 1)))
                        .font(.footnote)
                        .foregroundStyle(AppColors.error)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.bottom, Spacing.sm)
                }
            }
            if ticker.vol > Ticker.missing || ticker.volQuote > Ticker.missing {
                let hasVol = ticker.vol > Ticker.missing
                let hasVolQuote = ticker.volQuote > Ticker.missing
                statRow("ticker_vol_base", hasVol ? amount(ticker.vol) : "—", hasVol ? base : nil,
                        "ticker_vol_quote", hasVolQuote ? amount(ticker.volQuote) : "—", hasVolQuote ? quote : nil)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func amount(_ value: Double) -> String { PriceFormat.formatDouble(value) }

    private func statRow(_ lt: String, _ lv: String, _ lq: String?,
                         _ rt: String, _ rv: String, _ rq: String?) -> some View {
        HStack(alignment: .top, spacing: 12) {
            stat(lt, lv, lq).frame(maxWidth: .infinity, alignment: .leading)
            stat(rt, rv, rq).frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.bottom, Spacing.sm)
    }

    /// Kennzahl: Gegenwährung klein und grau wie der Börsenname der Merkliste.
    private func stat(_ title: String, _ value: String, _ currency: String?) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(L(title)).font(.caption).foregroundStyle(AppColors.onSurfaceVariant)
            PriceWithQuote(price: value, quote: currency, font: .subheadline.weight(.medium).monospacedDigit())
                .minimumScaleFactor(0.7)
        }
    }

    static func sameDayTimeOrDate(_ millis: Int64) -> String {
        let d = Date(millis: millis)
        let f = DateFormatter()
        if Calendar.current.isDateInToday(d) { f.timeStyle = .short; f.dateStyle = .none } else { f.dateStyle = .short; f.timeStyle = .none }
        return f.string(from: d)
    }
}
