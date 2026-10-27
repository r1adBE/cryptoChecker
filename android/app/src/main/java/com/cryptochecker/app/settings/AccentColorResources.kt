package com.cryptochecker.app.settings

import com.cryptochecker.app.R

/** Name der Akzentfarbe in der Farbauswahl und der Einstellungs-Suche ([AccentColor]). */
val AccentColor.labelRes: Int
    get() = when (this) {
        AccentColor.ORANGE -> R.string.accent_orange
        AccentColor.RED -> R.string.accent_red
        AccentColor.BLUE -> R.string.accent_blue
        AccentColor.GREEN -> R.string.accent_green
        AccentColor.MARRS_GREEN -> R.string.accent_marrs_green
    }

/**
 * Logo in der App je Farbe in zwei Fassungen: schwarzer Grund ([dark]) und weisser Grund (hell).
 */
fun AccentColor.logoRes(dark: Boolean): Int = when (this) {
    AccentColor.ORANGE -> if (dark) R.drawable.ic_app_logo_orange_dark else R.drawable.ic_app_logo_orange_light
    AccentColor.RED -> if (dark) R.drawable.ic_app_logo_red_dark else R.drawable.ic_app_logo_red_light
    AccentColor.BLUE -> if (dark) R.drawable.ic_app_logo_blue_dark else R.drawable.ic_app_logo_blue_light
    AccentColor.GREEN -> if (dark) R.drawable.ic_app_logo_green_dark else R.drawable.ic_app_logo_green_light
    AccentColor.MARRS_GREEN -> if (dark) R.drawable.ic_app_logo_marrs_green_dark else R.drawable.ic_app_logo_marrs_green_light
}
