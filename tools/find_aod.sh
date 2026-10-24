#!/bin/bash
cd /d/Projects/hyperfontfix || exit 1
echo "== classes with Aod+Battery in name =="
find smali_c1 smali_c2 smali_c3 -name "*.smali" | grep -iE "aod" | grep -iE "batter" | head -20
echo "== AodBattery strings =="
grep -rlE "AodBattery|AODBattery|aodBattery" smali_c1 smali_c2 smali_c3 2>/dev/null | head -10
echo "== doze dream battery text views =="
find smali_c1 smali_c2 smali_c3 -path "*doze*" -name "*.smali" 2>/dev/null | grep -iE "batter|clock|text" | head -15
echo "== updateWeight body =="
awk '/\.method.*updateWeight/,/\.end method/' smali_c1/com/android/systemui/controlcenter/shade/NotificationHeaderExpandController.smali | head -160
