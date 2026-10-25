import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipException;

/**
 * The zipalign step this pipeline was missing.
 *
 * Why it matters: from Android 11 on, an APK whose resources.arsc is not stored
 * uncompressed AND not aligned on a 4-byte boundary is refused at install time
 * with INSTALL_PARSE_FAILED_RESOURCES_ARSC_COMPRESSED = -124 (PackageManager),
 * which HyperOS renders as "\u8be5\u5b89\u88c5\u5305\u4e0e\u60a8\u7684\u7cfb\u7edf\u4e0d\u517c\u5bb9".
 * v1.0-alpha landed on an aligned offset by pure luck; v1.1 only shortened
 * versionName ("1.0-alpha" -> "1.1"), AndroidManifest.xml lost 7 bytes in the
 * UTF-16 string pool, everything behind it moved by 7 and resources.arsc came to
 * rest on offset 829 -> clean systems failed with -124 while a device whose PMS
 * checks are patched (core-patch) never noticed. Never rely on luck.
 *
 * Method: entries are copied byte for byte (no re-deflate, CRCs untouched); only
 * the "extra" field of the local header grows, which is exactly what zipalign
 * does. Padding is declared as the PADDED ALIGNMENT extra field (APPNOTE 4.3.16,
 * id 0xd935) plus a filler field, so no parser ever sees stray bytes.
 *
 * Rules used here: uncompressed entries and resources.arsc -> 4 bytes (what the
 * platform checks), uncompressed .so -> 4096 (Android 15+ 16 KB pages; we ship
 * none today, it costs nothing to already be correct).
 *
 * Must run BEFORE signing: the APK Signing Block goes behind the last entry, so
 * aligning afterwards would invalidate the v2/v3 signature.
 *
 * Usage: AlignApk <in.apk> <out.apk>
 */
public final class AlignApk {

    private static final long LFH_SIG = 0x04034b50L;
    private static final long CEN_SIG = 0x02014b50L;
    private static final long EOCD_SIG = 0x06054b50L;
    private static final long DESC_SIG = 0x08074b50L;

    private static final class Entry {
        int flags;
        int method;
        long crc;
        long csize;
        long usize;
        long localOffset;
        byte[] name;
        byte[] data;          // stored bytes, data descriptor included when present
        int align = 4;
        long newDataOffset;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: AlignApk <in.apk> <out.apk>");
            System.exit(2);
        }
        Path in = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        byte[] raw = Files.readAllBytes(in);
        List<Entry> entries = read(raw);

