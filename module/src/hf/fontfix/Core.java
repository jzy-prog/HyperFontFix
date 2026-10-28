package hf.fontfix;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.view.View;
import android.widget.TextView;

import java.lang.reflect.Constructor;
import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hook installer shared by legacy (Main) and modern libxposed (Entry) entries;
 * installs exactly once per process.

 * Strategy: clamp at the TYPEFACE SOURCE. MIUI pre-bakes ramp weights into
 * Typeface instances via Typeface.Builder("'wght' N"). Those baked numbers were
 * tuned for MiSans and become thin with a VF theme (axis default 330). Whenever
 * such a Typeface (or a mipro* family request) reaches a whitelisted view, we
 * REPLACE it with a re-baked clone carrying our constant axis value, using ONLY
 * the real variable font wght axis (never fake bold).
 */
public final class Core {

    static final String TAG = "HyperFontFix";
    static final String SYSUI = "com.android.systemui";
    /**
     * Out of the module scope since v5.1 (see installOnce and tools/build_v4.ps1).
     * The constant stays so `settings put system hf_ff_aod 1` + re-adding the scope in
     * LSPosed can bring it back for experiments.
     */
    static final String AOD = "com.miui.aod";

    private static final String MIUI_CONFIGS = "com.miui.utils.configs.MiuiConfigs";
    private static final String NHEC = "com.android.systemui.controlcenter.shade.NotificationHeaderExpandController";
    private static final String MOBILE_TYPE_DRAWABLE = "com.miui.systemui.statusbar.views.MobileTypeDrawable";
    static final String BATTERY_METER_VIEW = "com.android.systemui.statusbar.views.MiuiBatteryMeterView";
    static final String HOLLOW_ICON_VIEW = "com.android.systemui.statusbar.views.MiuiHollowBatteryMeterIconView";
    static final String BATTERY_UTILS = "com.android.systemui.statusbar.util.MiuiBatteryUtils";
    private static final String VAR_FONT_UTILS = "miuix.animation.font.VarFontUtils";
    private static final String MIUI_CLOCK = "com.android.systemui.statusbar.views.MiuiClock";
    private static final String HEADER_VIEW = "com.android.systemui.qs.MiuiNotificationHeaderView";
    /**
     * Keyguard clock face base class: setTextFontDark(Z) is the ROM's own write point
     * for the lock-screen date line (see ROLE_LOCKDATE).
     */
    private static final String MIUI_BASE_CLOCK = "com.miui.clock.MiuiBaseClock";
    /**
     * The carrier label container. One class, three instances (control centre,
     * notification shade, keyguard) - see inKeyguardCarrierLayout().
     */
    private static final String CARRIER_LAYOUT =
            "com.android.systemui.controlcenter.shade.MiuiCarrierTextLayout";
    /** One SIM's label + its "HD" / "+" satellites (LinearLayout). */
    private static final String CARRIER_TEXT_WIDGET =
            "com.android.systemui.controlcenter.shade.ControlCenterCarrierText";

    /**
     * Where the theme engine puts the applied third-party font. Verified in
     * miuix.animation.font.VarFontUtils (THEME_FONT_DIR) and on-device: every
     * weight slot in here is a symlink to ONE variable font file, so
     * Typeface.create("mipro-medium") and friends all collapse onto its fvar
     * default instance. Re-baking our axis from that exact file keeps the
     * theme's glyphs and changes ONLY the weight.
     */
    private static final String THEME_FONT_DIR = "/data/system/theme/fonts/";
    private static final String MI_FONT_PATH_PROP = "ro.miui.ui.font.mi_font_path";

    private static final int ROLE_NONE = -1;
    private static final int ROLE_CLOCK = 0;
    private static final int ROLE_NET = 1;
    private static final int ROLE_CARRIER = 2;
    private static final int ROLE_BATTERY = 3;
    private static final int ROLE_MARK = 4;
    private static final int ROLE_HOLLOW = 8;
    private static final int ROLE_DATE = 5;
    private static final int ROLE_ISLAND = 6;
    private static final int ROLE_OTHER = 7;
    /** Status-bar network speed indicator: its number and its unit. */
    private static final int ROLE_SPEED = 9;
    /**
     * Alarm countdown that takes over the carrier slot on the keyguard status bar.
     * It is an android.widget.Chronometer, so classify() alone cannot separate it
     * from the elapsed-time clocks and calls it ROLE_DATE - which is why this label
     * stayed thin while its carrier neighbour got fixed.
     */
    private static final int ROLE_ALARM = 10;
    /**
     * Lock-screen date line under the keyguard clock. The clock faces own this text:
     * com.miui.clock.MiuiBaseClock.setTextFontDark() writes mCurrentDate and
     * mLunarCalendarInfo with Typeface.create("mipro-regular", 0), and with
     * Typeface.create("mipro-medium", 0) when the wallpaper needs dark text. The
     * first alias is COMMENTED OUT in the ROM's own font config
     * (fonts/fonts_system.xml line 1034), so that request lands on the default family
     * at its regular weight (400); mipro-medium does exist, line 1035, and is
     * sans-serif at weight 500. Views: TextView@id/current_date
     * (res/layout/miui_left_top_clock.xml line 25, android:fontFamily="mipro-regular")
     * plus the per-style equivalents (id/oversize_b_date, id/pad_c_date,
     * id/vertical_data_upper_view_date, ...).
     * This is the one role whose FAMILY is wrong, not only its weight: the dead alias
     * resolves to the unthemed default (MiSans), so a theme font never reaches this
     * text. The face we install therefore comes from themeFaceAt(), i.e. from a source
     * that is known to render with the applied font (the clock face's own time view, the
     * theme font file, the default family) - never from a re-weighted copy of this view's
     * own MiSans face, which is what v1.3 did and why the date looked MORE like MiSans
     * with hf_ff_lockdate set than with it at 0.
     */
    private static final int ROLE_LOCKDATE = 11;

    /**
     * Class-name patterns that are NOT status-bar text and must keep their own
     * weight no matter what: 超级岛/胶囊 and anything else built on the same
     * font helpers. Matched against the view class AND against the caller of the
     * process-wide Typeface hooks.
     */
    private static final String[] EXCLUDED_HINTS = {
            "island", "capsule", "deepspace", "hyperisland",
    };

    private static final AtomicBoolean sInstalled = new AtomicBoolean(false);
    private static final Map<View, Integer> sRoles =
            Collections.synchronizedMap(new WeakHashMap<View, Integer>());
    private static final Map<String, Typeface> sFixed = new ConcurrentHashMap<String, Typeface>();
    /** Typefaces we produced ourselves (idempotence marker). */
    private static final java.util.Set<Typeface> sOurClones =
            Collections.newSetFromMap(new ConcurrentHashMap<Typeface, Boolean>());
    /**
     * Weight each of our clones was baked at. A clone carries a CONSTANT axis, so
     * neither getWeight() nor the paint string tells whether it still matches the
     * current config - Typeface#getWeight() reports the file's default style, not the
     * 'wght' variation we asked for.
     */
    private static final Map<Typeface, Integer> sCloneW =
            new ConcurrentHashMap<Typeface, Integer>();
    /** Typeface instances that ARE MiuiConfigs baked statics (final -> set() blocked). */
    private static final java.util.Set<Typeface> sMcStatics =
            Collections.newSetFromMap(new ConcurrentHashMap<Typeface, Boolean>());
    /** sMiproTypefaceWght500/600 instances: consumed by battery percent + mark. */
    private static final java.util.Set<Typeface> sMcBattery =
            Collections.newSetFromMap(new ConcurrentHashMap<Typeface, Boolean>());
    /** Paints we own (MobileTypeDrawable 4G/5G text) -> role; P1 restores their axis. */
    private static final Map<Paint, Integer> sPaints =
            Collections.synchronizedMap(new WeakHashMap<Paint, Integer>());
    /** True when this process is com.miui.aod (AOD/ambient display). */
    static volatile boolean mAodProc = false;
    private static final Map<Object, String> sBuilderFamily =
            Collections.synchronizedMap(new WeakHashMap<Object, String>());

    private static volatile Context sCtx;
    private static volatile Class<?> sMcClass;
    private static volatile boolean sMcProbed;
    private static volatile SharedPreferences sRemote;
    private static volatile long sCfgTs;
    private static volatile boolean sEnable = true;
    // Defaults are the weights the ROM itself asks for while MiSansVF is still in
    // place, read off every MiuiConfigs.applyStatusBarTypeface call site
    // (tools/callsites.txt): clock 500 (pad 460), 4G/5G 660, carrier/sign separator
    // 430 (sMiproTypeface), battery digits 500, battery '%' 600, hollow battery icon
    // paint 620 (MiuiBatteryUtils.FONT_WGHT). Date / 超级岛 / everything else default
    // to 0 = do not touch, they are opt-in via Settings.System.
    static final int DEFAULT_CLOCK = 500;
    /**
     * 660 is not our invention: MobileTypeDrawable.<clinit> bakes sMiproTypeface from
     * MiuiConfigs.FONT_MIPRO_PATH with exactly "'wght' 660", and that is the face the
     * 4G/5G label is drawn with. See applyNetPaint() for why this number had no
     * visible effect at all until v5.6.
     */
    static final int DEFAULT_NET = 660;
    /**
     * 430 is the ROM's own number (MiuiConfigs sMiproTypeface). v5.7 briefly shipped
     * 500 on the theory that the theme font's 150-700 axis makes 430 look light -
     * ruled out: the user swept several weights with hf_ff_carrier on v5.6 and the
     * lock-screen label did not move AT ALL. Same signature as the 5G label, i.e. we
     * never reach that view; the weight is not the question, the class name is.
     */
    static final int DEFAULT_CARRIER = 430;
    static final int DEFAULT_BATTERY = 500;
    static final int DEFAULT_MARK = 600;
    static final int DEFAULT_HOLLOW = 620;
    static final int DEFAULT_DATE = 0;
    static final int DEFAULT_ISLAND = 0;
    static final int DEFAULT_OTHER = 0;
    /**
     * 900 is the ROM's own request, not a taste call: the speed indicator takes its
     * weight from TextAppearance.StatusBar.NetWorkSpeedNumber (0x7f1506c2) and
     * ...NetWorkSpeedUnit (0x7f1506c3) -- res/layout/network_speed.xml sets nothing
     * else -- and BOTH styles declare android:fontFamily="mipro-bold" plus
     * textStyle=bold. /system/etc/fonts.xml maps mipro-bold to
     * "sans-serif weight=900", so 900 is what MiSansVF is asked for. There is no
     * baked Typeface anywhere on this path (NetworkSpeedView never touches
     * MiuiConfigs), so the alias is the only weight signal -- and under a theme font
     * an alias carries no axis, which is why the label collapses to the fvar default
     * (330 on the tested font). bakeFromFontFile() clamps to whatever the applied
     * font can actually do, so a font topping out at 700 lands on 700.
     */
    static final int DEFAULT_SPEED = 900;
    /**
     * 500, asked for twice by the ROM itself:
     *   1) res/layout/keyguard_status_bar.xml line 85, the Chronometer that carries
     *      @id/alarm_display declares android:fontVariationSettings="'wght' 500";
     *   2) MiuiKeyguardStatusBarView (updateResources + onMiuiThemeChanged) hands
     *      mAlarmText to MiuiConfigs.applyStatusBarTypeface(sMiproTypefaceWght500, ..).
     * Same baked slot as the clock and the battery digits, so nothing here is new:
     * only the view was missing from our role list.
     */
    static final int DEFAULT_ALARM = 500;
    /**
     * 0 = leave the lock-screen date alone, which is what every version up to v1.2
     * did. The ROM asks for regular (400), or 500 while the clock uses dark text, so
     * 400 is the value that reproduces the stock look once a theme font is applied.
     */
    static final int DEFAULT_LOCKDATE = 400;
    /** com.miui.aod is out of scope now; hf_ff_aod=1 re-enables it for experiments. */
    static final int DEFAULT_AOD = 0;
    /**
     * The two process-wide Typeface rewrites: H0a/H0b/H0c answer every "mipro-*"
     * family request in the process, and G1 answers every caller of the one shared
     * MiuiConfigs getter. Neither can see WHICH widget asked, and the caller class
     * is a poor proxy for it, so both re-weighted the Super Island capsule and the
     * control-centre date (the capsule asks for mipro-semibold / mipro-demibold,
     * both of which contain the substring "bold" and used to jump to max(net,
     * carrier)=660). The per-role view hooks cover clock / network / carrier /
     * battery without that collateral, so both global paths are opt-in now.
     */
    static final int DEFAULT_FAMILY = 0;
    /**
     * Still off by default, but no longer needed: the thin keyguard status bar (the
     * operator name on the lock screen) is com.android.keyguard.CarrierText - a
     * TextView we already CLAIM as role=carrier, it was only ever thin because the
     * draw-time channel wrote the (dead) axis string instead of a real Typeface.
     * See stampPaintOf(). G1 stays here as the process-wide experiment it is, now
     * role-aware (globalWeight) instead of a blanket clock-weight stamp.
     */
    static final int DEFAULT_G1 = 0;
    /** Nominal weight of getMiProTypefaceWght460 itself; hf_ff_global. */
    static final int DEFAULT_GLOBAL = 460;
    /** 0 = per-role only. hf_ff_all=N restores the v4.0 "bold everything" mode. */
    static final int DEFAULT_ALL = 0;
    /**
     * This one changes WHAT is displayed rather than how heavy a glyph is, so unlike
     * every weight it stays off until the user asks for it. hf_ff_singlecarrier 1 =
     * on the lock screen show only the primary SIM's operator name.
     *
     * Why it exists: the keyguard carrier block is TWO ControlCenterCarrierText labels
     * (slot 0 = SIM1 first, slot 1 second) with a separator between them, and
     * MiuiKeyguardStatusBarView translates/hides the WHOLE block when the Super Island
     * needs the room - so with two operator names side by side the user often ends up
     * seeing neither, and the left of the keyguard status bar goes empty. Showing only
     * the primary label keeps the block short enough to survive the avoidance.
     * The separators are hidden by the ROM itself: MiuiCarrierTextLayout.onMeasure only
     * shows them while BOTH labels report ControlCenterCarrierText.shouldShow(), and
     * that is exactly the verdict we override (T14).
     */
    static final int DEFAULT_SINGLE = 0;
    private static volatile boolean sAod = false;
    private static volatile int sClockW = DEFAULT_CLOCK;
    private static volatile int sNetW = DEFAULT_NET;
    private static volatile int sCarrierW = DEFAULT_CARRIER;
    private static volatile int sBatteryW = DEFAULT_BATTERY;
    private static volatile int sMarkW = DEFAULT_MARK;
    private static volatile int sHollowW = DEFAULT_HOLLOW;
    private static volatile int sDateW = DEFAULT_DATE;
    private static volatile int sIslandW = DEFAULT_ISLAND;
    private static volatile int sOtherW = DEFAULT_OTHER;
    private static volatile int sSpeedW = DEFAULT_SPEED;
    private static volatile int sAlarmW = DEFAULT_ALARM;
    private static volatile int sLockDateW = DEFAULT_LOCKDATE;
    /** 1 = let H0a/H0b/H0c rewrite mipro-* family resolution process-wide (off default). */
    private static volatile boolean sFamily = DEFAULT_FAMILY != 0;
    /** 1 = let G1 rewrite MiuiConfigs.getMiProTypefaceWght460 for every caller. */
    private static volatile boolean sG1 = DEFAULT_G1 != 0;
    /** Weight the global channels serve callers we cannot classify (ROM nominal). */
    private static volatile int sGlobalW = DEFAULT_GLOBAL;
    /** v4.0 compatibility switch: >0 serves this weight to everything unclassified. */
    private static volatile int sAll = DEFAULT_ALL;
    /** hf_ff_singlecarrier: keyguard shows the primary operator label only. */
    private static volatile boolean sSingleCarrier = DEFAULT_SINGLE != 0;
    private static volatile boolean sLog;
    private static volatile boolean sLogVerbose;
    private static volatile boolean sTriedCloneCtor;
    private static volatile boolean sHasCloneCtor = true;

