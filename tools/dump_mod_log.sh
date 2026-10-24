#!/bin/bash
cd /d/Projects/hyperfontfix/lslog4 || exit 1
F=log/verbose_2026-10-20T19_04_47.136994.log
[ -f "$F" ] || F=$(ls -t log/verbose_*.log | head -1)
echo "using $F"
echo "=== clone fail / re-baked / set fail / tf-> / MCFG ==="
grep -hE "HyperFontFix:.*(clone fail|re-baked|set fail|tf->|MCFG)" "$F" | sed -E 's/^.*HyperFontFix: //' | sort | uniq -c | sort -rn | head -30
echo "=== all distinct message shapes ==="
grep -h "HyperFontFix:" "$F" | sed -E 's/^.*HyperFontFix: //; s/->[0-9]+/@W/; s/@ [A-Za-z0-9]+/@X/' | sort | uniq -c | sort -rn | head -40
