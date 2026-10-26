#!/system/bin/sh
# Snapshot the font state a third-party app is looking at, so "it went bold
# again" can be attributed to a cause instead of guessed.
#
#   adb push tools/fontstate.sh /data/local/tmp/
#   adb shell su -c "sh /data/local/tmp/fontstate.sh com.deepseek.chat"
#
# Run it TWICE: once while the text looks wrong, once after re-applying the
# theme font. Compare theme mtime with the app process start time -- that
# ordering is the whole story (font state is baked per process at start).

PKG=${1:-com.deepseek.chat}

echo "===== now: $(date)"

echo "===== theme font slots (inode / size / mtime)"
ls -li /data/system/theme/fonts/ 2>/dev/null

echo "===== theme master font file"
for f in /data/system/theme/fonts/Roboto-Regular.ttf /data/system/theme/fonts/*.ttf; do
    stat -c '%n inode=%i size=%s mtime=%y' "$f" 2>/dev/null
done | head -n 20

echo "===== theme master font magic bytes (sfnt / ttcf header)"
od -An -tx1 -N16 /data/system/theme/fonts/Roboto-Regular.ttf 2>/dev/null

echo "===== app processes (compare START with the mtime above)"
for p in $(pgrep -f "$PKG"); do
    echo "-- pid $p  cmdline=$(tr '\0' ' ' < /proc/$p/cmdline)"
    stat -c '   proc start: %y' /proc/$p 2>/dev/null
done

echo "===== theme / font chatter in the last 1500 log lines"
logcat -d -v time -t 1500 2>/dev/null | grep -iE "theme|font" | tail -n 40

echo "===== theme service providers visible to the shell"
dumpsys activity providers 2>/dev/null | grep -iE "theme|font" | head -n 20

echo "===== is the app still alive with an old font config? (recreate test)"
echo "    am kill $PKG   # then reopen the app; if it turns normal, the state was per-process"
