#!/bin/bash
cd /d/Projects/hyperfontfix/refs/libxposed || exit 1
JH=/d/Projects/hyperfontfix/tools/jdk/jdk-21.0.12.1+1/bin
"$JH/javap.exe" -cp api-102.0.0-classes.jar io.github.libxposed.api.XposedInterface 2>&1 | grep -v "^Compiled" | head -40
echo === MODULE-IF ===
"$JH/javap.exe" -cp api-102.0.0-classes.jar io.github.libxposed.api.XposedModuleInterface 2>&1 | head -20
echo === HOOKBUILDER ===
"$JH/javap.exe" -cp api-102.0.0-classes.jar 'io.github.libxposed.api.XposedInterface$HookBuilder' 2>&1 | head -15
echo === HOOKER ===
"$JH/javap.exe" -cp api-102.0.0-classes.jar 'io.github.libxposed.api.XposedInterface$Hooker' 2>&1 | head -8
echo === CLASSFILE VERSION ===
python -X utf8 -c "
import zipfile
z=zipfile.ZipFile('api-102.0.0-classes.jar')
d=z.read('io/github/libxposed/api/XposedModule.class')
print('major version', int.from_bytes(d[6:8],'big'))
"
