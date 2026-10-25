import com.android.apksig.ApkVerifier;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Collection;

/**
 * apksig's own verdict on the finished APK. Cheap insurance after the align step:
 * alignment moves local header offsets, so if anything in AlignApk were wrong the
 * v2/v3 signature would break, and we would rather learn that here than from a
 * device reporting -124 / a signature error.
 *
 * Usage: VerifyApk <apk>
 */
public final class VerifyApk {

    public static void main(String[] a) throws Exception {
        if (a.length < 1) {
            System.err.println("usage: VerifyApk <apk>");
            System.exit(2);
        }
        // apksig renamed Result accessors more than once (getErrors() is
        // List<IssueWithParams> here, List<ApkVerificationIssue> elsewhere), so
        // everything beyond isVerified() is read reflectively: the build step must
        // not break when the jar is bumped.
        ApkVerifier.Result r =
                new ApkVerifier.Builder(new File(a[0])).setMinCheckedPlatformVersion(26).build()
                        .verify();
        System.out.println("verified=" + r.isVerified() + schemes(r));
        print("ERROR", call(r, "getErrors"));
        print("warn ", call(r, "getWarnings"));
        if (!r.isVerified()) {
            System.out.println("SIGNATURE NOT VERIFIED");
            System.exit(1);
        }
        System.out.println("signature OK");
    }

    private static String schemes(Object r) {
        Object signers = call(r, "getSigners");
        if (!(signers instanceof Collection)) return "";
        StringBuilder sb = new StringBuilder(" signers=");
        int n = 0;
        for (Object s : (Collection<?>) signers) {
            n++;
            sb.append(' ');
            sb.append(bool(s, "isV1Signature") ? "v1" : "--");
            sb.append('/');
            sb.append(bool(s, "isV2Signature") ? "v2" : "--");
            sb.append('/');
            sb.append(bool(s, "isV3Signature") ? "v3" : "--");
            sb.append(" id=").append(text(s, "getId"));
        }
        return sb.append(" (").append(n).append(')').toString();
    }

    private static void print(String label, Object holder) {
        if (!(holder instanceof Collection)) return;
        for (Object o : (Collection<?>) holder) {
            System.out.println("  " + label + " " + text(o, null));
        }
    }

    private static Object call(Object target, String name) {
        try {
            Method m = target.getClass().getMethod(name);
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean bool(Object target, String name) {
        Object v = call(target, name);
        return Boolean.TRUE.equals(v);
    }

    /** Prefer the accessor / public field, fall back to toString(). */
    private static String text(Object o, String accessor) {
        if (o == null) return "null";
        if (accessor != null) {
            Object v = call(o, accessor);
            if (v != null) return String.valueOf(v);
        }
        try {
            return String.valueOf(o.getClass().getField("message").get(o));
        } catch (Throwable t) {
            return String.valueOf(o);
        }
    }
}
