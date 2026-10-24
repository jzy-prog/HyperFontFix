#!/bin/bash
cd /d/Projects/hyperfontfix/refs/docs || exit 1
curl -sS --connect-timeout 15 "https://api.github.com/orgs/libxposed/repos?per_page=100" -o libxposed_org.json
curl -sS --connect-timeout 15 -H "Accept: application/vnd.github.raw+json" "https://api.github.com/repos/libxposed/api/contents/README.md?ref=master" -o api_README.md
python -X utf8 -c "
import json
d=json.load(open('libxposed_org.json',encoding='utf-8'))
if isinstance(d,list):
    for rp in d: print('repo:', rp['name'], '|', rp['default_branch'], '|', (rp.get('description') or '')[:70])
else: print('ERR', d.get('message'))
"
wc -c api_README.md && head -80 api_README.md
