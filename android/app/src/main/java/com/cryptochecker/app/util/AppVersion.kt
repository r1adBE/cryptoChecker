package com.cryptochecker.app.util

import com.cryptochecker.app.BuildConfig

/** Version samt Build-Nummer, z. B. «16.2.2 (234)» — wie iOS `AboutContent.versionText` und die Uhren. */
object AppVersion {
    val text: String get() = format(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

    /** Ohne Endung «-debug» der Testbuilds: Die Nummer gleicht der Release-Linie (wie iOS). */
    fun format(name: String, build: Int): String = "${name.removeSuffix("-debug")} ($build)"
}
