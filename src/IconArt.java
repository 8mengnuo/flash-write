import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/**
 * IconArt —— 应用图标统一绘制（年轻化设计）。
 * 设计：深蓝夜色圆角底 + 蓝绿渐变毛笔（笔杆天蓝 → 笔尖翠绿）+ 笔尖右下的菱形星光
 * （主星白、辅星青、小星暖黄）。
 * 窗口/任务栏图标（FlashWrite.setIconImages）、托盘图标（initTray）、
 * exe/桌面图标（MakeIcon → icon.ico）共用此渲染，保证四处完全一致。
 */
public class IconArt {

    public static BufferedImage render(int s) {
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        float f = s / 256f;

        /* ── 背景：深蓝夜色圆角方块 + 一层极淡的蓝绿对角渐变提亮 ── */
        RoundRectangle2D.Float bg = new RoundRectangle2D.Float(
                14 * f, 14 * f, s - 28 * f, s - 28 * f, 56 * f, 56 * f);
        g.setPaint(new Color(0x1F, 0x24, 0x40));
        g.fill(bg);
        g.setComposite(AlphaComposite.SrcOver.derive(0.16f));
        g.setPaint(new GradientPaint(0, 0, new Color(0x5B7CFA), s, s, new Color(0x2DD4A7)));
        g.fill(bg);
        g.setComposite(AlphaComposite.SrcOver);

        /* ── 毛笔：局部坐标笔杆朝上、笔尖朝下，整体旋转 -45° 指向右下 ──
           蓝绿渐变沿笔身：天蓝(笔杆) → 翠绿(笔毫) */
        Graphics2D gb = (Graphics2D) g.create();
        gb.translate(0.52f * s, 0.52f * s);
        gb.rotate(-Math.PI / 4);
        GradientPaint bp = new GradientPaint(
                0, -0.40f * s, new Color(0x4F, 0xA3, 0xF7),
                0,  0.30f * s, new Color(0x35, 0xE0, 0xA6));
        // 笔杆（圆头长条）
        gb.setPaint(bp);
        gb.fill(new RoundRectangle2D.Float(
                -0.052f * s, -0.42f * s, 0.104f * s, 0.46f * s, 0.10f * s, 0.10f * s));
        // 笔头金属箍（白色亮环）
        gb.setColor(new Color(255, 255, 255, 235));
        gb.fill(new RoundRectangle2D.Float(
                -0.072f * s, 0.035f * s, 0.144f * s, 0.05f * s, 0.03f * s, 0.03f * s));
        // 笔毫（两侧内收的锋曲线，收成一个尖）
        gb.setPaint(bp);
        Path2D.Float bristle = new Path2D.Float();
        bristle.moveTo(-0.070f * s, 0.085f * s);
        bristle.quadTo(-0.052f * s, 0.220f * s, 0.0f, 0.335f * s);
        bristle.quadTo(0.052f * s, 0.220f * s, 0.070f * s, 0.085f * s);
        bristle.quadTo(0.0f, 0.130f * s, -0.070f * s, 0.085f * s);
        bristle.closePath();
        gb.fill(bristle);
        gb.dispose();

        /* ── 笔尖处生出的菱形星光 ── */
        sparkle(g, 0.815f * s, 0.795f * s, 0.085f * s, Color.WHITE);          // 主星
        sparkle(g, 0.665f * s, 0.845f * s, 0.048f * s, new Color(0x8F, 0xF7, 0xEC)); // 辅星（青）
        sparkle(g, 0.875f * s, 0.600f * s, 0.034f * s, new Color(0xFD, 0xE6, 0x8A)); // 小星（暖黄）

        g.dispose();
        return img;
    }

    /** 四角菱形星（凹边曲线，像 ✦ 闪形） */
    private static void sparkle(Graphics2D g, float cx, float cy, float r, Color c) {
        Path2D.Float p = new Path2D.Float();
        p.moveTo(cx, cy - r);
        p.quadTo(cx, cy, cx + r, cy);
        p.quadTo(cx, cy, cx, cy + r);
        p.quadTo(cx, cy, cx - r, cy);
        p.quadTo(cx, cy, cx, cy - r);
        p.closePath();
        g.setColor(c);
        g.fill(p);
    }
}
