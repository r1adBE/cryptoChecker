import XCTest
import SwiftUI
@testable import CryptoChecker

/// Screenreader-Sätze ohne Katalog — wie `A11ySpeechTest.kt`.
final class A11ySpeechTests: XCTestCase {

    private let minute: Int64 = 60_000
    private var hour: Int64 { 60 * minute }
    private var day: Int64 { 24 * hour }
    private let now: Int64 = 1_700_000_000_000

    /// Deutsche Satzteile wie in values-de (Pluralform von Hand).
    private let german = A11ySpeech.Texts(
        disabled: "Deaktiviert",
        notTraded: "Wird an der Börse nicht mehr gehandelt",
        outdated: "Kurs veraltet",
        updated: { age in
            switch age {
            case .justNow: return "zuletzt aktualisiert gerade eben"
            case .minutes(let n): return "zuletzt aktualisiert vor \(n) " + (n == 1 ? "Minute" : "Minuten")
            case .hours(let n): return "zuletzt aktualisiert vor \(n) " + (n == 1 ? "Stunde" : "Stunden")
            case .days(let n): return "zuletzt aktualisiert vor \(n) " + (n == 1 ? "Tag" : "Tagen")
            }
        },
        unreachable: { "\($0) nicht erreichbar" })

    func testAgeInWholeUnits() {
        XCTAssertEqual(A11ySpeech.age(now - 59_999, now: now), .justNow)
        XCTAssertEqual(A11ySpeech.age(now + 5 * minute, now: now), .justNow)
        XCTAssertEqual(A11ySpeech.age(now - minute, now: now), .minutes(1))
        XCTAssertEqual(A11ySpeech.age(now - 45 * minute - 59_000, now: now), .minutes(45))
        XCTAssertEqual(A11ySpeech.age(now - hour, now: now), .hours(1))
        XCTAssertEqual(A11ySpeech.age(now - day + 1, now: now), .hours(23))
        XCTAssertEqual(A11ySpeech.age(now - 2 * day - hour, now: now), .days(2))
    }

    func testFreshnessFollowsOutdatedRule() {
        let after = 2 * minute
        func f(disabled: Bool = false, notTraded: Bool = false, failed: Bool = false, age: Int64) -> A11ySpeech.Freshness {
            A11ySpeech.freshness(disabled: disabled, notTraded: notTraded, failed: failed,
                                 lastUpdate: now - age, now: now, outdatedAfter: after)
        }
        XCTAssertEqual(f(age: after), .fresh)
        XCTAssertEqual(f(age: after + 1), .outdated)
        XCTAssertEqual(f(failed: true, age: minute), .unreachable)
        XCTAssertEqual(f(failed: true, age: 45 * minute), .unreachableOutdated)
        // Deaktiviert und «nicht mehr gehandelt» sind Zustände, nie «veraltet»
        XCTAssertEqual(f(disabled: true, age: day), .disabled)
        XCTAssertEqual(f(notTraded: true, age: day), .notTraded)
        // Noch nie aktualisiert bzw. Grenze 0: nichts sagen
        XCTAssertEqual(A11ySpeech.freshness(disabled: false, notTraded: false, failed: false,
                                            lastUpdate: 0, now: now, outdatedAfter: after), .fresh)
        XCTAssertEqual(A11ySpeech.freshness(disabled: false, notTraded: false, failed: false,
                                            lastUpdate: now - day, now: now, outdatedAfter: 0), .fresh)
    }

