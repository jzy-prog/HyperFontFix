"""List method_ids (owner->name proto) of a dex or of classes.dex inside an APK.

Usage: python dex_methods.py <file.dex|file.apk> [regex]
Used to catch descriptor mismatches: the HyperOS framework declares
TextView.setFontVariationSettings(String)Z while android.jar 34 declares ...V,
so a module compiled against the old SDK gets NoSuchMethodError at invoke time.
"""
import os
import re
import sys
import zipfile

_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, _HERE)
# dexdump.py runs main() on import, so load its Dex class without executing it.
src = open(os.path.join(_HERE, "dexdump.py"), encoding="utf-8").read()
src = src.replace("\nmain()", "\n")
ns = {"__name__": "dexdump_lib"}
exec(compile(src, "dexdump.py", "exec"), ns)
Dex = ns["Dex"]

path = sys.argv[1]
rx = re.compile(sys.argv[2]) if len(sys.argv) > 2 else None

if path.lower().endswith(".apk") or path.lower().endswith(".jar") or path.lower().endswith(".zip"):
    import os
    import tempfile
    z = zipfile.ZipFile(path)
    tmp = os.path.join(tempfile.gettempdir(), "hf_probe.dex")
    with open(tmp, "wb") as f:
        f.write(z.read("classes.dex"))
    path = tmp

d = Dex(path)
seen = set()
for i in range(d.method_ids_size):
    s = d.method(i)
    if rx and not rx.search(s):
        continue
    if s in seen:
        continue
    seen.add(s)
for s in sorted(seen):
    print(s)
print("# total", len(seen))
