# Börsenklassen werden über ihren einfachen Klassennamen als Schlüssel geführt
# (Market.key = javaClass.simpleName). Umbenennen würde gespeicherte Watchlist-
# Einträge und Widgets unbrauchbar machen.
-keep class com.cryptochecker.marketdata.model.market.** { *; }

# Room-Entitäten
-keep class com.cryptochecker.app.data.local.model.** { *; }

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
# ins Leere. R8 entfernt sie damit samt ihrer Argumente vollständig.
-assumenosideeffects class timber.log.Timber* {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
