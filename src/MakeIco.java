import java.io.*;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * MakeIco —— 用 IconArt 渲染并直接生成多尺寸 Windows ICO（BMP 条目）。
 * 用法：java MakeIco build/icon.ico
 * 尺寸：16/24/32/48/64/256，含 alpha 透明（AND mask 全 0）。
 * 注意：ICO/BMP 全部字段是**小端**，必须用 ByteBuffer LITTLE_ENDIAN。
 */
public class MakeIco {

    public static void main(String[] args) throws Exception {
        int[] sizes = {16, 24, 32, 48, 64, 256};
        byte[][] imgs = new byte[sizes.length][];
        for (int i = 0; i < sizes.length; i++) imgs[i] = bmpEntry(IconArt.render(sizes[i]));

        int total = 6 + 16 * sizes.length;
        for (byte[] b : imgs) total += b.length;
        ByteBuffer buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
        int offset = 6 + 16 * sizes.length;
        for (int i = 0; i < sizes.length; i++) {
            int s = sizes[i];
            buf.put((byte) (s == 256 ? 0 : s));  // 宽（256 写 0）
            buf.put((byte) (s == 256 ? 0 : s));  // 高
            buf.put((byte) 0);                   // 调色板
            buf.put((byte) 0);                   // 保留
            buf.putShort((short) 1);             // 平面
            buf.putShort((short) 32);            // 位深
            buf.putInt(imgs[i].length);          // 数据长度
            buf.putInt(offset);                  // 数据偏移
            offset += imgs[i].length;
        }
        for (byte[] b : imgs) buf.put(b);
        try (FileOutputStream f = new FileOutputStream(args[0])) {
            f.write(buf.array());
        }
        System.out.println("ICO_OK " + args[0] + " " + total + " bytes");
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
