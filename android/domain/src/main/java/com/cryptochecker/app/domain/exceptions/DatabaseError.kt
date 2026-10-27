package com.cryptochecker.app.domain.exceptions

/** Speichern in der lokalen Datenbank gescheitert (z. B. SQLiteException). */
class DatabaseError(cause: Exception) : MarketError(cause)
