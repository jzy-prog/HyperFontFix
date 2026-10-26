# Build HyperFontFix v4.0 without Gradle/Android Studio.
# stubs -> javac -> r8 -> aapt2 link -> zip stage -> apksig sign -> verify.
# Kept under its v4 name so the previously approved command line keeps working;
# the output version is now a parameter (default 1.3).
# Two params, ONE block: PowerShell allows param() only as the first statement.
#   -Ver  versionName to stamp into the APK
#   -Root optional project root; default = parent of tools/ so a clean clone builds.
param([string]$Ver = "1.3", [string]$Root = "")
$ErrorActionPreference = "stop"
$root   = if ($Root) { $Root } else { Split-Path -Parent $PSScriptRoot }
$mod    = "$root\module"
$jdk    = "$root\tools\jdk\jdk-21.0.12.1+1\bin"
$java   = "$jdk\java.exe"
$aapt2  = "$root\tools\aapt2\aapt2.exe"
$aj     = "$root\tools\sdk\android-34\android.jar"
$py     = if (Test-Path "D:\hclaw\python\python.exe") { "D:\hclaw\python\python.exe" }
         elseif (Get-Command python -ErrorAction SilentlyContinue) { (Get-Command python).Source }
         else { $null }
$xap    = "$root\tools\jars\xposed-api-82.jar"
$lxa    = "$root\refs\libxposed\api-102.0.0-classes.jar"
$stub   = "$root\tools\jars\stubs.jar"
$r8     = "$root\tools\jars\r8.jar"
$apksig = "$root\tools\jars\apksig-8.1.4.jar"
$signc  = "$root\tools\signsrc\classes"
$ks     = "$root\tools\debug.keystore"
$out    = "$root\HyperFontFix-v$Ver.apk"
$cp     = "$xap;$lxa;$stub"

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

New-Item -ItemType Directory -Force -Path "$mod\build\meta\META-INF\xposed" | Out-Null
Set-Content -NoNewline -Encoding ascii -Path "$mod\build\meta\META-INF\xposed\module.prop" -Value "minApiVersion=101`ntargetApiVersion=101`nstaticScope=true`n"
Set-Content -NoNewline -Encoding ascii -Path "$mod\build\meta\META-INF\xposed\java_init.list" -Value "hf.fontfix.Entry`n"
# com.miui.aod used to be in here. On this ROM that process has no MiuiConfigs and no
# SystemUI view classes at all (see the ClassNotFound storm in the v3.7 log: hooks=6 vs
# 15), so the module could only install its process-wide Typeface rewrites there - pure
# blast radius on the always-on-display / lock-screen clock editors, zero benefit.
# The AOD and lock-screen text that does get fixed is drawn by com.android.systemui.
Set-Content -NoNewline -Encoding ascii -Path "$mod\build\meta\META-INF\xposed\scope.list" -Value "com.android.systemui`n"

# r8 8.1 only accepts class files with major <= 61, so rebuild the stubs at target 8
Remove-Item -Recurse -Force "$root\tools\stubs\out" -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$root\tools\stubs\out" | Out-Null
$stubSrc = @(Get-ChildItem -Recurse "$root\tools\stubs" -Filter *.java | ForEach-Object { $_.FullName })
& "$jdk\javac.exe" -source 8 -target 8 -nowarn -d "$root\tools\stubs\out" $stubSrc 2>$null | Out-Null
& "$jdk\jar.exe" cf $stub -C "$root\tools\stubs\out" .
if ($LASTEXITCODE -ne 0) { throw "stub jar failed" }

Remove-Item -Recurse -Force "$mod\build\classes" -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$mod\build\classes" | Out-Null
$srcs = @(Get-ChildItem -Recurse "$mod\src" -Filter *.java | ForEach-Object { $_.FullName })
& "$jdk\javac.exe" -source 8 -target 8 -nowarn -bootclasspath $aj -classpath $cp -d "$mod\build\classes" $srcs
if ($LASTEXITCODE -ne 0) { throw "module javac failed" }
"compiled"

$classes = @(Get-ChildItem -Recurse "$mod\build\classes" -Filter *.class | ForEach-Object { $_.FullName })
Remove-Item -Force "$mod\build\dexout.zip" -ErrorAction SilentlyContinue
Push-Location $mod
& $java -cp $r8 com.android.tools.r8.R8 --release --pg-conf keep3.pro --lib $aj --classpath $xap --classpath $lxa --classpath $stub --output "build/dexout.zip" $classes
$rc = $LASTEXITCODE
Pop-Location
if ($rc -ne 0) { throw "r8 failed" }
"dexed"

