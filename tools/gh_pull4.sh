#!/bin/bash
cd /d/Projects/hyperfontfix/refs/docs && mkdir -p example || exit 1
cd example || exit 1
raw(){ curl -sS --connect-timeout 15 -H "Accept: application/vnd.github.raw+json" "https://api.github.com/repos/libxposed/example/contents/$1?ref=master" -o "$(basename $1)" && echo "OK $(basename $1) $(wc -c < $(basename $1)) B"; }
raw "app/src/main/AndroidManifest.xml"
raw "app/src/main/resources/META-INF/xposed/module.prop"
raw "app/src/main/resources/META-INF/xposed/java_init.list"
raw "app/src/main/resources/META-INF/xposed/scope.list"
raw "app/src/main/java/io/github/libxposed/example/ModuleMain.java"
raw "app/src/main/java/io/github/libxposed/example/App.kt"
echo ALLDONE
