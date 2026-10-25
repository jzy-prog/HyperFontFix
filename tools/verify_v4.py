# Verify a built module APK: entries, dex magic, signature blocks, libxposed metadata.
import sys
import zipfile

path = sys.argv[1]
raw = open(path, "rb").read()
z = zipfile.ZipFile(path)
names = z.namelist()
print("size", len(raw))
for it in z.infolist():
    print("  ", it.filename, it.file_size)
print("dex magic", z.read("classes.dex")[:8])
print("sigblock v2", b"APK Sig Block 42" in raw)
print("v1 cert", any(n.upper().endswith(".RSA") for n in names))
# ARSC alignment: Android 11+ refuses the install with
# INSTALL_PARSE_FAILED_RESOURCES_ARSC_COMPRESSED (-124) unless resources.arsc is
# stored uncompressed and starts on a 4-byte boundary. Assert it on every build.
arsc = [i for i in z.infolist() if i.filename == "resources.arsc"]
if not arsc:
    print("arsc MISSING")
else:
    it = arsc[0]
    nl = int.from_bytes(raw[it.header_offset + 26:it.header_offset + 28], "little")
    el = int.from_bytes(raw[it.header_offset + 28:it.header_offset + 30], "little")
    off = it.header_offset + 30 + nl + el
    ok = it.compress_type == 0 and off % 4 == 0
    print("arsc method", it.compress_type, "data@", off, "mod4", off % 4,
          "->", "ALIGNED" if ok else "*** NOT ALIGNED (install fails with -124) ***")
for n in ("META-INF/xposed/module.prop", "META-INF/xposed/java_init.list",
          "META-INF/xposed/scope.list", "assets/xposed_init"):
    print("---", n, "--->", repr(z.read(n).decode("utf-8")) if n in names else "MISSING")
