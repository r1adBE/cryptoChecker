import UIKit
import XCTest
@testable import CryptoChecker

/// Logo-Bilder nur verkleinert dekodieren, Dekompressions-Bomben (riesige Masse in wenigen Bytes) gar nicht
/// (`CoinLogoDecode`, `CoinLogos.maxSourcePixels`). Wie Android `LogoImageCodec`.
final class CoinLogoDecodeTests: XCTestCase {

    /// CRC-32 (PNG-Chunks).
    private func crc32(_ bytes: [UInt8]) -> UInt32 {
        var crc: UInt32 = 0xFFFF_FFFF
        for byte in bytes {
            crc ^= UInt32(byte)
            for _ in 0..<8 { crc = (crc & 1) != 0 ? (crc >> 1) ^ 0xEDB8_8320 : crc >> 1 }
        }
        return ~crc
    }

    private func bigEndian(_ value: UInt32) -> [UInt8] {
        [UInt8(value >> 24 & 0xFF), UInt8(value >> 16 & 0xFF), UInt8(value >> 8 & 0xFF), UInt8(value & 0xFF)]
    }

    private func chunk(_ type: String, _ body: [UInt8]) -> [UInt8] {
        let typed = Array(type.utf8) + body
        return bigEndian(UInt32(body.count)) + typed + bigEndian(crc32(typed))
    }

    /// Winziges PNG, dessen Kopf `side` × `side` Pixel ankündigt (Inhalt unvollständig).
    private func bombPNG(side: UInt32) -> Data {
        let signature: [UInt8] = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]
        let header = bigEndian(side) + bigEndian(side) + [8, 6, 0, 0, 0]
        // zlib-Strom «leer, gespeichert» — reicht dem Dekoder nie für alle Zeilen
        let idat: [UInt8] = [0x78, 0x9C, 0x63, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01]
        return Data(signature + chunk("IHDR", header) + chunk("IDAT", idat) + chunk("IEND", []))
    }

    private func renderedPNG(side: CGFloat) -> Data {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: format).image { context in
            UIColor.orange.setFill()
            context.fill(CGRect(x: 0, y: 0, width: side, height: side))
        }
        return image.pngData()!
    }

    func testPngMitDreissigtausendPixelKanteWirdAbgelehnt() {
        let data = bombPNG(side: 30_000)
        XCTAssertLessThan(data.count, 100)
        if let size = CoinLogoDecode.pixelSize(data) {
            XCTAssertEqual(size.width, 30_000)
            XCTAssertEqual(size.height, 30_000)
        }
        XCTAssertNil(CoinLogoDecode.pngData(data))
    }

    func testGrenzeDerQuellpixel() {
        XCTAssertFalse(CoinLogos.sourceTooLarge(width: 4096, height: 4096))
        XCTAssertTrue(CoinLogos.sourceTooLarge(width: 4097, height: 4096))
        XCTAssertTrue(CoinLogos.sourceTooLarge(width: 30_000, height: 30_000))
        XCTAssertTrue(CoinLogos.sourceTooLarge(width: 0, height: 10))
    }

    func testKleinesPngBleibtUnveraendert() {
        let data = renderedPNG(side: 64)
        XCTAssertEqual(CoinLogoDecode.pngData(data), data)
    }

    func testGrossesBildWirdAufGespeicherteKanteVerkleinert() throws {
        let data = renderedPNG(side: 1024)
        let png = try XCTUnwrap(CoinLogoDecode.pngData(data))
        XCTAssertTrue(CoinLogoDecode.isPNG(png))
        let size = try XCTUnwrap(CoinLogoDecode.pixelSize(png))
        XCTAssertEqual(max(size.width, size.height), CoinLogos.storedPixels)
    }

    func testKeinBildKeineDaten() {
        XCTAssertNil(CoinLogoDecode.pixelSize(Data("kein Bild".utf8)))
        XCTAssertNil(CoinLogoDecode.pngData(Data("kein Bild".utf8)))
    }
}
