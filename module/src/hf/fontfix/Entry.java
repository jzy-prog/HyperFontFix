package hf.fontfix;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Modern libxposed entry point (META-INF/xposed/java_init.list).
 * Runs on API 101/102 frameworks. Uses remote preferences as the hot config
 * channel when the framework advertises PROP_CAP_REMOTE.
 */
public class Entry extends XposedModule {

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        Core.log("modern entry; fw=" + getFrameworkName() + " api=" + getApiVersion());
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        String pkg = param.getPackageName();
        if (!Core.SYSUI.equals(pkg) && !Core.AOD.equals(pkg)) return;
        if ((getFrameworkProperties() & PROP_CAP_REMOTE) != 0) {
            try {
                SharedPreferences sp = getRemotePreferences("hyperfontfix");
                Core.setRemoteStore(sp);
                sp.registerOnSharedPreferenceChangeListener(new SharedPreferences.OnSharedPreferenceChangeListener() {
                    @Override public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
                        Core.pokeCfg();
                    }
                });
                Core.log("remote prefs channel active");
            } catch (Throwable t) {
                Core.log("remote prefs unavailable: " + t);
            }
        }
        Core.mAodProc = Core.AOD.equals(pkg);
        Core.installOnce(param.getClassLoader());
    }

    @Override
    public boolean onHotReloading(@NonNull HotReloadingParam param) {
        return true;
    }
}
