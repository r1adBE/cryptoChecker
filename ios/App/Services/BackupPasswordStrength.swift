import Foundation

/// Schwache Passwörter im Blatt «Sichern» — wie `BackupPasswordStrength` in `BackupCrypto.kt` (gemeinsame Beispiele in
/// `testdata/parity/backup_passwords.json`): sperren das Sichern mit Passwort (`BackupCrypto.canExport`). Entschlüsseln
/// prüft das Passwort nicht.
enum BackupPasswordStrength {
    /// Häufige Folgen (klein geschrieben); kommt eine davon vor, ist das Passwort schwach.
    private static let common = ["password", "passwort", "12345678", "87654321", "abcdefgh"]

    /// Tastaturreihen (QWERTZ, QWERTY, AZERTY); 6 Zeichen in Folge (auch rückwärts) gelten als Tastatur-Spaziergang.
    private static let keyboardRows = [
        "qwertzuiop", "qwertyuiop", "azertyuiop", "asdfghjkl", "qsdfghjklm", "yxcvbnm", "zxcvbnm", "wxcvbn",
    ]
    private static let walk = 6

    /// Wörter, die mit Jahr/Zahl und Zeichen geschmückt ein Passwort nicht stark machen («Summer2026!», «Winter2025»).
    private static let words = [
        // Jahreszeiten
        "spring", "summer", "autumn", "fall", "winter", "fruehling", "frühling", "sommer", "herbst",
        "printemps", "ete", "été", "automne", "hiver",
        // Monate
        "january", "february", "march", "april", "may", "june", "july", "august", "september", "october",
        "november", "december", "januar", "februar", "maerz", "märz", "mai", "juni", "juli", "oktober", "dezember",
        // Häufige Wörter
        "password", "passwort", "welcome", "willkommen", "hello", "hallo", "letmein", "admin", "secret", "geheim",
        "iloveyou", "monkey", "dragon", "sunshine", "football", "fussball", "master", "qwerty", "qwertz",
        "bitcoin", "crypto", "ethereum", "test",
        // Name der App
        "cryptochecker", "crypto-checker", "crypto checker",
    ]

    /// Höchstens so viele Ziffern/Zeichen um ein Wort herum gelten als Schmuck.
    private static let decoration = 6

    /// Schwach (für neue Sicherungen gesperrt) — Regeln wie Android:
    ///  - kürzer als `BackupCrypto.minPasswordLength`, nur Ziffern oder ein einziges Zeichen wiederholt;
    ///  - eine häufige Folge oder ein Tastatur-Spaziergang (6 Zeichen einer Reihe, auch rückwärts);
    ///  - ein Wort (Jahreszeit, Monat, häufiges Wort, Name der App) mit höchstens 6 Ziffern/Zeichen davor bzw. danach;
    ///  - ein wiederholtes Stück («abcabcabcabc», «Test1Test1»);
    ///  - fast nur ein Zeichen: höchstens 3 verschiedene Zeichen, mindestens 75 % dasselbe Zeichen oder ein einziges
    ///    wiederholtes Zeichen, wenn man Ziffern/Zeichen vorne und hinten weglässt («aaaaaaaaaaa1», «!!bbbbbbbbbb»).
    /// Fälle in `Tests/Parity/backup_passwords.json` (gleiche Datei wie Android).
    /// Eine Regel «höchstens 2 Zeichenklassen und kürzer als 12 Zeichen» braucht es nicht: sie deckt die
    /// Mindestlänge 12 ab (Zeichenklassen spielen ab 12 Zeichen keine Rolle).
    static func isWeak(_ password: String) -> Bool {
        let normalized = password.precomposedStringWithCanonicalMapping
        let scalars = Array(normalized.unicodeScalars)
        if scalars.count < BackupCrypto.minPasswordLength { return true }
        let digits: ClosedRange<Unicode.Scalar> = "0"..."9"
        if scalars.allSatisfy({ digits.contains($0) }) { return true }
        if Set(scalars).count == 1 { return true }
        if mostlyOneChar(scalars) { return true }
        let lower = normalized.lowercased()
        if common.contains(where: { lower.contains($0) }) { return true }
        if keyboardWalk(lower) { return true }
        if decoratedWord(lower) { return true }
        return repeatedChunk(scalars)
    }

    /// 6 aufeinanderfolgende Zeichen einer Tastaturreihe (vorwärts oder rückwärts).
    private static func keyboardWalk(_ lower: String) -> Bool {
        keyboardRows.contains { row -> Bool in
            [row, String(row.reversed())].contains { r -> Bool in
                let chars = Array(r)
                guard chars.count >= walk else { return false }
                return (0...(chars.count - walk)).contains { i -> Bool in lower.contains(String(chars[i..<(i + walk)])) }
            }
        }
    }

    /// Wort mit Schmuck: vorne und hinten nur Ziffern/Zeichen (keine Buchstaben), zusammen höchstens `decoration`.
    private static func decoratedWord(_ lower: String) -> Bool {
        words.contains { w -> Bool in
            var searchStart = lower.startIndex
            while searchStart < lower.endIndex, let r = lower.range(of: w, range: searchStart..<lower.endIndex) {
                let rest: String = String(lower[lower.startIndex..<r.lowerBound]) + String(lower[r.upperBound...])
                if rest.unicodeScalars.count <= decoration && !rest.contains(where: { $0.isLetter }) { return true }
                searchStart = lower.index(after: r.lowerBound)
            }
            return false
        }
    }

    /// Fast nur ein Zeichen: höchstens 3 verschiedene Zeichen, mindestens 75 % dasselbe Zeichen, oder ohne
    /// Ziffern/Zeichen (alles ausser Buchstaben) vorne und hinten bleibt ein einziges wiederholtes Zeichen.
    private static func mostlyOneChar(_ s: [Unicode.Scalar]) -> Bool {
        if Set(s).count <= 3 { return true }
        var counts: [Unicode.Scalar: Int] = [:]
        for c in s { counts[c, default: 0] += 1 }
        if let most = counts.values.max(), most * 4 >= s.count * 3 { return true }
        guard let first = s.firstIndex(where: isLetterScalar), let last = s.lastIndex(where: isLetterScalar) else { return false }
        return s[first...last].allSatisfy { $0 == s[first] }
    }

    /// Buchstabe = Unicode-Kategorie L* (wie `Character.isLetter(codePoint)` unter Android).
    private static func isLetterScalar(_ c: Unicode.Scalar) -> Bool {
        switch c.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter: return true
        default: return false
        }
    }

    /// Ganzes Passwort = ein Stück, mindestens zweimal wiederholt.
    private static func repeatedChunk(_ s: [Unicode.Scalar]) -> Bool {
        let n = s.count
        guard n >= 2 else { return false }
        for p in 1...(n / 2) where n % p == 0 {
            if (p..<n).allSatisfy({ s[$0] == s[$0 - p] }) { return true }
        }
        return false
    }

    /// Hinweis «zu leicht zu erraten» zeigen? Erst ab der Mindestlänge (vorher gilt «mindestens 12 Zeichen»).
    static func showWeakHint(_ password: String) -> Bool {
        BackupCrypto.isPasswordLongEnough(password) && isWeak(password)
    }
}
