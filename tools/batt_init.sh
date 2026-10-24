#!/bin/bash
cd /d/Projects/hyperfontfix || exit 1
F=smali_c3/com/android/systemui/statusbar/views/MiuiBatteryMeterView.smali
echo "== method containing line 2037 =="
awk 'NR<=2037 && /^\.method/{m=$0; ln=NR} END{}' "$F" >/dev/null
grep -n "^\.method" "$F" | awk -F: '$1<2037' | tail -2
echo "== onFinishInflate / onAttachedToWindow / updateAll if applyStatus there =="
grep -n "applyStatusBarTypeface\|setMiuiStatusBarTypeface\|setMiuiOptimization" "$F" | head -12
echo "== second caller near 3586 context method =="
grep -n "^\.method" "$F" | awk -F: '$1<3586' | tail -1
