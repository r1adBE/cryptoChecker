# Regeln für jede App, die marketdata einbindet und mit R8 verkleinert.

# Börsenklassen: Market.key ist ein fester Text je Börse und hängt nicht mehr am Klassennamen.
# Die Klassen bleiben trotzdem unverändert: MarketsConfig erzeugt sie direkt, Stacktraces und
# Fehlermeldungen nennen sie beim Namen, und so stimmt der Schlüssel weiter mit dem Namen überein.
-keep class com.cryptochecker.marketdata.model.market.** { *; }

# Fehlerklassen: Ohne Meldungstext zeigt die App den einfachen Klassennamen. Nur die Namen
# bleiben, ungenutzte Klassen darf R8 weiterhin entfernen.
-keepnames class com.cryptochecker.marketdata.exceptions.** { *; }