    func testFreshnessText() {
        XCTAssertEqual(A11ySpeech.freshnessText(.outdated, lastUpdate: now - 45 * minute, now: now,
                                                market: "Binance", texts: german),
                       "Kurs veraltet, zuletzt aktualisiert vor 45 Minuten")
        XCTAssertEqual(A11ySpeech.freshnessText(.unreachable, lastUpdate: now - minute, now: now,
                                                market: "Binance", texts: german),
                       "zuletzt aktualisiert vor 1 Minute, Binance nicht erreichbar")
        XCTAssertEqual(A11ySpeech.freshnessText(.unreachableOutdated, lastUpdate: now - 3 * hour, now: now,
                                                market: "Kraken", texts: german),
                       "Kurs veraltet, zuletzt aktualisiert vor 3 Stunden, Kraken nicht erreichbar")
        XCTAssertEqual(A11ySpeech.freshnessText(.disabled, lastUpdate: now, now: now, market: "Binance", texts: german),
                       "Deaktiviert")
        XCTAssertEqual(A11ySpeech.freshnessText(.notTraded, lastUpdate: now, now: now, market: "Binance", texts: german),
                       german.notTraded)
        XCTAssertNil(A11ySpeech.freshnessText(.fresh, lastUpdate: now, now: now, market: "Binance", texts: german))
    }

    func testAlarmFiredRecently() {
        XCTAssertTrue(A11ySpeech.firedRecently(now - minute, now: now))
        XCTAssertTrue(A11ySpeech.firedRecently(now - A11ySpeech.firedMillis, now: now))
        XCTAssertFalse(A11ySpeech.firedRecently(now - A11ySpeech.firedMillis - 1, now: now))
        XCTAssertFalse(A11ySpeech.firedRecently(0, now: now))
        XCTAssertFalse(A11ySpeech.firedRecently(now + minute, now: now))
    }

    func testStatusAnnouncedOnlyOnChange() {
        XCTAssertEqual(A11ySpeech.statusKind(none: false, offline: false, warn: false, newest: now), .fresh)
        XCTAssertEqual(A11ySpeech.statusKind(none: false, offline: false, warn: true, newest: now), .stale)
        XCTAssertEqual(A11ySpeech.statusKind(none: false, offline: true, warn: true, newest: now), .offline)
        XCTAssertNil(A11ySpeech.statusKind(none: true, offline: true, warn: true, newest: now))
        XCTAssertNil(A11ySpeech.statusKind(none: false, offline: false, warn: false, newest: 0))

        XCTAssertFalse(A11ySpeech.shouldAnnounce(previous: nil, current: .stale))
        XCTAssertFalse(A11ySpeech.shouldAnnounce(previous: .fresh, current: .fresh))
        XCTAssertFalse(A11ySpeech.shouldAnnounce(previous: .fresh, current: nil))
        XCTAssertTrue(A11ySpeech.shouldAnnounce(previous: .fresh, current: .stale))
        XCTAssertTrue(A11ySpeech.shouldAnnounce(previous: .stale, current: .offline))
        XCTAssertTrue(A11ySpeech.shouldAnnounce(previous: .offline, current: .fresh))
    }

    func testDotsBecomePauses() {
        XCTAssertEqual(A11ySpeech.spokenDots("Alles aktuell · gerade eben"), "Alles aktuell, gerade eben")
        XCTAssertEqual(A11ySpeech.spokenDots("1 von 3 veraltet"), "1 von 3 veraltet")
    }

    func testFiatSpokenAsWordCryptoUnchanged() {
        let en = Locale(identifier: "en")
        XCTAssertEqual(A11ySpeech.currencyName("USD", locale: en), "US Dollar")
        XCTAssertEqual(A11ySpeech.currencyName("EUR", locale: Locale(identifier: "de")), "Euro")
        XCTAssertNil(A11ySpeech.currencyName("USDT", locale: en))
        XCTAssertNil(A11ySpeech.currencyName("BTC", locale: en))
        XCTAssertNil(A11ySpeech.currencyName("XAU", locale: en))
        XCTAssertNil(A11ySpeech.currencyName("usd", locale: en))
        XCTAssertEqual(A11ySpeech.spokenPrice("61,234.00 USD", quote: "USD", locale: en), "61,234.00 US Dollar")
        XCTAssertEqual(A11ySpeech.spokenPrice("61,234.00 USDT", quote: "USDT", locale: en), "61,234.00 USDT")
        XCTAssertEqual(A11ySpeech.spokenPrice("—", quote: "USD", locale: en), "—")
    }

