import zipfile, hashlib
a = zipfile.ZipFile('HyperFontFix-v1.0.apk').read('classes.dex')
b = zipfile.ZipFile('module/build/dexout.zip').read('classes.dex')
print('dex match:', hashlib.md5(a).hexdigest() == hashlib.md5(b).hexdigest())
import subprocess
