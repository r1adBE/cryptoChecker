# Regeln für jede App, die marketdata einbindet und mit R8 verkleinert.

# Börsenklassen: kein keep – Market.key ist ein fester Text je Börse, kein Zugriff über Klassennamen;
# Stacktraces lassen sich über mapping.txt zurückübersetzen.

# Fehlerklassen: Ohne Meldungstext zeigt die App den einfachen Klassennamen. Nur die Namen
# bleiben, ungenutzte Klassen darf R8 weiterhin entfernen.
-keepnames class com.cryptochecker.marketdata.exceptions.** { *; }
