import XCTest
@testable import CryptoChecker

private let pwSecret = "geheim123"
private let pwRight = "richtig-123"
private let pwWrong = "falsch-1234"
private let pwEight = "12345678"

/// Wie `BackupCryptoTest.kt` — vor allem die festen Prüfwerte (mit Python `cryptography`
/// nachgerechnet, auch in `backup_crypto.json`): Android und iOS müssen Sicherungen gegenseitig lesen können.
final class BackupCryptoTests: XCTestCase {

    private typealias V = BackupCrypto.TestVector
    private let salt = "AAECAwQFBgcICQoLDA0ODw=="
    private let iv = "oKGio6Slpqeoqaqr"

    /// Rasche Argon2id-Parameter für Tests, die nicht den Prüfwert betreffen.
    private let fastKdf = BackupCrypto.Kdf.argon2(memoryKiB: 256, time: 1, parallelism: 1)
    /// Beliebige gültige Base64-Daten (≥ 16 Bytes) für Hüllen, die schon beim Lesen scheitern.
    private let someData =
        "F8ZO22w17BWWRjDa0IBQavtrw3kOeap/pSDHBNMr2yCCLsZy82l3RqH6OxEDzba6sota8lpn/SClooHDYy0tm81k5gCByAiia3plAA=="

    private func data(_ base64: String) throws -> Data { try XCTUnwrap(Data(base64Encoded: base64)) }

    private func isWrongPassword(_ error: Error) -> Bool {
        if case BackupCrypto.Failure.wrongPassword = error { return true }
        return false
    }

    private func isUnsupported(_ error: Error) -> Bool {
        if case BackupCrypto.Failure.unsupported = error { return true }
        return false
    }

