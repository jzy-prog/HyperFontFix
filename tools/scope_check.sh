#!/bin/bash
cd /d/Projects/hyperfontfix || exit 1
echo "== AOD/Doze/Dream packages in dex =="
find smali_c1 smali_c2 smali_c3 -type d | grep -iE "/(aod|doze|dream)" | head -10
echo "== files named *Aod* =="
find smali_c1 smali_c2 smali_c3 -name "*Aod*" | head -8
echo "== battery percent text in dream/aod paths =="
grep -rl "battery_percent\|percentText" smali_c1/com/android/systemui/dream smali_c1/com/android/systemui/doze 2>/dev/null | head -5
