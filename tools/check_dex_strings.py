# Confirm the new v4 code paths survived r8 into classes.dex.
import sys
import zipfile

apk = sys.argv[1]
d = zipfile.ZipFile(apk).read("classes.dex")
wanted = [
    "v4.0 DRAW-AUTHORITY",
    "T11 fail",
    "T12 fail",
    "fontPath=",
    "/data/system/theme/fonts/",
    "ro.miui.ui.font.mi_font_path",
    "updateResources$16",
    "com.android.systemui.statusbar.views.MiuiClock",
    "com.android.systemui.qs.MiuiNotificationHeaderView",
    "APPLY ",
]
for s in wanted:
    print(("OK  " if s.encode() in d else "MISS"), s)
print("dex bytes", len(d))