New-Item -ItemType Directory -Force -Path "$mod\build\obj" | Out-Null
New-Item -ItemType Directory -Force -Path "$mod\build\link" | Out-Null
Push-Location $mod
& $aapt2 compile --dir "res" -o "build\obj"
$rc = $LASTEXITCODE
Pop-Location
if ($rc -ne 0) { throw "aapt2 compile failed" }
"res-compiled"

Push-Location $mod
& $aapt2 link --manifest AndroidManifest.xml -I $aj "build\obj\values_arrays.arsc.flat" --min-sdk-version 26 --target-sdk-version 34 -o "build\link\base.apk"
$rc = $LASTEXITCODE
Pop-Location
if ($rc -ne 0) { throw "aapt2 link failed" }
"linked"

# read classes.dex out of r8's zip
$zsrc = [System.IO.Compression.ZipFile]::OpenRead("$mod\build\dexout.zip")
$dexBytes = $null
$e = $zsrc.GetEntry("classes.dex")
if ($null -eq $e) { $zsrc.Dispose(); throw "classes.dex missing from r8 output" }
$ms = New-Object System.IO.MemoryStream
$es = $e.Open(); $es.CopyTo($ms); $es.Dispose()
$dexBytes = $ms.ToArray(); $ms.Dispose(); $zsrc.Dispose()

Copy-Item "$mod\build\link\base.apk" "$mod\build\final-unsigned.apk" -Force
$z = [System.IO.Compression.ZipFile]::Open("$mod\build\final-unsigned.apk", [System.IO.Compression.ZipArchiveMode]::Update)
function Add-Entry($zip, [string]$name, $bytes) {
    $old = $zip.GetEntry($name); if ($old) { $old.Delete() }
    $en = $zip.CreateEntry($name); $st = $en.Open(); $st.Write($bytes, 0, $bytes.Length); $st.Dispose()
}
Add-Entry $z "classes.dex" $dexBytes
Add-Entry $z "assets/xposed_init" ([System.IO.File]::ReadAllBytes("$mod\assets\xposed_init"))
foreach ($n in @("module.prop", "java_init.list", "scope.list")) {
    Add-Entry $z ("META-INF/xposed/" + $n) ([System.IO.File]::ReadAllBytes("$mod\build\meta\META-INF\xposed\" + $n))
}
$z.Dispose()
"staged"

# The three helpers ship as source; compile them if the (git-ignored) classes dir
# is gone or any of them is out of date.
$helpers = @("SignApk", "AlignApk", "VerifyApk")
$need = $false
foreach ($h in $helpers) { if (-not (Test-Path "$signc\$h.class")) { $need = $true } }
if ($need) {
    New-Item -ItemType Directory -Force -Path "$signc" | Out-Null
    & "$jdk\javac.exe" -nowarn -classpath $apksig -d "$signc" `
        "$root\tools\signsrc\SignApk.java" "$root\tools\signsrc\AlignApk.java" `
        "$root\tools\signsrc\VerifyApk.java"
    if ($LASTEXITCODE -ne 0) { throw "helper compile failed" }
}

# Align BEFORE signing. From Android 11 on an APK whose resources.arsc is not stored
# uncompressed and aligned on a 4-byte boundary is refused with
# INSTALL_PARSE_FAILED_RESOURCES_ARSC_COMPRESSED = -124, which HyperOS shows as
# "package incompatible with your system". Not theoretical: v1.1 shipped an unaligned
# ARSC only because versionName got 7 bytes shorter, and only devices whose PMS checks
# are patched could install it. apksig appends the signing block behind the last
# entry, so anything that moves entry offsets must happen before signing.
$aligned = "$mod\build\aligned.apk"
& $java -cp "$signc" AlignApk "$mod\build\final-unsigned.apk" $aligned
if ($LASTEXITCODE -ne 0) { throw "align failed" }

Remove-Item -Force $out -ErrorAction SilentlyContinue
& $java -cp "$apksig;$signc" SignApk $aligned $out $ks hf android
if ($LASTEXITCODE -ne 0) { throw "sign failed" }
# Alignment moved local header offsets, so re-verify the signature on the final file
# instead of trusting that apksig and AlignApk agree about the zip layout.
& $java -cp "$apksig;$signc" VerifyApk $out
if ($LASTEXITCODE -ne 0) { throw "signature verification failed" }
if ($py) { & $py "$root\tools\verify_v4.py" $out } else { "verify skipped (no python on PATH)" }
"DONE $out"
