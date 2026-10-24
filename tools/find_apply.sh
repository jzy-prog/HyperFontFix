#!/bin/bash
cd /d/Projects/hyperfontfix || exit 1
echo "== MiuiConfigs locations =="
find smali_c1 smali_c2 smali_c3 -path "*miui/utils/configs*" -name "*.smali" 2>/dev/null | head
F=$(find smali_c1 smali_c2 smali_c3 -name "MiuiConfigs.smali" 2>/dev/null | head -1)
echo "FILE=$F"
if [ -n "$F" ]; then
  echo "== methods =="
  grep -n "^\.method" "$F" | head -30
  echo "== applyStatusBarTypeface body =="
  awk '/applyStatusBarTypeface/,/\.end method/' "$F" | head -120
fi
