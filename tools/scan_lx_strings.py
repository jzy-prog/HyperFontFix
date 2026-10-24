import zipfile, re, sys
# scan service aar classes + sources for manifest meta-data key constants
for path in ["refs/libxposed/service-102.0.0-sources.jar"]:
    z = zipfile.ZipFile(path)
    for n in z.namelist():
        if n.endswith(".java") and ("Meta" in n or "Manifest" in n or "Constant" in n or "meta" in n.lower()):
            print("="*15, path, n, "="*15)
            print(z.read(n).decode("utf-8")[:4000])
# also grep all sources for the literal "libxposed"
z = zipfile.ZipFile("refs/libxposed/service-102.0.0-sources.jar")
for n in z.namelist():
    if n.endswith(".java"):
        t = z.read(n).decode("utf-8", "replace")
        for m2 in re.finditer(r'"[^"]*libxposed[^"]*"', t):
            print(n, "->", m2.group(0))
z2 = zipfile.ZipFile("refs/libxposed/api-102.0.0-sources.jar")
for n in z2.namelist():
    if n.endswith(".java"):
        t = z2.read(n).decode("utf-8", "replace")
        for m2 in re.finditer(r'"[^"]*xposed[^"]*"', t):
            print("API", n, "->", m2.group(0))
