"""Which string literals in Core.java are missing from an already built APK?

Used to detect edits that happened AFTER a build (two agents were editing the
same source tree). Everything listed here is code that the shipped APK does not
contain yet (or that r8 removed as dead code).

Usage: python delta_probe.py <apk> [java-file ...]
"""
import os
import re
import sys
import tempfile
import zipfile

_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, _HERE)
src = open(os.path.join(_HERE, "dexdump.py"), encoding="utf-8").read()
src = src.replace("\nmain()", "\n")
ns = {"__name__": "dexdump_lib"}
exec(compile(src, "dexdump.py", "exec"), ns)
Dex = ns["Dex"]

apk = sys.argv[1]
files = sys.argv[2:] or [r"D:\Projects\hyperfontfix\module\src\hf\fontfix\Core.java"]

tmp = os.path.join(tempfile.gettempdir(), "hf_delta_%d.dex" % os.getpid())
open(tmp, "wb").write(zipfile.ZipFile(apk).read("classes.dex"))
d = Dex(tmp)
dexstr = {d.string(i) for i in range(d.string_ids_size)}

LIT = re.compile(r'"([^"\n]{2,90})"')
for f in files:
    code = open(f, encoding="utf-8", errors="replace").read()
    lits = set(LIT.findall(code))
    # string concatenations in Java are stored piecewise, so short pieces matter too
    missing = sorted(x for x in lits if x not in dexstr)
    print("== %s: %d literals, %d missing from %s" % (os.path.basename(f), len(lits), len(missing), os.path.basename(apk)))
    for m in missing:
        print("   ", repr(m))
print("# dex strings:", len(dexstr))
