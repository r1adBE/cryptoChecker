# Börsenklassen: Market.key ist ein fester Text je Börse (früher der Klassenname). Die Regel
# bleibt als zweite Sicherung neben marketdata/consumer-rules.pro, damit Stacktraces und ältere
# Annahmen auf Klassennamen weiter stimmen.
-keep class com.cryptochecker.marketdata.model.market.** { *; }

# Fehlerklassen: Ohne Meldungstext zeigt die App den einfachen Klassennamen (Bericht der
# Aktualisierung, Explorer, «Paar bearbeiten», Drosselung). Umbenannt stünde dort «a» oder «b».
# keepnames: nur die Namen bleiben, ungenutzte Klassen darf R8 weiterhin entfernen.
# (Das Modul marketdata bringt dieselben Regeln in consumer-rules.pro mit; hier doppelt zur Sicherheit.)
-keepnames class com.cryptochecker.marketdata.exceptions.** { *; }
-keepnames class com.cryptochecker.app.domain.exceptions.** { *; }

# Typsichere Navigationsziele (@Serializable, ScreenRoute): Navigation erzeugt Routen und Argumente über die
# Serializer dieser Klassen. kotlinx.serialization bringt eigene Regeln mit; diese hier ist die zweite Sicherung.
-keep class com.cryptochecker.app.ui.navigation.ScreenRoute$* { *; }

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
