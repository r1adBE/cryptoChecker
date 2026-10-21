// Bild-Bytes der Coin-Logos (Teil von `LogoImageStore`): PNG prüfen, verkleinern, dekodieren, sicher schreiben.
// Ohne Zustand.
package com.cryptochecker.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.cryptochecker.app.domain.logos.CoinLogos
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/** PNG-Bytes und Bilder der Coin-Logos für [LogoImageStore]. */
internal object LogoImageCodec {

    /** Anfang jeder PNG-Datei. */
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /**
     * Zu speichernde PNG-Bytes: schon ein PNG in passender Grösse (CoinGecko «small», viele
     * Binance-Logos) → unverändert, ohne Umrechnen (spart Zeit beim Laden aller Logos); sonst
     * verkleinert auf [CoinLogos.STORED_PX] und als PNG.
     */
    fun pngBytes(bytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Dekompressions-Bombe (riesige Masse in wenigen Bytes): gar nicht erst dekodieren, wie iOS
        if (CoinLogos.sourceTooLarge(bounds.outWidth, bounds.outHeight)) return null
        if (isPng(bytes) && max(bounds.outWidth, bounds.outHeight) <= CoinLogos.STORED_PX) return bytes
        val bitmap = decodeScaled(bytes, bounds) ?: return null
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    fun isPng(bytes: ByteArray): Boolean =
        bytes.size > PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }

    /** Verkleinert beim Dekodieren (inSampleSize) und danach genau auf [CoinLogos.STORED_PX]. */
    fun decodeScaled(bytes: ByteArray, bounds: BitmapFactory.Options): Bitmap? {
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= CoinLogos.STORED_PX) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val longest = max(decoded.width, decoded.height)
        if (longest <= CoinLogos.STORED_PX) return decoded
        val scale = CoinLogos.STORED_PX.toFloat() / longest
        return Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true,
        ).also { if (it !== decoded) decoded.recycle() }
    }

    fun decodeFile(file: File): Bitmap? = runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()

    /** Erst in eine Hilfsdatei, dann umbenennen (nie ein halbes Bild). */
    fun writeBytes(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        runCatching {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) tmp.delete()
        }.onFailure { tmp.delete() }
    }
}
