#!/bin/bash
cd /d/Projects/hyperfontfix/lslog5 || exit 1
F=$(ls -t log/verbose_*.log | head -1)
echo "== view names touched by T1/T2/T6 (v3.3 process only) =="
# v3.3 process: lines after the last "v3.3 installed"
grep -h "HyperFontFix:" "$F" | sed -n '/v3.3 installed/,$p' | sed -E 's/^.*HyperFontFix: //' | awk '/^T[0-9]|^H0|^clone/{print}' | sed -E 's/.*@ (.*)$/\1/' | sort | uniq -c | sort -rn | head -25
echo ""
echo "== all MCFG/rebake label shapes v3.3 =="
grep -h "HyperFontFix:" "$F" | sed -n '/v3.3 installed/,$p' | sed -E 's/^.*HyperFontFix: //' | grep -E "MCFG|re-bake|clonepath|set fail" | head -12
