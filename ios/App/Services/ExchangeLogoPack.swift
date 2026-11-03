import UIKit

/// Mitgelieferte Börsen-Logos (`ExchangeLogos.packName` in `App/Resources`): Paket einmal gemappt, jedes Logo einmal
/// entschlüsselt und behalten (rund 70 kleine Bilder). Kein Abruf. Wie Android `ExchangeLogoPack`.
@MainActor
enum ExchangeLogoPack {
    /// Gemappte Datei und Inhaltsverzeichnis; nil = Paket fehlt oder unlesbar.
    private static let pack: (bytes: Data, index: [String: CoinLogos.IconSlice])? = {
        guard let url = Bundle.main.url(forResource: ExchangeLogos.packName, withExtension: ExchangeLogos.packExtension),
              let data = try? Data(contentsOf: url, options: .mappedIfSafe),
              let index = CoinLogos.parseIconPack(data)
        else { return nil }
        return (data, index)
    }()

    private static var images: [String: UIImage] = [:]

    /// Logo `name` aus dem Paket; nil = nicht im Paket.
    static func image(_ name: String) -> UIImage? {
        if let image = images[name] { return image }
        guard let pack, let slice = pack.index[name] else { return nil }
        let start = pack.bytes.startIndex + slice.offset
        guard let image = UIImage(data: pack.bytes.subdata(in: start..<(start + slice.length))) else { return nil }
        images[name] = image
        return image
    }
}
