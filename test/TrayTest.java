import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/** 弹出 AWT 菜单 + Robot 截图验证渲染；args[0]=font 时显式设置中文字体 */
public class TrayTest {
    public static void main(String[] args) throws Exception {
        boolean useFont = args.length > 0 && args[0].equals("font");
        String out = args.length > 1 ? args[1] : "test/tt.png";
        Frame f = new Frame();
        f.setUndecorated(true);
        f.setBounds(300, 300, 10, 10);
        f.setVisible(true);
        PopupMenu m = new PopupMenu();
        MenuItem i1 = new MenuItem("显示 / 隐藏");
        MenuItem i2 = new MenuItem("退出");
        m.add(i1); m.add(i2);
        if (useFont) {
            Font ft = new Font("Microsoft YaHei", Font.PLAIN, 12);
            i1.setFont(ft); i2.setFont(ft); m.setFont(ft);
        }
        f.add(m);
        m.show(f, 5, 5);
        Thread.sleep(1200);
        Robot r = new Robot();
        BufferedImage img = r.createScreenCapture(new Rectangle(240, 240, 360, 220));
        javax.imageio.ImageIO.write(img, "png", new File(out));
        System.out.println("OK " + out);
        System.exit(0);
    }
}
