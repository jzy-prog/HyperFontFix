#!/bin/bash
cd /d/Projects/hyperfontfix/refs/docs || exit 1
api(){ curl -sS --connect-timeout 15 "https://api.github.com/$1" -o "$2" 2>/dev/null; }
for repo in libxposed/api libxposed/sample-libxposed-module libxposed/service LSPosed/sample-libxposed-module libxposed/libxposed.github.io Lackluster/mihelper-src; do
  slug=$(echo $repo | tr '/' '_')
  api "repos/$repo" repo_$slug.json
  python -X utf8 -c "
import json,sys
try:
    d=json.load(open('repo_$slug.json',encoding='utf-8'))
    if 'default_branch' in d: print('REPO $repo -> branch', d['default_branch'], '| archived', d['archived'], '|', (d.get('description') or '')[:60])
    else: print('REPO $repo -> MISS:', d.get('message'))
except Exception as e: print('REPO $repo -> ERR', e)
"
done
echo META_DONE
