"""Print the smali instructions that feed each MiuiConfigs.applyStatusBarTypeface call.

Purpose: recover the *stock* weight every status-bar text view is given when the
device still uses MiSansVF, so the module's defaults can mirror the system's own
values instead of guesses. Usage: python callsite_probe.py [depth]
"""
import os
import re
import sys

ROOTS = [r"D:\Projects\hyperfontfix\smali_c1",
         r"D:\Projects\hyperfontfix\smali_c2",
         r"D:\Projects\hyperfontfix\smali_c3"]
TARGET = "applyStatusBarTypeface"
depth = int(sys.argv[1]) if len(sys.argv) > 1 else 14

pat_static = re.compile(r"sget-object\s+v\d+,\s*L[\w/$]+;->(s\w+|FONT\w*)")
pat_field = re.compile(r"iget-object\s+v\d+,\s*[\w/]+,[\w/]+->(\w+):Landroid/widget/TextView;")

for root in ROOTS:
    for dp, _dirs, files in os.walk(root):
        for f in files:
            if not f.endswith(".smali"):
                continue
            p = os.path.join(dp, f)
            try:
                txt = open(p, encoding="utf-8", errors="replace").read()
            except OSError:
                continue
            if TARGET not in txt:
                continue
            lines = txt.split("\n")
            hits = [i for i, l in enumerate(lines) if TARGET in l]
            if not hits:
                continue
            cls = os.path.relpath(p, root).replace(os.sep, ".")[:-6]
            for h in hits:
                if "invoke" not in lines[h]:
                    continue
                lo = max(0, h - depth)
                ctx = lines[lo:h + 1]
                stats = [m.group(1) for l in ctx for m in [pat_static.search(l)] if m]
                flds = [m.group(1) for l in ctx for m in [pat_field.search(l)] if m]
                print("%-78s  statics=%s  tvfields=%s" % (cls, stats or "-", flds or "-"))
                if cls.endswith("MiuiConfigs"):
                    continue
                for l in ctx:
                    s = l.strip()
                    if s.startswith(".line") or not s:
                        continue
                    print("       |", s[:110])
                print("       v" + "-" * 40)
