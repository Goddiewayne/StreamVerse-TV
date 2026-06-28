-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep class com.streamverse.core.data.remote.** { *; }
-keep class com.streamverse.core.domain.model.** { *; }
-dontwarn okhttp3.**
-dontwarn org.jsoup.**
-dontwarn org.mozilla.javascript.tools.**

# NewPipeExtractor: keep Mozilla Rhino used for JavaScript deciphering
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter

# Rhino references desktop-only JRE classes unavailable on Android
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**

# Strip verbose debug logging from release builds (avoids leaking URLs/state and the call cost).
# Warnings and errors are kept for production crash diagnosis.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}