    func testRowOrder() {
        let sentence = A11ySpeech.row(
            pair: "BTC/USDT an Binance",
            price: "Kurs 61’234.00 USDT",
            change: "gestiegen um 2.14% in 24 Stunden",
            state: ["Kurs veraltet, zuletzt aktualisiert vor 45 Minuten"],
            details: [nil, "Notiz: Langfristig", " "],
            alarms: ["Alarm ausgelöst", "Alarme: 2"],
            rest: ["Mitteilung"])
        XCTAssertEqual(sentence,
                       "BTC/USDT an Binance, Kurs 61’234.00 USDT, gestiegen um 2.14% in 24 Stunden, "
                       + "Kurs veraltet, zuletzt aktualisiert vor 45 Minuten, Notiz: Langfristig, "
                       + "Alarm ausgelöst, Alarme: 2, Mitteilung")
    }

    /// Kursmitteilung wie die Glocke, vor den Alarmen — wie Android.
    func testPriceNotificationLikeTheBellBeforeAlarms() {
        XCTAssertTrue(A11ySpeech.priceNotificationOn(pairOn: true, masterOn: true))
        // Paar aus oder Hauptschalter aus: durchgestrichene Glocke → «aus»
        XCTAssertFalse(A11ySpeech.priceNotificationOn(pairOn: false, masterOn: true))
        XCTAssertFalse(A11ySpeech.priceNotificationOn(pairOn: true, masterOn: false))
        let sentence = A11ySpeech.row(
            pair: "BTC/USDT an Binance",
            price: "Kurs 61’234.00 USDT",
            change: "gestiegen um 2.14% in 24 Stunden",
            alarms: ["Kursmitteilung an", nil, "Alarme: 2"])
        XCTAssertEqual(sentence,
                       "BTC/USDT an Binance, Kurs 61’234.00 USDT, gestiegen um 2.14% in 24 Stunden, "
                       + "Kursmitteilung an, Alarme: 2")
    }

    func testWatchRowSaysPriceNotificationOnlyWhenOff() {
        let watch = Watch(id: 1, marketKey: "Binance", marketName: "Binance", baseAsset: "BTC", quoteAsset: "USDT")
        let on = A11y.watchRow(watch, priceNotificationOn: true)
        let off = A11y.watchRow(watch, priceNotificationOn: false)
        // «An» ist der Normalfall: nicht in jeder Zeile vorlesen
        XCTAssertFalse(on.contains(L("a11y_price_notification_on")))
        XCTAssertFalse(on.contains(L("a11y_price_notification_off")))
        XCTAssertTrue(off.contains(L("a11y_price_notification_off")))
        // Widgets ohne Glocke: kein Satzteil
        let widget = A11y.watchRow(watch)
        XCTAssertFalse(widget.contains(L("a11y_price_notification_on")))
        XCTAssertFalse(widget.contains(L("a11y_price_notification_off")))
    }

    /// Tableiste: bei Standardschrift 64 pt, mit grosser Schrift höher (Beschriftung nicht abgeschnitten).
    func testTabBarGrowsWithDynamicType() {
        XCTAssertEqual(AppTabBarMetrics.scaledHeight(labelLine: AppTabBarMetrics.labelLineHeight), AppTabBarMetrics.height)
        XCTAssertGreaterThan(AppTabBarMetrics.scaledHeight(labelLine: 48), AppTabBarMetrics.height)
        XCTAssertGreaterThanOrEqual(AppTabBarMetrics.scaledHeight(labelLine: 48),
                                    AppTabBarMetrics.indicatorHeight + 48)
    }

    /// Tableiste wächst nur bis `.accessibility1` (≈ 1,5 ×, wie Android `TAB_LABEL_MAX_SCALE`); darüber Large
    /// Content Viewer.
    func testTabBarGrowthIsCappedAtAccessibility1() {
        XCTAssertEqual(AppTabBarMetrics.maxTypeSize, .accessibility1)
        XCTAssertTrue(AppTabBarMetrics.maxTypeSize < .accessibility2)
    }
}
