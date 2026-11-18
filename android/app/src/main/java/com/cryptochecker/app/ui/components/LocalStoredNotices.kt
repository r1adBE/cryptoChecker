package com.cryptochecker.app.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import com.cryptochecker.app.domain.local.StoredNotices

/** Vermerke dieses Geräts für einzelne Bausteine (einmal in der MainActivity gesetzt); ohne: Baustein zeigt nichts. */
val LocalStoredNotices = staticCompositionLocalOf<StoredNotices?> { null }
