package com.cryptochecker.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.LruCache
import androidx.core.graphics.ColorUtils
import com.cryptochecker.app.data.CoinLogoRepository
import com.cryptochecker.app.domain.logos.CoinLogos
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Coin-Logos für die Widgets «Merkliste» und «Einzelner Coin» (Schalter «Coin-Logos in
 * Widgets»). Fertig gerundete, kleine Bilder: echtes Logo auf hellem bzw. dunklem Grund, sonst
 * der Kreis mit Initialen in der Akzentfarbe — nie ein kaputtes Bild. Laden mit Zeitlimit, damit
 * das Widget nie hängt. Die Bilder kommen nur vom Gerät (Abgleich aller Logos: CoinLogoSync).
 */
@Singleton
class WidgetCoinLogos @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: CoinLogoRepository,
) {
    /** Zuletzt gezeichnete Bilder (Widgets aktualisieren oft, die Logos ändern sich selten). */
    private val rendered = LruCache<String, Bitmap>(RENDERED_CACHE_SIZE)

    /** Kantenlänge in Pixeln: 24 dp, höchstens [MAX_PX] (Grösse eines Binder-Aufrufs). */
    val sizePx: Int
        get() = min((WIDGET_LOGO_DP * context.resources.displayMetrics.density).roundToInt(), MAX_PX)

    /**
     * Bilder für [symbols] (gleiches Symbol → dasselbe Bitmap, das System schickt es nur einmal).
     * Gibt es ein Logo nicht rechtzeitig, steht dort der Initialen-Kreis.
     */
    suspend fun bitmaps(symbols: Collection<String>, colors: WidgetColors): Map<String, Bitmap> = coroutineScope {
        val distinct = symbols.distinct()
        val px = sizePx
        if (distinct.isEmpty()) return@coroutineScope emptyMap()
        val logos = withTimeoutOrNull(LOAD_TIMEOUT_MILLIS) {
            distinct.map { symbol -> async { symbol to safeLogo(symbol) } }.awaitAll().toMap()
        } ?: distinct.associateWith { repository.cached(it) }
        distinct.associateWith { symbol ->
            val logo = logos[symbol]
            // Gleiches Logo, gleiche Farben und Grösse → das schon gezeichnete Bild
            val key = "$symbol|${colors.accentColor}|${colors.logoBackingColor}|$px|${logo?.let { System.identityHashCode(it) } ?: 0}"
            rendered.get(key) ?: render(symbol, logo, colors, px).also { rendered.put(key, it) }
        }
    }

    /**
     * Schlüssel des Logos für ein Paar: TradFi-Paare im eigenen Namensraum (nie das Logo eines
     * gleichnamigen Krypto-Tokens), sonst das Basis-Symbol.
     */
    fun logoKey(watch: com.cryptochecker.app.data.local.model.WatchEntity): String =
        repository.logoKey(watch.marketKey, watch.baseAsset, watch.quoteAsset, watch.contractType.name)

    /** Ein Bild für das Einzel-Widget; [logo] = false → Initialen (DEX-Pools). */
    suspend fun bitmap(symbol: String, colors: WidgetColors, logo: Boolean = true): Bitmap =
        if (logo) bitmaps(listOf(symbol), colors).getValue(symbol) else initials(symbol, colors)

    /** Nur der Initialen-Kreis, ohne Laden. */
    fun initials(symbol: String, colors: WidgetColors): Bitmap {
        val px = sizePx
        val key = "$symbol|${colors.accentColor}|initials|$px"
        return rendered.get(key) ?: render(symbol, null, colors, px).also { rendered.put(key, it) }
    }

    private suspend fun safeLogo(symbol: String): Bitmap? = try {
        repository.logo(symbol)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun render(symbol: String, logo: Bitmap?, colors: WidgetColors, px: Int): Bitmap {
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val r = px / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (logo != null && logo.width > 0 && logo.height > 0) {
            paint.color = colors.logoBackingColor
            canvas.drawCircle(r, r, r, paint)
            canvas.clipPath(Path().apply { addCircle(r, r, r, Path.Direction.CW) })
            // Seitenverhältnis behalten, mittig
            val scale = min(px.toFloat() / logo.width, px.toFloat() / logo.height)
            val w = logo.width * scale
            val h = logo.height * scale
            canvas.drawBitmap(logo, null, RectF((px - w) / 2f, (px - h) / 2f, (px + w) / 2f, (px + h) / 2f), paint)
        } else {
            paint.color = ColorUtils.setAlphaComponent(colors.accentColor, FALLBACK_ALPHA)
            canvas.drawCircle(r, r, r, paint)
            val initials = CoinLogos.initials(symbol)
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colors.accentColor
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
                textSize = px * if (initials.length <= 3) 0.33f else 0.275f
            }
            val baseline = r - (text.descent() + text.ascent()) / 2f
            canvas.drawText(initials, r, baseline, text)
        }
        return out
    }

    private companion object {
        const val WIDGET_LOGO_DP = 24
        const val MAX_PX = 48
        const val LOAD_TIMEOUT_MILLIS = 6_000L
        const val RENDERED_CACHE_SIZE = 96

        /** 14 % wie der Kreis in der App. */
        const val FALLBACK_ALPHA = 36
    }
}
