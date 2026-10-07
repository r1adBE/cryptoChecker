import AVFoundation

/// Sprachausgabe — wie `TtsSpeaker.kt`. Stimme in der Sprache der App.
///
/// Automatische Ansagen ([enqueue]) laufen einzeln über eine eigene Warteschlange
/// (`AnnouncementQueue`): die nächste erst, wenn die vorige gesprochen ist. Mehrere Alarme
/// eines Durchlaufs bleiben so alle hörbar, Kursansagen werden je Paar auf die neueste
/// zusammengefasst. Sie nutzen die Audio-Kategorie `.ambient` und schweigen damit bei
/// Stummschaltung (Klingel-/Stumm-Schalter bzw. Aktionstaste) — wie die Alarm-Mitteilung
/// selbst, deren Ton dann ebenfalls stumm ist (Android: Lautlos/Vibration).
/// Vom Nutzer angestossene Ansagen ([speak]: Stimme testen, Alarm testen) nutzen `.playback`
/// und sind immer hörbar.
@MainActor
final class Speaker: NSObject, AVSpeechSynthesizerDelegate {
    static let shared = Speaker()

    private let synthesizer = AVSpeechSynthesizer()
    private var queue = AnnouncementQueue()
    /// Gerade gesprochene automatische Ansage.
    private var current: (id: ObjectIdentifier, kind: AnnouncementQueue.Kind)?
    /// Laufende Ansagen aus [speak]; solange eine läuft, wartet die Warteschlange.
    private var manual: Set<ObjectIdentifier> = []

    private override init() {
        super.init()
        synthesizer.delegate = self
    }

    /// Sofort vorlesen (vom Nutzer angestossen), auch bei Stummschaltung.
    /// - Parameter flush: true bricht laufende Ansagen ab.
    func speak(_ text: String, rate: Double, flush: Bool = false) {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio, options: [.duckOthers])
        try? AVAudioSession.sharedInstance().setActive(true)

        if flush { synthesizer.stopSpeaking(at: .immediate) }
        let utterance = Self.utterance(text, rate: rate)
        manual.insert(ObjectIdentifier(utterance))
        synthesizer.speak(utterance)
    }

    /// Automatische Ansage aus der Kurs-Aktualisierung, ohne zu warten.
    /// - Parameters:
    ///   - alarm: true = Alarm: nie verworfen, vor wartenden Kursansagen, unterbricht eine
    ///     laufende Kursansage (nicht aber einen anderen Alarm).
    ///   - key: Paar (Watch-Id) einer Kursansage: Von einem Paar wartet nur die neueste.
    func enqueue(_ text: String, rate: Double, alarm: Bool, key: Int64? = nil) {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        queue.offer(AnnouncementQueue.Item(text: text, rate: rate, kind: alarm ? .alarm : .price,
                                           key: alarm ? nil : key))
        // Sicherheitsnetz: Spricht gerade nichts, kann keine Rückmeldung mehr ausstehen —
        // eine verlorene Rückmeldung soll die Warteschlange nicht dauerhaft blockieren.
        if !synthesizer.isSpeaking {
            current = nil
            manual.removeAll()
        }
        if alarm, current?.kind == .price {
            // didCancel gibt die Warteschlange frei
            synthesizer.stopSpeaking(at: .immediate)
            return
        }
        next()
    }

    private func next() {
        guard current == nil, manual.isEmpty, let item = queue.poll() else { return }
        // .ambient: folgt der Stummschaltung des Geräts
        try? AVAudioSession.sharedInstance().setCategory(.ambient)
        try? AVAudioSession.sharedInstance().setActive(true)
        let utterance = Self.utterance(item.text, rate: item.rate)
        current = (ObjectIdentifier(utterance), item.kind)
        synthesizer.speak(utterance)
    }

    private func finished(_ id: ObjectIdentifier) {
        if current?.id == id { current = nil }
        manual.remove(id)
        next()
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        let id = ObjectIdentifier(utterance)
        Task { @MainActor in self.finished(id) }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        let id = ObjectIdentifier(utterance)
        Task { @MainActor in self.finished(id) }
    }

    private static func utterance(_ text: String, rate: Double) -> AVSpeechUtterance {
        let utterance = AVSpeechUtterance(string: text)
        // Android: 0.5 – 2.0, 1 = normal. iOS: Standard 0.5, Bereich 0 – 1.
        let clamped = min(max(rate, 0.5), 2.0)
        utterance.rate = Float(AVSpeechUtteranceDefaultSpeechRate) * Float(clamped)
        utterance.rate = min(max(utterance.rate, AVSpeechUtteranceMinimumSpeechRate), AVSpeechUtteranceMaximumSpeechRate)
        let lang = Bundle.main.preferredLocalizations.first ?? Locale.current.identifier
        utterance.voice = AVSpeechSynthesisVoice(language: lang) ?? AVSpeechSynthesisVoice(language: Locale.current.identifier)
        return utterance
    }
}
