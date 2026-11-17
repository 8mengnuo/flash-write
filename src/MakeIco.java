import java.io.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * MakeIco —— 用 IconArt 渲染并直接生成多尺寸 Windows ICO（BMP 条目）。
 * 用法：java MakeIco build/icon.ico
 * 尺寸：16/24/32/48/64/256，含 alpha 透明（AND mask 全 0）。
 * 注意：ICO/BMP 全部字段是**小端**，必须用 ByteBuffer LITTLE_ENDIAN。
 */
public class MakeIco {

    private static final int[] SIZES = {16, 24, 32, 48, 64, 256};

    public static void main(String[] args) throws Exception {
        byte[][] imgs = new byte[SIZES.length][];
        for (int i = 0; i < SIZES.length; i++) imgs[i] = bmpEntry(IconArt.render(SIZES[i]));
        try (FileOutputStream f = new FileOutputStream(args[0])) {
            f.write(assemble(imgs));
        }
        System.out.println("ICO_OK " + args[0]);
    }

    /** 把任意一张图片缩放成多尺寸 ICO 字节（自定义图标换桌面快捷方式时用）。
     *  256 条目走 PNG 压缩：系统自带 ico 都是这么做的。之前 256 用 270KB 未压缩
     *  BMP，Explorer 从快捷方式的 IconLocation 加载这种超大未压缩条目时兼容性差，
     *  表现就是桌面图标空白。PNG 条目体积从 304KB 降到 ~40KB，且被 Vista+ 原生支持。
     *  小尺寸（16–64）仍用 BMP —— 兼容所有 Windows 版本，体积也可忽略。 */
    public static byte[] toIco(BufferedImage src) throws Exception {
        if (src == null) throw new IllegalArgumentException("null image");
        byte[][] imgs = new byte[SIZES.length][];
        for (int i = 0; i < SIZES.length; i++) {
            int s = SIZES[i];
            BufferedImage scaled = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(src, 0, 0, s, s, null);
            g.dispose();
            imgs[i] = (s == 256) ? pngEntry(scaled) : bmpEntry(scaled);
        }
        return assemble(imgs);
    }

    /** 256×256 条目：直接写 PNG 字节（ICO 规范里 indirection 为 PNG 的合法形式）。
     *  IconDir 的 bpp/planes 字段对 PNG 条目无意义，assemble 统一写 32/1，Explorer 忽略。 */
    private static byte[] pngEntry(BufferedImage img) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bo);
        return bo.toByteArray();
    }

    /** 拼 ICONDIR + 各尺寸目录项 + 数据（小端）。 */
    private static byte[] assemble(byte[][] imgs) {
        int sizes = imgs.length;
        int total = 6 + 16 * sizes;
        for (byte[] b : imgs) total += b.length;
        ByteBuffer buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 0).putShort((short) 1).putShort((short) sizes);
        int offset = 6 + 16 * sizes;
        for (int i = 0; i < sizes; i++) {
            int s = SIZES[i];
            buf.put((byte) (s == 256 ? 0 : s));
            buf.put((byte) (s == 256 ? 0 : s));
            buf.put((byte) 0).put((byte) 0);
            buf.putShort((short) 1).putShort((short) 32);
            buf.putInt(imgs[i].length).putInt(offset);
            offset += imgs[i].length;
        }
        for (byte[] b : imgs) buf.put(b);
        return buf.array();
    }

    /** 单个尺寸 -> BITMAPINFOHEADER + 自下而上 BGRA 像素 + 全 0 AND mask（小端） */
    private static byte[] bmpEntry(BufferedImage img) {
        int s = img.getWidth();
        int maskRow = ((s + 31) / 32) * 4;
        ByteBuffer b = ByteBuffer.allocate(40 + s * s * 4 + maskRow * s)
                                  .order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(40);                            // biSize
        b.putInt(s);                             // biWidth
        b.putInt(s * 2);                         // biHeight（XOR+AND 两倍）
        b.putShort((short) 1);                   // biPlanes
        b.putShort((short) 32);                  // biBitCount
        b.putInt(0);                             // biCompression = BI_RGB
        b.putInt(s * s * 4 + maskRow * s);       // biSizeImage
        b.putInt(0); b.putInt(0);                // 像素密度
        b.putInt(0); b.putInt(0);                // 调色板
        for (int y = s - 1; y >= 0; y--)
            for (int x = 0; x < s; x++) {
                int p = img.getRGB(x, y);
                b.put((byte) p);                 // B
                b.put((byte) (p >>> 8));         // G
                b.put((byte) (p >>> 16));        // R
                b.put((byte) (p >>> 24));        // A
            }
        for (int i = 0; i < maskRow * s; i++) b.put((byte) 0);  // AND mask
        return b.array();
    }
}
