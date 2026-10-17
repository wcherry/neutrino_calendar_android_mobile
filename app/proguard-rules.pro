# kotlinx.serialization: keep generated serializers for the DTOs core decodes.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.neutrino.** {
    *** Companion;
}
-keepclasseswithmembers class com.neutrino.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
# Rhino runs the date-holidays bundle; it reflects over its own classes.
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
