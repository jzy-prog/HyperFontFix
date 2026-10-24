
#!/usr/bin/env python3
"""Cross-reference: find which classes' code actually references a given method string.
Strategy: dex string_ids are referenced by encoded_value / debug / const-string.
But method refs are in method_ids, and CODE references them via const/method idx in
instruction payloads. We scan each class_def's code_items for invoke* opcodes and
collect referenced method_id indices, then map back to the defining class.
Usage: python xref.py file.dex regex
"""
import struct, sys, re

def uleb(d, p):
    r = 0; s = 0
    while True:
        b = d[p]; p += 1
        r |= (b & 0x7f) << s
        if not (b & 0x80): return r, p
        s += 7

class Dex:
    def __init__(self, path):
        self.d = d = open(path, "rb").read()
        u = lambda off: struct.unpack_from("<I", d, off)[0]
        self.string_ids_size, self.string_ids_off = u(0x38), u(0x3c)
        self.type_ids_size, self.type_ids_off = u(0x40), u(0x44)
        self.proto_ids_size, self.proto_ids_off = u(0x48), u(0x4c)
        self.field_ids_size, self.field_ids_off = u(0x50), u(0x54)
        self.method_ids_size, self.method_ids_off = u(0x58), u(0x5c)
        self.class_defs_size, self.class_defs_off = u(0x60), u(0x64)
        self.map_list_off = u(0x34)
        self._s = {}; self._t = {}; self._p = {}; self._m = {}
    def string(self, idx):
        if idx in self._s: return self._s[idx]
        off = struct.unpack_from("<I", self.d, self.string_ids_off + idx*4)[0]
        p = off
        while self.d[p] & 0x80: p += 1
        p += 1; start = p
        while self.d[p] != 0: p += 1
        s = self.d[start:p].replace(b"\xc0\x80", b"\x00").decode("utf-8", "replace")
        self._s[idx] = s; return s
    def type_desc(self, t):
        if t in self._t: return self._t[t]
        s = self.string(struct.unpack_from("<I", self.d, self.type_ids_off + t*4)[0])
        self._t[t] = s; return s
    def proto_sig(self, pidx):
        if pidx in self._p: return self._p[pidx]
        base = self.proto_ids_off + pidx*12
        rtype = struct.unpack_from("<I", self.d, base + 4)[0]
        params_off = struct.unpack_from("<I", self.d, base + 8)[0]
        pl = []
        if params_off:
            size = struct.unpack_from("<I", self.d, params_off)[0]
            for k in range(size):
                pl.append(self.type_desc(struct.unpack_from("<H", self.d, params_off + 4 + k*4)[0]))
        s = "(" + ",".join(pl) + ")" + self.type_desc(rtype)
        self._p[pidx] = s; return s
    def method(self, midx):
        if midx in self._m: return self._m[midx]
        off = self.method_ids_off + midx*8
        cidx, pidx = struct.unpack_from("<HH", self.d, off)
        s = "%s->%s %s" % (self.type_desc(cidx), self.string(struct.unpack_from("<I", self.d, off+4)[0]), self.proto_sig(pidx))
        self._m[midx] = s; return s
    # map_list -> class_data_item offsets
    def class_data_items(self):
        d = self.d
        size = struct.unpack_from("<I", d, self.map_list_off)[0]
        for i in range(size):
            utype, unused, off, nsz = struct.unpack_from("<HHII", d, self.map_list_off + 4 + i*12)
            if utype == 0x2008:   # TYPE_CLASS_DATA_ITEM
                return off, nsz
        return None, 0
    def code_offsets_for(self, class_def_idx):
        """Return list of (method_name_sig, code_off) for direct+virtual methods of a class_def."""
        d = self.d
        base = self.class_defs_off + class_def_idx*32
        class_data_off = struct.unpack_from("<I", d, base + 24)[0]
        if not class_data_off: return []
        out = []
        p = class_data_off
        sfields, p = uleb(d, p); ifields, p = uleb(d, p)
        dmethods, p = uleb(d, p); vmethods, p = uleb(d, p)
        midx = 0
        for count, kind in ((dmethods, "D"), (vmethods, "V")):
            diff = 0
            for _ in range(count):
                diff, p = uleb(d, p); midx += diff
                access, p = uleb(d, p)
                if kind == "D":
                    code_off, p = uleb(d, p)
                    if code_off: out.append((self.method(midx), code_off))
                else:
                    code_off, p = uleb(d, p)
                    if code_off: out.append((self.method(midx), code_off))
        return out

INVOKE_FORMAT = {0x6e:3, 0x6f:3, 0x70:3, 0x71:3, 0x72:3, 0x73:5, 0x74:5, 0x75:5, 0x76:5, 0x77:5, 0x78:5}

def main():
    path = sys.argv[1]; rx = re.compile(sys.argv[2])
    dex = Dex(path)
    targets = {i for i in range(dex.method_ids_size) if rx.search(dex.method(i))}
    print("# %s target method_ids: %d" % (path, len(targets)))
    for t in sorted(targets): print("   TARGET", dex.method(t))
    cd_off, cd_sz = dex.class_data_items()
    hits = {}
    for ci in range(dex.class_defs_size):
        base = dex.class_defs_off + ci*32
        cdef = dex.type_desc(struct.unpack_from("<I", dex.d, base)[0])
        cdata = struct.unpack_from("<I", dex.d, base + 24)[0]
        if not cdata: continue
        try:
            for sig, code_off in dex.code_offsets_for(ci):
                d = dex.d
                # code_item: registers_size(2) ins(2) outs(2) tries(2) debug(4) insns_size(4) insns...
                insns_size = struct.unpack_from("<I", d, code_off + 12)[0]
                p = code_off + 16
                end = p + insns_size*2
                while p + 1 < end:
                    op = d[p]
                    if op in INVOKE_FORMAT:
                        width = INVOKE_FORMAT[op]
                        midx = struct.unpack_from("<H", d, p + 2)[0]
                        if op in (0x78, 0x79, 0x7a, 0x7b, 0x7c):  # range: kind at +4
                            midx = struct.unpack_from("<H", d, p + 4)[0]
                        if midx in targets:
                            hits.setdefault(cdef, set()).add(sig)
                        p += width*2
                    elif op == 0x00:  # nop / packed-switch / sparse-switch payload
                        ident = struct.unpack_from("<H", d, p + 2)[0]
                        if ident == 0x0100:  # packed-switch-payload
                            size = struct.unpack_from("<H", d, p + 6)[0]
                            p += (8 + size*2 + 1) & ~1
                        elif ident == 0x0200:
                            size = struct.unpack_from("<H", d, p + 6)[0]
                            p += (8 + size*4 + 1) & ~1
                        elif ident == 0x0300:
                            n = struct.unpack_from("<I", d, p + 4)[0]
                            p += (4 + n*4 + 1) & ~1
                        else:
                            p += 2
                    else:
                        p += 2
        except Exception as e:
            print("  !! %s %s" % (cdef, e))
    print("## caller classes (%d):" % len(hits))
    for c in sorted(hits):
        print("   ", c)
        for s in sorted(hits[c]): print("        in", s)

main()
