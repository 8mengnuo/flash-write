import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * LnkIcon —— 纯 Java 读写 Windows .lnk（Shell Link）的图标位置，不依赖 PowerShell/COM。
 *
 * 桌面放的是「闪写.exe - 快捷方式.lnk」，换图标时改它的 IconLocation 指向我们
 * 生成在 data 目录里的 .ico，就能让桌面图标即时跟着变（exe 本体图标写死在文件里，
 * 运行时改不了，只有重打包才变——这正是要走快捷方式的原因）。
 *
 * .lnk 布局（全小端）：
 *   ShellLinkHeader 76 字节（LinkFlags 在偏移 20）
 *   [LinkTargetIDList]  HasLinkTargetIDList=0x1 → u16 size + size 字节
 *   [LinkInfo]          HasLinkInfo=0x2         → u32 size + size 字节
 *   [StringData]        按 NAME/REL/WORKDIR/ARGS/ICON 顺序，每个 = u16 字符数 + UTF-16LE
 *   [ExtraData]         若干块 + 4 字节 0 终止块
 * 只在 StringData 尾部替换/追加 ICON_LOCATION 字段并置 HasIconLocation=0x40，
 * 其余结构原样保留 → 目标、工作目录、参数、备注都不丢。
 */
public final class LnkIcon {
    private static final int HDR = 76;
    private static final int F_HAS_IDLIST = 0x1, F_HAS_LINKINFO = 0x2;
    private static final int F_HAS_NAME = 0x4, F_HAS_REL = 0x8, F_HAS_WORKDIR = 0x10;
    private static final int F_HAS_ARGS = 0x20, F_HAS_ICON = 0x40;

    private LnkIcon() {}

