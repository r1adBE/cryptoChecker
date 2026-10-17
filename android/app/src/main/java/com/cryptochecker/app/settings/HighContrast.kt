package com.cryptochecker.app.settings

import android.app.UiModeManager
import android.content.Context
import android.os.Build

/**
 * Hoher Kontrast wirkt, wenn die Einstellung an ist ODER das System mehr Kontrast
 * verlangt (ab Android 14: Kontrast-Regler ≥ 0.5). Auf älteren Versionen zählt
 * nur die Einstellung.
 */
object HighContrast {
    /** Ab diesem Wert von [UiModeManager.getContrast] gilt «mehr Kontrast». */
    private const val SYSTEM_THRESHOLD = 0.5f

    fun systemRequestsMore(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val manager = context.getSystemService(UiModeManager::class.java) ?: return false
        return runCatching { manager.contrast >= SYSTEM_THRESHOLD }.getOrDefault(false)
    }

    fun isEffective(context: Context, setting: Boolean): Boolean =
        setting || systemRequestsMore(context)
}
