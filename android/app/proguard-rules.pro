# Börsenklassen werden über ihren einfachen Klassennamen als Schlüssel geführt
# (Market.key = javaClass.simpleName). Umbenennen würde gespeicherte Watchlist-
# Einträge und Widgets unbrauchbar machen.
-keep class com.cryptochecker.marketdata.model.market.** { *; }

# Fehlerklassen: Ohne Meldungstext zeigt die App den einfachen Klassennamen (Bericht der
# Aktualisierung, Explorer, «Paar bearbeiten», Drosselung). Umbenannt stünde dort «a» oder «b».
# keepnames: nur die Namen bleiben, ungenutzte Klassen darf R8 weiterhin entfernen.
# (Das Modul marketdata wird nicht selbst verkleinert und hat keine consumer-Regeln: die Regel gilt hier.)
-keepnames class com.cryptochecker.marketdata.exceptions.** { *; }
-keepnames class com.cryptochecker.app.domain.exceptions.** { *; }

# Room-Entitäten
-keep class com.cryptochecker.app.data.local.model.** { *; }

# SQLCipher: Der native Teil ruft Java-Klassen und -Felder über ihren Namen auf (JNI) —
# umbenannt oder entfernt stürzte jedes Öffnen der Datenbank ab.
-keep class net.zetetic.** { *; }
-keep interface net.zetetic.** { *; }

# Enums, deren Namen in der Datenbank stehen
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Im Release ist kein Timber-Baum gepflanzt, die Aufrufe laufen also ohnehin
# ins Leere. R8 entfernt v/d/i damit samt ihrer Argumente vollständig.
# Timber 5 ist Kotlin: Kotlin-Aufrufer rufen die Instanzmethoden des Begleitobjekts
# (`Timber$Forest`) auf, Java-Aufrufer die statischen Weiterleitungen in `Timber` —
# eine Regel nur auf `public static` träfe die Kotlin-Aufrufe nie.
-assumenosideeffects class timber.log.Timber$Forest {
    public void v(...);
    public void d(...);
    public void i(...);
}
-assumenosideeffects class timber.log.Timber {
    public static void v(...);
    public static void d(...);
    public static void i(...);
}

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