    private static int u16(byte[] b, int p) {
        return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8);
    }
    private static long u32(byte[] b, int p) {
        return (b[p] & 0xFFL) | ((b[p + 1] & 0xFFL) << 8)
                | ((b[p + 2] & 0xFFL) << 16) | ((b[p + 3] & 0xFFL) << 24);
    }
    private static void put32(byte[] b, int p, int v) {
        b[p] = (byte) v; b[p + 1] = (byte) (v >>> 8);
        b[p + 2] = (byte) (v >>> 16); b[p + 3] = (byte) (v >>> 24);
    }

    /** 把 .lnk 的 ICON_LOCATION 设成 iconLocation（如 "D:\...\app_icon.ico,0"）。 */
    public static byte[] setIconLocation(byte[] lnk, String iconLocation) throws Exception {
        if (lnk == null || lnk.length < HDR) throw new IllegalArgumentException("not a lnk");
        int flags = (int) u32(lnk, 20);
        int pos = HDR;
        if ((flags & F_HAS_IDLIST) != 0) {
            int sz = u16(lnk, pos);
            pos += 2 + sz;
        }
        if ((flags & F_HAS_LINKINFO) != 0) {
            pos += (int) u32(lnk, pos);
        }
        int stringStart = pos;

        boolean[] present = {(flags & F_HAS_NAME) != 0, (flags & F_HAS_REL) != 0,
                (flags & F_HAS_WORKDIR) != 0, (flags & F_HAS_ARGS) != 0,
                (flags & F_HAS_ICON) != 0};
        int iconStart = -1, iconEnd = -1;
        for (int i = 0; i < 5; i++) {
            if (!present[i]) continue;
            int start = pos;
            int cnt = u16(lnk, pos);
            pos += 2 + cnt * 2;
            if (i == 4) { iconStart = start; iconEnd = pos; }
        }
        int extraStart = pos;

        char[] cs = iconLocation.toCharArray();
        ByteBuffer field = ByteBuffer.allocate(2 + cs.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        field.putShort((short) cs.length);
        for (char c : cs) field.putShort((short) c);
        byte[] iconField = field.array();

        byte[] header = Arrays.copyOfRange(lnk, 0, HDR);
        if (iconStart < 0) put32(header, 20, flags | F_HAS_ICON);

        ByteArrayOutputStream out = new ByteArrayOutputStream(lnk.length + iconField.length + 8);
        out.write(header);                                              // header（含修订后的 flags）
        out.write(lnk, HDR, stringStart - HDR);                         // IDList + LinkInfo 原样
        if (iconStart >= 0) {
            out.write(lnk, stringStart, iconStart - stringStart);       // ICON 之前的 StringData
            out.write(iconField);                                       // 新 ICON
            out.write(lnk, iconEnd, extraStart - iconEnd);              // ICON 之后的 StringData
        } else {
            out.write(lnk, stringStart, extraStart - stringStart);      // 全部 StringData
            out.write(iconField);                                       // 追加 ICON
        }
        out.write(lnk, extraStart, lnk.length - extraStart);            // ExtraData 原样
        return out.toByteArray();
    }

    /** 从 LinkInfo.LocalBasePath 取出目标路径（用来判断这个快捷方式是不是指向我们的 exe）。 */
    public static String readTargetPath(byte[] lnk) {
        try {
            int flags = (int) u32(lnk, 20);
            int pos = HDR;
            if ((flags & F_HAS_IDLIST) != 0) pos += 2 + u16(lnk, pos);
            if ((flags & F_HAS_LINKINFO) == 0 || pos + 24 > lnk.length) return "";
            int li = pos;
            int headerSize = (int) u32(lnk, li + 4);
            int liFlags = (int) u32(lnk, li + 8);
            if ((liFlags & 0x1) == 0) return "";                       // 没有 LocalBasePath
            int lbpOff = (int) u32(lnk, li + 16);
            int p = li + lbpOff;
            if (p < 0 || p >= lnk.length) return "";
            ByteArrayOutputStream sb = new ByteArrayOutputStream();
            while (p < lnk.length && lnk[p] != 0) sb.write(lnk[p++]);
            byte[] raw = sb.toByteArray();
            /* .lnk 的 LocalBasePath 编码不固定：现代系统多写 UTF-8，老系统写 ANSI(GBK)。
               先试 UTF-8（严格模式，遇到非法序列直接抛），失败再回退 GBK，避免中文路径
               被解成乱码后写进 IconLocation 导致图标空白。 */
            try {
                java.nio.charset.CharsetDecoder dec = java.nio.charset.StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
                return dec.decode(java.nio.ByteBuffer.wrap(raw)).toString();
            } catch (Exception utf8Fail) {
                return new String(raw, "GBK");
            }
        } catch (Exception e) { return ""; }
    }

    /** 读出 ICON_LOCATION（自测验证用）。 */
    public static String readIconLocation(byte[] lnk) {
        try {
            int flags = (int) u32(lnk, 20);
            int pos = HDR;
            if ((flags & F_HAS_IDLIST) != 0) pos += 2 + u16(lnk, pos);
            if ((flags & F_HAS_LINKINFO) != 0) pos += (int) u32(lnk, pos);
            boolean[] present = {(flags & F_HAS_NAME) != 0, (flags & F_HAS_REL) != 0,
                    (flags & F_HAS_WORKDIR) != 0, (flags & F_HAS_ARGS) != 0,
                    (flags & F_HAS_ICON) != 0};
            for (int i = 0; i < 5; i++) {
                if (!present[i]) continue;
                int cnt = u16(lnk, pos); pos += 2;
                if (i == 4) {
                    char[] c = new char[cnt];
                    for (int k = 0; k < cnt; k++) c[k] = (char) u16(lnk, pos + k * 2);
                    return new String(c);
                }
                pos += cnt * 2;
            }
        } catch (Exception ignored) {}
        return "";
    }

    /** 构造一个最小合法 .lnk（仅自测用：目标 notepad + 无 icon）。 */
    public static byte[] sampleLnk(String targetIso) throws Exception {
        // LinkInfo: size + headerSize(0x1C) + flags(0x1) + VolumeIDOffset(0) + LocalBasePathOffset + CNRLinkOffset(0) + suffixOffset + path + '\0'
        byte[] path = targetIso.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        int liSize = 28 + path.length + 1;
        byte[] li = new byte[liSize];
        put32(li, 0, liSize); put32(li, 4, 0x1C); put32(li, 8, 0x1);
        put32(li, 12, 0); put32(li, 16, 28); put32(li, 20, 0); put32(li, 24, 28 + path.length);
        System.arraycopy(path, 0, li, 28, path.length);
        int flags = F_HAS_LINKINFO | F_HAS_NAME;
        byte[] hdr = new byte[HDR];
        hdr[0] = 0x4C;
        byte[] clsid = {0x01,0x14,0x02,0x00,0x00,0x00,0x00,0x00,(byte)0xC0,0,0,0,0,0,0,0x46};
        System.arraycopy(clsid, 0, hdr, 4, 16);
        put32(hdr, 20, flags);
        byte[] name = new byte[2];   // NAME_STRING 空
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(hdr, 0, HDR);
        out.write(li, 0, li.length);
        out.write(name, 0, 2);
        out.write(new byte[]{0,0,0,0});  // ExtraData 终止块
        return out.toByteArray();
    }
}
