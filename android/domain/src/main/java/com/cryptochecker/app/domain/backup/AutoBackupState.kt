package com.cryptochecker.app.domain.backup

/** Zustand der automatischen Sicherung (nur dieses Gerät). */
data class AutoBackupState(
    val enabled: Boolean = false,
    /** Ordner (Storage Access Framework, dauerhafte Freigabe); null = keiner gewählt. */
    val folderUri: String? = null,
    /** Anzeigename des Ordners. */
    val folderName: String? = null,
    val lastSuccessAt: Long = 0L,
    val lastAttemptAt: Long = 0L,
    /** Letzter Versuch gescheitert (bis zur nächsten gelungenen Sicherung). */
    val lastFailed: Boolean = false,
)
