import re
p = 'log/modules_2026-10-19T18_35_15.546372.log'
lines = open(p,'rb').read().decode('utf-8','replace').splitlines()
print('total lines', len(lines))
agg = {}
errs = []
t2 = []
for l in lines:
    m = re.search(r'HyperFontFix: (.*)', l)
    if not m: continue
    body = m.group(1)
    body = re.sub(r'->\d+', '->W', body)
    body = re.sub(r'@ \S+', '@ X', body)
    agg[body] = agg.get(body,0)+1
    if 'T2' in body and len(t2)<8: t2.append(l[:200])
    if 'fail' in body or 'Error' in body or 'Exception' in body: errs.append(l[:220])
for k,v in sorted(agg.items(), key=lambda x:-x[1]): print(v, k)
print('--- T2 samples ---')
for l in t2: print(l)
print('--- errors ---')
for l in errs[:15]: print(l)
