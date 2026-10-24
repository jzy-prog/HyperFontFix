
from fontTools.ttLib import TTFont
f = TTFont("fonts/Roboto-Regular.ttf")
v = f["fvar"]
name = f["name"]
def nm(nid):
    try:
        for pid in (3,1,0):
            for eid in (1,0):
                for lang in (0x409,0):
                    r = name.getName(nid,pid,eid,lang)
                    if r: return r.toUnicode()
    except Exception: pass
    return None
print("=== fvar axes (%d) ===" % len(v.axes))
for a in v.axes:
    print("  tag=%s min=%s default=%s max=%s flags=%s name=%s" % (a.axisTag,a.minValue,a.defaultValue,a.maxValue,getattr(a,"flags","?"),nm(a.axisNameID)))
print("named instances:", len(v.instances))
for inst in v.instances[:80]:
    print("   ", nm(inst.subfamilyNameID), "| ps:", nm(inst.postscriptNameID), "->", dict(inst.coordinates))
print("=== variation tables present ===")
print({t: (t in f) for t in ("HVAR","MVAR","AVAR","VVAR","gvar","cvar","GDEF","STAT")})
