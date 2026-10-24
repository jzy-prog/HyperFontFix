#!/bin/bash
cd /d/Projects/hyperfontfix || exit 1
echo "== getMiProTypefaceWght460 callers =="
grep -rln "getMiProTypefaceWght460" smali_c1 smali_c2 smali_c3 2>/dev/null | head -20
echo ""
echo "== NHEC clinit ramp build =="
awk '/\.method static constructor <clinit>/,/\.end method/' smali_c1/com/android/systemui/controlcenter/shade/NotificationHeaderExpandController.smali | grep -E "const-string|->wght|Builder|setFontVariation|add|0x1dc|0x2bc|wght|Method" | head -40
echo ""
echo "== miproMedium/miproNormal fields in NHEC =="
grep -n "miproMedium\|miproNormal" smali_c1/com/android/systemui/controlcenter/shade/NotificationHeaderExpandController.smali | head
