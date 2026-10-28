import XCTest
@testable import CryptoChecker

/// Wie `SettingsSearchTest.kt`.
final class SettingsSearchTests: XCTestCase {

    private let de = Locale(identifier: "de")
    private let entries: [SettingsSearchEntry] = [
        SettingsSearchEntry(id: "updates", title: "Aktualisierung", path: "Allgemein"),
        SettingsSearchEntry(id: "updates.background", title: "Im Hintergrund aktualisieren", path: "Aktualisierung",
                            synonyms: ["Prüft Kurse und Alarme regelmässig, auch wenn die App zu ist."]),
        SettingsSearchEntry(id: "alarms", title: "Alarme", path: "Alarme & Mitteilungen"),
        SettingsSearchEntry(id: "alarms.quiet", title: "Ruhezeit", path: "Alarme",
                            synonyms: ["In dieser Zeit klingeln Alarme nicht."]),
        SettingsSearchEntry(id: "display.contrast", title: "Hoher Kontrast", path: "Modus",
                            synonyms: ["Kräftigere Farben und Linien."]),
        SettingsSearchEntry(id: "about.privacy", title: "Datenschutzerklärung", path: "Über"),
    ]

    private func ids(_ query: String) -> [String] {
        SettingsSearch.search(entries, query: query, locale: de).map(\.id)
    }

    func testEmptyOrBlankQueryFindsNothing() {
        XCTAssertEqual(ids(""), [])
        XCTAssertEqual(ids("   "), [])
    }

    func testCaseAndDiacriticsAreIgnored() {
        XCTAssertEqual(ids("KONTRAST").first, "display.contrast")
        XCTAssertEqual(ids("datenschutzerklarung").first, "about.privacy")
        XCTAssertEqual(ids("DATENSCHUTZERKLÄRUNG").first, "about.privacy")
        // Akzent in der Suche, keiner im Titel
        XCTAssertEqual(ids("Rühezeit").first, "alarms.quiet")
    }

    func testTitleStartBeatsWordStartBeatsPartBeatsPathAndSynonym() {
        // «Alarme» (Titel beginnt) vor «Ruhezeit» (Seite Alarme) vor «Im Hintergrund …» (Hinweis)
        XCTAssertEqual(ids("alarm"), ["alarms", "alarms.quiet", "updates.background"])
        // Titel beginnt vor Wortanfang im Titel
        XCTAssertEqual(ids("aktualis"), ["updates", "updates.background"])
        XCTAssertEqual(ids("hintergr"), ["updates.background"])
    }

    func testEveryTokenMustMatch() {
        XCTAssertEqual(ids("hintergrund kurse"), ["updates.background"])
        XCTAssertEqual(ids("hintergrund kontrast"), [])
    }

    func testSynonymsNeedTwoCharacters() {
        // Ein Zeichen: nur Titel und Seite zählen, nicht die Hinweise
        XCTAssertEqual(ids("z"), ["alarms.quiet", "about.privacy"])
        XCTAssertTrue(ids("kl").contains("alarms.quiet")) // «klingeln» im Hinweis
    }

    func testDuplicateIdsCountOnce() {
        let twice = entries + [SettingsSearchEntry(id: "alarms", title: "Alarme (doppelt)")]
        XCTAssertEqual(SettingsSearch.index(twice, locale: de).count, entries.count)
    }

    func testNormalizeSpecialLetters() {
        XCTAssertEqual(SettingsSearch.normalize("Straße", locale: de), "strasse")
        XCTAssertEqual(SettingsSearch.normalize("Øresund", locale: Locale(identifier: "en_US_POSIX")), "oresund")
        XCTAssertEqual(SettingsSearch.normalize("  A \n\t B  ", locale: Locale(identifier: "en_US_POSIX")), "a b")
        // Türkisch: «I» → «ı» → «i», «İ» → «i»
        let tr = Locale(identifier: "tr")
        XCTAssertEqual(SettingsSearch.normalize("İLETİŞİM", locale: tr), "iletisim")
        XCTAssertEqual(SettingsSearch.normalize("IŞIK", locale: tr), "isik")
        // Englisch: «İ» verliert nur den Punkt
        XCTAssertEqual(SettingsSearch.normalize("İ", locale: Locale(identifier: "en")), "i")
    }

    func testNormalizeKeepsOtherScripts() {
        // Dakuten bleiben (が ≠ か), Hangul und Han unverändert
        XCTAssertEqual(SettingsSearch.normalize("が", locale: Locale(identifier: "ja")), "が")
        XCTAssertEqual(SettingsSearch.normalize("알림", locale: Locale(identifier: "ko")), "알림")
        // Arabisch: Vokalzeichen und Tatweel weg
        XCTAssertEqual(SettingsSearch.normalize("تَنْبِيـه", locale: Locale(identifier: "ar")), "تنبيه")
        // Kyrillisch und Griechisch: Kleinbuchstaben, Akzente weg
        XCTAssertEqual(SettingsSearch.normalize("Уведомления", locale: Locale(identifier: "ru")), "уведомления")
        XCTAssertEqual(SettingsSearch.normalize("Ειδοποίηση", locale: Locale(identifier: "el")), "ειδοποιηση")
    }

