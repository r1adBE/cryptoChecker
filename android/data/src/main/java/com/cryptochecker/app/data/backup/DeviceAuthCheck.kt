package com.cryptochecker.app.data.backup

/**
 * Kann das Gerät entsperren (Biometrie oder eingerichtete Displaysperre)? Die Sicherung braucht es beim Wiederherstellen
 * der Portfolio-Sperre ([BackupSettingsCodec]); umgesetzt in der App (`AppLockAuth`, Modul `app`).
 */
fun interface DeviceAuthCheck {
    fun canAuthenticate(): Boolean
}
