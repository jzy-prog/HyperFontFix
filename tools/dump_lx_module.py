import zipfile
z = zipfile.ZipFile("refs/libxposed/api-102.0.0-sources.jar")
for n in z.namelist():
    if n.endswith("XposedModule.java"):
        print(z.read(n).decode("utf-8"))
print("=" * 30, "AAR manifest/proguard", "=" * 30)
za = zipfile.ZipFile("refs/libxposed/api-102.0.0.aar")
print("AndroidManifest:", za.read("AndroidManifest.xml").decode("utf-8")[:600])
print("proguard:", za.read("proguard.txt").decode("utf-8")[:400])
