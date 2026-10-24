import zipfile
z = zipfile.ZipFile("refs/libxposed/api-102.0.0-sources.jar")
for n in z.namelist():
    if n.endswith("XposedModuleInterface.java"):
        t = z.read(n).decode("utf-8")
        print(t)
