import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;

/**
 * 系统音量监听：跟随系统里正在播放的音乐驱动环绕元素律动。
 *
 * 为什么这么绕：Java 标准库读不到"系统正在输出多大的声音"（Java Sound 只有录音设备，
 * 没有 WASAPI 回环）。但 Windows 自带 .NET Framework 的 C# 编译器（csc.exe），
 * 用它编译一个 ~60 行的小程序，通过 COM 的 IAudioMeterInformation 读默认播放设备
 * 的峰值电平（0.0~1.0），每 50ms 从 stdout 报一行 —— 零第三方依赖、无需管理员权限。
 *
 * 失败策略：编译不了/运行不了就静默降级，peak 恒为 0，"跟随音乐"开关等于没开，
 * 绝不影响其他功能。
 */
public final class VolumeMeter {

    private static volatile float peak = 0f;
    private static volatile boolean started = false;

    /** 当前系统音频峰值（0~1），供 JS 轮询 */
    public static float peak() { return peak; }

    /** 惰性启动：第一次开启"跟随音乐"时调用，重复调用无害 */
    public static synchronized void start() {
        if (started) return;
        started = true;
        Thread t = new Thread(() -> {
            try {
                File dir = new File("data");
                if (!dir.exists()) dir.mkdirs();
                File cs = new File(dir, "volmeter.cs");
                File exe = new File(dir, "volmeter.exe");
                writeIfChanged(cs, CS);
                if (!exe.exists() || exe.lastModified() < cs.lastModified()) {
                    String csc = findCsc();
                    if (csc == null) {
                        System.out.println("[闪写] 未找到 csc.exe，系统音量跟随不可用");
                        return;
                    }
                    Process p = new ProcessBuilder(csc, "/nologo", "/target:exe",
                            "/out:" + exe.getAbsolutePath(), cs.getAbsolutePath())
                            .redirectErrorStream(true).start();
                    BufferedReader r = new BufferedReader(
                            new InputStreamReader(p.getInputStream()));
                    String l;
                    while ((l = r.readLine()) != null) System.out.println("[csc] " + l);
                    int code = p.waitFor();
                    if (code != 0 || !exe.exists()) {
                        System.out.println("[闪写] 音量监听编译失败 code=" + code);
                        return;
                    }
                }
                System.out.println("[闪写] 系统音量监听已启动");
                Process p = new ProcessBuilder(exe.getAbsolutePath()).start();
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), Charset.forName("UTF-8")));
                String line;
                while ((line = r.readLine()) != null) {
                    try { peak = Float.parseFloat(line.trim()); } catch (Exception ignored) {}
                }
            } catch (Throwable ex) {
                System.out.println("[闪写] 音量监听不可用: " + ex);
            }
        }, "volmeter");
        t.setDaemon(true);
        t.start();
    }

    /** csc.exe 位置：.NET Framework 4.x 自带（Win10/11 都有） */
    private static String findCsc() {
        String[] cands = {
                "C:\\Windows\\Microsoft.NET\\Framework64\\v4.0.30319\\csc.exe",
                "C:\\Windows\\Microsoft.NET\\Framework\\v4.0.30319\\csc.exe",
        };
        for (String c : cands) if (new File(c).exists()) return c;
        return null;
    }

    private static void writeIfChanged(File f, String content) throws Exception {
        if (f.exists() && readAll(f).equals(content)) return;
        OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(f), Charset.forName("UTF-8"));
        w.write(content);
        w.close();
    }

    private static String readAll(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream(f), Charset.forName("UTF-8")));
        String l;
        while ((l = r.readLine()) != null) sb.append(l).append('\n');
        r.close();
        return sb.toString();
    }

    /* ---- C# 源码：读默认播放设备的峰值电平，50ms 一行输出 ---- */
    private static final String CS =
        "using System;\n" +
        "using System.Globalization;\n" +
        "using System.Runtime.InteropServices;\n" +
        "using System.Threading;\n" +
        "static class VolMeter {\n" +
        "  [ComImport, Guid(\"BCDE0395-E52F-467C-8E3D-C4579291692E\")] class MMDeviceEnumerator {}\n" +
        "  [Guid(\"A95664D2-9614-4F35-A746-DE8DB63617E6\"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]\n" +
        "  interface IMMDeviceEnumerator {\n" +
        "    int EnumAudioEndpoints(int dataFlow, int mask, out IntPtr devs);\n" +
        "    int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice dev);\n" +
        "  }\n" +
        "  [Guid(\"D666063F-1587-4E43-81F1-B948E807363F\"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]\n" +
        "  interface IMMDevice {\n" +
        "    int Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object iface);\n" +
        "  }\n" +
        "  [Guid(\"C02216F6-8C67-4B5B-9D00-D008E73E0064\"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]\n" +
        "  interface IAudioMeterInformation { int GetPeakValue(out float peak); }\n" +
        "  [STAThread]\n" +
        "  static void Main() {\n" +
        "    try {\n" +
        "      var en = (IMMDeviceEnumerator)(object)new MMDeviceEnumerator();\n" +
        "      IMMDevice dev;\n" +
        "      en.GetDefaultAudioEndpoint(0, 1, out dev);   // render, multimedia\n" +
        "      var iid = new Guid(\"C02216F6-8C67-4B5B-9D00-D008E73E0064\");\n" +
        "      object o;\n" +
        "      dev.Activate(ref iid, 1, IntPtr.Zero, out o);\n" +
        "      var meter = (IAudioMeterInformation)o;\n" +
        "      while (true) {\n" +
        "        float p;\n" +
        "        meter.GetPeakValue(out p);\n" +
        "        Console.WriteLine(p.ToString(\"0.000\", CultureInfo.InvariantCulture));\n" +
        "        Console.Out.Flush();\n" +
        "        Thread.Sleep(50);\n" +
        "      }\n" +
        "    } catch (Exception e) { Console.Error.WriteLine(e.Message); Environment.Exit(1); }\n" +
        "  }\n" +
        "}\n";
}
