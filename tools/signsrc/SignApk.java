import com.android.apksig.ApkSigner;
import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SignApk {
    public static void main(String[] a) throws Exception {
        // in.apk out.apk keystore alias storepass [keypass]
        KeyStore ks = KeyStore.getInstance("JKS");
        char[] pw = a[4].toCharArray();
        char[] kp = (a.length > 5 ? a[5] : a[4]).toCharArray();
        FileInputStream fin = new FileInputStream(a[2]);
        ks.load(fin, pw);
        fin.close();
        PrivateKey key = (PrivateKey) ks.getKey(a[3], kp);
        Certificate[] raw = ks.getCertificateChain(a[3]);
        List<X509Certificate> chain = new ArrayList<X509Certificate>();
        for (Certificate c : raw) chain.add((X509Certificate) c);
        ApkSigner.SignerConfig sc = new ApkSigner.SignerConfig.Builder("HF", key, chain).build();
        new ApkSigner.Builder(Collections.singletonList(sc))
            .setInputApk(new File(a[0]))
            .setOutputApk(new File(a[1]))
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setMinSdkVersion(26)
            .build()
            .sign();
        System.out.println("signed -> " + a[1]);
    }
}
