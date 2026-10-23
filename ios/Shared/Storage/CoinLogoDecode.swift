import Foundation
import ImageIO
import UIKit

extension CoinLogos {
    /// Höchstens so viele Pixel (Breite × Höhe laut Bildkopf) hat ein Logo-Quellbild; grössere werden gar nicht erst
    /// dekodiert. Die 512-KB-Grenze der Übertragung schützt nicht: Ein stark komprimiertes PNG von wenigen hundert KB
    /// kann 20'000 × 20'000 Pixel haben (voll dekodiert rund 1,6 GB). Wie Android `CoinLogos.MAX_SOURCE_PIXELS`.
    static let maxSourcePixels: Int64 = 4096 * 4096

    /// Quellbild zu gross (oder ohne gültige Masse)?
    static func sourceTooLarge(width: Int, height: Int) -> Bool {
        width <= 0 || height <= 0 || Int64(width) * Int64(height) > maxSourcePixels
    }
}

/// Bild-Bytes der Coin-Logos (Teil von `CoinLogoStore`): Masse aus dem Bildkopf lesen und nur verkleinert dekodieren
/// (`CGImageSourceCreateThumbnailAtIndex`), nie das volle Quellbild — wie Android `LogoImageCodec` mit
/// `inJustDecodeBounds`/`inSampleSize`.
enum CoinLogoDecode {
    /// Breite und Höhe in Pixeln laut Bildkopf, ohne zu dekodieren; nil = kein lesbares Bild.
    static func pixelSize(_ data: Data) -> (width: Int, height: Int)? {
        let options = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithData(data as CFData, options),
              CGImageSourceGetCount(source) > 0,
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, options) as? [CFString: Any],
              let width = (properties[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue,
              let height = (properties[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue,
              width > 0, height > 0
        else { return nil }
        return (width, height)
    }

    /// Zu speichernde PNG-Daten: schon ein PNG in passender Grösse (CoinGecko «small», viele Binance-Logos) →
    /// unverändert; zu grosses Quellbild (`CoinLogos.sourceTooLarge`) → nil; sonst beim Dekodieren auf
    /// `CoinLogos.storedPixels` verkleinert (Seitenverhältnis bleibt) als PNG.
    static func pngData(_ data: Data) -> Data? {
        guard let size = pixelSize(data), !CoinLogos.sourceTooLarge(width: size.width, height: size.height)
        else { return nil }
        if isPNG(data), max(size.width, size.height) <= CoinLogos.storedPixels { return data }
        guard let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary)
        else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: CoinLogos.storedPixels,
        ]
        guard let thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        return UIImage(cgImage: thumbnail).pngData()
    }

    /// Anfang jeder PNG-Datei.
    private static let pngSignature: [UInt8] = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]

    static func isPNG(_ data: Data) -> Bool {
        data.count > pngSignature.count && Array(data.prefix(pngSignature.count)) == pngSignature
    }
}
