import zipfile, re, sys
path, pat = sys.argv[1], sys.argv[2].encode()
z = zipfile.ZipFile(path)
hits = set()
for n in z.namelist():
    if n.endswith(".class") or n.endswith(".xml") or n.endswith(".txt"):
        d = z.read(n)
        for m in re.findall(rb"[ -~]{8,}", d):
            if pat in m:
                hits.add((n, m.decode()))
for n, s in sorted(hits)[:40]:
    print(n, "->", s)
