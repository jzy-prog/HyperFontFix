#!/bin/bash
# usage: gh_api_get.sh owner/repo path out ; tries main then master
cd /d/Projects/hyperfontfix/refs/docs || exit 1
get1(){ curl -sS --connect-timeout 15 -H "Accept: application/vnd.github.raw+json" "https://api.github.com/repos/$1/contents/$2?ref=$3" -o "$4" 2>/dev/null; sz=$(wc -c < "$4" 2>/dev/null || echo 0); grep -q "Not Found" "$4" 2>/dev/null && sz=0; [ "$sz" -gt 50 ]; }
for spec in "$@"; do
  IFS='|' read -r repo path out <<< "$spec"
  ok=0
  for br in main master; do get1 "$repo" "$path" "$br" "$out" && { ok=1; echo "OK $out ($br) $(wc -c < $out) B"; break; }; done
  [ $ok -eq 0 ] && echo "FAIL $out"
done
echo GHAPI_DONE
