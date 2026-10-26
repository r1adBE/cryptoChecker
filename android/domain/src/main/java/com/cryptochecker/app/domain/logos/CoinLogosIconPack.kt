package com.cryptochecker.app.domain.logos

import com.cryptochecker.app.domain.logos.CoinLogos.IconSlice

// Mitgeliefertes Icon-Paket (Teil von [CoinLogos]): Name eines Icons und Inhaltsverzeichnis des Pakets
// ([CoinLogos.ICON_PACK_FILE]). Erweiterungsfunktionen, damit `CoinLogos.parseIconPack(…)` usw. bleiben; gleich in
// iOS `CoinLogos+IconPack.swift`.

private const val ICON_PACK_MAGIC = "CCIP1"

/** Höchstgrösse des Inhaltsverzeichnisses (Schutz gegen kaputte Dateien). */
private const val ICON_PACK_HEADER_MAX = 64 * 1024

/** Name eines Icons im Paket («btc»); null bei TradFi oder ungültigem Kürzel. */
fun CoinLogos.bundledIconName(symbol: String): String? {
    if (symbol.startsWith(TRADFI_PREFIX)) return null
    val key = normalize(symbol)
    if (fileName(key) == null) return null
    return key.lowercase()
}

/**
 * Inhaltsverzeichnis des Icon-Pakets: Zeile «CCIP1», je Icon eine Zeile «name länge», eine
 * Leerzeile, dann die PNG-Bytes in derselben Reihenfolge. Ergebnis: Name → [IconSlice] in [bytes];
 * null = kein Paket oder kaputt (falsche Kennung, ungültige Zeile, Daten zu kurz).
 */
fun CoinLogos.parseIconPack(bytes: ByteArray): Map<String, IconSlice>? {
    val limit = minOf(bytes.size, ICON_PACK_HEADER_MAX)
    var end = -1
    for (i in 0 until limit - 1) {
        if (bytes[i] == '\n'.code.toByte() && bytes[i + 1] == '\n'.code.toByte()) {
            end = i
            break
        }
    }
    if (end < 0) return null
    val lines = String(bytes, 0, end, Charsets.US_ASCII).split('\n')
    if (lines.firstOrNull() != ICON_PACK_MAGIC) return null
    val out = LinkedHashMap<String, IconSlice>()
    var offset = end + 2
    for (line in lines.drop(1)) {
        val parts = line.split(' ')
        if (parts.size != 2) return null
        val name = parts[0]
        val length = parts[1].toIntOrNull() ?: return null
        if (name.isEmpty() || name.any { it !in 'a'..'z' && it !in '0'..'9' } || length <= 0) return null
        if (length > bytes.size - offset) return null
        if (name !in out) out[name] = IconSlice(offset, length)
        offset += length
    }
    return out
}
