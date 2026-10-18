package com.cryptochecker.app.settings

import com.cryptochecker.app.R

/**
 * Akzentfarbe der App. Bestimmt Farbschema, Widget-Akzent, Benachrichtigungs-
 * farbe und das App-Icon auf dem Startbildschirm.
 *
 * @param seed Grundfarbe, aus der das Farbschema abgeleitet ist.
 * Das Logo in der App gibt es je Farbe in zwei Fassungen: schwarzer Grund
 * (Dunkel) und weisser Grund (Hell). Das App-Icon hängt nur an der Farbe: je
 * Farbe ein activity-alias im Manifest (weisse Glocke auf dem Farbverlauf, auf
 * hellem wie dunklem Startbildschirm lesbar); aktiv ist immer genau einer.
 */
enum class AccentColor(
    val seed: Int,
    val labelRes: Int,
    private val logoDark: Int,
    private val logoLight: Int,
    private val aliasBase: String,
) {
    /** Orange #ED835E (wie App-Icon; hell als dunklere Stufe #A64827). Standard bei der ersten Installation. */
    ORANGE(0xFFED835E.toInt(), R.string.accent_orange, R.drawable.ic_app_logo_orange_dark, R.drawable.ic_app_logo_orange_light, "Orange"),
    RED(0xFFE8414D.toInt(), R.string.accent_red, R.drawable.ic_app_logo_red_dark, R.drawable.ic_app_logo_red_light, "Red"),
    BLUE(0xFF3B78F0.toInt(), R.string.accent_blue, R.drawable.ic_app_logo_blue_dark, R.drawable.ic_app_logo_blue_light, "Blue"),
    GREEN(0xFF22A96C.toInt(), R.string.accent_green, R.drawable.ic_app_logo_green_dark, R.drawable.ic_app_logo_green_light, "Green"),
    /** Marrs Green (#4BACA5), «Lieblingsfarbe der Welt» der G.F-Smith-Umfrage 2017. Am Ende angehängt: gespeicherte Namen bleiben gültig. */
    MARRS_GREEN(0xFF4BACA5.toInt(), R.string.accent_marrs_green, R.drawable.ic_app_logo_marrs_green_dark, R.drawable.ic_app_logo_marrs_green_light, "MarrsGreen"),
    ;

    fun logoRes(dark: Boolean): Int = if (dark) logoDark else logoLight

    /**
     * Akzent als Schrift/Knopf (primary des App-Farbschemas und der Widgets, `WidgetColors`),
     * hell die dunklere Stufe (AA bis #E2E2E2). Bezug für den Abstand der Kursfarben
     * ([PriceColorScheme]). Wie iOS `primaryHex(dark:)`.
     */
    fun primary(dark: Boolean): Int = when (this) {
        ORANGE -> if (dark) 0xFFED835E.toInt() else 0xFFA64827.toInt()
        RED -> if (dark) 0xFFFF7173.toInt() else 0xFFBE2B38.toInt()
        BLUE -> if (dark) 0xFF73A3FC.toInt() else 0xFF2B5FC8.toInt()
        GREEN -> if (dark) 0xFF4ABE83.toInt() else 0xFF107348.toInt()
        MARRS_GREEN -> if (dark) 0xFF5FB8B1.toInt() else 0xFF22706B.toInt()
    }

    /**
     * Voller Klassenname des activity-alias dieser Farbe, z. B. …LauncherOrange. Hängt nicht
     * an Hell/Dunkel: Ein Wechsel des Alias entfernt bei manchen Launchern die Verknüpfung
     * auf dem Startbildschirm — er geschieht nur noch, wenn der Nutzer die Farbe wechselt.
     */
    fun launcherAlias(): String = "$ALIAS_PREFIX$aliasBase"

    companion object {
        val DEFAULT = ORANGE

        fun fromName(name: String?): AccentColor =
            entries.firstOrNull { it.name == name } ?: DEFAULT

        private const val ALIAS_PREFIX = "com.cryptochecker.app.Launcher"

        /** Alle Launcher-Aliase im Manifest (je Farbe einer). */
        fun allLauncherAliases(): List<String> = entries.map { it.launcherAlias() }
    }
}
