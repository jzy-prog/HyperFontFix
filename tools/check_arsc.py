import zipfile
data = zipfile.ZipFile('HyperFontFix-v1.0.apk').read('resources.arsc')
for name in ['xposed_scope', 'com.android.systemui', 'HyperFontFix']:
    print(name, ':', name.encode('utf-16-le') in data)
