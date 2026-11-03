package com.cryptochecker.app.ui.features.explorer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.domain.logos.parseIconPack
import com.cryptochecker.app.domain.market.ExchangeLogos
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Mitgelieferte Börsen-Logos ([ExchangeLogos.PACK_FILE] in `assets/`): Paket einmal gelesen, jedes Logo einmal
 * entschlüsselt und behalten (rund 70 kleine Bilder). Kein Abruf. Wie iOS `ExchangeLogoPack`.
 */
internal object ExchangeLogoPack {

    @Volatile private var pack: Pair<ByteArray, Map<String, CoinLogos.IconSlice>>? = null
    @Volatile private var unreadable = false
    private val bitmaps = ConcurrentHashMap<String, Bitmap>()

    /** Schon entschlüsseltes Logo [name] oder null (dann [load]). */
    fun cached(name: String): Bitmap? = bitmaps[name]

    /** Logo [name] aus dem Paket (Dateizugriff: nicht auf dem Hauptthread aufrufen); null = nicht im Paket. */
    fun load(context: Context, name: String): Bitmap? {
        bitmaps[name]?.let { return it }
        val (bytes, index) = pack(context) ?: return null
        val slice = index[name] ?: return null
        val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, slice.offset, slice.length) }.getOrNull()
            ?: return null
        bitmaps[name] = bitmap
        return bitmap
    }

    private fun pack(context: Context): Pair<ByteArray, Map<String, CoinLogos.IconSlice>>? {
        pack?.let { return it }
        if (unreadable) return null
        synchronized(this) {
            pack?.let { return it }
            val bytes = runCatching { context.assets.open(ExchangeLogos.PACK_FILE).use { it.readBytes() } }
                .onFailure { Timber.w("Börsen-Logos nicht lesbar: %s", it.message) }
                .getOrNull()
            val parsed = bytes?.let { b -> CoinLogos.parseIconPack(b)?.let { b to it } }
            if (parsed == null) unreadable = true
            pack = parsed
            return parsed
        }
    }
}
