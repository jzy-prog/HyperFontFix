import zipfile, struct, sys
ax = zipfile.ZipFile(sys.argv[1]).read('AndroidManifest.xml')
typ, hs, size, sc, stc, flags, ss, sts = struct.unpack_from('<HHIIIIII', ax, 8)
print('pool count', sc, 'flags', hex(flags), 'strstart', ss)
offsets = struct.unpack_from('<%dI' % sc, ax, 8 + hs)
base = 8 + ss
out = []
for o in offsets:
    p = base + o
    if flags & 0x100:
        ln = ax[p]
        p2 = p + 1
        if ln & 0x80:
            ln = ((ln & 0x7f) << 8) | ax[p2]
            p2 += 1
        u8 = ax[p2]
        if u8 & 0x80:
            u8 = ((u8 & 0x7f) << 8) | ax[p2 + 1]
            p2 += 2
        else:
            p2 += 1
        out.append(ax[p2:p2 + u8].decode('utf-8', 'replace'))
    else:
        n = struct.unpack_from('<H', ax, p)[0]
        p2 = p + 2
        if n & 0x8000:
            n = ((n & 0x7fff) << 16) | struct.unpack_from('<H', ax, p2)[0]
            p2 += 2
        out.append(ax[p2:p2 + 2 * n].decode('utf-16-le', 'replace'))
for s in out:
    print(repr(s))
