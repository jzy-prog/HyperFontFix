#!/bin/bash
set -e
cd /d/Projects/hyperfontfix/module || exit 1
JH=/d/Projects/hyperfontfix/tools/jdk/jdk-21.0.12.1+1/bin
AAPT2='D:\Projects\hyperfontfix\tools\aapt2\aapt2.exe'
JAVA=/d/Projects/hyperfontfix/tools/jdk/jdk-21.0.12.1+1/bin/java.exe
AJ='D:\Projects\hyperfontfix\tools\sdk\android-34\android.jar'
CPX='D:\Projects\hyperfontfix\tools\jars\xposed-api-82.jar;D:\Projects\hyperfontfix\refs\libxposed\api-102.0.0-classes.jar;D:\Projects\hyperfontfix\tools\jars\stubs.jar'

mkdir -p build/meta/META-INF/xposed
printf 'minApiVersion=101\ntargetApiVersion=101\nstaticScope=true\n' > build/meta/META-INF/xposed/module.prop
printf 'hf.fontfix.Entry\n' > build/meta/META-INF/xposed/java_init.list
printf 'com.android.systemui\ncom.miui.aod\n' > build/meta/META-INF/xposed/scope.list

cat > keep3.pro <<'KEEPEOF'
-keep class hf.fontfix.** { *; }
-dontwarn de.robv.android.xposed.**
-dontwarn io.github.libxposed.**
-dontwarn androidx.annotation.**
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
-adaptresourcefilecontents META-INF/xposed/java_init.list
KEEPEOF

# rebuild stubs at target 8 (r8 8.1 rejects major 65)
rm -rf /d/Projects/hyperfontfix/tools/stubs/out
/d/Projects/hyperfontfix/tools/jdk/jdk-21.0.12.1+1/bin/javac.exe -source 8 -target 8 -nowarn -d /d/Projects/hyperfontfix/tools/stubs/out $(find /d/Projects/hyperfontfix/tools/stubs -name "*.java") 2>/dev/null
/d/Projects/hyperfontfix/tools/jdk/jdk-21.0.12.1+1/bin/jar.exe cf /d/Projects/hyperfontfix/tools/jars/stubs.jar -C /d/Projects/hyperfontfix/tools/stubs/out .

echo "probe libxposed jar version: 61 expected; r8 8.1 max is 61 (java17)"

rm -rf build/classes build/dexout.zip
mkdir -p build/classes
"$JH/javac.exe" -source 8 -target 8 -nowarn -bootclasspath "$AJ" -classpath "$CPX" -d build/classes $(find src -name "*.java")
echo compiled

LXA='D:\Projects\hyperfontfix\refs\libxposed\api-102.0.0-classes.jar'
STUB='D:\Projects\hyperfontfix\tools\jars\stubs.jar'
"$JAVA" -cp 'D:\Projects\hyperfontfix\tools\jars\r8.jar' com.android.tools.r8.R8 --release --pg-conf keep3.pro --lib "$AJ" --classpath "D:\Projects\hyperfontfix\tools\jars\xposed-api-82.jar" --classpath "$LXA" --classpath "$STUB" --output build/dexout.zip $(find build/classes -name "*.class")
echo dexed

"$AAPT2" link --manifest AndroidManifest.xml -I "$AJ" "build\\obj\\values_arrays.arsc.flat" --min-sdk-version 26 --target-sdk-version 34 -o "build\\link\\base.apk"
echo linked

python - <<'PY'
import zipfile, shutil
shutil.copy("build/link/base.apk", "build/unsigned.apk")
dexbytes = zipfile.ZipFile("build/dexout.zip").read("classes.dex")
zin = zipfile.ZipFile("build/unsigned.apk")
zout = zipfile.ZipFile("build/final-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for it in zin.infolist():
    zout.writestr(it, zin.read(it.filename))
zout.writestr("classes.dex", dexbytes)
zout.writestr("assets/xposed_init", open("assets/xposed_init", "rb").read())
for name in ("module.prop", "java_init.list", "scope.list"):
    zout.writestr("META-INF/xposed/" + name, open("build/meta/META-INF/xposed/" + name, "rb").read())
zout.close()
print("staged")
PY

"$JAVA" -cp "D:\Projects\hyperfontfix\tools\jars\apksig-8.1.4.jar;D:\Projects\hyperfontfix\tools\signsrc\classes" SignApk "D:\Projects\hyperfontfix\module\build\final-unsigned.apk" "D:\Projects\hyperfontfix\HyperFontFix-v3.7.apk" "D:\Projects\hyperfontfix\tools\debug.keystore" hf android

python -X utf8 - <<'PY'
import zipfile
z = zipfile.ZipFile("D:/Projects/hyperfontfix/HyperFontFix-v3.7.apk")
print("entries:", z.namelist())
for n in ("META-INF/xposed/module.prop", "META-INF/xposed/java_init.list", "META-INF/xposed/scope.list", "assets/xposed_init"):
    print("---", n, "--->", repr(z.read(n).decode("utf-8")))
print("bytes", len(open("D:/Projects/hyperfontfix/HyperFontFix-v3.7.apk", "rb").read()))
PY
echo V3_DONE
