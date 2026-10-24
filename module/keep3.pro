-keep class hf.fontfix.** { *; }
-dontwarn de.robv.android.xposed.**
-dontwarn io.github.libxposed.**
-dontwarn androidx.annotation.**
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
-adaptresourcefilecontents META-INF/xposed/java_init.list
