package com.cryptochecker.app.settings

import com.cryptochecker.app.R

/**
 * Akzentfarbe der App. Bestimmt Farbschema, Widget-Akzent, Benachrichtigungs-
 * farbe und das App-Icon auf dem Startbildschirm.
 *
 * @param seed Grundfarbe, aus der das Farbschema abgeleitet ist.
 * Logo und App-Icon gibt es je Farbe in zwei Fassungen: schwarzer Grund
 * (Dunkel) und weisser Grund (Hell). Für das Icon ist jede Kombination ein
 * eigener activity-alias im Manifest; aktiv ist immer genau einer.
 */
enum class AccentColor(
    val seed: Int,
    val labelRes: Int,
    private val logoDark: Int,
    private val logoLight: Int,
    private val aliasBase: String,
) {
    /** Wie Claude-Orange. Standard bei der ersten Installation. */
    ORANGE(0xFFDD6F48.toInt(), R.string.accent_orange, R.drawable.ic_app_logo_orange_dark, R.drawable.ic_app_logo_orange_light, "Orange"),
    RED(0xFFE8414D.toInt(), R.string.accent_red, R.drawable.ic_app_logo_red_dark, R.drawable.ic_app_logo_red_light, "Red"),
    BLUE(0xFF3B78F0.toInt(), R.string.accent_blue, R.drawable.ic_app_logo_blue_dark, R.drawable.ic_app_logo_blue_light, "Blue"),
    GREEN(0xFF22A96C.toInt(), R.string.accent_green, R.drawable.ic_app_logo_green_dark, R.drawable.ic_app_logo_green_light, "Green"),
    /** Marrs Green (#4BACA5), «Lieblingsfarbe der Welt» der G.F-Smith-Umfrage 2017. Am Ende angehängt: gespeicherte Namen bleiben gültig. */
    MARRS_GREEN(0xFF4BACA5.toInt(), R.string.accent_marrs_green, R.drawable.ic_app_logo_marrs_green_dark, R.drawable.ic_app_logo_marrs_green_light, "MarrsGreen"),
    ;

    fun logoRes(dark: Boolean): Int = if (dark) logoDark else logoLight

    /** Voller Klassenname des passenden activity-alias, z. B. …LauncherOrange / …LauncherOrangeLight. */
    fun launcherAlias(dark: Boolean): String =
        "com.cryptochecker.app.Launcher$aliasBase" + if (dark) "" else "Light"

    companion object {
        val DEFAULT = ORANGE

        fun fromName(name: String?): AccentColor =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
