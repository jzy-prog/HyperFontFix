package hf.fontfix;

import android.content.Context;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/** Legacy (de.robv) entry point, loaded via assets/xposed_init. */
public class Main implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        if (!Core.SYSUI.equals(pkg) && !Core.AOD.equals(pkg)) return;
        Core.mAodProc = Core.AOD.equals(pkg);

        XC_MethodHook cap = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Object app = p.getResult();
                if (app instanceof Context) Core.setCtx((Context) app);
            }
        };
        try {
            XposedHelpers.findAndHookMethod("android.app.Instrumentation", lpparam.classLoader,
                    "newApplication", ClassLoader.class, String.class, cap);
        } catch (Throwable ignored) { }
        try {
            XposedHelpers.findAndHookMethod("android.app.Instrumentation", lpparam.classLoader,
                    "newApplication", ClassLoader.class, String.class, String.class, cap);
        } catch (Throwable ignored) { }
        Core.ctxViaActivityThread();

        Core.installOnce(lpparam.classLoader);
    }
}
