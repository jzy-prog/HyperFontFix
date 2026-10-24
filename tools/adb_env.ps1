$ErrorActionPreference = "Continue"
$adb = "D:\Android Devices\platform-tools\adb.exe"
$env:ADB_VENDOR_KEYS = "$env:USERPROFILE\.android\adbkey"
$homeDir = "D:\Projects\hyperfontfix\.adbhome"
New-Item -ItemType Directory -Force -Path $homeDir | Out-Null
$env:HOME = $homeDir
& $adb @args
exit $LASTEXITCODE
