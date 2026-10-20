package com.cryptochecker.app.widget

import android.text.SpannableString
import android.text.Spanned
import android.text.style.TypefaceSpan
import android.widget.RemoteViews
import com.cryptochecker.app.util.LocaleNumbers
import java.util.Locale

/**
 * Schrift der Beträge in Widgets wie in der App (`amountNumbers`, Typography.kt): Rubik (in den Layouts
 * `@font/rubik_*`), schreibt die App-Sprache aber eigene Ziffern (Arabisch «٧٢», Persisch «۷۲»), die
 * Systemschrift — Rubik hat diese Ziffern nicht, ein Betrag mischte sonst zwei Schriften. RemoteViews kennen kein
 * `setTypeface`; ein [TypefaceSpan] mit Familienname ist parcelbar und überschreibt die Schrift des Layouts
 * (gleich breite Ziffern bleiben über `fontFeatureSettings` des Layouts).
 */
internal object WidgetAmountFont {
    /** Systemschrift halbfett-nah (Rubik SemiBold/Medium der Layouts). */
    const val SYSTEM_FAMILY = "sans-serif-medium"

    /** Systemschrift statt Rubik? Gleiche Regel wie `TextStyle.amountNumbers()` der App. */
    fun usesSystemFont(locale: Locale = LocaleNumbers.appLocale): Boolean = !LocaleNumbers.hasLatinDigits(locale)

    /** [text] unverändert oder ganz in der Systemschrift; bestehende Spans (kleine Währung, Grau) bleiben. */
    fun apply(text: CharSequence, systemFont: Boolean = usesSystemFont()): CharSequence {
        if (!systemFont || text.isEmpty()) return text
        return SpannableString(text).apply {
            setSpan(TypefaceSpan(SYSTEM_FAMILY), 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }
    }
}

/** Betrag setzen (Kurs, Wert, Veränderung) mit der Schrift-Regel von [WidgetAmountFont]. */
internal fun RemoteViews.setAmountText(viewId: Int, text: CharSequence) =
    setTextViewText(viewId, WidgetAmountFont.apply(text))
