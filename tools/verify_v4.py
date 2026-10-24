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
for n in ("META-INF/xposed/module.prop", "META-INF/xposed/java_init.list",
          "META-INF/xposed/scope.list", "assets/xposed_init"):
    print("---", n, "--->", repr(z.read(n).decode("utf-8")) if n in names else "MISSING")
