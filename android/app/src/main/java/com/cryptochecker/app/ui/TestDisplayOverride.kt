package com.cryptochecker.app.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.util.Locale

/**
 * Nur für instrumentierte Tests (Barrierefreiheits-Audit mit grosser Schrift und auf Arabisch): Schriftgrösse und Sprache
 * allein für die Oberfläche der App — nie systemweit und ohne Konfigurationswechsel der Activity. So bleiben die übrigen
 * Tests im selben Lauf unberührt (ein Weg über `settings put system font_scale` und die App-Sprache stellte
 * das Gerät nicht sauber zurück). Im Betrieb immer leer: [Provide] gibt den Inhalt unverändert weiter.
 */
object TestDisplayOverride {
    /** Schriftfaktor (z. B. 2.0); 0 = unverändert. */
    @VisibleForTesting
    @Volatile
    var fontScale: Float = 0f

    /** Sprach-Tag (z. B. «ar»); leer = unverändert. */
    @VisibleForTesting
    @Volatile
    var languageTag: String = ""

    /** Zurück auf «unverändert» (nach jedem Test). */
    @VisibleForTesting
    fun reset() {
        fontScale = 0f
        languageTag = ""
    }

    @Composable
    fun Provide(content: @Composable () -> Unit) {
        val scale = fontScale
        val tag = languageTag
        if (scale <= 0f && tag.isEmpty()) {
            content()
            return
        }
        val context = LocalContext.current
        val density = LocalDensity.current
        val current = LocalConfiguration.current
        val configured = remember(context, current, scale, tag) {
            val config = Configuration(current)
            if (scale > 0f) config.fontScale = scale
            if (tag.isNotEmpty()) {
                val locale = Locale.forLanguageTag(tag)
                config.setLocales(LocaleList(locale))
                config.setLayoutDirection(locale)
            }
            // Activity bleibt darunter (findActivity, Berechtigungen); nur die Ressourcen kommen aus der Konfiguration
            config to ResourcesContext(context, context.createConfigurationContext(config).resources)
        }
        val (config, localized) = configured
        val direction = if (config.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL) {
            LayoutDirection.Rtl
        } else {
            LayoutDirection.Ltr
        }
        CompositionLocalProvider(
            LocalConfiguration provides config,
            LocalContext provides localized,
            LocalDensity provides Density(density.density, if (scale > 0f) scale else density.fontScale),
            LocalLayoutDirection provides direction,
            content = content,
        )
    }
}

/** [base] mit anderen Ressourcen [res] (Sprache, Schrift) — alles andere wie [base]. */
private class ResourcesContext(base: Context, private val res: Resources) : ContextWrapper(base) {
    override fun getResources(): Resources = res

    override fun getAssets(): AssetManager = res.assets
}
