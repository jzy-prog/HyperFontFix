import re, io, os
pats = re.compile(b'HyperFontFix|hf.fontfix', re.I)
for root, dirs, files in os.walk('.'):
    for f in files:
        p = os.path.join(root, f)
        if 'tombstone' in p or 'dropbox' in p or p.endswith('.pb') or 'crash' in p: continue
        try:
            if os.path.getsize(p) > 60*1024*1024: continue
        except Exception: continue
        try:
            with open(p,'rb') as fh:
                hits = [m for m in fh if pats.search(m)]
            if hits:
                print('####', p, len(hits))
                for h in hits[-40:]:
                    print('   ', h.decode('utf-8','replace').rstrip()[:220])
        except Exception as e:
            pass