    func testScriptsWithoutSpacesMatchInsideTitle() {
        let zh = [
            SettingsSearchEntry(id: "a", title: "价格提醒", path: "提醒"),
            SettingsSearchEntry(id: "b", title: "语音播报", path: "通知", synonyms: ["朗读价格变化"]),
        ]
        XCTAssertEqual(SettingsSearch.search(zh, query: "价格", locale: Locale(identifier: "zh")).map(\.id), ["a", "b"])
    }

    /// Kurs-Aktualisierung unter «Allgemein», Widgets und Coin-Logos unter «Darstellung»; kein «Erweitert» mehr
    ///: %-Änderung und Token & TradFi auf «Merkliste», Markt-Meldungen und Sprachausgabe auf «Alarme»,
    /// Lizenzen und Entwickler auf «Über die App». Portfolio nur unter «Allgemein» — wie `SettingsPlacementTest`.
    func testPagesSitWhereTheMainPageShowsThem() {
        XCTAssertEqual(SettingsSearchPage.updates.home, .general)
        XCTAssertEqual(SettingsSearchPage.alarms.home, .general)
        XCTAssertEqual(SettingsSearchPage.widgets.home, .appearance)
        XCTAssertEqual(SettingsSearchPage.priceColors.home, .appearance)
        XCTAssertEqual(SettingsSearchPage.coinLogos.home, .appearance)
        XCTAssertEqual(SettingsSearchPage.portfolio.home, .general)
        let nested: [SettingsSearchPage] = [.exchanges, .marketAlerts, .speech, .developer, .licenses, .changeBasis]
        for page in nested {
            XCTAssertNil(page.home, "\(page)")
        }
    }

    /// Der Pfad eines Treffers folgt dem Ort der Seite.
    @MainActor
    func testSearchPathFollowsPlacement() {
        let items = SettingsSearchCatalog.items(settings: AppSettings())
        func path(_ id: String) -> String? { items.first { $0.entry.id == id }?.entry.path }
        XCTAssertEqual(path("page.updates"), L("settings_group_general"))
        XCTAssertEqual(path("page.widgets"), L("settings_section_appearance"))
        XCTAssertEqual(path("page.coin_logos"), L("settings_section_appearance"))
        XCTAssertEqual(path("page.exchanges"), L("settings_row_watchlist"))
        XCTAssertEqual(path("page.market_alerts"), L("settings_row_alarms"))
        XCTAssertEqual(path("page.speech"), L("settings_row_alarms"))
        XCTAssertNil(path("page.advanced"))
        XCTAssertEqual(path("page.portfolio"), L("settings_group_general"))
        XCTAssertNil(path("main.portfolio"))
    }

    /// Portfolio an einem Ort — die Suche nach «Portfolio» zeigt zuerst die Seite (Zeile unter
    /// «Allgemein»), den alten Schalter der Gruppe «Mehr» gibt es nicht mehr — wie `portfolio_pageComesFirst` (Android).
    @MainActor
    func testPortfolioSearchFindsPageFirst() {
        let items = SettingsSearchCatalog.items(settings: AppSettings())
        let hits = SettingsSearch.search(items.map(\.entry), query: L("portfolio_title"), locale: Locale.current).map(\.id)
        XCTAssertEqual(hits.first, "page.portfolio")
        XCTAssertEqual(items.first { $0.entry.id == "page.portfolio" }?.target, .page(.portfolio, anchor: nil))
        XCTAssertFalse(hits.contains("main.portfolio"))
    }

    /// «Damit Alarme ankommen», «Diagnose teilen» und «Alarm-Protokoll» sind auffindbar und führen zu ihrer Zeile —
    /// wie `SettingsSearchAnchorsTest` (Android).
    @MainActor
    func testDeliveryDiagnosticsAndAlarmLogAreSearchable() {
        let items = SettingsSearchCatalog.items(settings: AppSettings())
        func item(_ id: String) -> SettingsSearchItem? { items.first { $0.entry.id == id } }
        XCTAssertEqual(item("alarms.delivery")?.target, .page(.alarms, anchor: "alarms.delivery"))
        XCTAssertEqual(item("alarms.delivery")?.entry.path, L("settings_row_alarms"))
        XCTAssertEqual(item("about.diagnostics")?.target, .page(.about, anchor: "about.diagnostics"))
        XCTAssertEqual(item("about.diagnostics")?.entry.path, L("settings_row_about"))
        // Der Knopf steht in der Karte «Letzte Prüfungen»: dorthin
        XCTAssertEqual(item("updates.alarm_log")?.target, .page(.updates, anchor: "updates.check_runs"))
        XCTAssertEqual(item("updates.alarm_log")?.entry.path, L("settings_row_updates"))

        let entries = items.map(\.entry)
        func first(_ query: String) -> String? { SettingsSearch.search(entries, query: query, locale: Locale.current).first?.id }
        XCTAssertEqual(first(L("delivery_title")), "alarms.delivery")
        XCTAssertEqual(first(L("diagnostics_share")), "about.diagnostics")
        XCTAssertEqual(first(L("alarm_log_title")), "updates.alarm_log")
        // «Letzte Prüfungen» mit eigenem Treffer, der zuerst kommt
        XCTAssertEqual(item("updates.check_runs")?.target, .page(.updates, anchor: "updates.check_runs"))
        XCTAssertEqual(first(L("settings_check_runs_title")), "updates.check_runs")
    }
}