    private Core() { }

    static void installOnce(ClassLoader cl) {
        if (!sInstalled.compareAndSet(false, true)) return;
        refreshCfg();
        if (mAodProc && !sAod) {
            // com.miui.aod is no longer in the module scope. In that process MiuiConfigs
            // and every SystemUI view class is missing (v3.7 log: hooks=6 instead of 15,
            // a ClassNotFound for T1/T2/T3/T6/T7/T8/T9/T10/G1), so the only thing that
            // ever installed was the process-wide Typeface rewrite - pure collateral damage
            // on the always-on-display and lock-screen clock editors, no benefit at all.
            // hf_ff_aod=1 re-enables it for experiments.
            log("com.miui.aod: out of scope (hf_ff_aod=0), nothing installed");
            return;
        }
        int n = 0;

        // ---------- H0a: Typeface.create(String,int) ----------
        try {
            XposedHelpers.findAndHookMethod(Typeface.class, "create", String.class, int.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            Typeface fixed = fixFamily((String) p.args[0], (Typeface) p.getResult(), "H0a");
                            if (fixed != null) p.setResult(fixed);
                        }
                    });
            n++;
        } catch (Throwable t) { log("H0a fail: " + t); }

        // ---------- H0b: remember family per Builder(String) ----------
        try {
            XposedHelpers.findAndHookConstructor(Typeface.Builder.class, String.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (p.args[0] instanceof String) {
                                sBuilderFamily.put(p.thisObject, (String) p.args[0]);
                            }
                        }
                    });
            n++;
        } catch (Throwable t) { log("H0b fail: " + t); }

        // ---------- H0c: clamp Builder(...).build() ----------
        try {
            XposedHelpers.findAndHookMethod(Typeface.Builder.class, "build",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            String fam = sBuilderFamily.remove(p.thisObject);
                            Typeface fixed = fixFamily(fam, (Typeface) p.getResult(), "H0c");
                            if (fixed != null) p.setResult(fixed);
                        }
                    });
            n++;
        } catch (Throwable t) { log("H0c fail: " + t); }

        // ---------- T1: MiuiConfigs.applyStatusBarTypeface(Typeface, TextView[]/TextView) ----------
        try {
            Class<?> c = XposedHelpers.findClass(MIUI_CONFIGS, cl);
            XC_MethodHook t1h = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    // The baked MiuiConfigs statics are final (reflection set blocked);
                    // the only write point left is the CALL SITE: swap the argument so
                    // the system itself applies our constant-weight Typeface. Fixes the
                    // battery/pull-down revert flicker at its root.
                    refreshCfg();
                    if (!sEnable) return;
                    try {
                        Typeface tf = (Typeface) p.args[0];
                        if (tf == null || !sMcStatics.contains(tf)) return;
                        Object a1 = p.args[1];
                        // One baked slot can be handed to the clock AND the date in the
                        // SAME call, so swapping the argument would re-weight the date as
                        // a side effect. Resolve every view first and only swap when the
                        // whole call wants one single weight; otherwise leave the system
                        // argument alone and let the after-hook work view by view.
                        int w = 0;
                        boolean uniform = true;
                        if (a1 instanceof TextView[]) {
                            TextView[] tvs = (TextView[]) a1;
                            for (int i = 0; i < tvs.length; i++) {
                                if (tvs[i] == null) continue;
                                int wi = wghtFor(roleFor(tvs[i], tf));
                                if (w == 0) w = wi;
                                else if (wi != w) { uniform = false; break; }
                            }
                        } else if (a1 instanceof TextView) {
                            w = wghtFor(roleFor((TextView) a1, tf));
                        }
                        if (w <= 0) return;
                        if (!uniform) {
                            logOnce("t1-mixed", "T1 mixed-weight slot, per-view only");
                            return;
                        }
                        Typeface fixed = cloneWithAxis(tf, w);
                        if (fixed != null) {
                            own(fixed, w);
                            p.args[0] = fixed;
                            if (sLogVerbose) log("T1pre swap->" + w);
                        }
                    } catch (Throwable ignored) { }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    refreshCfg();
                    if (!sEnable) return;
                    if (sMcClass != null && !sMcProbed) {
                        sMcProbed = true;
                        probeMiuiConfigs(sMcClass, "t1");
                    }
                    Object a1 = p.args[1];
                    if (a1 instanceof TextView[]) {
                        TextView[] tvs = (TextView[]) a1;
                        Typeface tf0 = (Typeface) p.args[0];
                        for (TextView tv : tvs) {
                            if (tv == null) continue;
                            int role = roleFor(tv, tf0);
                            sRoles.put(tv, role);
                            int w = wghtFor(role);
                            if (w <= 0) { skipped(tv, role); continue; }
                            forceTypeface(tv, w, "T1");
                        }
                    } else if (a1 instanceof TextView) {
                        TextView tv = (TextView) a1;
                        int role = roleFor(tv, (Typeface) p.args[0]);
                        sRoles.put(tv, role);
                        int w = wghtFor(role);
                        if (w <= 0) skipped(tv, role);
                        else forceTypeface(tv, w, "T1s");
                    }
                }
            };
            XposedHelpers.findAndHookMethod(c, "applyStatusBarTypeface",
                    Typeface.class, TextView[].class, t1h);
            try {
                XposedHelpers.findAndHookMethod(c, "applyStatusBarTypeface",
                        Typeface.class, TextView.class, t1h);
            } catch (Throwable ignored) { }
            n++;
        } catch (Throwable t) { log("T1 fail: " + t); }

        // ---------- T2: NotificationHeaderExpandController.updateWeight(F) ----------
        try {
            Class<?> c = XposedHelpers.findClass(NHEC, cl);
            XposedHelpers.findAndHookMethod(c, "updateWeight", float.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    refreshCfg();
                    if (!sEnable) return;
                    TextView big = findBigTime(p.thisObject);
                    if (big != null) { sRoles.put(big, ROLE_CLOCK); forceTypeface(big, sClockW, "T2"); }
                }
            });
            n++;
        } catch (Throwable t) { log("T2 fail: " + t); }

        // ---------- T3: MobileTypeDrawable.setMiuiStatusBarTypeface(Paint[]) ----------
        try {
            Class<?> c = XposedHelpers.findClass(MOBILE_TYPE_DRAWABLE, cl);
            XposedHelpers.findAndHookMethod(c, "setMiuiStatusBarTypeface",
                    Paint[].class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            refreshCfg();
                            if (!sEnable || sNetW <= 0) return;
                            Paint[] paints = (Paint[]) p.args[0];
                            if (paints == null) return;
                            for (int i = 0; i < paints.length; i++) applyNetPaint(paints[i], "T3");
                        }
                    });
            n++;
        } catch (Throwable t) { log("T3 fail: " + t); }

        // ---------- T9: MobileTypeDrawable.draw -> stamp the 4G/5G axis EVERY frame ----------
        // setMiuiStatusBarTypeface (T3) runs once; a theme font re-apply rebuilds these
        // Paints afterwards and silently drops the 'wght' axis, so 4G/5G stays thin until
        // the next pull-down re-triggers T3 (visible thin -> bold jump). Stamping right
        // before each draw leaves no window in which the thin weight can ever render.
        try {
            Class<?> mtd = XposedHelpers.findClass(MOBILE_TYPE_DRAWABLE, cl);
            XposedHelpers.findAndHookMethod(mtd, "draw", Canvas.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    refreshCfg();
                    if (!sEnable || sNetW <= 0) return;
                    try {
                        stampPaint(p.thisObject, "mMobileTypeTextPaint");
                        stampPaint(p.thisObject, "mMobileTypePlusPaint");
                    } catch (Throwable ignored) { }
                }
            });
            n++;
        } catch (Throwable t) { log("T9 fail: " + t); }

        // ---------- T4: TextView.setTypeface -> REPLACE the incoming Typeface ----------
        try {
            XC_MethodHook rep = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    refreshCfg();
                    if (!sEnable) return;
                    View v = (View) p.thisObject;
                    if (v instanceof TextView && roleExcluded((TextView) v)) return;
                    Integer role = sRoles.get(v);
                    if (role == null) {
                        int r = classify(v.getClass().getName());
                        if (r == ROLE_NONE) return;
                        role = r;
                        sRoles.put(v, role);
                    }
                    Typeface fixed = fixPlain((Typeface) p.args[0], wghtFor(role));
                    if (fixed != null) p.args[0] = fixed;
                }
            };
            XposedHelpers.findAndHookMethod(TextView.class, "setTypeface", Typeface.class, rep);
            XposedHelpers.findAndHookMethod(TextView.class, "setTypeface", Typeface.class, int.class, rep);
            n += 2;
        } catch (Throwable t) { log("T4 fail: " + t); }

        // ---------- T5: Folme VarFontUtils.setVariationFont(TextView,int) ----------
        try {
            Class<?> c = XposedHelpers.findClass(VAR_FONT_UTILS, cl);
            XposedHelpers.findAndHookMethod(c, "setVariationFont",
                    TextView.class, int.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            refreshCfg();
                            if (!sEnable) return;
                            TextView tv = (TextView) p.args[0];
                            Integer role = tv == null ? null : sRoles.get(tv);
                            if (role == null) return;
                            int w = wghtFor(role);
                            if (w > 0) { apply(tv, w, "T5"); p.setResult(null); }
                        }
                    });
            n++;
        } catch (Throwable t) { log("T5 fail: " + t); }

        // ---------- T6: shade expansion callback -> flatten big-clock weight ----------
        // updateWeight(F) is driven per frame by the panel-expansion callback
        // (NotificationHeaderExpandController$notificationCallback$1.onExpansionChanged)
        // and may be AOT-inlined; the callback itself is a stable interface method.
        try {
            Class<?> cb = XposedHelpers.findClass(NHEC + "$notificationCallback$1", cl);
            Class<?> owner = XposedHelpers.findClass(NHEC, cl);
            XposedHelpers.findAndHookMethod(cb, "onExpansionChanged", float.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        refreshCfg();
                        if (!sEnable || sClockW <= 0) return;
                        try {
                            Object o = outerOf(p.thisObject, owner);
                            if (o == null) return;
                            TextView big = findBigTime(o);
                            if (big != null) { sRoles.put(big, ROLE_CLOCK); forceTypeface(big, sClockW, "T6"); }
                        } catch (Throwable ignored) { }
                    }
                });
            n++;
        } catch (Throwable t) { log("T6 fail: " + t); }

        // ---------- T7: MiuiConfigs baked statics: probe lazily on first use ----------
        // (<clinit> cannot be hooked by Xposed; probing on the first
        // applyStatusBarTypeface call guarantees the class is initialized.)
        try {
            sMcClass = XposedHelpers.findClass(MIUI_CONFIGS, cl);
            // Probe NOW: the very first applyStatusBarTypeface call happens during boot,
            // and an empty sMcBattery there would misclassify the battery as CLOCK and
            // freeze that wrong role in sRoles for the rest of the process.
            probeMiuiConfigs(sMcClass, "boot");
            sMcProbed = true;
        } catch (Throwable t) { log("T7 fail: " + t); }

        // ---------- T8: battery digits + '%' mark, tagged by VIEW IDENTITY ----------
        // MiuiBatteryMeterView keeps the digits (mBatteryPercentView) and the '%' sign
        // (mBatteryPercentMarkView) in two SEPARATE TextViews - which is why any content
        // regex missed the battery and instead hit unrelated digit text (e.g. the charging
        // notification inside the Super Island, turned heavy by v3.5). Tag the two fields
        // by object identity: the in-icon digits AND the always-on-display外显 both flow
        // through them, and nothing else does.
        try {
            Class<?> bmv = XposedHelpers.findClass(BATTERY_METER_VIEW, cl);
            XC_MethodHook tag = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    tagBattery(p.thisObject, "T8");
                }
            };
            XposedHelpers.findAndHookMethod(bmv, "onChanged", boolean.class, tag);
            n++;
            try {
                XposedHelpers.findAndHookConstructor(bmv, android.content.Context.class,
                        android.util.AttributeSet.class, tag);
                n++;
            } catch (Throwable ignored) { }
        } catch (Throwable t) { log("T8 fail: " + t); }

        // ---------- T10: hollow battery icon digits + '%' stamped every frame ----------
        try {
            Class<?> hv = XposedHelpers.findClass(HOLLOW_ICON_VIEW, cl);
            try {
                sFontWght = (Typeface) XposedHelpers.getStaticObjectField(
                        XposedHelpers.findClass(BATTERY_UTILS, cl), "FONT_WGHT");
                if (sLog) log("T10 FONT_WGHT resolved=" + (sFontWght != null));
            } catch (Throwable ignored) { }
            XposedHelpers.findAndHookMethod(hv, "onDraw", Canvas.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    refreshCfg();
                    if (!sEnable || sHollowW <= 0) return;
                    stampBatteryPaint(p.thisObject, "textPaint");
                    stampBatteryPaint(p.thisObject, "hollowTextPaint");
                }
            });
            n++;
        } catch (Throwable t) { log("T10 fail: " + t); }

        // ---------- G1: MiuiConfigs.getMiProTypefaceWght460 -> constant clock axis ----------
        // The ONLY Typeface getter on this ROM. Keyguard status bar, the clock controllers
        // and the big-time fallback branch all pull their Typeface from it; it returns a
        // baked instance with NO 'wght' axis (fam=null var=null), which is exactly why the
        // lock screen / full-screen AOD looked thin while the shade was fine.
        try {
            Class<?> mc = sMcClass != null ? sMcClass : XposedHelpers.findClass(MIUI_CONFIGS, cl);
            XposedHelpers.findAndHookMethod(mc, "getMiProTypefaceWght460", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    refreshCfg();
                    if (!sEnable) return;
                    int w = globalWeight();
                    if (w <= 0) return;
                    try {
                        Typeface r = (Typeface) p.getResult();
                        if (r == null || sOurClones.contains(r)) return;
                        Typeface nb = clamp(r, w, "G1");
                        if (nb != null) p.setResult(nb);
                    } catch (Throwable ignored) { }
                }
            });
            n++;
        } catch (Throwable t) { log("G1 fail: " + t); }

        // ---------- T11: clock draw -> last write before rasterisation ----------
        // MiuiClock declares no onDraw of its own, so this hooks TextView.onDraw.
        // That is deliberate: the ONLY thing that still decides the rendered weight
        // is the TextPaint at draw time, whatever the animation wrote a moment
        // earlier (updateWeight / Folme / theme re-apply / setTextAppearance).
        // Views we never claimed keep their own weight, so no unrelated text moves.
        try {
            Class<?> mc = XposedHelpers.findClass(MIUI_CLOCK, cl);
            XC_MethodHook clockDrawHook = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!sEnable) return;
                    TextView tv = (TextView) p.thisObject;
                    Integer role = sRoles.get(tv);
                    if (role == null) {
                        // Hot path: this hook sees every TextView draw in the process.
                        // Resolve the class-name role once per CLASS, not once per frame.
                        Class<?> kc = tv.getClass();
                        Integer cached = sClassRoles.get(kc);
                        if (cached == null) {
                            int r = classify(kc.getName());
                            cached = Integer.valueOf(r);
                            sClassRoles.put(kc, cached);
                        }
                        if (cached.intValue() == ROLE_NONE) {
                            // Some MIUI status-bar labels are drawn by generic text
                            // views whose CLASS name says nothing - the lock-screen
                            // operator label is one of them (plain android.widget
                            // .TextView, id=carrier_text, inside ControlCenterCarrierText
                            // inside MiuiCarrierTextLayout/id=keyguard_carrier_layout),
                            // which is why it stayed thin through v5.1..v5.6 and why no
                            // value of hf_ff_carrier moved it. Two extra signals, both
                            // deliberately carrier-only: the parent chain (carrier chip
                            // container) and the resource ENTRY NAME. Matching ids
                            // generally would be a disaster - AOSP notification rows have
                            // an id called "time".
                            int idr = inCarrierChip(tv) ? ROLE_CARRIER
                                    : (inSpeedChip(tv) ? ROLE_SPEED : roleFromResourceId(tv));
                            if (idr == ROLE_NONE) idr = roleFromLockDate(tv);
                            if (idr == ROLE_NONE) {
                                // Remember the negative verdict too: one parent-chain
                                // walk and at most one id lookup per view, ever.
                                sRoles.put(tv, Integer.valueOf(ROLE_NONE));
                                seenUnclaimed(tv);
                                return;
                            }
                            if (viewExcluded(tv)) idr = ROLE_ISLAND;
                            sRoles.put(tv, Integer.valueOf(idr));
                            role = Integer.valueOf(idr);
                        } else {
                            // Class name did classify: still decide the island veto once
                            // PER INSTANCE (the parent-chain walk is the expensive part)
                            // and remember the verdict, so a capsule widget can never be
                            // pinned as something else by its class name alone.
                            int idr2 = ROLE_NONE;
                            if (cached.intValue() == ROLE_DATE) {
                                // android.widget.Chronometer is TWO widgets here: the
                                // elapsed-time clocks nobody asked about, and the alarm
                                // countdown that takes over the carrier slot on the lock
                                // screen (id/alarm_display, ROM weight 500). Identical
                                // class name, so a DATE verdict only sticks after the
                                // resource id has failed to claim it.
                                idr2 = roleFromResourceId(tv);
                                if (idr2 == ROLE_NONE) idr2 = roleFromLockDate(tv);
                            }
                            if (idr2 != ROLE_NONE) {
                                role = Integer.valueOf(viewExcluded(tv) ? ROLE_ISLAND : idr2);
                            } else {
                                role = viewExcluded(tv)
                                        ? Integer.valueOf(ROLE_ISLAND) : cached;
                            }
                            sRoles.put(tv, role);
                        }
                    }
                    int w = wghtFor(role.intValue());
                    if (w <= 0) { skipped(tv, role.intValue()); return; }
                    stampPaintOf(tv, w, role.intValue());
                    drawDiag(tv, role.intValue(), w);
                }
            };
            // On some ROM builds the inherited onDraw is not accepted by the
            // single-method finder. Prefer inherited matching, then fall back to
            // the declared TextView method so the draw-time authority still lands.
            java.lang.reflect.Method[] onDraws = XposedHelpers.findMethodsByExactParameters(
                    mc, Canvas.class, new Class<?>[] { Canvas.class });
            if (onDraws != null && onDraws.length > 0) {
                for (int i = 0; i < onDraws.length; i++) {
                    XposedBridge.hookMethod(onDraws[i], clockDrawHook);
                    n++;
                }
            log("T11 hooked MiuiClock onDraw x" + onDraws.length);
            } else {
                java.lang.reflect.Method tvOnDraw = XposedHelpers.findMethodExact(
                        TextView.class, "onDraw", Canvas.class);
                XposedBridge.hookMethod(tvOnDraw, clockDrawHook);
                n++;
            log("T11 fallback TextView onDraw");
            }
            // The all-in-one / classic clock styles' date line is ClassicTextAreaView: it
            // overrides onDraw WITHOUT calling super, so the TextView hook above never
            // sees it even though it paints through getPaint(). Give that one method the
            // same draw-time authority. It is the only clock-package class that needs it;
            // the digits keep their own wght animation.
            try {
                Class<?> ta = XposedHelpers.findClass(
                        "com.miui.clock.classic.ClassicTextAreaView", cl);
                java.lang.reflect.Method taDraw = XposedHelpers.findMethodExact(
                        ta, "onDraw", Canvas.class);
                XposedBridge.hookMethod(taDraw, clockDrawHook);
                n++;
                log("T11 hooked ClassicTextAreaView onDraw");
            } catch (Throwable t) { log("T11 classic text area fail: " + t); }
        } catch (Throwable t) { log("T11 fail: " + t); }

        // ---------- T12: header resource/theme refresh ----------
        // updateResources$16 re-runs on every theme or font change and re-applies a
        // text appearance + family Typeface to the big clock; nothing else in our
        // chain sees that, so previously the fix only survived until the next
        // pull-down. Re-assert immediately.
        try {
            Class<?> hv = XposedHelpers.findClass(HEADER_VIEW, cl);
            XposedHelpers.findAndHookMethod(hv, "updateResources$16", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    refreshCfg();
                    if (!sEnable || sClockW <= 0) return;
                    try {
                        Object big = XposedHelpers.getObjectField(p.thisObject, "mBigTime");
                        if (big instanceof TextView) {
                            TextView tv = (TextView) big;
                            sRoles.put(tv, Integer.valueOf(ROLE_CLOCK));
                            forceTypeface(tv, sClockW, "T12");
                        }
                    } catch (Throwable ignored) { }
                    // updateResources$NN is also the theme / font-change signal: the family
                    // we borrowed for the lock-screen date belongs to the OLD font now, so
                    // throw away the derived faces and let T13 capture the new one.
                    if (sFamilyRef != null) {
                        sFamilyRef = null;
                        sThemeFaces.clear();
                        sFixed.clear();
                        log("theme change: lock-date family source dropped");
                    }
                }
            });
            n++;
        } catch (Throwable t) { log("T12 fail: " + t); }

        // ---------- T13: lock-screen date at the ROM's own write point ----------
        // com.miui.clock.MiuiBaseClock.setTextFontDark(Z) is the ONLY place the keyguard
        // clock faces hand the date line a Typeface, and it also runs on every wallpaper
        // palette change (MiuiClockController -> setTextColorDark -> setTextFontDark).
        // Hooking it does two things the draw hook cannot: it captures the FAMILY the
        // same clock face paints its big time with (themeFaceAt), and it re-asserts the
        // date face right after the ROM overwrote it, so the very first frame is already
        // correct. MiuiBaseClock.onFinishInflate covers the styles that never see a
        // palette change. All four clock styles (LeftTop / LeftTopLarge / Vertical /
        // CenterHorizontal) override setTextFontDark and call invoke-super, so hooking
        // the base method once is enough; the subclass hooks below are belt-and-braces
        // for styles that skip super.
        try {
            XC_MethodHook dateFace = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    lockDateFromClock(p.thisObject, "T13");
                }
            };
            Class<?> base = XposedHelpers.findClass(MIUI_BASE_CLOCK, cl);
            XposedHelpers.findAndHookMethod(base, "setTextFontDark", boolean.class, dateFace);
            XposedHelpers.findAndHookMethod(base, "onFinishInflate", dateFace);
            n += 2;
            String[] styles = {
                    "com.miui.clock.MiuiLeftTopClock",
                    "com.miui.clock.MiuiLeftTopLargeClock",
                    "com.miui.clock.MiuiVerticalClock",
                    "com.miui.clock.MiuiCenterHorizontalClock",
            };
            for (int i = 0; i < styles.length; i++) {
                try {
                    Class<?> c = XposedHelpers.findClass(styles[i], cl);
                    XposedHelpers.findAndHookMethod(c, "setTextFontDark", boolean.class, dateFace);
                    n++;
                } catch (Throwable ignored) { }
            }
            log("T13 lock-screen date hooked at MiuiBaseClock");
        } catch (Throwable t) { log("T13 fail: " + t); }

        // ---------- T14: keyguard carrier verdict ----------
        // ControlCenterCarrierText.shouldShow() is the single input MiuiCarrierTextLayout
        // .onMeasure uses for BOTH of its decisions: whether the separator between the
        // two operator labels is visible, and how the available width is split. Answering
        // "no" for the SECOND label (only on the keyguard instance, only while the
        // primary label has something to show, only with hf_ff_singlecarrier=1) therefore
        // hides the " | " separator with the ROM's own code instead of fighting it.
        try {
            Class<?> widget = XposedHelpers.findClass(CARRIER_TEXT_WIDGET, cl);
            XposedHelpers.findAndHookMethod(widget, "shouldShow", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (secondaryCarrierSuppressed(p.thisObject)) p.setResult(Boolean.FALSE);
                }
            });
            n++;
        } catch (Throwable t) { log("T14 fail: " + t); }

        // ---------- T15: hide the second label itself ----------
        // T14 only shrinks the layout's own arithmetic; the second ControlCenterCarrierText
        // is still a VISIBLE container holding its (still filled) TextView, so it has to be
        // taken out of the line. Doing it in BEFORE onMeasure means the ROM's very next
        // measurement already ignores it, and the width the keyguard status bar sees is the
        // short one - which is the whole point: the Super Island avoidance then has room.
        // Nothing in the ROM ever sets the visibility of these two containers (it only
        // touches their children), so the views we hide are ours to restore.
        try {
            Class<?> layout = XposedHelpers.findClass(CARRIER_LAYOUT, cl);
            XposedHelpers.findAndHookMethod(layout, "onMeasure", int.class, int.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            applySingleCarrier(p.thisObject);
                        }
                    });
            n++;
        } catch (Throwable t) { log("T15 fail: " + t); }

        refreshCfg();
        log("v1.4.0 PER-ROLE installed, hooks=" + n
                + " clock=" + sClockW + " net=" + sNetW + " carrier=" + sCarrierW
                + " batt=" + sBatteryW + " mark=" + sMarkW + " hollow=" + sHollowW
                + " date=" + sDateW + " island=" + sIslandW + " other=" + sOtherW
                + " speed=" + sSpeedW
                + " alarm=" + sAlarmW
                + " lockdate=" + sLockDateW
                + " singlecarrier=" + (sSingleCarrier ? 1 : 0)
                + " family=" + (sFamily ? 1 : 0) + " g1=" + (sG1 ? 1 : 0)
                + " global=" + sGlobalW + " all=" + sAll
                + " aod=" + (sAod ? 1 : 0)
                + " log=" + (sLogVerbose ? 2 : (sLog ? 1 : 0)));
        scheduleSelfTest();
    }

    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, Integer> sClassRoles =
            new java.util.concurrent.ConcurrentHashMap<Class<?>, Integer>();

    /** Rate-limited draw-time proof: is T11 seeing the right view/typeface? */
    private static final Map<String, Long> sDrawDiagAt =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();

    /**
     * Paint-only weight stamp; never invalidates or relayouts from inside a draw.
     *
     * v5.1..v5.5 only wrote the 'wght' STRING here, which turned out to be a dead
     * channel for native/theme typefaces (see applyNetPaint) - the views that were
     * claimed but never re-baked by T1/T2/T6/T12 therefore stayed thin, the keyguard
     * operator name being the visible one. So along with the string we now write a
     * real baked Typeface into both paints. Paint-level Typeface replacement cannot
     * trigger a layout, and the applied font has no HVAR/MVAR (advance widths do not
     * depend on the axis), so the glyph run cannot reflow or clip.
     */
    private static void stampPaintOf(TextView tv, int wght, int role) {
        try {
            String want = "'wght' " + wght;
            TextPaint tp = tv.getPaint();
            TextPaint lp = layoutPaint(tv);
            if (tp != null) stamp(tp, want);
            if (lp != null) stamp(lp, want);
            if (!drawFaceAllowed(tv, role)) return;
            Typeface base = tv.getTypeface();
            if (base == null) return;
            // ROLE_LOCKDATE is the one role that needs a different FAMILY: its own face
            // comes from the dead "mipro-regular" alias (MiSans), so re-baking the axis of
            // that face would faithfully reproduce MiSans at exactly the weight we asked
            // for - which is what v1.3 did. Everything else keeps its family and only gets
            // the axis moved.
            Typeface face;
            if (role == ROLE_LOCKDATE) {
                face = themeFaceAt(wght, "draw");
            } else if (sOurClones.contains(base)) {
                // Ours is a constant-axis face: if the config moved on, the string
                // stamp above cannot move it, so bake the requested weight again.
                face = cloneWeight(base) == wght ? base : rebake(base, wght);
            } else {
                face = clamp(base, wght, "draw");
            }
            if (face == null) return;
            if (tp != null && tp.getTypeface() != face) {
                tp.setTypeface(face);
                logOnce("face:" + tv.getClass().getName(), "T11 face " + wght + " -> "
                        + tv.getClass().getSimpleName());
            }
            if (lp != null && lp.getTypeface() != face) lp.setTypeface(face);
        } catch (Throwable ignored) { }
    }

    /**
     * Blast-radius guard for the draw-time Typeface write (stampPaintOf).
     *
     * The lock screen ships a whole zoo of clock faces (com.miui.clock.*,
     * com.android.keyguard.clock.*, com.android.systemui.customization.clocks.*)
     * whose class names contain "clock" and therefore classify as ROLE_CLOCK, and
     * they animate the wght axis themselves (DigitalClockTextView$AxisAnimation,
     * ClockAxisStyle, AxisPresetConfig) using their own bundled fonts. v5.1..v5.5
     * only ever wrote the dead axis string there, so opening that surface now would
     * create a new bug rather than fix one. Status-bar/board chrome only,.plus any
     * *CarrierText - the lock-screen operator name is com.android.keyguard.CarrierText.
     */
    private static final String[] DRAW_FACE_PACKAGES = {
            "com.android.systemui.statusbar.",
            "com.android.systemui.controlcenter.",
            "com.android.systemui.shade.",
            "com.android.systemui.carrier.",
            "com.android.systemui.qs.",
    };
    private static final Map<String, Boolean> sDrawFaceOk =
            new java.util.concurrent.ConcurrentHashMap<String, Boolean>();

    private static boolean drawFaceAllowed(TextView tv, int role) {
        // These roles win outright: the role can only have come from a class name
        // or a resource entry name that says carrier / network-speed / alarm_display,
        // and the views are plain android.widget.TextView or android.widget.Chronometer
        // (id=carrier_text, id/network_speed_number|_unit, id/alarm_display), i.e.
        // classes the package list below could never recognise. The alarm countdown
        // is a Chronometer, so without this draw-time gate it would be allowed only
        // by coincidence of a class name and then stamped by an inapplicable weight.
        if (role == ROLE_CARRIER || role == ROLE_SPEED || role == ROLE_ALARM) return true;
        // The lock-screen date is a plain TextView inside a clock face, i.e. exactly the
        // combination this guard exists to refuse. It gets in on its own role, which is
        // only ever assigned to a view whose resource entry name says "date" AND whose
        // parent chain contains a clock face (see roleFromLockDate) - the two conditions
        // together are what keeps this open without opening the clock-face zoo.
        if (role == ROLE_LOCKDATE) return true;
        String n = tv.getClass().getName();
        Boolean known = sDrawFaceOk.get(n);
        if (known != null) {
            if (known.booleanValue()) return true;
            logOnce("drawface:" + n, "T11 face refused " + n);
            return false;
        }
        // Any carrier label, wherever it lives: classify() already limits the
        // CARRIER role to class names that say "carrier", so widening the draw-time
        // guard for that one role cannot reach a clock face, a date or a capsule.
        boolean ok = n.toLowerCase().indexOf("carrier") >= 0;
        if (!ok) {
            for (int i = 0; i < DRAW_FACE_PACKAGES.length; i++) {
                if (n.startsWith(DRAW_FACE_PACKAGES[i])) { ok = true; break; }
            }
        }
        sDrawFaceOk.put(n, Boolean.valueOf(ok));
        if (!ok) logOnce("drawface:" + n, "T11 face refused " + n);
        return ok;
    }

    /**
     * The Layout the view is about to draw keeps its OWN TextPaint copy
     * (Layout#getPaint), and that copy - not TextView's - is the paint the text
     * is shaped and rasterised with. Writing TextView#getPaint() only can leave
     * the current frame untouched, so both are stamped. Safe here and only here:
     * the applied font has no HVAR/MVAR table, so the axis cannot change advance
     * widths and a stamp without a re-layout can never reflow or clip text.
     */
    private static TextPaint layoutPaint(TextView tv) {
        try {
            android.text.Layout l = tv.getLayout();
            return l == null ? null : l.getPaint();
        } catch (Throwable ignored) { return null; }
    }

    private static void drawDiag(TextView tv, int role, int wght) {
        if (!sLogVerbose || tv == null) return;
        long now = System.currentTimeMillis();
        Long last = sDrawDiagAt.get(roleName(role));
        if (last != null && now - last.longValue() < 1000) return;
        sDrawDiagAt.put(roleName(role), Long.valueOf(now));
        try {
            TextPaint tp = tv.getPaint();
            TextPaint lp = layoutPaint(tv);
            Typeface tf = tv.getTypeface();
            log("T11DIAG " + roleName(role) + " @" + tv.getClass().getSimpleName()
                    + " want=" + wght + " ourtf=" + (tf != null && sOurClones.contains(tf))
                    + " tvaxis=" + tv.getFontVariationSettings()
                    + " paint=" + (tp == null ? "null" : tp.getFontVariationSettings())
                    + " layout=" + (lp == null ? "none" : String.valueOf(lp.getFontVariationSettings()))
                    + " tfw=" + (tf == null ? -1 : XposedHelpers.callMethod(tf, "getWeight"))
                    + " ptFW=" + (tp == null || tp.getTypeface() == null ? -1
                            : XposedHelpers.callMethod(tp.getTypeface(), "getWeight"))
                    + " ours=" + (tf != null && sOurClones.contains(tf)));
        } catch (Throwable ignored) { }
    }

    private static void stamp(TextPaint p, String want) {
        try {
            if (p == null) return;
            if (!want.equals(p.getFontVariationSettings())) p.setFontVariationSettings(want);
            if (p.isFakeBoldText()) p.setFakeBoldText(false);
        } catch (Throwable ignored) { }
    }

    /**
     * Log every static Typeface field of MiuiConfigs (family + baked variation) and
     * re-bake the ones whose baked name maps to one of our roles. Idempotent.
     */
    private static void probeMiuiConfigs(Class<?> mc, String when) {
        java.lang.reflect.Field[] fs;
        try { fs = mc.getDeclaredFields(); } catch (Throwable t) { return; }
        for (int i = 0; i < fs.length; i++) {
            java.lang.reflect.Field f = fs[i];
            try {
                if (!Typeface.class.equals(f.getType())) continue;
                if ((f.getModifiers() & java.lang.reflect.Modifier.STATIC) == 0) continue;
                f.setAccessible(true);
                Typeface tf = (Typeface) f.get(null);
                if (tf == null) continue;
                if (!sOurClones.contains(tf)) {
                    sMcStatics.add(tf);
                    if (f.getName().toLowerCase().contains("500")
                            || f.getName().toLowerCase().contains("600")) sMcBattery.add(tf);
                }
                if (sOurClones.contains(tf)) continue;
                String fam = null;
                try { fam = (String) XposedHelpers.callMethod(tf, "getFamily"); } catch (Throwable ignored) { }
                log("MCFG(" + when + ")." + f.getName() + " fam=" + fam
                        + " var=" + variationOf(tf) + " style=" + tf.getStyle());
                String ln = f.getName().toLowerCase();
                int target = -1;
                if (ln.contains("bold")) target = Math.max(sNetW, sCarrierW);
                else if (ln.contains("500") || ln.contains("600")) target = sBatteryW;
                else if (ln.contains("460") || ln.contains("wght")) target = sClockW;
                if (target <= 0) continue;
                Typeface fixed = cloneWithAxis(tf, target);
                if (fixed == null) continue;
                own(fixed, target);
                try {
                    f.set(null, fixed);
                    log("MCFG(" + when + ")." + f.getName() + " re-baked -> " + target);
                } catch (Throwable t2) {
                    log("MCFG(" + when + ")." + f.getName() + " set fail: " + t2);
                }
            } catch (Throwable ignored) { }
        }
    }

    private static final Map<String, Long> sDiagAt =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();

    /**
     * Rate-limited (1/s per hook tag) read-back of the three places a weight can
     * live in: the TextView, its TextPaint and the Layout's own copy. Typeface has
     * no variation getter on this API level (only getWeight/getStyle/
     * getSystemFontFamilyName), so the paint strings are the evidence.
     */
    private static void diag(TextView tv, TextPaint tp, String via) { // TEMP-DIAG
        if (!sLog) return;
        long now = System.currentTimeMillis();
        Long last = sDiagAt.get(via);
        if (last != null && now - last.longValue() < 1000) return;
        sDiagAt.put(via, Long.valueOf(now));
        try {
            Typeface ttf = tv.getTypeface();
            TextPaint lp = layoutPaint(tv);
            log("APPLY " + via + " @" + tv.getClass().getSimpleName()
                    + " tv=" + tv.getFontVariationSettings()
                    + " tf=" + (ttf == null ? "null" : ttf.getClass().getSimpleName()
                            + "/w" + XposedHelpers.callMethod(ttf, "getWeight"))
                    + " paint=" + (tp == null ? "null" : tp.getFontVariationSettings())
                    + " layout=" + (lp == null ? "none" : String.valueOf(lp.getFontVariationSettings()))
                    + " bold=" + (tp != null && tp.isFakeBoldText()));
        } catch (Throwable ignored) { }
    }

    static void setRemoteStore(SharedPreferences sp) { sRemote = sp; }

    static void setCtx(Context c) { if (c != null) sCtx = c; }

    static void pokeCfg() { sCfgTs = 0; refreshCfg(); }

    // ------------------------------------------------------------------
    // Typeface source clamping
    // ------------------------------------------------------------------

    private static Typeface fixFamily(String fam, Typeface res, String via) {
        try {
            if (fam == null || res == null) return null;
            String fl = fam.toLowerCase();
            // mipro* families and MIUI ramp builders named "'wght' N" both flow here
            if (!fl.contains("mipro") && !fl.contains("wght")) return null;
            refreshCfg();
            if (!sEnable || (!sFamily && sAll <= 0)) return null;
            // This hook answers EVERY "mipro-*" / "'wght' N" family request in the whole
            // process, which is exactly how the 超级岛 capsule got bolded: only reply for
            // callers we are not vetoing (see familyCallerAllowed).
            if (!familyCallerAllowed()) return null;
            int w = familyWeight(fl);
            if (w <= 0) return null;
            return clamp(res, w, via + " " + fam);
        } catch (Throwable t) {
            if (sLog) log("fixFamily err " + t);
            return null;
        }
    }

    /**
     * Weight for a process-wide family request, used only by the aggressive
     * hf_ff_family path. It names the MIUI ramp entries explicitly instead of
     * substring-testing "bold": mipro-demibold and mipro-semibold both contain it,
     * which is what turned the Super Island capsule into 660-heavy text.
     */
    private static int familyWeight(String fl) {
        if (fl.contains("demibold") || fl.contains("semibold") || fl.contains("bold"))
            return Math.max(sNetW, sCarrierW);
        return sClockW;
    }

    private static Typeface fixPlain(Typeface res, int w) {
        try {
            refreshCfg();
            if (!sEnable) return null;
            return clamp(res, w, "clone");
        } catch (Throwable t) {
            if (sLog) log("fixPlain err " + t);
            return null;
        }
    }

    private static Typeface clamp(Typeface res, int w, String label) {
        if (res == null || w <= 0) return null;
        if (sOurClones.contains(res)) return null;
        String key = System.identityHashCode(res) + "|" + w;
        Typeface fixed = sFixed.get(key);
        if (fixed == null) {
            fixed = cloneWithAxis(res, w);
            if (fixed == null) return null;
            own(fixed, w);
            sFixed.put(key, fixed);
        }
        logOnce("clamp:" + label, label + " re-bake -> axis " + w);
        return fixed;
    }

    /** Parse numeric wght from "'wght' 460..." -> 460; -1 if absent/unparsable. */
    private static int parseWght(String settings) {
        try {
            int i = settings.indexOf("wght");
            if (i < 0) return -1;
            int j = i + 4;
            while (j < settings.length() && (settings.charAt(j) == ' ' || settings.charAt(j) == 39)) j++;
            int k = j;
            boolean dot = false;
            while (k < settings.length()) {
                char c = settings.charAt(k);
                if (c >= '0' && c <= '9') { k++; }
                else if (c == '.' && !dot) { dot = true; k++; }
                else break;
            }
            if (k == j) return -1;
            return (int) Float.parseFloat(settings.substring(j, k));
        } catch (Throwable t) { return -1; }
    }

    private static String variationOf(Typeface tf) {
        try { return (String) XposedHelpers.callMethod(tf, "getVariationSettings"); }
        catch (Throwable t) { return null; }
    }

    /**
     * Rebuild a real-'wght'-axis Typeface at the target weight.
     * Recipe identical to HyperLight's proven builder (d81.l(I)):
     *   1) new Typeface.Builder(family).setFontVariationSettings("'wght' N").build()
     *   2) Typeface.create(base, weight, false)   <-- public since API 31,
     *      the REAL font-weight API that drives the variable axis (no fake bold).
     * The former clone-ctor path (Typeface.Builder(Typeface,boolean)) does NOT
     * exist on this ROM; it silently returned null and disabled every
     * typeface-level replacement (root cause of the v3.1/v3.2 issues).
     */
    private static Typeface cloneWithAxis(Typeface base, int wght) {
        try {
            // Preferred route: bake our axis straight out of the font FILE that the
            // system's own ramp is built from (stock MiSansVF, or the single theme
            // font file once a third-party font is applied). This is exactly what
            // MiuiConfigs does, so the result is a real fvar instance with no
            // dependency on a clone constructor or on family-name resolution.
            Typeface byFile = Boolean.FALSE.equals(sBakeTrust)
                    ? null : bakeFromFontFile(wght);
            if (byFile != null) { logClonePath("file"); return byFile; }
            if (Boolean.FALSE.equals(sBakeTrust)) {
                logOnce("bake-off", "bake skipped: font file != on-screen face (paint channel only)");
            }
            String fam = null;
            try { fam = (String) XposedHelpers.callMethod(base, "getFamily"); } catch (Throwable ignored) { }
            if (fam != null && fam.length() > 0 && !fam.startsWith("'") && !fam.contains("wght")) {
                try {
                    Typeface.Builder b = new Typeface.Builder(fam);
                    b.setFontVariationSettings("'wght' " + wght);
                    Typeface t = b.build();
                    if (t != null) { logClonePath("builder:" + fam); return t; }
                } catch (Throwable ignored) { }
            }
            try {
                Typeface t = Typeface.create(base, wght, false);
                logClonePath("create3");
                return t;
            } catch (Throwable t2) {
                if (sLog) log("clone3 fail " + t2);
            }
            return null;
        } catch (Throwable t) {
            if (sLog) log("clone fail " + t);
            return null;
        }
    }

    private static final Map<String, Typeface> sBaked = new ConcurrentHashMap<String, Typeface>();
    private static volatile String sFontPath;
    private static volatile long sFontPathAt;

    /**
     * A Typeface that is known to render with the font the user actually applied, used
     * only to give the lock-screen date a family. Captured from the clock face's own time
     * view (MiuiBaseClock.getTimeView()) in T13, i.e. from the text painted immediately
     * above the date line - whatever the theme engine did to that one is exactly what the
     * date should look like. Never one of our own clones.
     */
    private static volatile Typeface sFamilyRef;
    private static final Map<String, Typeface> sThemeFaces =
            new ConcurrentHashMap<String, Typeface>();

    private static String familyOf(Typeface tf) {
        if (tf == null) return null;
        try { return (String) XposedHelpers.callMethod(tf, "getFamily"); }
        catch (Throwable t) { return null; }
    }

    /**
     * `ro.miui.ui.font.mi_font_path` never changes during a process, and themeFaceAt()
     * needs it on the DRAW path to tell "we found the theme file" apart from "we fell back
     * to the stock file" - so read it once. Reflection per frame would be silly.
     */
    private static volatile String sStockPath;
    private static volatile boolean sStockPathProbed;

    private static String stockPath() {
        if (sStockPathProbed) return sStockPath;
        String p = sysProp(MI_FONT_PATH_PROP);
        sStockPath = p;
        sStockPathProbed = true;
        return p;
    }

    /**
     * Family name of the font the ROM itself ships (ro.miui.ui.font.mi_font_path, i.e.
     * MiSans on this build). Read once from the FILE, because that is the only answer that
     * cannot have been influenced by the theme engine, and it is what lets us tell
     * "this face really is the user's theme font" apart from "this face is still the stock
     * font wearing a different alias".
     */
    private static volatile String sStockFamily;
    private static volatile boolean sStockFamilyProbed;

    private static String stockFamily() {
        if (sStockFamilyProbed) return sStockFamily;
        try {
            String p = stockPath();
            if (p != null && p.length() > 0) sStockFamily = familyOf(rawBake(p));
        } catch (Throwable ignored) { }
        // Latch afterwards: until the first answer lands themedFamily() accepts, which is
        // the same thing it would do on a ROM without the property.
        sStockFamilyProbed = true;
        if (sLog) log("stock family=" + sStockFamily);
        return sStockFamily;
    }

    /**
     * Does this face carry a family that is NOT the ROM's built-in font? Some clock styles
     * paint their digits with Typeface.create("miclock-time", 0) and that alias does not
     * exist in the ROM's font config either, so the sibling next to the date line can be
     * MiSans - adopting it blindly would reproduce the exact bug this is meant to fix.
     */
    private static boolean themedFamily(Typeface tf) {
        String fam = familyOf(tf);
        if (fam == null || fam.length() == 0 || fam.startsWith("'")) return false;
        String stock = stockFamily();
        return stock == null || stock.length() == 0 || !stock.equalsIgnoreCase(fam);
    }

    /**
     * Remember where "the applied font" comes from, so the derived faces can be rebuilt
     * when the font changes. A theme or font change clears it (T12) and the next palette
     * change supplies the new one.
     */
    private static void rememberClockFace(Typeface tf, String via) {
        try {
            if (tf == null || sOurClones.contains(tf)) return;
            Typeface cur = sFamilyRef;
            if (cur != null && familyOf(tf).equals(familyOf(cur))) return;
            if (!themedFamily(tf)) {
                // Not a rejection of the whole idea, just of this source - themeFaceAt
                // still has the theme font FILE and the default family to try.
                logOnce("famref:" + via + ":" + familyOf(tf),
                        "lockdate family source refused via=" + via + " fam=" + familyOf(tf)
                        + " (stock=" + stockFamily() + ")");
                return;
            }
            String fam = familyOf(tf);
            sFamilyRef = tf;
            sThemeFaces.clear();
            log("lockdate family source=" + via + " fam=" + fam + " var=" + variationOf(tf));
        } catch (Throwable ignored) { }
    }

    /** Re-weight one candidate family source; cached per (instance, weight). */
    private static Typeface reweight(Typeface ref, int wght, String via, String tag) {
        if (ref == null) return null;
        try {
            String key = System.identityHashCode(ref) + "@" + wght;
            Typeface hit = sThemeFaces.get(key);
            if (hit == null) {
                try { hit = Typeface.create(ref, wght, false); } catch (Throwable t) { hit = null; }
                if (hit != null) {
                    own(hit, wght);
                    sThemeFaces.put(key, hit);
                    log("lockdate face source=" + tag + " via=" + via + " fam=" + familyOf(hit)
                            + " w=" + wght + " var=" + variationOf(hit));
                }
            }
            return hit;
        } catch (Throwable t) {
            if (sLog) log("reweight(" + tag + ") err " + t);
            return null;
        }
    }

    /**
     * A face with the family the rest of the screen uses, at our weight - the answer to
     * "the lock-screen date is still MiSans". Order matters:
     *   1) the sibling face of the same clock face (T13), but only when its family is
     *      provably not the stock font: the face is re-weighted with the real font-weight
     *      API (Typeface.create(base, weight, false)), so its family cannot drift from
     *      what the ROM paints one line above the date;
     *   2) a fresh bake of the file the theme engine landed in /data/system/theme/fonts/,
     *      i.e. the same source MiuiConfigs' own ramp is rebuilt from once a font is
     *      applied - this is the route the lock-date unification has always used;
     *   3) a bake of the stock file: with no theme file in play that is exactly what the
     *      dead alias hands the view anyway, so it can only reproduce the ROM's own look;
     *   4) last, the process default family (Typeface.DEFAULT). It is what third-party
     *      apps follow when a theme font is applied, but it is a Latin-first family with
     *      its CJK glyphs coming from a fallback list, so it is the one candidate we cannot
     *      prove keeps the Chinese date characters - hence the last chance, used when the
     *      file route is unavailable or distrusted (see faceMatchCheck/sBakeTrust).
     * Returns null only if every route failed: callers then leave the view alone rather
     * than install a face whose family they only believe they know.
     */
    private static Typeface themeFaceAt(int wght, String via) {
        if (wght <= 0) return null;
        Typeface hit = reweight(sFamilyRef, wght, via, "clockface");
        if (hit != null) return hit;
        boolean trustBake = !Boolean.FALSE.equals(sBakeTrust);
        String path = fontPath();
        String stock = stockPath();
        if (trustBake && path != null) {
            hit = bakeFromFontFile(wght);
            if (hit != null) {
                // "themed" here only means: the file we just baked is not the stock one.
                logOnce("bakepath:" + path + ":" + wght,
                        "lockdate baked from " + path
                        + (path.equals(stock) ? " (stock)" : " (theme file)"));
                return hit;
            }
        }
        return reweight(Typeface.DEFAULT, wght, via, "default");
    }

    /**
     * T13 body: take the two date TextViews of one clock face away from the dead
     * "mipro-regular" alias. Also grabs the family source from the face's own time view
     * while we are here - at this point the ROM has just written ITS faces, so the time
     * view still carries what the theme engine produced.
     */
    private static void lockDateFromClock(Object clock, String via) {
        refreshCfg();
        if (!sEnable || clock == null) return;
        int w = sLockDateW;
        if (w <= 0) return;
        try {
            Object time = XposedHelpers.callMethod(clock, "getTimeView");
            if (time instanceof TextView) rememberClockFace(((TextView) time).getTypeface(), via);
        } catch (Throwable ignored) {
            // Not every style exposes a time view; themeFaceAt() still has the file route.
        }
        String[] fields = { "mCurrentDate", "mLunarCalendarInfo" };
        for (int i = 0; i < fields.length; i++) {
            try {
                Object o = XposedHelpers.getObjectField(clock, fields[i]);
                if (!(o instanceof TextView)) continue;
                TextView tv = (TextView) o;
                sRoles.put(tv, Integer.valueOf(ROLE_LOCKDATE));
                forceTypeface(tv, w, via);
            } catch (Throwable ignored) { }
        }
    }

    /**
     * Which of the MiuiCarrierTextLayout instances is this? The class is used by the
     * control centre (id/normal_control_center_carrier_layout), the notification shade
     * (id/notification_shade_carrier_layout) and the lock screen
     * (id/keyguard_carrier_layout, res/layout/keyguard_status_bar.xml line 49), and the
     * ROM tells them apart with the Kotlin lazy keyguardHeaderLayout$delegate
     * (MiuiCarrierTextLayout$$ExternalSyntheticLambda0: getId() == id/keyguard_carrier_layout).
     * Ask the ROM first, fall back to our own resource entry name. Cached per id.
     */
    private static final Map<Integer, Boolean> sKeyguardLayout =
            new ConcurrentHashMap<Integer, Boolean>();

    private static boolean inKeyguardCarrierLayout(android.view.View v) {
        int id;
        try { id = v.getId(); } catch (Throwable t) { return false; }
        if (id == 0) return false;
        Boolean known = sKeyguardLayout.get(Integer.valueOf(id));
        if (known != null) return known.booleanValue();
        boolean ok = false;
        try {
            Object lazy = XposedHelpers.getObjectField(v, "keyguardHeaderLayout$delegate");
            if (lazy != null) ok = Boolean.TRUE.equals(XposedHelpers.callMethod(lazy, "getValue"));
        } catch (Throwable ignored) { }
        if (!ok) {
            try {
                String n = v.getResources().getResourceEntryName(id);
                ok = n != null && n.toLowerCase().indexOf("keyguard") >= 0;
            } catch (Throwable ignored) { }
        }
        sKeyguardLayout.put(Integer.valueOf(id), Boolean.valueOf(ok));
        if (sLog) log("carrier-layout @" + id + " keyguard=" + ok);
        return ok;
    }

    /** Does the FIRST label of this layout have something to draw? ROM's own verdict. */
    private static boolean primaryCarrierShows(Object layout) {
        try {
            // leftCarrierTextView is a ControlCenterCarrierText (a LinearLayout that OWNS
            // the label), not a TextView - the TextView check this shipped with in v1.4
            // was therefore always false and neither T14 nor T15 ever did anything.
            Object left = XposedHelpers.getObjectField(layout, "leftCarrierTextView");
            if (left == null) return false;
            return Boolean.TRUE.equals(XposedHelpers.callMethod(left, "shouldShow"));
        } catch (Throwable t) { return false; }
    }

    /**
     * Is this ControlCenterCarrierText the one we suppress? Three conditions, all of them
     * the ROM's own data: it must be the SECOND slot (innerCarrierSlotId != 0, i.e. not
     * SIM1), it must belong to a keyguard instance (isKeyguardLayout is written by
     * MiuiCarrierTextLayout from getKeyguardHeaderLayout()), and the primary label must
     * actually be showing - otherwise hiding the second one would leave the keyguard with
     * no operator name at all.
     *
     * No recursion risk with T14: this only ever asks shouldShow() of the PRIMARY label,
     * and the hook returns before reaching here for slot 0.
     */
    private static boolean secondaryCarrierSuppressed(Object widget) {
        if (!sEnable || !sSingleCarrier || !(widget instanceof android.view.View)) return false;
        try {
            if (XposedHelpers.getIntField(widget, "innerCarrierSlotId") == 0) return false;
            if (!XposedHelpers.getBooleanField(widget, "isKeyguardLayout")) return false;
            Object parent = ((android.view.View) widget).getParent();
            if (parent == null) return false;
            return primaryCarrierShows(parent);
        } catch (Throwable ignored) { }
        return false;
    }

    /** Views whose visibility WE set to GONE, so only those get restored. */
    private static final java.util.Set<android.view.View> sCarrierHidden =
            Collections.newSetFromMap(new WeakHashMap<android.view.View, Boolean>());

    private static void hideCarrierChild(Object owner, String field, boolean hide) {
        try {
            Object o = XposedHelpers.getObjectField(owner, field);
            if (!(o instanceof android.view.View)) return;
            android.view.View v = (android.view.View) o;
            if (hide) {
                if (v.getVisibility() != android.view.View.GONE) {
                    v.setVisibility(android.view.View.GONE);
                    sCarrierHidden.add(v);
                    log("singlecarrier hid " + field);
                }
            } else if (sCarrierHidden.remove(v) && v.getVisibility() == android.view.View.GONE) {
                v.setVisibility(android.view.View.VISIBLE);
                log("singlecarrier restored " + field);
            }
        } catch (Throwable ignored) { }
    }

    /**
     * T15 body, run before every measure of a carrier layout. When the switch is off or
     * this is not the keyguard instance the only thing it does is undo our own GONE, so
     * flipping hf_ff_singlecarrier live converges on the next layout pass.
     * hf_ff_enable 0 has to take the SAME path (restore), otherwise disabling the module
     * would leave the second label hidden - which is not what "changes nothing" means.
     */
    private static void applySingleCarrier(Object layout) {
        boolean hide = false;
        try {
            hide = sEnable
                    && sSingleCarrier
                    && inKeyguardCarrierLayout((android.view.View) layout)
                    && primaryCarrierShows(layout);
        } catch (Throwable ignored) { }
        hideCarrierChild(layout, "rightCarrierTextView", hide);
        if (!hide) return;
        // The ROM hides both separators for us once T14 has answered "no" for the second
        // label, because that is precisely its own condition. Saying it again costs
        // nothing (setVisibility with an unchanged value is a no-op) and covers a build
        // where the separator decision moved somewhere else.
        hideCarrierChild(layout, "carrierSeparatorText", true);
        hideCarrierChild(layout, "carrierSeparatorView", true);
    }

    private static String sysProp(String key) {
        try {
            Object v = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.os.SystemProperties", Core.class.getClassLoader()),
                    "get", key);
            return v == null ? null : v.toString();
        } catch (Throwable t) { return null; }
    }

    /** Largest readable font under the theme dir (= the real file the symlinks point at). */
    private static String themeFontPath() {
        try {
            File[] fs = new File(THEME_FONT_DIR).listFiles();
            if (fs == null) return null;
            File pick = null;
            for (int i = 0; i < fs.length; i++) {
                File f = fs[i];
                if (!f.isFile() || !f.canRead()) continue;
                String n = f.getName().toLowerCase();
                if (!n.endsWith(".ttf") && !n.endsWith(".otf") && !n.endsWith(".ttc")) continue;
                if (pick == null || f.length() > pick.length()) pick = f;
            }
            return pick == null ? null : pick.getAbsolutePath();
        } catch (Throwable t) { return null; }
    }

    /**
     * The base glyph source for our weight instances. Theme file wins when present
     * (that is the face "mipro-*" resolves to after theming); otherwise the ROM's
     * own mi_font_path, which is what MiuiConfigs baked its ramp from.
     */
    private static String fontPath() {
        long now = System.currentTimeMillis();
        String p = sFontPath;
        // "" is a cached "no usable font file"; without the sentinel a missing theme
        // directory would make every frame re-list /data/system/theme/fonts.
        if (p != null && now - sFontPathAt < 5000) return p.length() == 0 ? null : p;
        sFontPathAt = now;
        String themed = themeFontPath();
        String prop = sysProp(MI_FONT_PATH_PROP);
        if (themed != null) p = themed;
        else if (prop != null && prop.length() > 0 && new File(prop).canRead()) p = prop;
        else p = "";
        sFontPath = p;
        if (sLog) log("fontPath=" + p);
        return p.length() == 0 ? null : p;
    }

    private static Typeface bakeFromFontFile(int wght) {
        if (wght <= 0) return null;
        // Keep the request inside the legal wght range; the applied theme font
        // measures 150-700 (default 330), so nonsense config values would only
        // be clamped by Skia anyway.
        wght = wght < 100 ? 100 : (wght > 900 ? 900 : wght);
        String path = fontPath();
        if (path == null) return null;
        String key = path + "@" + wght;
        Typeface t = sBaked.get(key);
        if (t != null) return t;
        try {
            Typeface.Builder b = new Typeface.Builder(path);
            b.setFontVariationSettings("'wght' " + wght);
            t = b.build();
        } catch (Throwable th) {
            if (sLog) log("bake fail " + th);
            return null;
        }
        if (t != null) {
            sBaked.put(key, t);
            own(t, wght);
            if (sLog) log("bake " + key);
        }
        return t;
    }

    private static volatile boolean sClonePathShown;
    private static void logClonePath(String p) {
        if (sClonePathShown || !sLog) return;
        sClonePathShown = true;
        log("clonepath=" + p);
    }

    /** Once-per-tag evidence line: hot paths stay quiet, but nothing is invisible. */
    private static final java.util.Set<String> sShown =
            java.util.Collections.newSetFromMap(
                    new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    private static void logOnce(String tag, String msg) {
        if (!sLog) return;
        if (sLogVerbose || sShown.add(tag)) log(msg);
    }

    // ------------------------------------------------------------------
    // One-shot in-process proof of what the renderer can do with this font
    // ------------------------------------------------------------------

    private static volatile boolean sSelfTestDone;

    /**
     * Answers the only two questions that decide whether our writes can ever be
     * visible, without needing anyone to look at the screen:
     *   AXIS_TF    - does a file-baked Typeface at wght 150 vs 700 rasterise
     *                differently (i.e. is Typeface.Builder+setFontVariationSettings
     *                a real axis instance here)?
     *   AXIS_PAINT - does a PAINT-level 'wght' change rasterise at all on a
     *                Typeface that carries no baked variation, which is exactly
     *                the object the theme font hands the status bar?
     * Ink bounds are the signal, NOT advance widths: the applied file has no
     * HVAR/MVAR table, so its advance widths are weight-independent by design.
     * Runs once on a worker thread (font is 34 MB; keeps the boot frame clean).
     */
    private static void scheduleSelfTest() {
        if (sSelfTestDone) return;
        sSelfTestDone = true;
        try {
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        String path = fontPath();
                        if (path == null) { log("SELFTEST no-font-path"); return; }
                        if (sLog) log("THEMEDIR " + themeDirList());
                        android.graphics.Paint p = new android.graphics.Paint();
                        p.setTextSize(120f);
                        Typeface a = bakeFromFontFile(150);
                        Typeface b = bakeFromFontFile(700);
                        Typeface raw = null;
                        try { raw = new Typeface.Builder(path).build(); } catch (Throwable ignored) { }
                        String ia = ink(p, a), ib = ink(p, b);
                        float wa = adv(p, a), wb = adv(p, b);
                        String ra = ink(p, raw);
                        String rb = "-";
                        if (raw != null) {
                            try { p.setFontVariationSettings("'wght' 700"); } catch (Throwable ignored) { }
                            rb = ink(p, raw);
                        }
                        log("SELFTEST file=" + path
                                + " tf150=" + ia + " tf700=" + ib
                                + " adv=" + wa + "/" + wb
                                + " raw=" + ra + " rawPaint700=" + rb
                                + " AXIS_TF=" + grew(ia, ib) + " AXIS_PAINT=" + grew(ra, rb));
                        // Warm the stock-family probe on THIS thread: it loads the ROM's
                        // own 34 MB font file, and the only consumer is the lock-screen
                        // date (T13), which runs on the inflating thread.
                        stockFamily();
                        faceMatchCheck();
                    } catch (Throwable t) { log("SELFTEST fail " + t); }
                }
            }, "hf-selftest").start();
        } catch (Throwable ignored) { }
    }

    /** Ink bounds of "0888" as WxH, or -1/-2 on failure. */
    private static String ink(android.graphics.Paint p, Typeface tf) {
        try {
            if (tf == null) return "null";
            p.setTypeface(tf);
            try { p.setFontVariationSettings(null); } catch (Throwable ignored) { }
            android.graphics.Rect r = new android.graphics.Rect();
            p.getTextBounds("0888", 0, 4, r);
            return r.width() + "x" + r.height();
        } catch (Throwable t) { return "err"; }
    }

    private static float adv(android.graphics.Paint p, Typeface tf) {
        return advText(p, tf, "0888");
    }

    private static float advText(android.graphics.Paint p, Typeface tf, String text) {
        try {
            p.setTypeface(tf);
            try { p.setFontVariationSettings(null); } catch (Throwable ignored) { }
            return p.measureText(text);
        } catch (Throwable t) { return -1f; }
    }

    /**
     * Tri-state guard for the one way this module could make things LOOK worse: if
     * SystemUI cannot read /data/system/theme/fonts/ then fontPath() falls back to
     * the stock ro.miui.ui.font.mi_font_path (MiSansVF), and every Typeface we
     * install would silently swap the user's themed face for MiSans. Compare the
     * advance width of our bake against the face the system renders with; the
     * applied file has no HVAR, so width tracks the glyph DESIGN, not the weight,
     * which is exactly the difference being tested. False => stop baking, the
     * paint-level channel stays available and keeps the real themed glyphs.
     */
    private static volatile Boolean sBakeTrust;

    private static void faceMatchCheck() {
        try {
            String path = fontPath();
            if (path == null) return;
            android.graphics.Paint p = new android.graphics.Paint();
            p.setTextSize(120f);
            final String probe = "MWmw@%0123";
            float mine = advText(p, rawBake(path), probe);
            float screen = advText(p, null, probe);
            float misans = -1f;
            String prop = sysProp(MI_FONT_PATH_PROP);
            if (prop != null && prop.length() > 0 && !prop.equals(path)) {
                Typeface mt = rawBake(prop);
                if (mt != null) misans = advText(p, mt, probe);
            }
            boolean trust = true;
            if (mine > 0 && screen > 0 && Math.abs(mine - screen) / screen >= 0.02f
                    && misans > 0 && Math.abs(mine - misans) / misans < 0.02f) {
                trust = false;
            }
            sBakeTrust = Boolean.valueOf(trust);
            log("FACEMATCH mine=" + mine + " screen=" + screen + " misans=" + misans
                    + " trust=" + trust);
            if (!trust) { sFixed.clear(); sBaked.clear(); }
        } catch (Throwable t) { log("FACEMATCH fail " + t); }
    }

    private static Typeface rawBake(String path) {
        try { return new Typeface.Builder(path).build(); } catch (Throwable t) { return null; }
    }

    /** True when the second ink box is strictly wider AND taller than the first. */
    private static boolean grew(String from, String to) {
        try {
            int f1 = from.indexOf('x'), t1 = to.indexOf('x');
            int fw = Integer.parseInt(from.substring(0, f1));
            int fh = Integer.parseInt(from.substring(f1 + 1));
            int tw = Integer.parseInt(to.substring(0, t1));
            int th = Integer.parseInt(to.substring(t1 + 1));
            return tw > fw && th >= fh;
        } catch (Throwable t) { return false; }
    }

    private static String themeDirList() {
        StringBuilder sb = new StringBuilder();
        try {
            File[] fs = new File(THEME_FONT_DIR).listFiles();
            if (fs == null) return "unlistable";
            for (int i = 0; i < fs.length; i++) {
                if (i > 0) sb.append(' ');
                sb.append(fs[i].getName()).append('=').append(fs[i].length());
            }
        } catch (Throwable t) { return "err:" + t; }
        return sb.toString();
    }

    // ------------------------------------------------------------------

    private static int classify(String cn) {
        String c = cn.toLowerCase();
        for (int i = 0; i < EXCLUDED_HINTS.length; i++) {
            if (c.contains(EXCLUDED_HINTS[i])) return ROLE_ISLAND;
        }
        if (c.contains("mobiletype")) return ROLE_NET;
        // NetworkSpeedView is a LinearLayout; its two labels are plain TextViews
        // and are caught by inSpeedChip() / the entry-name rule below.
        if (c.contains("networkspeed")) return ROLE_SPEED;
        if (c.contains("batterymeter") || c.contains("batterypercent")) return ROLE_BATTERY;
        if (c.contains("carrier")) return ROLE_CARRIER;
        // Date surfaces the user never asked about, and they are reached through the very
        // same MiuiConfigs.applyStatusBarTypeface write point: ControlCenterDateView,
        // DateView, VariableDateView, MiuiDateTimeView (control centre), ChipDateTimeView,
        // and the MIUI clock faces whose names contain "Date". Also Chronometer (media /
        // smart-chip elapsed time). Default weight for this role is 0 = untouched.
        if (c.contains("date") || c.contains("chronometer")) return ROLE_DATE;
        // The all-in-one / classic clock styles paint their date-lunar-weather line with
        // com.miui.clock.classic.ClassicTextAreaView: a TextView subclass whose name lives
        // under com.miui.clock, whose id is plain @text_area, and whose onDraw is its own
        // (it never calls super). The "clock" rule below used to swallow it, so the one
        // date line that is actually on screen never reached hf_ff_lockdate. It is a date
        // surface, not clock digits: hand it to the lock-date role.
        if (c.contains("classictextareaview")) return ROLE_LOCKDATE;
        if (c.contains("clock")) return ROLE_CLOCK;
        if (c.contains("battery")) return ROLE_BATTERY;
        return ROLE_NONE;
    }

    /** 超级岛/胶囊 and friends: never ours, whatever the caller asks for. */
    private static boolean nameExcluded(String cn) {
        if (cn == null) return false;
        String c = cn.toLowerCase();
        for (int i = 0; i < EXCLUDED_HINTS.length; i++) {
            if (c.contains(EXCLUDED_HINTS[i])) return true;
        }
        return false;
    }

    /** View-level veto: the view itself, or any of its 8 parents, is an excluded widget. */
    private static boolean viewExcluded(android.view.View v) {
        try {
            for (int i = 0; v != null && i < 8; i++) {
                if (nameExcluded(v.getClass().getName())) return true;
                Object p = v.getParent();
                v = (p instanceof android.view.View) ? (android.view.View) p : null;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /** Role for a plain-TextView fed by applyStatusBarTypeface: battery statics win. */
    private static int roleFor(TextView tv, Typeface tf) {
        if (tv == null) return ROLE_NONE;
        if (viewExcluded(tv)) return ROLE_ISLAND;
        // A per-instance tag set by T8 (the battery digits and their '%' are two
        // different views with two different stock weights) is exact identity, so it
        // outranks the shared-static guess below.
        Integer tag = sRoles.get(tv);
        if (tag != null) {
            int t = tag.intValue();
            if (t == ROLE_MARK || t == ROLE_BATTERY || t == ROLE_CLOCK
                    || t == ROLE_NET || t == ROLE_CARRIER) return t;
        }
        // The SAME baked slot (sMiproTypefaceWght500) is reused by the keyguard and the
        // always-on-display status bar, so the static is a stronger signal than a class
        // name for those, but weaker than the identity tag above.
        if (tf != null && sMcBattery.contains(tf)) return ROLE_BATTERY;
        int c = classify(tv.getClass().getName());
        if (c == ROLE_DATE) {
            // The alarm countdown in the carrier slot is a Chronometer too, and the
            // ROM feeds it through this very method with sMiproTypefaceWght500. Let
            // the instance signals settle it before the class name calls it a date.
            if (inCarrierChip(tv)) return ROLE_CARRIER;
            if (inSpeedChip(tv)) return ROLE_SPEED;
            int byId = roleFromResourceId(tv);
            if (byId == ROLE_NONE) byId = roleFromLockDate(tv);
            if (byId != ROLE_NONE) return byId;
        }
        if (c != ROLE_NONE) return c;
        // Operator name / "HD" / "+" are plain TextViews inside a carrier container:
        // without this they fall through to ROLE_OTHER (weight 0) and the whole
        // hf_ff_carrier knob does nothing at all - which is exactly what happened on
        // the lock screen up to and including v5.6.
        if (inCarrierChip(tv)) return ROLE_CARRIER;
        // Speed indicator labels: same shape, same need.
        if (inSpeedChip(tv)) return ROLE_SPEED;
        if (tag != null && tag.intValue() != ROLE_NONE) return tag;
        // Anything else that still flows through MiuiConfigs.applyStatusBarTypeface:
        // ROLE_OTHER, whose default weight is 0 = leave it alone. v4.0 used to fall back
        // to CLOCK here, which is exactly how the 超级岛 capsule and the control-centre
        // date ended up bold; opt it back in with hf_ff_other if you ever want that.
        return ROLE_OTHER;
    }

    private static final Map<String, Long> sSeenAt =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();

    /**
     * Inventory of the text views we deliberately do NOT touch, class + resource
     * entry name + current weight, throttled per class. This is how the lock-screen
     * operator label gets FOUND instead of guessed: lock the screen once with
     * hf_ff_log=2 and the log names the widget that is still thin.
     */
    private static void seenUnclaimed(TextView tv) {
        if (!sLogVerbose) return;
        try {
            String cn = tv.getClass().getName();
            long now = System.currentTimeMillis();
            Long last = sSeenAt.get(cn);
            if (last != null && now - last.longValue() < 3000) return;
            sSeenAt.put(cn, Long.valueOf(now));
            String id = "?";
            try {
                int vid = tv.getId();
                if (vid != 0) id = tv.getResources().getResourceEntryName(vid);
            } catch (Throwable ignored) { }
            android.graphics.Paint p = tv.getPaint();
            android.graphics.Typeface pf = p == null ? null : p.getTypeface();
            log("T11SEEN " + cn + " id=" + id
                    + " tfw=" + (tv.getTypeface() == null ? -1
                            : XposedHelpers.callMethod(tv.getTypeface(), "getWeight"))
                    + " ptFW=" + (pf == null ? -1 : XposedHelpers.callMethod(pf, "getWeight")));
        } catch (Throwable ignored) { }
    }

    /**
     * True when the view lives inside a carrier chip. MiuiCarrierTextLayout holds the
     * per-SIM ControlCenterCarrierText, and each of those keeps the operator name plus
     * its "HD" (id hd_text) and "+" (id plus_text) satellites in PLAIN TextViews -
     * they all have to carry the carrier weight or the chip looks broken, so match on
     * the container instead of on three resource ids.
     */
    private static boolean inCarrierChip(android.view.View v) {
        try {
            for (int i = 0; v != null && i < 6; i++) {
                if (v.getClass().getName().toLowerCase().indexOf("carrier") >= 0) return true;
                Object p = v.getParent();
                v = (p instanceof android.view.View) ? (android.view.View) p : null;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /**
     * Network speed labels: res/layout/network_speed.xml is
     * NetworkSpeedView(LinearLayout) > FrameLayout@id/network_speed_container >
     * two plain android.widget.TextView (id/network_speed_number, ..._unit). The
     * class name says nothing, identical to the carrier chip, so walk the parent
     * chain: same 6-level bound, one walk per view and the verdict is cached.
     */
    private static boolean inSpeedChip(android.view.View v) {
        try {
            for (int i = 0; v != null && i < 6; i++) {
                if (v.getClass().getName().toLowerCase().indexOf("networkspeed") >= 0) return true;
                Object p = v.getParent();
                v = (p instanceof android.view.View) ? (android.view.View) p : null;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /** resource entry id -> role; ids are stable within the app, so cache forever. */
    private static final Map<Integer, Integer> sIdRoles =
            new java.util.concurrent.ConcurrentHashMap<Integer, Integer>();

    /**
     * Role taken from the view's resource entry name, for views whose class name
     * gives no clue at all. Carrier and network-speed labels only, and the names
     * stay deliberately narrow: matching ids generally is a disaster (AOSP
     * notification rows have an id called "time" -- see the T11 call site).
     */
    private static int roleFromResourceId(TextView tv) {
        int id;
        try { id = tv.getId(); } catch (Throwable t) { return ROLE_NONE; }
        if (id == 0) return ROLE_NONE;
        Integer known = sIdRoles.get(Integer.valueOf(id));
        if (known != null) return known.intValue();
        int r = ROLE_NONE;
        String n = null;
        try {
            n = tv.getResources().getResourceEntryName(id);
            String ln = n == null ? null : n.toLowerCase();
            if (ln != null && ln.indexOf("carrier") >= 0) r = ROLE_CARRIER;
            else if (ln != null && ln.indexOf("network_speed") >= 0) r = ROLE_SPEED;
            // Full name, not a substring: "alarm" alone would also catch
            // id/dream_overlay_alarm_set and id/zen_alarm_warning, which are not the
            // keyguard status bar and never asked for 500.
            else if (ln != null && ln.indexOf("alarm_display") >= 0) r = ROLE_ALARM;
        } catch (Throwable ignored) { }
        sIdRoles.put(Integer.valueOf(id), Integer.valueOf(r));
        if (r != ROLE_NONE) logOnce("idrole:" + id, "id-role " + roleName(r) + " for @" + n);
        return r;
    }

    /**
     * Is this view inside a lock-screen clock face? The faces are the only place with
     * a date line the ROM feeds through Typeface.create("mipro-regular"), and they
     * are referenced from layouts, so these two package prefixes survive whatever
     * obfuscation the rest of the APK went through.
     */
    private static boolean inLockScreenClock(android.view.View v) {
        try {
            for (int i = 0; v != null && i < 6; i++) {
                String n = v.getClass().getName().toLowerCase();
                if (n.indexOf("miui.clock") >= 0 || n.indexOf("keyguard.clock") >= 0) return true;
                Object p = v.getParent();
                v = (p instanceof android.view.View) ? (android.view.View) p : null;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /**
     * Lock-screen date. Two signals are needed, because this role has to open the
     * draw-time clock-face ban (drawFaceAllowed) and that is not something a single weak
     * match should be able to do: a resource entry name that says "date" AND a clock face
     * somewhere above it in the parent chain. The pair is cached per id because an id only
     * ever appears in the layouts it belongs to.
     *
     * The role is assigned exactly the way clock / carrier / battery are - it is NOT gated
     * on its own key having a non-zero value. hf_ff_lockdate only decides what happens
     * once the role has matched: 0 leaves the view alone (wghtFor -> 0 -> skipped), any
     * other value stamps it. Same contract as every other role.
     */
    private static final Map<Integer, Boolean> sLockDateIds =
            new java.util.concurrent.ConcurrentHashMap<Integer, Boolean>();

    private static int roleFromLockDate(TextView tv) {
        int id;
        try { id = tv.getId(); } catch (Throwable t) { return ROLE_NONE; }
        if (id == 0) return ROLE_NONE;
        Boolean known = sLockDateIds.get(Integer.valueOf(id));
        if (known != null) return known.booleanValue() ? ROLE_LOCKDATE : ROLE_NONE;
        boolean ok = false;
        String n = null;
        try {
            n = tv.getResources().getResourceEntryName(id);
            String ln = n == null ? null : n.toLowerCase();
            ok = ln != null && ln.indexOf("date") >= 0 && inLockScreenClock(tv);
        } catch (Throwable ignored) { }
        sLockDateIds.put(Integer.valueOf(id), Boolean.valueOf(ok));
        if (ok) logOnce("lockdate:" + id, "id-role " + roleName(ROLE_LOCKDATE) + " for @" + n);
        return ok ? ROLE_LOCKDATE : ROLE_NONE;
    }

    /** Should this view be left completely alone? Role 0/false for the disabled ones. */
    private static boolean roleExcluded(TextView tv) {
        if (tv == null) return false;
        // hf_ff_island > 0 (or the v4.0 all-mode) means the user ASKED for island text
        // to be re-weighted: stop vetoing the views and let the role table decide, so
        // the capsule weight is a real setting instead of a hardcoded no.
        if (sIslandW > 0 || sAll > 0) return false;
        if (viewExcluded(tv)) return true;
        Integer r = sRoles.get(tv);
        String cn = tv.getClass().getName();
        if (r != null && r.intValue() == ROLE_ISLAND) return true;
        return nameExcluded(cn) || classify(cn) == ROLE_ISLAND;
    }

    /** One line per (class, role) so "what we now leave alone" is verifiable. */
    private static void skipped(TextView tv, int role) {
        if (!sLog || tv == null) return;
        String cn = tv.getClass().getSimpleName();
        logOnce("skip:" + cn + ":" + role, "SKIP " + cn + " role=" + roleName(role));
    }

    private static String roleName(int role) {
        switch (role) {
            case ROLE_CLOCK: return "clock";
            case ROLE_NET: return "network";
            case ROLE_CARRIER: return "carrier";
            case ROLE_BATTERY: return "battery";
            case ROLE_SPEED: return "speed";
            case ROLE_ALARM: return "alarm";
            case ROLE_LOCKDATE: return "lockdate";
            case ROLE_MARK: return "mark";
            case ROLE_HOLLOW: return "hollow";
            case ROLE_DATE: return "date";
            case ROLE_ISLAND: return "island";
            case ROLE_OTHER: return "other";
            default: return "none";
        }
    }
    private static final Map<String, Boolean> sCallerOk =
            new java.util.concurrent.ConcurrentHashMap<String, Boolean>();
    /** caller class -> island/date veto; cleared whenever a weight changes. */
    private static final Map<String, Boolean> sCallerVeto =
            new java.util.concurrent.ConcurrentHashMap<String, Boolean>();

    /**
     * H0a / H0c / G1 swap a Typeface BEFORE its caller ever sees it, so they are the only
     * hooks with a process-wide reach - and the reason unrelated widgets (超级岛, clock
     * faces, any plugin that asks for a "mipro-*" family) picked up our weight. Allow
     * them only when the request comes from code we are not vetoing; the answer is
     * cached per caller class, so the stack walk costs anything per class once.
     */
    private static boolean familyCallerAllowed() {
        if (!sFamily && sAll <= 0) return false;
        return !callerVetoed(firstForeignCaller());
    }

    /**
     * Which callers the process-wide channels must never serve: Super Island text and
     * every date surface. That veto is a WEIGHT question now - hf_ff_island /
     * hf_ff_date > 0 means the user explicitly asked for those widgets, so the veto
     * lifts with them instead of being hardcoded. The verdict is cached per caller
     * class and cleared whenever a weight changes (see refreshCfg).
     */
    private static boolean callerVetoed(String c) {
        if (c == null) return false;
        Boolean known = sCallerVeto.get(c);
        if (known != null) return known.booleanValue();
        int role = classify(c);
        boolean veto = (role == ROLE_ISLAND || role == ROLE_DATE) && wghtFor(role) <= 0;
        sCallerVeto.put(c, Boolean.valueOf(veto));
        return veto;
    }

    /**
     * Weight that G1 - MiuiConfigs.getMiProTypefaceWght460, the only Typeface getter
     * on this ROM, used by the keyguard status bar (operator name!) and the clock
     * controllers - may serve. v5.5 stamped the CLOCK weight here for every caller,
     * which is how the lock-screen operator name got 500; now the caller's role
     * decides, and callers we cannot classify get the getter's own nominal weight
     * (hf_ff_global, default 460) = precisely what the ROM asked for. 0 = refuse.
     */
    private static int globalWeight() {
        if (!sG1 && sAll <= 0) return 0;
        String c = firstForeignCaller();
        if (callerVetoed(c)) return 0;
        int role = c == null ? ROLE_NONE : classify(c);
        if (role != ROLE_NONE && role != ROLE_OTHER) {
            int w = wghtFor(role);
            if (w > 0) { logOnce("g1:" + c, "G1 ->" + w + " " + roleName(role) + " @" + c); return w; }
        }
        int fw = sAll > 0 ? sAll : sGlobalW;
        if (fw > 0) logOnce("g1:" + c, "G1 ->" + fw + " nominal @" + c);
        return fw;
    }

    /** First stack frame that is neither our module nor the Xposed plumbing. */
    private static String firstForeignCaller() {
        try {
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            for (int i = 0; i < st.length; i++) {
                String c = st[i].getClassName();
                if (c.startsWith("hf.fontfix.") || c.startsWith("de.robv.")
                        || c.startsWith("org.lsposed.") || c.startsWith("android.os.")
                        || c.startsWith("java.lang.")) continue;
                return c;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static int wghtFor(int role) {
        switch (role) {
            case ROLE_NET: return sNetW;
            case ROLE_CARRIER: return sCarrierW;
            case ROLE_SPEED: return sSpeedW;
            case ROLE_ALARM: return sAlarmW;
            case ROLE_LOCKDATE: return sLockDateW;
            case ROLE_BATTERY: return sBatteryW;
            case ROLE_MARK: return sMarkW;
            case ROLE_HOLLOW: return sHollowW;
            case ROLE_DATE: return sDateW;
            case ROLE_ISLAND: return sIslandW;
            case ROLE_OTHER: return sOtherW > 0 ? sOtherW : (sAll > 0 ? sAll : 0);
            case ROLE_CLOCK: return sClockW;
            default: return 0;
        }
    }

    /**
     * Replace the view's current Typeface at the SOURCE with a re-baked constant-axis
     * clone (real 'wght' axis, never fake bold), then sync the paint. Idempotent:
     * once the view already carries one of our clones it is left untouched.
     *
     * The ROLE_LOCKDATE view is the exception: cloning ITS face keeps the wrong family
     * (see themeFaceAt), so for that one role the replacement comes from the sibling
     * clock face / theme font file instead.
     */
    private static void forceTypeface(TextView tv, int w, String via) {
        if (tv == null || w <= 0) return;
        try {
            Integer r = sRoles.get(tv);
            boolean needFamily = r != null && r.intValue() == ROLE_LOCKDATE;
            Typeface base = tv.getTypeface();
            if (base != null) {
                Typeface nb;
                if (needFamily) {
                    // Keyed by weight already, so a config change yields a new face and
                    // an unchanged one is the very instance the view already carries.
                    nb = themeFaceAt(w, via);
                } else if (sOurClones.contains(base)) {
                    // A clone is a constant-axis instance: keeping it means the view
                    // stays at the weight it was baked at, no matter what the config
                    // now says. Re-bake from the font file so live slider changes (and
                    // the status bar clock, which never gets a fresh setTypeface from
                    // the ROM) follow the setting.
                    nb = cloneWeight(base) == w ? null : rebake(base, w);
                } else {
                    nb = cloneWithAxis(base, w);
                }
                if (nb != null && nb != base) {
                    own(nb, w);
                    tv.setTypeface(nb);
                    if (sLog) log(via + " tf->" + w + " @ " + tv.getClass().getSimpleName());
                }
            }
            apply(tv, w, via);
        } catch (Throwable ignored) { }
    }

    /** Register one of our baked faces together with the weight it was baked at. */
    private static void own(Typeface tf, int wght) {
        if (tf == null) return;
        sOurClones.add(tf);
        sCloneW.put(tf, Integer.valueOf(wght));
    }

    /**
     * Weight one of our own clones was baked at, or -1 when unknown. Never trust
     * Typeface#getWeight() first: for a face built with setFontVariationSettings()
     * it reports the file's default style, not the 'wght' instance we requested.
     */
    private static int cloneWeight(Typeface tf) {
        if (tf == null) return -1;
        Integer known = sCloneW.get(tf);
        if (known != null) return known.intValue();
        int fromVar = parseWght(variationOf(tf));
        if (fromVar > 0) return fromVar;
        try {
            Object g = XposedHelpers.callMethod(tf, "getWeight");
            return g instanceof Number ? ((Number) g).intValue() : -1;
        } catch (Throwable t) { return -1; }
    }

    /**
     * Bake a replacement for one of our own clones at a new weight. Cached per
     * (clone, weight): the draw path asks once per frame, and a theme font re-bake
     * must not run at 60 Hz.
     */
    private static Typeface rebake(Typeface base, int wght) {
        String key = System.identityHashCode(base) + "|" + wght;
        Typeface fixed = sFixed.get(key);
        if (fixed == null) {
            int had = cloneWeight(base);
            fixed = cloneWithAxis(base, wght);
            if (fixed == null) fixed = base;
            else {
                own(fixed, wght);
                logOnce("rebake:" + key, "rebake " + had + "->" + wght);
            }
            sFixed.put(key, fixed);
        }
        return fixed;
    }

    /**
     * Tag MiuiBatteryMeterView's two TextViews by IDENTITY (digits + separate '%' sign).
     * Covers the in-icon percentage AND the always-on-display外显, without touching any
     * unrelated digit text such as the Super Island charging notification.
     */
    private static void tagBattery(Object owner, String via) {
        refreshCfg();
        if (!sEnable) return;
        // GUARD. The tag hook sits on onChanged(boolean), which MiuiBatteryMeterView
        // inherits from the BatteryController callback INTERFACE - hooking it therefore
        // fires for other implementers too, and the v3.7 log proves what that did:
        //   T8 ->500 @ MiuiDateTimeView / DateTimeView / Chronometer /
        //             ChipChronometer / HyperChronometer
        // i.e. the control-centre date and every elapsed-time chip got pinned to the
        // battery weight. Only the real battery meter may flow through here.
        String oc = owner == null ? "" : owner.getClass().getName();
        if (!oc.contains("BatteryMeter")) {
            logOnce("t8-owner:" + oc, "T8 ignored owner=" + oc);
            return;
        }
        // Two separate TextViews with two different stock weights: the ROM feeds the
        // digits MiuiConfigs.sMiproTypefaceWght500 and the '%' sign sMiproTypefaceWght600.
        stampBatteryField(owner, "mBatteryPercentView", ROLE_BATTERY, via);
        stampBatteryField(owner, "mBatteryPercentMarkView", ROLE_MARK, via);
    }

    private static void stampBatteryField(Object owner, String field, int role, String via) {
        try {
            TextView tv = (TextView) XposedHelpers.getObjectField(owner, field);
            if (tv == null) return;
            if (viewExcluded(tv)) return;
            // Never let a date / elapsed-time view be dragged in as "battery".
            int cls = classify(tv.getClass().getName());
            if (cls == ROLE_DATE || cls == ROLE_ISLAND) return;
            sRoles.put(tv, Integer.valueOf(role));
            int w = wghtFor(role);
            if (sLog) log(via + " fld " + field + " tgt " + w);
            forceTypeface(tv, w, via);
        } catch (Throwable ignored) { }
    }

    /** Re-stamp the network weight on one of MobileTypeDrawable's text paints. */
    private static void stampPaint(Object owner, String field) {
        try {
            applyNetPaint((Paint) XposedHelpers.getObjectField(owner, field), "T9");
        } catch (Throwable ignored) { }
    }

    /**
     * THE 4G/5G LABEL. It is not a TextView: MobileTypeDrawable draws it with two
     * bare Paints, and MobileTypeDrawable.setMiuiStatusBarTypeface() hands them
     * Typeface.create("mipro-bold", 0), which /system/etc/fonts.xml aliases to
     * <alias name="mipro-bold" to="sans-serif" weight="900" />. With a variable
     * theme font in place that request resolves to the theme FILE at the theme's own
     * weight, and Paint.setFontVariationSettings() cannot re-weight a paint whose
     * Typeface is a native instance - measured on-device (v4.0, live, no restart):
     * sweeping hf_ff_network 0..700 changed not ONE pixel of the label, 325 dark
     * pixels in every frame. The axis string was a dead channel, so the label has
     * been rendering at the theme's own resolution ever since the process-wide
     * Typeface rewrites went off in v5.1, and no per-role change in v5.1..v5.5 could
     * possibly have touched it - which is exactly why the version bisect was empty.
     * The channel that DOES work is replacing the Typeface itself (the trick T10 uses
     * for the hollow battery digits), so that is what we do now and hf_ff_network
     * finally means something. The applied font has no HVAR/MVAR, so the advance
     * widths - and with them the drawable's measured box - cannot move.
     */
    private static void applyNetPaint(Paint pt, String via) {
        if (pt == null || sNetW <= 0) return;
        sPaints.put(pt, ROLE_NET);
        try {
            Typeface cur = pt.getTypeface();
            Typeface nb = cur == null ? bakeFromFontFile(sNetW) : clamp(cur, sNetW, via + " net");
            if (nb != null && nb != cur) {
                pt.setTypeface(nb);
                if (sLog) log(via + " net face->" + sNetW + " @" + (cur == null ? "no-face" : "themed"));
            }
            // Belt and braces: free on every font, effective where the string channel
            // does work (plain framework typefaces).
            String want = "'wght' " + sNetW;
            if (!want.equals(pt.getFontVariationSettings())) pt.setFontVariationSettings(want);
        } catch (Throwable ignored) { }
    }

    private static volatile Typeface sFontWght;

    /**
     * Stamp the battery axis on the hollow-icon Paints. The in-icon percentage is NOT a
     * TextView: MiuiHollowBatteryMeterIconView draws it with Canvas.drawText using its own
     * textPaint/hollowTextPaint and MiuiBatteryUtils.FONT_WGHT, which is built from the
     * ro.miui.ui.font.mi_font_path file with a hardcoded 'wght' 620. After a theme change
     * that path points at the theme font file, the built face carries no family name, the
     * 620 is lost and the digits render at the default ~330. TextView hooks can never see
     * this; stamp every paint just before each frame instead.
     */
    private static void stampBatteryPaint(Object owner, String field) {
        try {
            Paint pt = (Paint) XposedHelpers.getObjectField(owner, field);
            if (pt == null) return;
            if (sHollowW <= 0) return;
            Typeface tf = pt.getTypeface();
            if (tf == null) tf = sFontWght;
            if (tf != null) {
                Typeface nb = cloneWithAxis(tf, sHollowW);
                if (nb != null) pt.setTypeface(nb);
            }
            String want = "'wght' " + sHollowW;
            if (!want.equals(pt.getFontVariationSettings())) {
                pt.setFontVariationSettings(want);
                if (sLog) log("T10 stamp " + want + " @" + field);
            }
        } catch (Throwable ignored) { }
    }

    /** Find a field on cb whose value is an instance of want (outer-controller link). */
    private static Object outerOf(Object cb, Class<?> want) {
        try {
            java.lang.reflect.Field[] fs = cb.getClass().getDeclaredFields();
            for (int i = 0; i < fs.length; i++) {
                java.lang.reflect.Field f = fs[i];
                if (!want.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Object o = f.get(cb);
                if (want.isInstance(o)) return o;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static TextView findBigTime(Object controller) {
        try {
            Object lazy = XposedHelpers.getObjectField(controller, "headerController");
            Object chc = XposedHelpers.callMethod(lazy, "get");
            return (TextView) XposedHelpers.getObjectField(chc, "notificationBigTime");
        } catch (Throwable t) { return null; }
    }

    private static void apply(TextView tv, int wght, String via) {
        if (wght <= 0) return;
        try {
            String want = "'wght' " + wght;
            TextPaint tp = tv.getPaint();
            TextPaint lp = layoutPaint(tv);
            boolean axisOk = want.equals(tv.getFontVariationSettings());
            boolean paintOk = tp != null && want.equals(tp.getFontVariationSettings());
            boolean layoutOk = lp == null || want.equals(lp.getFontVariationSettings());
            // Idempotent fast path: axis already correct everywhere and no fake-bold
            // left over -> touch NOTHING (no setter, no invalidate). The expansion
            // controller drives this every frame and a redundant invalidate() is itself
            // an extra draw, i.e. a visible weight hiccup during the animation.
            // The Layout copy is part of the test on purpose: it is the paint that
            // actually rasterises the frame, see layoutPaint().
            if (axisOk && paintOk && layoutOk && sOurClones.contains(tv.getTypeface())
                    && (tp == null || !tp.isFakeBoldText()) && (tv.getPaintFlags() & 32) == 0) {
                return;
            }
            // Own try/catch: TextView.setFontVariationSettings is (String)Z on this
            // framework (AOSP declares it void), so a future re-compile against a
            // different android.jar could fail to resolve. Nothing after this line
            // may be allowed to die with it.
            if (!axisOk) {
                try { tv.setFontVariationSettings(want); } catch (Throwable ignored) { }
            }
            if (tp != null) {
                // defensive: real variable axis only, never fake bold
                if (tp.isFakeBoldText()) tp.setFakeBoldText(false);
                int flags = tv.getPaintFlags();
                if ((flags & 32) != 0) tv.setPaintFlags(flags & ~32);
            }
            // view paint AND the Layout's own copy, both in one place
            Integer rr = sRoles.get(tv);
            stampPaintOf(tv, wght, rr == null ? ROLE_NONE : rr.intValue());
            tv.invalidate();
            diag(tv, tp, via); // TEMP-DIAG
            if (sLogVerbose) log(via + " ->" + wght + " @ " + tv.getClass().getSimpleName());
        } catch (Throwable ignored) { }
    }

    /**
     * A ROLE_NONE verdict in sRoles is permanent for that view, so a view measured while
     * hf_ff_lockdate was 0 (the documented way to leave the lock-screen date alone) would
     * never be re-examined after the user raised it. Dropping ONLY the negative verdicts
     * on that transition keeps the "flip the key, live in two seconds" promise without
     * disturbing the positive claims - the per-instance battery tags must survive.
     */
    private static void maybeDropLockDateVerdicts(int prev) {
        if (sLockDateW <= 0 || prev > 0) return;
        try {
            synchronized (sRoles) {
                java.util.Iterator<java.util.Map.Entry<View, Integer>> it =
                        sRoles.entrySet().iterator();
                while (it.hasNext()) {
                    if (it.next().getValue().intValue() == ROLE_NONE) it.remove();
                }
            }
        } catch (Throwable ignored) { }
        sLockDateIds.clear();
        log("lockdate enabled: unclaimed-view verdicts dropped");
    }

    static void refreshCfg() {
        long now = System.currentTimeMillis();
        if (now - sCfgTs < 2000) return;
        sCfgTs = now;
        int prevLockDateW = sLockDateW;
        int prevClockW = sClockW;
        Context c = sCtx;
        if (c == null) { ctxViaActivityThread(); c = sCtx; }
        if (c == null) return;
        try {
            sEnable = android.provider.Settings.System.getInt(c.getContentResolver(), "hf_ff_enable", 1) != 0;
            sClockW = android.provider.Settings.System.getInt(c.getContentResolver(), "hf_ff_clock", DEFAULT_CLOCK);
            sNetW = android.provider.Settings.System.getInt(c.getContentResolver(), "hf_ff_network", DEFAULT_NET);
            sCarrierW = android.provider.Settings.System.getInt(c.getContentResolver(), "hf_ff_carrier", DEFAULT_CARRIER);
            sBatteryW = android.provider.Settings.System.getInt(c.getContentResolver(), "hf_ff_battery", DEFAULT_BATTERY);
            sMarkW = android.provider.Settings.System.getInt(c.getContentResolver(), "hf_ff_mark", DEFAULT_MARK);
            sHollowW = android.provider.Settings.System.getInt(c.getContentResolver(), "hf_ff_hollow", DEFAULT_HOLLOW);
            sDateW = android.provider.Settings.System.getInt(c.getContentResolver(), "hf_ff_date", DEFAULT_DATE);
            sIslandW = android.provider.Settings.System.getInt(c.getContentResolver(), "hf_ff_island", DEFAULT_ISLAND);
            sOtherW = android.provider.Settings.System.getInt(c.getContentResolver(), "hf_ff_other", DEFAULT_OTHER);
            sSpeedW = android.provider.Settings.System.getInt(c.getContentResolver(), "hf_ff_speed", DEFAULT_SPEED);
            sAlarmW = android.provider.Settings.System.getInt(c.getContentResolver(), "hf_ff_alarm", DEFAULT_ALARM);
            sLockDateW = android.provider.Settings.System.getInt(c.getContentResolver(), "hf_ff_lockdate", DEFAULT_LOCKDATE);
            sSingleCarrier = android.provider.Settings.System.getInt(c.getContentResolver(),
                    "hf_ff_singlecarrier", DEFAULT_SINGLE) != 0;
            sFamily = android.provider.Settings.System.getInt(c.getContentResolver(), "hf_ff_family", DEFAULT_FAMILY) != 0;
            sG1 = android.provider.Settings.System.getInt(c.getContentResolver(), "hf_ff_g1", DEFAULT_G1) != 0;
            sGlobalW = android.provider.Settings.System.getInt(c.getContentResolver(), "hf_ff_global", DEFAULT_GLOBAL);
            sAll = android.provider.Settings.System.getInt(c.getContentResolver(), "hf_ff_all", DEFAULT_ALL);
            sCallerVeto.clear();
            sAod = android.provider.Settings.System.getInt(c.getContentResolver(), "hf_ff_aod", DEFAULT_AOD) != 0;
            int lv = android.provider.Settings.System.getInt(c.getContentResolver(), "hf_ff_log", 0);
            sLog = lv != 0;
            sLogVerbose = lv >= 2;
        } catch (Throwable ignored) { }
        maybeDropLockDateVerdicts(prevLockDateW);
        // remote prefs overlay Settings.System; each key wins only when actually present,
        // so an empty remote store never shadows the adb-tunable hf_ff_* keys.
        SharedPreferences sp = sRemote;
        if (sp != null) {
            try {
                // Read the whole map instead of getInt(key, current): some framework
                // remote stores answer 0 for a key they do not hold, which silently
                // zeroed hf_ff_log (and could zero any weight) on every refresh.
                java.util.Map<String, ?> all = sp.getAll();
                if (all != null && !all.isEmpty()) {
                    sEnable = intOf(all, "enable", sEnable ? 1 : 0) != 0;
                    sClockW = intOf(all, "clock", sClockW);
                    sNetW = intOf(all, "network", sNetW);
                    sCarrierW = intOf(all, "carrier", sCarrierW);
                    sBatteryW = intOf(all, "battery", sBatteryW);
                    sMarkW = intOf(all, "mark", sMarkW);
                    sHollowW = intOf(all, "hollow", sHollowW);
                    sDateW = intOf(all, "date", sDateW);
                    sIslandW = intOf(all, "island", sIslandW);
                    sOtherW = intOf(all, "other", sOtherW);
                    sSpeedW = intOf(all, "speed", sSpeedW);
                    sAlarmW = intOf(all, "alarm", sAlarmW);
                    sLockDateW = intOf(all, "lockdate", sLockDateW);
                    sSingleCarrier = intOf(all, "singlecarrier", sSingleCarrier ? 1 : 0) != 0;
                    sFamily = intOf(all, "family", sFamily ? 1 : 0) != 0;
                    sG1 = intOf(all, "g1", sG1 ? 1 : 0) != 0;
                    sGlobalW = intOf(all, "global", sGlobalW);
                    sAll = intOf(all, "all", sAll);
                    sCallerVeto.clear();
                    sAod = intOf(all, "aod", sAod ? 1 : 0) != 0;
                    int lv2 = intOf(all, "log", sLogVerbose ? 2 : (sLog ? 1 : 0));
                    sLog = lv2 != 0;
                    sLogVerbose = lv2 >= 2;
                    maybeDropLockDateVerdicts(prevLockDateW);
                }
            } catch (Throwable ignored) { }
        }
        if (sClockW != prevClockW) reweightClocks();
    }

    /**
     * The status bar clock is only re-fed a Typeface by the ROM on a theme or font
     * change, so after a live weight change it would keep its old baked face until the
     * view happens to redraw - and during the shade pull-down it then disagrees with
     * the header clock, which the ROM re-types every frame. Walk the views we already
     * own as CLOCK on the UI thread and re-bake them immediately.
     */
    private static void reweightClocks() {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                @Override public void run() {
                    java.util.ArrayList<TextView> tvs = new java.util.ArrayList<TextView>(4);
                    synchronized (sRoles) {
                        for (Map.Entry<View, Integer> e : sRoles.entrySet()) {
                            if (e.getValue().intValue() != ROLE_CLOCK
                                    || !(e.getKey() instanceof TextView)) continue;
                            TextView tv = (TextView) e.getKey();
                            // Only the chrome the draw hook already re-faces; the
                            // lock-screen clock faces animate their own axis and must
                            // not be given a constant-axis setTypeface.
                            if (drawFaceAllowed(tv, ROLE_CLOCK)) tvs.add(tv);
                        }
                    }
                    for (int i = 0; i < tvs.size(); i++) {
                        TextView tv = tvs.get(i);
                        forceTypeface(tv, sClockW, "cfg");
                        tv.invalidate();
                    }
                    if (sLog && tvs.size() > 0) {
                        log("cfg clock=" + sClockW + " reweighted " + tvs.size() + " view(s)");
                    }
                }
            });
        } catch (Throwable ignored) { }
    }

    private static int intOf(java.util.Map<String, ?> m, String key, int def) {
        Object v = m.get(key);
        return v instanceof Number ? ((Number) v).intValue() : def;
    }

    static void ctxViaActivityThread() {
        if (sCtx != null) return;
        try {
            Object at = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", Core.class.getClassLoader()),
                    "currentApplication");
            if (at instanceof Context) sCtx = (Context) at;
        } catch (Throwable ignored) { }
    }

    static void log(String m) {
        android.util.Log.i(TAG, m);
        try { XposedBridge.log(TAG + ": " + m); } catch (Throwable ignored) { }
    }
}
