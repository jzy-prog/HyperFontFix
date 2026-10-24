import zipfile, sys
f = sys.argv[1]
try:
    z = zipfile.ZipFile(f)
    names = z.namelist()
    print("OK", f, names)
    if "classes.jar" in names:
        out = f.replace(".aar", "-classes.jar")
        open(out, "wb").write(z.read("classes.jar"))
        zz = zipfile.ZipFile(out)
        for n in zz.namelist():
            if n.endswith(".class"):
                print("   ", n)
except Exception as e:
    print("BAD", f, str(e)[:80])
