#!/bin/bash
cd /d/Projects/hyperfontfix/refs/docs || exit 1
tryget(){ desc="$1"; url="$2"; out="$3"; i=0; while [ $i -lt 4 ]; do i=$((i+1)); curl -sSL --connect-timeout 15 -o "$out" "$url" && sz=$(wc -c < "$out") && [ "$sz" -gt 200 ] && { echo "OK $desc ($sz B)"; return 0; }; sleep 3; done; echo "FAIL $desc ($(wc -c < "$out" 2>/dev/null || echo 0) B)"; return 1; }
tryget "sample manifest" https://raw.githubusercontent.com/LSPosed/sample-libxposed-module/master/app/src/main/AndroidManifest.xml sample_manifest.xml
tryget "sample LSPEntry" "https://raw.githubusercontent.com/LSPosed/sample-libxposed-module/master/app/src/main/java/org/lsp/sample/module/XposedEntry.java" sample_LSPEntry.java
tryget "sample XposedEntry" "https://raw.githubusercontent.com/LSPosed/sample-libxposed-module/master/app/src/main/java/org/lsp/sample/module/LibXposedEntry.java" sample_LibXposedEntry.java
tryget "sample module gradle" "https://raw.githubusercontent.com/LSPosed/sample-libxposed-module/master/app/build.gradle.kts" sample_build.gradle.kts
tryget "docs usage md" "https://raw.githubusercontent.com/libxposed/libxposed.github.io/main/docs/guide/develop/entrypoint.md" docs_entrypoint.md
tryget "docs index" "https://raw.githubusercontent.com/libxposed/libxposed.github.io/main/docs/guide/develop/module.md" docs_module.md
ls -la
