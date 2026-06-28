-keep class com.streamverse.tv.ui.browse.ChannelPresenter { *; }

# NewPipeExtractor: keep Mozilla Rhino used for JavaScript deciphering
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**

# Strip verbose debug logging from release builds.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}
