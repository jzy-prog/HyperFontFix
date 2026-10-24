#!/bin/bash
cd /d/Projects/hyperfontfix/refs/libxposed || exit 1
B=https://repo1.maven.org/maven2/io/github/libxposed
curl -sS --connect-timeout 15 -o api-101.0.0.aar "$B/api/101.0.0/api-101.0.0.aar"
curl -sS --connect-timeout 15 -o api-102.0.0.aar "$B/api/102.0.0/api-102.0.0.aar"
for f in api-101.0.0.aar api-102.0.0.aar; do
  python -X utf8 /d/Projects/hyperfontfix/tools/unpack_aar.py "$f"
done
echo AARS_DONE
