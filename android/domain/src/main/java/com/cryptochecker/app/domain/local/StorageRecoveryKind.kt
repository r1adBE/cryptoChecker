package com.cryptochecker.app.domain.local

/** Hinweis zum Zustand der gespeicherten Daten (vorgemerkt von `StorageRecoveryNotice`, gezeigt über der ganzen App). */
enum class StorageRecoveryKind {
    SET_ASIDE,
    KEY_RESTORED,
    RESTORE_ROLLED_BACK,
    RESTORE_INCOMPLETE,
    SYSTEM_BACKUP_NOT_IMPORTED,
    SETTINGS_RESET,
}
