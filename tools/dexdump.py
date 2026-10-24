
#!/usr/bin/env python3
"""Minimal DEX parser (no deps).
Usage: python dexdump.py file.dex [regex ...]
No regex: dump all class descriptors.
"""
import struct, sys, re

class Dex:
    def __init__(self, path):
        self.d = open(path, "rb").read(); self.path = path
        d = self.d
        if d[:3] != b'dex': raise ValueError("not a dex: %s" % path)
        u = lambda off: struct.unpack_from("<I", d, off)[0]
        # header_item field offsets
        self.string_ids_size,  self.string_ids_off  = u(0x38), u(0x3c)
        self.type_ids_size,    self.type_ids_off    = u(0x40), u(0x44)
        self.proto_ids_size,   self.proto_ids_off   = u(0x48), u(0x4c)
        self.field_ids_size,   self.field_ids_off   = u(0x50), u(0x54)
        self.method_ids_size,  self.method_ids_off  = u(0x58), u(0x5c)
        self.class_defs_size,  self.class_defs_off  = u(0x60), u(0x64)
        self._s = {}; self._t = {}; self._p = {}; self._m = {}
    def string(self, idx):
        if idx in self._s: return self._s[idx]
        off = struct.unpack_from("<I", self.d, self.string_ids_off + idx*4)[0]
        p = off
        while self.d[p] & 0x80: p += 1   # utf16 size (uleb128)
        p += 1
        start = p
        while self.d[p] != 0: p += 1
        s = self.d[start:p].replace(b"\xc0\x80", b"\x00").decode("utf-8", "replace")
        self._s[idx] = s; return s
    def type_desc(self, t):
        if t in self._t: return self._t[t]
        sidx = struct.unpack_from("<I", self.d, self.type_ids_off + t*4)[0]
        s = self.string(sidx); self._t[t] = s; return s
    def proto_sig(self, pidx):
        if pidx in self._p: return self._p[pidx]
        base = self.proto_ids_off + pidx*12
        # proto_id_item: uint shorty_idx; uint return_type_idx; uint parameters_off
        rtype = struct.unpack_from("<I", self.d, base + 4)[0]
        params_off = struct.unpack_from("<I", self.d, base + 8)[0]
        plist = []
        if params_off:
            size = struct.unpack_from("<I", self.d, params_off)[0]
            # The spec says list<type_id_item> holds uint entries, but d8/r8 write
            # 16-bit indices here; which one is right varies per producer. Decode both
            # and keep whichever stays inside type_ids_size, so one weird list cannot
            # kill a whole scan.
            vals = None
            for fmt, wide in (("<I", 4), ("<H", 2)):
                try:
                    v = [struct.unpack_from(fmt, self.d, params_off + 4 + k*wide)[0]
                         for k in range(size)]
                except struct.error:
                    continue
                if all(0 <= t < self.type_ids_size for t in v):
                    vals = v; break
            plist = [self.type_desc(t) for t in (vals or [])]
        s = "(" + ",".join(plist) + ")" + self.type_desc(rtype)
        self._p[pidx] = s; return s
    def method(self, midx):
        if midx in self._m: return self._m[midx]
        off = self.method_ids_off + midx*8
        cidx, pidx = struct.unpack_from("<HH", self.d, off)
        nidx = struct.unpack_from("<I", self.d, off + 4)[0]
        s = "%s->%s %s" % (self.type_desc(cidx), self.string(nidx), self.proto_sig(pidx))
        self._m[midx] = s; return s
    def field(self, fidx):
        off = self.field_ids_off + fidx*8
        cidx, _t = struct.unpack_from("<HH", self.d, off)
        nidx = struct.unpack_from("<I", self.d, off + 4)[0]
        return "%s->%s" % (self.type_desc(cidx), self.string(nidx))
    def class_list(self):
        return [self.type_desc(struct.unpack_from("<I", self.d, self.class_defs_off + i*32)[0])
                for i in range(self.class_defs_size)]

def main():
    path = sys.argv[1]; pats = sys.argv[2:]
    dex = Dex(path)
    cls = dex.class_list()
    print("# %s classes=%d method_ids=%d string_ids=%d" % (path, len(cls), dex.method_ids_size, dex.string_ids_size))
    if not pats:
        for c in cls: print(c)
        return
    rx = re.compile("|".join(pats))
    mc = [c for c in cls if rx.search(c)]
    print("## class defs matching (%d):" % len(mc))
    for c in mc: print("   ", c)
    mm = sorted({dex.method(i) for i in range(dex.method_ids_size) if rx.search(dex.method(i))})
    print("## method_ids matching (%d):" % len(mm))
    for m in mm: print("   ", m)
    ms = sorted({dex.string(i) for i in range(dex.string_ids_size) if rx.search(dex.string(i))})
    print("## string_ids matching (%d):" % len(ms))
    for s in ms: print("   ", repr(s))

main()
