import zipfile, sys
z = zipfile.ZipFile("refs/libxposed/api-102.0.0-sources.jar")
want = ["XposedModuleInterface.java", "XposedModule.java", "XposedInterface.java"]
for n in z.namelist():
    if any(n.endswith("/" + w) for w in want):
        print("=" * 20, n, "=" * 20)
        print(z.read(n).decode("utf-8"))