    func testVectorMatchesAndroidAndPython() throws {
        // Gleiche Werte wie in backup_crypto.json
        XCTAssertEqual(V.password, "Kryptö-Test 2026!")
        XCTAssertEqual(V.salt, salt)
        XCTAssertEqual(V.iv, iv)
        XCTAssertEqual(V.plain, #"{"format":"cryptochecker-backup","version":1,"portfolio":[]}"#)
        XCTAssertEqual(try data(salt), Data((0..<16).map { UInt8($0) }))
        XCTAssertEqual(try data(iv), Data((0..<12).map { (i: Int) -> UInt8 in UInt8(0xA0 + i) }))
        XCTAssertTrue(BackupCrypto.verifyTestVector())
    }

    func testNewBackupsUseArgon2idV3() throws {
        XCTAssertEqual(BackupCrypto.version, 3)
        XCTAssertEqual(BackupCrypto.defaultKdf, .argon2(memoryKiB: 65_536, time: 3, parallelism: 1))
        // Prüfwert mit den Parametern neuer Sicherungen (wie backup_crypto.json, erster v3-Fall)
        let env = try BackupCrypto.encrypt(Data(V.plain.utf8), password: V.password, salt: data(salt), iv: data(iv))
        XCTAssertEqual(env.version, 3)
        XCTAssertEqual(env.kdf, BackupCrypto.defaultKdf)
        XCTAssertEqual(env.data.base64EncodedString(), V.data3)
        let json = String(decoding: BackupCrypto.json(env), as: UTF8.self)
        for field in [#""v": 3"#, #""kdf": "Argon2id""#, #""m": 65536"#, #""t": 3"#, #""p": 1"#] {
            XCTAssertTrue(json.contains(field), field)
        }
        XCTAssertFalse(json.contains(#""iter""#))
        // Entschlüsseln mit 64 MiB prüft BackupCryptoParityTests (im Debug-Build dauert jede Ableitung einige Sekunden)
        // Zufälliges Salt neuer Sicherungen: 16 Bytes
        let random = try BackupCrypto.encrypt(Data("{}".utf8), password: pwSecret, kdf: fastKdf)
        XCTAssertEqual(random.salt.count, 16)
    }

    func testV3HeaderIsBoundAsAad() throws {
        let env = try BackupCrypto.encrypt(Data(V.plain.utf8), password: V.password, salt: data(salt), iv: data(iv),
                                           kdf: fastKdf)
        XCTAssertEqual(String(decoding: BackupCrypto.header(env), as: UTF8.self),
                       #"{"format":"cryptochecker-backup-enc","v":3,"enc":"AES-256-GCM","kdf":"Argon2id","m":256,"t":1,"p":1,"salt":"AAECAwQFBgcICQoLDA0ODw==","iv":"oKGio6Slpqeoqaqr"}"#)
        // Veränderter Kopf (t, p, m) → «falsches Passwort oder beschädigte Datei», nicht «nicht unterstützt»
        let changed: [BackupCrypto.Kdf] = [.argon2(memoryKiB: 256, time: 2, parallelism: 1),
                                           .argon2(memoryKiB: 256, time: 1, parallelism: 2),
                                           .argon2(memoryKiB: 264, time: 1, parallelism: 1)]
        for kdf in changed {
            XCTAssertThrowsError(try BackupCrypto.decrypt(
                BackupCrypto.Envelope(kdf: kdf, salt: env.salt, iv: env.iv, data: env.data), password: V.password
            )) { error in XCTAssertTrue(self.isWrongPassword(error), "\(kdf): \(error)") }
        }
    }

    func testArgon2LimitsProtectAgainstManipulatedFiles() throws {
        let rejected: [BackupCrypto.Kdf] = [
            .argon2(memoryKiB: 262_145, time: 3, parallelism: 1),   // über 256 MiB
            .argon2(memoryKiB: 65_536, time: 11, parallelism: 1),   // t über 10
            .argon2(memoryKiB: 65_536, time: 3, parallelism: 5),    // p über 4
            .argon2(memoryKiB: 65_536, time: 0, parallelism: 1),
            .argon2(memoryKiB: 65_536, time: 3, parallelism: 0),
            .argon2(memoryKiB: 31, time: 1, parallelism: 4),        // unter 8 KiB je Bahn
            .argon2(memoryKiB: Int.max, time: 3, parallelism: 1),
        ]
        for kdf in rejected {
            XCTAssertThrowsError(try BackupCrypto.decrypt(
                BackupCrypto.Envelope(kdf: kdf, salt: data(salt), iv: data(iv), data: Data(count: 32)), password: V.password
            )) { error in XCTAssertTrue(self.isUnsupported(error), "\(kdf): \(error)") }
        }
    }

    func testDecomposedPasswordEqualsComposed() throws {
        // «ö» als o + U+0308 (NFD) ergibt dieselben Bytes und denselben Schlüssel wie das vorkomponierte «ö»
        XCTAssertEqual(BackupCrypto.passwordBytes("Krypto\u{0308}-Test 2026!"), BackupCrypto.passwordBytes(V.password))
        let decomposed = try BackupCrypto.encrypt(Data(V.plain.utf8), password: "Krypto\u{0308}-Test 2026!",
                                                  salt: data(salt), iv: data(iv), kdf: V.kdf3Fast)
        XCTAssertEqual(decomposed.data.base64EncodedString(), V.data3Fast)
    }

    func testRoundTripWithRandomSaltAndIv() throws {
        let plain = Data(#"{"format":"cryptochecker-backup","version":1,"note":"Ünïcödé ✓"}"#.utf8)
        let a = try BackupCrypto.encrypt(plain, password: pwSecret, kdf: fastKdf)
        let b = try BackupCrypto.encrypt(plain, password: pwSecret, kdf: fastKdf)
        XCTAssertNotEqual(a.salt, b.salt)
        XCTAssertNotEqual(a.iv, b.iv)
        XCTAssertEqual(a.data.count, plain.count + BackupCrypto.tagBytes)
        XCTAssertEqual(try BackupCrypto.decrypt(a, password: pwSecret), plain)
        XCTAssertEqual(try BackupCrypto.decrypt(b, password: pwSecret), plain)
    }

    func testWrongPasswordIsReported() throws {
        let env = try BackupCrypto.encrypt(Data("{}".utf8), password: pwRight, kdf: fastKdf)
        XCTAssertThrowsError(try BackupCrypto.decrypt(env, password: pwWrong)) { error in
            XCTAssertTrue(self.isWrongPassword(error), "\(error)")
        }
    }

    func testTamperedDataIsRejected() throws {
        let env = try BackupCrypto.encrypt(Data(#"{"a":1}"#.utf8), password: pwRight, kdf: fastKdf)
        var bad = env.data
        bad[bad.startIndex] ^= 1
        XCTAssertThrowsError(try BackupCrypto.decrypt(
            BackupCrypto.Envelope(kdf: env.kdf, salt: env.salt, iv: env.iv, data: bad), password: pwRight
        )) { error in XCTAssertTrue(self.isWrongPassword(error), "\(error)") }
        var badTag = env.data
        badTag[badTag.index(before: badTag.endIndex)] ^= 0x80
        XCTAssertThrowsError(try BackupCrypto.decrypt(
            BackupCrypto.Envelope(kdf: env.kdf, salt: env.salt, iv: env.iv, data: badTag), password: pwRight
        )) { error in XCTAssertTrue(self.isWrongPassword(error), "\(error)") }
    }

    func testEnvelopeJsonHasTheAndroidFields() throws {
        let env = try BackupCrypto.encrypt(Data(V.plain.utf8), password: V.password, salt: data(salt), iv: data(iv),
                                           kdf: fastKdf)
        let json = String(decoding: BackupCrypto.json(env), as: UTF8.self)
        for field in [#""format": "cryptochecker-backup-enc""#, #""v": 3"#, #""enc": "AES-256-GCM""#,
                      #""kdf": "Argon2id""#, #""m": 256"#, #""t": 1"#, #""p": 1"#, "\"salt\": \"\(salt)\"",
                      "\"iv\": \"\(iv)\"", "\"data\": \"\(env.data.base64EncodedString())\""] {
            XCTAssertTrue(json.contains(field), field)
        }
        // Und wieder lesbar
        let object = try JSONSerialization.jsonObject(with: BackupCrypto.json(env))
        let root = try XCTUnwrap(object as? [String: Any])
        XCTAssertTrue(BackupCrypto.isEnvelope(root))
        XCTAssertEqual(try BackupCrypto.envelope(from: root).data, env.data)
    }

    func testUnsupportedEnvelopesAreRejected() {
        func root(v: Int = 3, enc: String = "AES-256-GCM", kdf: String = "Argon2id", argon2: Bool = true,
                  salt: String? = "AAECAwQFBgcICQoLDA0ODw==", iv: String = "oKGio6Slpqeoqaqr",
                  data: String? = nil) -> [String: Any] {
            var r: [String: Any] = ["format": "cryptochecker-backup-enc", "v": v, "enc": enc, "kdf": kdf,
                                    "iv": iv, "data": data ?? someData]
            if argon2 { r["m"] = 65_536; r["t"] = 3; r["p"] = 1 }
            if let salt { r["salt"] = salt }
            return r
        }
        // Neuere Hülle: eigene Meldung «neuere App-Version» (wie Android)
        XCTAssertThrowsError(try BackupCrypto.envelope(from: root(v: 4))) { error in
            XCTAssertEqual(error as? BackupVersion.Failure, .newer(4), "\(error)")
        }
        // Nur v3 mit Argon2id und m, t, p
        var tooBig = root()
        tooBig["m"] = 262_145
        var pbkdf2 = root(v: 2, kdf: "PBKDF2-HMAC-SHA256", argon2: false)
        pbkdf2["iter"] = 210_000
        let rejected: [[String: Any]] = [
            root(v: 1), root(v: 2), pbkdf2, root(kdf: "PBKDF2-HMAC-SHA256"), root(kdf: "scrypt"), root(enc: "ChaCha20"),
            root(argon2: false), tooBig, root(iv: "AAEC"), root(data: "%%%"), root(salt: nil), root(data: "AAEC"),
        ]
        for r in rejected {
            XCTAssertThrowsError(try BackupCrypto.envelope(from: r)) { error in
                XCTAssertTrue(self.isUnsupported(error), "\(error)")
            }
        }
    }

    func testEnvelopeDetection() {
        XCTAssertTrue(BackupCrypto.isEnvelope(["format": "cryptochecker-backup-enc", "enc": "AES-256-GCM"]))
        XCTAssertFalse(BackupCrypto.isEnvelope(["format": "cryptochecker-backup-enc"]))
        XCTAssertFalse(BackupCrypto.isEnvelope(["format": "cryptochecker-backup-enc", "enc": ""]))
        XCTAssertFalse(BackupCrypto.isEnvelope(["format": "other", "enc": "AES-256-GCM"]))
        XCTAssertFalse(BackupCrypto.isEnvelope(["format": "cryptochecker-backup", "enc": "AES-256-GCM"]))
    }

    func testPasswordRules() {
        // Mindestlänge 12 (neue Sicherungen)
        XCTAssertFalse(BackupCrypto.isPasswordLongEnough("12345678901"))
        XCTAssertTrue(BackupCrypto.isPasswordLongEnough("123456789012"))
        // Emoji zählt als ein Zeichen
        XCTAssertFalse(BackupCrypto.isPasswordLongEnough("1234567890🔒"))
        XCTAssertTrue(BackupCrypto.isPasswordLongEnough("12345678901🔒"))
        let good = "Kryptö-Test 2026!"
        XCTAssertTrue(BackupCrypto.canExport(protect: false, password: "", repeated: ""))
        XCTAssertFalse(BackupCrypto.canExport(protect: true, password: good, repeated: "Kryptö-Test 2027!"))
        XCTAssertFalse(BackupCrypto.canExport(protect: true, password: "1234", repeated: "1234"))
        XCTAssertTrue(BackupCrypto.canExport(protect: true, password: good, repeated: good))
        // Schwache Passwörter sperren das Sichern, auch ab 12 Zeichen
        // Liste statt Literal am Parameter: das Upload-Skript meldet sonst ein Passwort im Klartext
        let weakSamples = ["123456789012", "aaaaaaaaaaaa", "MeinPasswort"]
        for weak in weakSamples {
            XCTAssertFalse(BackupCrypto.canExport(protect: true, password: weak, repeated: weak), weak)
        }
        XCTAssertFalse(BackupCrypto.isPasswordAllowed("Abc!def9xy"))    // 10 Zeichen
        XCTAssertFalse(BackupCrypto.isPasswordAllowed("Abc!def9xyZ"))   // 11 Zeichen
        XCTAssertTrue(BackupCrypto.isPasswordAllowed("Abc!def9xyZ2"))   // 12 Zeichen
    }

    func testDecryptDoesNotCheckPasswordLength() throws {
        // Die Mindestlänge gilt nur beim Sichern
        let plain = Data("{}".utf8)
        let env = try BackupCrypto.encrypt(plain, password: pwEight, kdf: fastKdf)
        XCTAssertEqual(try BackupCrypto.decrypt(env, password: pwEight), plain)
    }

    // MARK: Schwaches Passwort — gleiche Fälle wie BackupPasswordStrengthTest.kt

    func testPasswordStrengthShortIsWeak() {
        XCTAssertTrue(BackupPasswordStrength.isWeak("Abc!def9"))
        XCTAssertTrue(BackupPasswordStrength.isWeak("Abc!def9x"))        // 9 Zeichen
        XCTAssertTrue(BackupPasswordStrength.isWeak("Abc!def9xy"))       // 10 Zeichen
        XCTAssertTrue(BackupPasswordStrength.isWeak("Abc!def9xyZ"))      // 11 Zeichen
        XCTAssertFalse(BackupPasswordStrength.isWeak("Abc!def9xyZ2"))    // 12 Zeichen
        // Emoji zählt als ein Zeichen
        XCTAssertTrue(BackupPasswordStrength.isWeak("Abc!def9xy🔒"))     // 11 Zeichen
        XCTAssertFalse(BackupPasswordStrength.isWeak("Abc!def9xyZ🔒"))   // 12 Zeichen
    }

    func testPasswordStrengthOnlyDigitsOrOneRepeatedChar() {
        XCTAssertTrue(BackupPasswordStrength.isWeak("982374659102"))
        XCTAssertTrue(BackupPasswordStrength.isWeak("aaaaaaaaaaaaaaa"))
        XCTAssertTrue(BackupPasswordStrength.isWeak("ööööööööööööö"))
    }

    func testPasswordStrengthCommonSequences() {
        XCTAssertTrue(BackupPasswordStrength.isWeak("12345678abcd"))
        XCTAssertTrue(BackupPasswordStrength.isWeak("MeinPassword!"))
        XCTAssertTrue(BackupPasswordStrength.isWeak("PASSWORT2026!"))
        XCTAssertTrue(BackupPasswordStrength.isWeak("Qwertz-Sommer"))
        XCTAssertTrue(BackupPasswordStrength.isWeak("xQWERTYx9!aa"))
        XCTAssertTrue(BackupPasswordStrength.isWeak("abcdefgh1234"))
    }

    func testPasswordStrengthLongMixedIsNotWeak() {
        XCTAssertFalse(BackupPasswordStrength.isWeak("Kryptö-Test 2026!"))
        XCTAssertFalse(BackupPasswordStrength.isWeak("blau Haus Fahrrad"))
        XCTAssertFalse(BackupPasswordStrength.isWeak("7Tg#kd92!mQz"))
    }

    func testPasswordStrengthHintOnlyFromMinimumLength() {
        XCTAssertFalse(BackupPasswordStrength.showWeakHint(""))
        XCTAssertFalse(BackupPasswordStrength.showWeakHint("12345678901"))   // noch «mindestens 12 Zeichen»
        XCTAssertFalse(BackupPasswordStrength.showWeakHint("Abc!def9"))
        XCTAssertTrue(BackupPasswordStrength.showWeakHint("123456789012"))
        XCTAssertTrue(BackupPasswordStrength.showWeakHint("Passwort1234"))
        XCTAssertFalse(BackupPasswordStrength.showWeakHint("7Tg#kd92!mQz"))
    }
}

/// Wie `BackupLimitsTest.kt`: Grenzen beim Einlesen und gezählte ungültige Portfolio-Einträge.
final class BackupLimitsTests: XCTestCase {
    func testDeepNestingIsRejectedButBracketsInTextsDoNotCount() throws {
        try BackupLimits.checkDepth(Data(#"{"a":[{"b":"[[[[[[[[[[[[[[[[[[[["}]}"#.utf8), max: 4)
        XCTAssertThrowsError(try BackupLimits.checkDepth(Data(String(repeating: "[", count: 5).utf8), max: 4))
        try BackupLimits.checkDepth(Data(#"{"a":"x\"[[[[[[["}"#.utf8), max: 2)
    }

    /// Klartext mit allen Teilen, die `plainExportData` schreibt (Einstellungen aus `backup_settings.json`).
    private func backupJSON(portfolio: [[String: Any]] = [], portfolioAlarms: [[String: Any]] = []) throws -> Data {
        let settings = try XCTUnwrap(TestSupport.fixture("backup_settings")["settings"] as? [String: Any])
        let root: [String: Any] = [
            "format": BackupManager.format, "version": BackupManager.version, "createdAt": 1, "watches": [[String: Any]](),
            "alarms": [[String: Any]](), "favorites": ["EXCHANGE": [String](), "COIN": ["BTC"], "QUOTE": [String]()],
            "settings": settings, WatchedAddressRules.backupKey: [[String: Any]](),
            "portfolio": portfolio, "portfolioAlarms": portfolioAlarms,
        ]
        return try JSONSerialization.data(withJSONObject: root)
    }

    /// Transaktion so, wie die Sicherung sie schreibt, mit geänderten Feldern `changes`.
    private func tx(_ changes: [String: Any] = [:]) -> [String: Any] {
        BackupManager.txToJson(PortfolioTx(id: 1, coin: "BTC", type: .BUY, amountExact: 1, time: 1))
            .merging(changes) { _, new in new }
    }

    func testInvalidPortfolioEntriesAreCounted() throws {
        let alarm = BackupManager.portfolioAlarmToJson(PortfolioAlarm(id: 1, kind: .CHANGE_UP, threshold: 5, currency: nil))
            .merging(["kind": "UNKNOWN"]) { _, new in new }
        let json = try backupJSON(portfolio: [tx(), tx(["amountText": "0"]), tx(["type": "LOAN"])], portfolioAlarms: [alarm])
        XCTAssertEqual(try BackupManager.invalidEntries(json), 3)
    }

    /// Grenzen der exakten Zahlen (wie Android `PortfolioJson.decimal`).
    func testTextNumbersHaveLimits() throws {
        let json = try backupJSON(portfolio: [
            tx(["amountText": "0.5"]), tx(["amountText": "1e300"]), tx(["amountText": "1e-50"]),
            tx(["priceText": "1e31"]), tx(["feeText": "1e35"]),
        ])
        // Menge zu gross bzw. zu fein → ungültig; ungültiger Preis bzw. Gebühr → Eintrag gilt ohne
        XCTAssertEqual(try BackupManager.invalidEntries(json), 2)
        // Zahl statt Text: Sicherung unlesbar
        XCTAssertThrowsError(try BackupManager.invalidEntries(backupJSON(portfolio: [tx(["amountText": 1])])))
    }

    /// Wie `BackupLimitsTest.kt`: Jedes Pflichtfeld eines Eintrags fehlt → Sicherung unlesbar; wahlfreie Felder dürfen
    /// fehlen (`Parity/backup_entries.json`).
    func testEntryFieldsMatchFixture() throws {
        let fixture = try TestSupport.fixture("backup_entries")
        for entry in BackupFields.entries {
            let spec = try XCTUnwrap(fixture[entry.part] as? [String: Any], entry.part)
            XCTAssertEqual(spec["required"] as? [String: String] ?? [:],
                           Dictionary(uniqueKeysWithValues: entry.required.map { ($0.key, $0.type.rawValue) }), entry.part)
            XCTAssertEqual(spec["optional"] as? [String: String] ?? [:],
                           Dictionary(uniqueKeysWithValues: entry.optional.map { ($0.key, $0.type.rawValue) }), entry.part)
            let sample = try XCTUnwrap(spec["sample"] as? [String: Any], entry.part)
            try BackupFields.checkEntry(entry, sample)
            for field in entry.required {
                var missing = sample
                missing.removeValue(forKey: field.key)
                XCTAssertThrowsError(try BackupFields.checkEntry(entry, missing), "\(entry.part) ohne \(field.key)")
            }
            for field in entry.optional {
                var without = sample
                without.removeValue(forKey: field.key)
                XCTAssertNoThrow(try BackupFields.checkEntry(entry, without), "\(entry.part) ohne \(field.key)")
            }
            XCTAssertThrowsError(try BackupFields.checkEntry(entry, "kein Objekt"), entry.part)
        }
        for c in TestSupport.list(fixture["invalid"]) {
            let part = c["part"] as? String ?? ""
            let entry = try XCTUnwrap(BackupFields.entries.first { $0.part == part }, part)
            var sample = try XCTUnwrap((fixture[part] as? [String: Any])?["sample"] as? [String: Any])
            sample[c["key"] as? String ?? ""] = c["value"] ?? NSNull()
            XCTAssertThrowsError(try BackupFields.checkEntry(entry, sample), c["name"] as? String ?? "")
        }
        // Was der Schreiber schreibt, besteht die Prüfung
        let watch = Watch(id: 1, exchangeKey: "Binance", exchangeName: "Binance", baseAsset: "BTC", quoteAsset: "USDT")
        try BackupFields.checkEntry(BackupFields.watch, BackupManager.watchToJson(watch))
        try BackupFields.checkEntry(BackupFields.alarm, BackupManager.alarmToJson(
            Alarm(id: 1, watchId: 1, condition: .PRICE_ABOVE, threshold: 1)))
        try BackupFields.checkEntry(BackupFields.tx, tx())
        try BackupFields.checkEntry(BackupFields.portfolioAlarm,
                                    BackupManager.portfolioAlarmToJson(PortfolioAlarm(id: 1, kind: .CHANGE_UP, threshold: 5, currency: nil)))
    }

    /// Wie `BackupLimitsTest.kt`: Jeder Teil, den `plainExportData` schreibt, ist Pflicht; ohne «version» unlesbar.
    func testEveryPartTheExportWritesIsRequired() throws {
        let full = try XCTUnwrap(JSONSerialization.jsonObject(with: backupJSON()) as? [String: Any])
        XCTAssertEqual(try BackupManager.invalidEntries(backupJSON()), 0)
        // Jede Favoriten-Art als Liste von Texten, jedes Feld der Einstellungen
        var noQuote = full
        noQuote["favorites"] = ["EXCHANGE": [String](), "COIN": [String]()]
        XCTAssertThrowsError(try BackupFields.checkParts(noQuote))
        var numberFavorite = full
        numberFavorite["favorites"] = ["EXCHANGE": [String](), "COIN": [1], "QUOTE": [String]()]
        XCTAssertThrowsError(try BackupFields.checkParts(numberFavorite))
        var noLock = full
        var settings = try XCTUnwrap(full["settings"] as? [String: Any])
        settings.removeValue(forKey: "appLock")
        noLock["settings"] = settings
        XCTAssertThrowsError(try BackupFields.checkParts(noLock))
        for key in ["createdAt", "watches", "alarms", "favorites", "settings", "portfolio", "portfolioAlarms",
                    WatchedAddressRules.backupKey] {
            var root = full
            root.removeValue(forKey: key)
            XCTAssertThrowsError(try BackupManager.invalidEntries(JSONSerialization.data(withJSONObject: root)), key) { error in
                guard case BackupManager.Failure.invalid(let part) = error else { return XCTFail("\(key): \(error)") }
                XCTAssertEqual(part, key)
            }
        }
        // Falscher Typ zählt wie fehlend
        var wrongType = full
        wrongType["portfolio"] = [String: Any]()
        XCTAssertThrowsError(try BackupFields.checkParts(wrongType))
        var noVersion = full
        noVersion.removeValue(forKey: "version")
        XCTAssertThrowsError(try BackupManager.invalidEntries(JSONSerialization.data(withJSONObject: noVersion))) { error in
            XCTAssertEqual(error as? BackupVersion.Failure, .invalid)
        }
    }

    /// Wie `BackupLimitsTest.kt`: nie eine Datei schreiben, die sich danach nicht mehr einlesen lässt.
    func testBackupLargerThanTheReadLimitIsNotWritten() throws {
        try BackupLimits.checkWritable(Data(count: 10), max: 10)
        XCTAssertThrowsError(try BackupLimits.checkWritable(Data(count: 11), max: 10)) { error in
            guard case BackupLimits.Failure.exportTooLarge = error else { return XCTFail("\(error)") }
        }
        // Schreiben und Lesen mit derselben Grenze, gleich wie Android (`BackupLimits.MAX_BYTES`)
        XCTAssertEqual(BackupLimits.maxBytes, 64 * 1024 * 1024)
    }

    /// Kompakt (kein `.prettyPrinted`), Schlüssel weiter sortiert; das Einlesen nimmt die Datei an.
    func testBackupIsWrittenCompactAndReadBack() throws {
        let root: [String: Any] = [
            "format": BackupManager.format, "version": BackupManager.version,
            "createdAt": 1,
            "watches": [BackupManager.watchToJson(Watch(id: 7, exchangeKey: "Binance", exchangeName: "Binance", baseAsset: "BTC",
                                                        quoteAsset: "USDT", note: "zwei\nZeilen"))],
            "alarms": [], "favorites": ["EXCHANGE": [String](), "COIN": [String](), "QUOTE": [String]()],
            "settings": TestSupport.settingsBackup(),
            "portfolio": [], "portfolioAlarms": [], WatchedAddressRules.backupKey: [],
        ]
        let data = try BackupManager.encode(root)
        let text = String(decoding: data, as: UTF8.self)
        XCTAssertFalse(text.contains("\n"))
        XCTAssertFalse(text.contains("  "))
        XCTAssertTrue(text.hasPrefix(#"{"alarms":[],"createdAt":1,"favorites":{"COIN":[],"EXCHANGE":[],"QUOTE":[]},"format":"cryptochecker-backup""#))
        XCTAssertFalse(try BackupManager.needsPassword(data))
        XCTAssertEqual(try BackupManager.plainJSON(data, password: nil), data)
        XCTAssertEqual(try BackupManager.invalidEntries(data), 0)
    }
}

/// Zurückrollen eines abgebrochenen Wiederherstellens beim Start (`BackupManager.recoverInterruptedRestore`): Journal nur
/// löschen, wenn erledigt oder nie zurückrollbar — wie Android `BackupManager.recoverInterruptedRestore`.
final class RestoreRecoveryTests: XCTestCase {
    private struct Boom: Error {}

    func testJournalIsKeptWhenRollBackFailedWhileWriting() {
        var deleted = false
        var marked = 0
        var notices: [Bool] = []
        BackupManager.finishRecovery(.incomplete, deleteJournal: { deleted = true }, notice: { notices.append($0) },
                                     markAttempt: { marked += 1 })
        // Journal bleibt für genau einen weiteren Versuch (vermerkt), der Hinweis «nicht vollständig» kommt trotzdem
        XCTAssertFalse(deleted)
        XCTAssertEqual(marked, 1)
        XCTAssertEqual(notices, [false])
    }

    func testJournalIsDeletedAfterRollBackOrWhenUnreadable() {
        var deleted = 0
        var marked = 0
        var notices: [Bool] = []
        BackupManager.finishRecovery(.rolledBack, deleteJournal: { deleted += 1 }, notice: { notices.append($0) },
                                     markAttempt: { marked += 1 })
        BackupManager.finishRecovery(.unreadable, deleteJournal: { deleted += 1 }, notice: { notices.append($0) },
                                     markAttempt: { marked += 1 })
        XCTAssertEqual(deleted, 2)
        XCTAssertEqual(marked, 0)
        XCTAssertEqual(notices, [true, false])
    }

    func testGiveUpDeletesJournalAndNoticesOnlyOnce() {
        var steps: [String] = []
        var notices: [Bool] = []
        // Zu alt, nie versucht: vermerken, löschen, Hinweis
        BackupManager.giveUpRecovery(showNotice: true, markAttempt: { steps.append("mark") },
                                     deleteJournal: { steps.append("delete") }, notice: { notices.append($0) })
        // Nach gescheitertem Versuch: der Hinweis kam schon
        BackupManager.giveUpRecovery(showNotice: false, markAttempt: { steps.append("mark") },
                                     deleteJournal: { steps.append("delete") }, notice: { notices.append($0) })
        XCTAssertEqual(steps, ["mark", "delete", "mark", "delete"])
        XCTAssertEqual(notices, [false])
    }

    func testFailureWhileWritingIsIncompleteOtherwiseUnreadable() {
        XCTAssertEqual(BackupManager.recoveryOutcome(failure: BackupManager.Failure.invalid("portfolio")), .incomplete)
        XCTAssertEqual(BackupManager.recoveryOutcome(failure: BackupManager.Failure.invalid(WatchedAddressRules.backupKey)), .incomplete)
        // Schon beim Lesen gescheitert: nie zurückrollbar
        XCTAssertEqual(BackupManager.recoveryOutcome(failure: BackupManager.Failure.notABackup), .unreadable)
        XCTAssertEqual(BackupManager.recoveryOutcome(failure: BackupManager.Failure.invalid("watches")), .unreadable)
        XCTAssertEqual(BackupManager.recoveryOutcome(failure: Boom()), .unreadable)
    }
}

/// Darf ein Journal noch zurückgerollt werden (`RestoreRecoveryPolicy`)? Wie `RestoreRecoveryPolicyTest.kt`.
final class RestoreRecoveryPolicyTests: XCTestCase {
    private let created: Int64 = 1_800_000_000_000
    private let hour: Int64 = 60 * 60 * 1000

    func testFreshJournalWithoutAttemptIsRolledBack() {
        XCTAssertEqual(RestoreRecoveryPolicy.decide(createdAt: created, attemptedFor: nil, now: created + hour), .rollBack)
        // Ein vermerkter Versuch für ein ANDERES (früheres) Journal zählt nicht
        XCTAssertEqual(RestoreRecoveryPolicy.decide(createdAt: created, attemptedFor: created - hour, now: created + hour), .rollBack)
        // Genau ein Tag alt: noch zurückrollen
        XCTAssertEqual(RestoreRecoveryPolicy.decide(createdAt: created, attemptedFor: nil, now: created + 24 * hour), .rollBack)
    }

    func testSecondAttemptForSameJournalGivesUp() {
        // Der vorige Start scheiterte mit diesem Journal: kein drittes Zurückrollen über inzwischen geänderte Daten
        XCTAssertEqual(RestoreRecoveryPolicy.decide(createdAt: created, attemptedFor: created, now: created + hour), .giveUp)
    }

    func testJournalOlderThanOneDayGivesUp() {
        XCTAssertEqual(RestoreRecoveryPolicy.decide(createdAt: created, attemptedFor: nil, now: created + 24 * hour + 1), .giveUp)
        XCTAssertEqual(RestoreRecoveryPolicy.decide(createdAt: created, attemptedFor: nil, now: created + 72 * hour), .giveUp)
    }

    func testUnknownAgeGivesUp() {
        XCTAssertEqual(RestoreRecoveryPolicy.decide(createdAt: 0, attemptedFor: nil, now: created), .giveUp)
        XCTAssertEqual(RestoreRecoveryPolicy.decide(createdAt: -5, attemptedFor: nil, now: created), .giveUp)
    }

    /// «createdAt» aus dem Journal; ohne gilt das Alter als unbekannt (0, aufgeben).
    func testCreatedAtComesFromTheJournal() {
        XCTAssertEqual(RestoreRecoveryPolicy.storedCreatedAt(Data(#"{"createdAt":1800000000000,"watches":[]}"#.utf8)), created)
        XCTAssertEqual(RestoreRecoveryPolicy.storedCreatedAt(Data(#"{"watches":[]}"#.utf8)), 0)
        XCTAssertEqual(RestoreRecoveryPolicy.storedCreatedAt(Data("kein json".utf8)), 0)
    }

    func testNoticeOnGiveUpOnlyOncePerJournal() {
        XCTAssertTrue(RestoreRecoveryPolicy.noticeOnGiveUp(createdAt: created, attemptedFor: nil))
        XCTAssertTrue(RestoreRecoveryPolicy.noticeOnGiveUp(createdAt: created, attemptedFor: created - hour))
        XCTAssertFalse(RestoreRecoveryPolicy.noticeOnGiveUp(createdAt: created, attemptedFor: created))
    }

    func testAttemptMarkerRoundTrip() {
        let defaults = UserDefaults(suiteName: "RestoreRecoveryPolicyTests")!
        defaults.removePersistentDomain(forName: "RestoreRecoveryPolicyTests")
        XCTAssertNil(StorageRecoveryNotice.rollBackAttemptedFor(defaults: defaults))
        StorageRecoveryNotice.recordRollBackAttempt(created, defaults: defaults)
        XCTAssertEqual(StorageRecoveryNotice.rollBackAttemptedFor(defaults: defaults), created)
        defaults.removePersistentDomain(forName: "RestoreRecoveryPolicyTests")
    }
}