        ByteArrayOutputStream bos = new ByteArrayOutputStream(raw.length + 1024);
        long pos = 0;
        int padded = 0;
        for (Entry e : entries) {
            int extraLen = padLength(pos, e.name.length, e.align);
            e.localOffset = pos;
            e.newDataOffset = pos + 30 + e.name.length + extraLen;
            pos = writeLocal(bos, e, extraLen, pos);
            if (extraLen > 0) padded++;
        }
        long cenStart = pos;
        for (Entry e : entries) {
            int extraLen = padLength(e.localOffset, e.name.length, e.align);
            pos = writeCentral(bos, e, extraLen, pos);
        }
        writeEocd(bos, entries.size(), pos - cenStart, cenStart);

        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(out))) {
            bos.writeTo(os);
        }
        System.out.println("aligned " + entries.size() + " entries, " + padded
                + " padded, " + raw.length + " -> " + pos + " bytes");
        for (Entry e : entries) {
            String n = new String(e.name, StandardCharsets.UTF_8);
            if (e.method == 0 || n.endsWith(".arsc")) {
                System.out.println("  " + n + " method=" + e.method + " data@" + e.newDataOffset
                        + " mod4=" + (e.newDataOffset % 4));
            }
        }
    }

    /** Bytes of padding so that (pos + 30 + nameLen + pad) % align == 0. */
    private static int padLength(long pos, int nameLen, int align) {
        long mod = (pos + 30 + nameLen) % align;
        int pad = (int) ((align - mod) % align);
        if (pad > 0 && pad < 6) pad += align;   // room for the 6-byte alignment header
        return pad;
    }

    /** PADDED ALIGNMENT (+ filler) extra field, or nothing when pad == 0. */
    private static byte[] extraField(int total, int align) {
        if (total <= 0) return new byte[0];
        byte[] b = new byte[total];
        int exp = align == 4096 ? 12 : 2;
        if (total >= 10) {                      // room for the 6-byte alignment header
            b[0] = (byte) 0x35;                 // id 0xd935, little endian
            b[1] = (byte) 0xd9;
            b[2] = 2;                           // data size: alignment + count
            b[3] = 0;
            b[4] = (byte) exp;                  // alignment as a power of two
            b[5] = 0;                           // no extra root offsets
            filler(b, 6, total - 6);
        } else {
            filler(b, 0, total);                // too tight for ASF, one filler field
        }
        return b;
    }

    /** A well-formed unknown extra field ("0x9999"), so no parser sees stray bytes. */
    private static void filler(byte[] b, int off, int len) {
        if (len < 4) return;
        int body = len - 4;
        b[off] = (byte) 0x99;
        b[off + 1] = (byte) 0x99;
        b[off + 2] = (byte) (body & 0xff);
        b[off + 3] = (byte) ((body >> 8) & 0xff);
    }

    private static long writeLocal(ByteArrayOutputStream o, Entry e, int extraLen, long pos)
            throws IOException {
        o.write(new byte[] { 0x50, 0x4b, 0x03, 0x04 }, 0, 4);
        le16(o, 20);                            // version needed to extract
        le16(o, 0);                             // flags: sizes known, no descriptor
        le16(o, e.method);
        le16(o, 0);                             // mod time  (normalised, reproducible)
        le16(o, 33);                            // mod date  = 1980-01-01
        le32(o, e.crc);
        le32(o, e.csize);
        le32(o, e.usize);
        le16(o, e.name.length);
        le16(o, extraLen);
        o.write(e.name);
        o.write(extraField(extraLen, e.align));
        o.write(e.data);
        return pos + 30 + e.name.length + extraLen + e.data.length;
    }

    private static long writeCentral(ByteArrayOutputStream o, Entry e, int extraLen, long pos)
            throws IOException {
        o.write(new byte[] { 0x50, 0x4b, 0x01, 0x02 }, 0, 4);
        le16(o, 20);                            // version made by
        le16(o, 20);                            // version needed
        le16(o, 0);                             // flags
        le16(o, e.method);
        le16(o, 0);
        le16(o, 33);
        le32(o, e.crc);
        le32(o, e.csize);
        le32(o, e.usize);
        le16(o, e.name.length);
        le16(o, extraLen);                      // mirrors the local extra
        le16(o, 0);                             // comment length
        le16(o, 0);                             // disk number start
        le16(o, 0);                             // internal attributes
        le32(o, 0);                             // external attributes
        le32(o, e.localOffset);
        o.write(e.name);
        o.write(extraField(extraLen, e.align));
        return pos + 46 + e.name.length + extraLen;
    }

    private static void writeEocd(ByteArrayOutputStream o, int count, long cenSize, long cenOff)
            throws IOException {
        o.write(new byte[] { 0x50, 0x4b, 0x05, 0x06 }, 0, 4);
        le16(o, 0);
        le16(o, 0);
        le16(o, count);
        le16(o, count);
        le32(o, cenSize);
        le32(o, cenOff);
        le16(o, 0);                             // archive comment length
    }

    /** Parse the central directory and copy each entry's stored bytes verbatim. */
    private static List<Entry> read(byte[] raw) throws IOException {
        int eocd = findEocd(raw);
        int count = u16(raw, eocd + 10);
        int p = (int) u32(raw, eocd + 16);
        List<Entry> list = new ArrayList<Entry>();
        for (int i = 0; i < count; i++) {
            if (u32(raw, p) != CEN_SIG) throw new ZipException("bad central header at " + p);
            Entry e = new Entry();
            e.flags = u16(raw, p + 8);
            e.method = u16(raw, p + 10);
            e.crc = u32(raw, p + 16);
            e.csize = u32(raw, p + 20);
            e.usize = u32(raw, p + 24);
            int nameLen = u16(raw, p + 28);
            int extraLen = u16(raw, p + 30);
            int commentLen = u16(raw, p + 32);
            e.localOffset = u32(raw, p + 42);
            e.name = new byte[nameLen];
            System.arraycopy(raw, p + 46, e.name, 0, nameLen);
            String n = new String(e.name, StandardCharsets.UTF_8);
            if (e.csize >= 0xffffffffL || e.localOffset >= 0xffffffffL) {
                throw new ZipException("zip64 not supported: " + n);
            }
            int lfh = (int) e.localOffset;
            if (u32(raw, lfh) != LFH_SIG) throw new ZipException("bad local header: " + n);
            int dataStart = lfh + 30 + u16(raw, lfh + 26) + u16(raw, lfh + 28);
            int len = (int) e.csize;
            if ((e.flags & 0x8) != 0) {
                len += u32(raw, dataStart + len) == DESC_SIG ? 24 : 16;
            }
            e.data = new byte[len];
            System.arraycopy(raw, dataStart, e.data, 0, len);
            e.align = n.endsWith(".so") ? 4096 : 4;
            list.add(e);
            p += 46 + nameLen + extraLen + commentLen;
        }
        return list;
    }

    private static int findEocd(byte[] raw) throws ZipException {
        for (int i = raw.length - 22; i >= 0 && i > raw.length - 66000; i--) {
            if (u32(raw, i) == EOCD_SIG && i + 22 + u16(raw, i + 20) == raw.length) return i;
        }
        for (int i = raw.length - 22; i >= 0; i--) {
            if (u32(raw, i) == EOCD_SIG) return i;
        }
        throw new ZipException("EOCD not found");
    }

    private static int u16(byte[] b, int i) {
        return (b[i] & 0xff) | ((b[i + 1] & 0xff) << 8);
    }

    private static long u32(byte[] b, int i) {
        return (b[i] & 0xffL) | ((b[i + 1] & 0xffL) << 8) | ((b[i + 2] & 0xffL) << 16)
                | ((b[i + 3] & 0xffL) << 24);
    }

    private static void le16(OutputStream o, int v) throws IOException {
        o.write(v & 0xff);
        o.write((v >> 8) & 0xff);
    }

    private static void le32(OutputStream o, long v) throws IOException {
        o.write((int) (v & 0xff));
        o.write((int) ((v >> 8) & 0xff));
        o.write((int) ((v >> 16) & 0xff));
        o.write((int) ((v >> 24) & 0xff));
    }
}
