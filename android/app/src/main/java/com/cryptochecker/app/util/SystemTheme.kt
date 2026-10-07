package com.cryptochecker.app.util

import android.content.res.Configuration
import android.content.res.Resources

/**
 * Hell/Dunkel des Systems — unabhängig vom Modus, den die App für ihre
 * eigenen Fenster gesetzt hat. Für Widgets bei «Wie das System».
 */
object SystemTheme {
    fun isDark(): Boolean =
        (Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
}
