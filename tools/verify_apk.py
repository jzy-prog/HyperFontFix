import zipfile
raw = open('HyperFontFix-v1.0.apk','rb').read()
z = zipfile.ZipFile('HyperFontFix-v1.0.apk')
print('size', len(raw))
for it in z.infolist(): print(' ', it.filename, it.file_size)
print('dex magic', dex_magic := z.read('classes.dex')[:8])
print('sigblock v2', b'APK Sig Block 42' in raw)
print('v1 cert', b'META-INF/HF.RSA' in str(z.namelist()).encode())
print('xposed_init', z.read('assets/xposed_init'))
