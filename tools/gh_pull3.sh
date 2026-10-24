#!/bin/bash
cd /d/Projects/hyperfontfix/refs/docs || exit 1
curl -sS --connect-timeout 20 "https://api.github.com/repos/libxposed/example/git/trees/master?recursive=1" -o example_tree.json
python -X utf8 -c "
import json
d=json.load(open('example_tree.json',encoding='utf-8'))
if 'tree' in d:
    for t in d['tree']:
        if t['type']=='blob' and any(k in t['path'] for k in ['AndroidManifest','java_init','xposed','Entry','build.gradle','proguard','Main']):
            print(t['path'])
else: print('ERR', d.get('message'))
"
