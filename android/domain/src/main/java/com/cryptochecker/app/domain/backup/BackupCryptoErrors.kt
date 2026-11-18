package com.cryptochecker.app.domain.backup

import java.security.GeneralSecurityException

/** Passwort falsch oder Datei verändert (GCM-Tag passt nicht, auch bei verändertem Kopf). */
class WrongPasswordException : GeneralSecurityException("Wrong password or damaged file")

/** Zu wenig Arbeitsspeicher für Argon2id (m KiB): klare Meldung statt Absturz, kein Rückfall auf ein schwächeres Verfahren. */
class NotEnoughMemoryException : GeneralSecurityException("Not enough memory for key derivation")
