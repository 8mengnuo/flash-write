import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.SnapshotResult;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.image.WritableImage;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import netscape.javascript.JSObject;

import javax.imageio.ImageIO;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/**
 * 闪写 SnapWrite 混合版 —— Java 后端 + WebView 前端（HTML/CSS/Vue）。
 *
 * 架构（Electron 同款思路，但不引 Node，纯 Java 生态）：
 *   Java 后端：全局键鼠钩子(Hooks)、系统托盘、窗口控制(置顶/透明/伸缩)、
 *              数据持久化(data/ 目录)、文本导出
 *   WebView  ：界面全部用 HTML/CSS/Vue 写（资源打包在 jar 根路径）
 *   桥梁     ：JS -> Java 通过 window.host（Bridge 对象）
 *              Java -> JS 通过 engine.executeScript("app.xxx()")
 *
 * 数据文件与 Swing 版格式兼容（draft.txt / words.txt / meta.properties / config.properties）。
 *
 * 编译（JDK 1.8，JavaFX 运行时在 jre/lib/ext/jfxrt.jar 中自动可见）：
 *   javac -encoding UTF-8 -cp app/lib/jnativehook-2.2.2.jar -d out src/*.java
 *   jar cfm app/FlashWriteFx.jar build/manifest.txt -C out . -C web .
 */
public class FxApp extends Application {

    /* ════════════════ 配置（持久化） ════════════════ */
    private int[] bossCodes = {NativeKeyEvent.VC_CONTROL, NativeKeyEvent.VC_SHIFT,
            NativeKeyEvent.VC_Z, NativeKeyEvent.VC_X};
    private boolean bossEnabled = true;
    private String gesture = "LLRRLL";
    private boolean gestureEnabled = true;
    private boolean gestureFix2 = false;  // 手势按钮号修复的一次性迁移标记
    private int bubbleMaxChars = 120;     // 气泡超过该字数后不再跟随变长
    private int bubbleCharsPerLine = 20;  // 气泡每行字数（起点电子书同款行宽）
    private String accent = "#6366F1";    // 主题色（年轻蓝）
    private String accent2 = "#22D3EE";   // 渐变第二色（双拼渐变用）
    private String themeMode = "gradient"; // gradient=双拼渐变 solid=单色
    private String closeAction = "ask";   // 关闭按钮：ask=每次询问 tray=最小化到托盘 exit=直接退出
    private int fontSize = 16;
    private boolean bold = false;
    private String wallpaperPath = "";    // 背景图片（data/wallpaper.img），空=无
    private int wallpaperOpacity = 40;    // 背景图不透明度 %
    private int winOpacity = 100;         // 窗口整体不透明度 %
    private String fontPath = "";         // 当前激活的导入字体（data/fonts/ 下相对路径），空=默认
    private String fontName = "";         // 字体族名
    private String fontWest = "";         // 西文字体（英文/数字优先用它，中文落到中文字体）
    private String fontUi = "";           // 工具栏选的系统字体族名，空=默认栈
    private int h1Size = 28, h2Size = 22, h3Size = 18;   // 标题级别字号
    private boolean h1Bold = true, h2Bold = true, h3Bold = true;
    private boolean pItalic = false, h1Italic = false, h2Italic = false, h3Italic = false; // 样式斜体
    private String pFont = "", h1Font = "", h2Font = "", h3Font = ""; // 样式字体族（空=默认栈）
    private String tagDefsJson = "";      // 标签定义 JSON：[{"name":"重要","color":"#F59E0B"}]
    private int tocSize = 16;             // 目录字号（生成前后都实时生效）
    private boolean bubbleCloud = true;   // 气泡：云彩环绕
    private boolean bubbleRhythm = true;  // 气泡：节奏律动
    private String bubbleCloudKind = "cloud"; // 环绕元素 cloud|star|petal|note
    private int cloudOpacity = 60;        // 云彩不透明度 %
    private int cloudMargin = 26;         // 环绕元素边距 px（透明/关闭时自动归零）
    private String bgmPath = "";          // 背景音乐（data/bgm.bin），空=无
    private String decorImgPath = "";     // 环绕元素自定义图片（data/decorimg.bin），空=无
    private String wallpaperFit = "cover"; // 背景图显示位置 cover|contain|tile|center
    private int bubbleLines = 3;          // 气泡可见行数（向上翻阅前文的大小）
    private int bubbleW = 420;            // 气泡手动宽度 px（bubbleManualSize=true 时生效）
    private int bubbleH = 132;            // 气泡手动高度 px
    private boolean bubbleManualSize = false;  // 气泡大小改由用户手动定（否则按文字自适应）
    private String bubbleCustomChars = ""; // 气泡自定义环绕元素（字符序列，循环使用）
    private String customIconPath = "";   // 用户自定义图标（data/custom_icon.png），空=默认
    private String skin = "light";        // 皮肤：light=浅色 dark=深色（黑色主题）
    private boolean bubbleDblRestore = true;  // 气泡：双击拖动条还原完整界面
    /* 气泡边缘拉伸状态（bubbleResizeStart/Move/End 三拍） */
    private boolean rszDrag = false;
    private String rszDir = "";
    private double rszSX, rszSY, rszX0, rszY0, rszW0, rszH0;
    private boolean bubbleAutoSize = true;    // 气泡：高度随字数自适应（false=固定大小）
    private boolean bubbleIntrude = false;    // 气泡：环绕元素侵入文本框内部
    private int intrudeOpacity = 40;      // 侵入元素不透明度 %
    private int intrudeSize = 100;        // 侵入元素大小 %
    private boolean autoSave = true;      // 自动保存（关掉后靠 Ctrl+S 手动保存）
    private boolean alwaysWords = false;  // 词库面板常驻右侧
    private boolean indentFirst = true;   // 全文段落自动首行缩进两格（回车新段也是）
    private String wordPrefix = "z";      // 词库快捷输入前缀（前缀+数字 一键插入）
    private boolean musicFollow = false;  // 跟随音乐：监听系统音量驱动律动
    private String currentDoc = "未命名.txt"; // 当前文档（data/docs/ 下的文件名）
    private File lastExportDir = null;        // 上次导出目录（本次运行内记忆）
    private boolean docDirty = false;     // 当前文档有未落盘的改动

    /* 可自定义的界面快捷键：{动作, 默认组合}；改键后存 config.properties 的 key.<动作> */
    private static final String[][] DEFAULT_KEYS = {
            {"undo", "Ctrl+Z"},          {"redo", "Ctrl+Shift+Z"}, {"deleteLine", "Ctrl+Y"},
            {"indent", "Ctrl+1"},        {"quote", "Ctrl+2"},      {"dash", "Ctrl+3"},
            {"ellipsis", "Ctrl+4"},      {"book", "Ctrl+5"},       {"chars", "Ctrl+6"},
            {"bold", "Ctrl+B"},          {"import", "Ctrl+O"},     {"history", "Ctrl+H"},
            {"find", "Ctrl+F"},
            {"save", "Ctrl+S"},          {"export", "Ctrl+E"},     {"words", "Ctrl+K"},
            {"bubble", "Ctrl+Shift+M"},  {"settings", "Ctrl+,"},
            {"pin", "Ctrl+Shift+T"},
            {"tag", "Ctrl+Shift+G"},      // 插入标签（选中套标签 / 空选插标签名）
            {"nav", "Ctrl+Shift+N"}       // 导航面板（标题+标签跳转 / 一键目录）
    };
    private final java.util.LinkedHashMap<String, String> keyMap =
            new java.util.LinkedHashMap<String, String>();

    {
        for (String[] kv : DEFAULT_KEYS) keyMap.put(kv[0], kv[1]);
    }

    /* ════════════════ 运行时状态 ════════════════ */
    private Stage stage;
    private WebView webView;
    private WebEngine engine;
    private String draft = "";
    /** 文档 → 本地关联文件（"存到桌面"）：每次修改实时写回该文件 */
    private final java.util.Map<String, String> docLinks = new java.util.HashMap<>();
    private final List<String[]> words = new ArrayList<String[]>();  // {code, content, cat}
    private int today0 = 0, total0 = 0;   // 会话开始时的今日字数/全文字数（增量统计基准）
    private boolean bubble = false;
    private boolean hiddenToTray = false; // 由隐藏逻辑维护的"已在托盘"状态（stage.isShowing 不可靠）
    private boolean composing = false;    // 输入法组字中（前端回报）：此时不转发快捷键，别抢候选框的键
    private String capturingKey = "";     // 前端正在录制某个快捷键（非空=录键中，按键只取值不执行）
    private javafx.animation.Timeline bubbleWatch;   // 气泡可见性看门狗（防"窗口还在但看不见"）
    private long lastBossAt = 0;          // 老板键去抖
    private double preW = 880, preH = 600;
    private TrayIcon trayIcon;
    private javafx.stage.Stage trayPop;   // 托盘右键自绘菜单
    private Bridge bridge;                // JS 桥（强引用防 GC，见 hookJs）
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /* 文字历史快照（Typora 式寻回）：内容有变化时记录，最多保留 200 条 */
    private final List<String[]> history = new ArrayList<String[]>();  // {毫秒时间, 全文}
    private String lastHistText = "";

    /* ════════════════ JavaFX 生命周期 ════════════════ */

    /* ---- 单实例：避免"关不掉→重复双击→开了五个"的雪球 ---- */
    private static File lockFile;
    private static java.nio.channels.FileLock instLock;
    private static File signalFile;
    private long lastSignalStamp = -1;

    /** 第一个实例返回 true；已有实例在跑则返回 false（调用方应写信号后退出） */
    public static boolean claimSingleInstance() {
        try {
            File d = new File("data");
            if (!d.exists()) d.mkdirs();
            lockFile = new File(d, "app.lock");
            signalFile = new File(d, "show.signal");
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(lockFile, "rw");
            instLock = raf.getChannel().tryLock();
            return instLock != null;
        } catch (Exception e) {
            return true;   // 拿不到锁也不能拦住用户启动
        }
    }

    /** 第二个实例调用：让已经在跑的那个把窗口弹出来，然后自己退出 */
    public static void signalPrimary() {
        try {
            File d = new File("data");
            if (!d.exists()) d.mkdirs();
            new FileOutputStream(new File(d, "show.signal")).close();
        } catch (Exception ignored) {}
    }

    /** 轮询信号文件：双击 exe 也能把隐藏的窗口叫回来 */
    private void startSummonWatcher() {
        final File f = new File(dataDir(), "show.signal");
        lastSignalStamp = f.exists() ? f.lastModified() : -1;
        Thread t = new Thread(() -> {
            while (true) {
                try { Thread.sleep(700); } catch (Exception ignored) {}
                long st = f.exists() ? f.lastModified() : -1;
                if (st != lastSignalStamp) {
                    lastSignalStamp = st;
                    Platform.runLater(() -> showFromTray(false));
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    /** 诊断日志（用户报"打不开/关不掉"时直接看 data/app.log） */
    private void log(String msg) {
        try {
            OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(
                    new File(dataDir(), "app.log"), true), UTF8);
            w.write(new SimpleDateFormat("MM-dd HH:mm:ss").format(new Date()) + "  " + msg + "\n");
            w.close();
        } catch (Exception ignored) {}
    }

    public void start(Stage s) {
        stage = s;
        /* 自测与截图都跑在隔离数据里，绝不碰用户真实草稿/文档/寻回历史 */
        if (FxMain.selfTest || FxMain.shotMode) dataRoot = "data/selftest";
        /* 字体/插件回归各自独立数据目录：它们会往 config 里写 fontPath、往 plugins 里放探针，
           混在 data/selftest 会把后续自测和截图污染掉（截图里突然变成楷体就查不出来了） */
        if (FxMain.fontTest) dataRoot = "data/selftest/fonttest";
        if (FxMain.plugTest) dataRoot = "data/selftest/plugtest";
        if (FxMain.plugTest) writeProbePlugin();
        loadAll();

        /* ★ 最关键的开关：JavaFX 默认「最后一个窗口隐藏 = 退出工具箱」。
           老板键 hide() 之后 FX 线程直接结束，此后所有 Platform.runLater 被静默丢弃：
             · 再按老板键 → toggleBoss 永远不执行 → 窗口唤不回来
             · 托盘"退出" → 也是 runLater → 点了没反应 → 只能任务管理器杀进程
             · 杀不干净就变成僵尸进程 → 用户反复双击 → "开了好几个"
           关掉隐式退出，隐藏只隐藏，工具箱一直活着。 */
        Platform.setImplicitExit(false);

        WebView web = new WebView();
        webView = web;
        /* ★ 全局关掉 WebView 自带的右键菜单：右键底纹/彩色文字时冒出来的
           "刷新文档/重新加载"那一套就是它。页面自己有完整的右键菜单，
           原生菜单只会添乱 —— 一刀切，永不再弹。 */
        web.setContextMenuEnabled(false);
        engine = web.getEngine();
        engine.load(getClass().getResource("/index.html").toExternalForm());
        // 页面加载完成后挂载 JS 桥（必须在 load 完成后，否则 window 上挂不上）
        engine.getLoadWorker().stateProperty().addListener((ov, oldV, newV) -> {
            if (newV == Worker.State.SUCCEEDED) hookJs();
        });

        Scene scene = new Scene(web, 880, 600, javafx.scene.paint.Color.TRANSPARENT);
        /* 按键转发：WebView 里 DOM 的 KeyboardEvent 在中文输入法/部分布局下
           key 会是 Process、code 可能为空，导致"快捷键时灵时不灵"。
           JavaFX 这一层永远拿得到真实的 KeyCode 与修饰键，所以在这里组一份
           标准组合串（Ctrl+F / Ctrl+, / Esc …）交给前端统一分发。 */
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::forwardKeyToJs);
        stage.setTitle("闪写");
        stage.setScene(scene);
        stage.initStyle(StageStyle.TRANSPARENT);   // 透明窗口：气泡模式的云彩可"浮"出内容区
        stage.setMinWidth(300);
        stage.setMinHeight(120);
        setStageIcon();
        makeWebViewTransparent(web);
        initTray();

        // 全局钩子：老板键 / 手势（回调统一切到 FX 线程）
        try {
            Hooks.register(bossEnabled ? bossCodes : new int[0], () ->
                    Platform.runLater(FxApp.this::toggleBoss));
            log("native hook ready");
        } catch (Throwable ex) {
            // 钩子装不上也不能让软件起不来：托盘/双击图标/右键唤回 仍然可用
            log("native hook FAILED: " + ex);
        }
        // 窗口隐藏时：三连击左键 / 单击右键 直接以气泡模式唤回
        Hooks.summonBubble = () -> Platform.runLater(() -> {
            if (hiddenToTray || !stage.isShowing()) {
                log("summon: 三连击/右键唤回（气泡）");
                showFromTray(true);
            }
        });
        syncHooksConfig();
        if (musicFollow) VolumeMeter.start();   // 跟随音乐：后台起系统音量监听

        stage.setOnCloseRequest(e -> { e.consume(); exitApp(); });
        if (!FxMain.shotMode) startSummonWatcher();

        stage.show();
        try { stage.setOpacity(winOpacity / 100.0); } catch (Exception ignored) {}
        if (FxMain.shotMode && !FxMain.bossTest && !FxMain.keyTest && !FxMain.selfTest
                && !FxMain.gestTest && !FxMain.fontTest && !FxMain.plugTest
                && !FxMain.dragTest) runShotTest();
        if (FxMain.selfTest) runSelfTest();
        if (FxMain.bossTest) runBossTest();
        if (FxMain.keyTest) runKeyTest();
        if (FxMain.gestTest) runGestTest();
        if (FxMain.fontTest) runFontTest();
        if (FxMain.plugTest) runPlugTest();
        if (FxMain.dragTest) runDragTest();
    }

    /* ══════════════════════════════════════════════════════════════════
       鼠标手势老板键回归：用 java.awt.Robot 真的点出 LLRRLL。
       为什么必须用真点击：手势走的是 JNativeHook 的全局鼠标钩子，
       DOM 合成事件根本到不了那一层，只有真的按下去才知道通不通。
       ══════════════════════════════════════════════════════════════════ */
    private void runGestTest() {
        Thread t = new Thread(() -> {
            sleepQuiet(2200);
            runOnFx(() -> { Hooks.gesture = "LLRRLL"; Hooks.gestureEnabled = true; });
            StringBuilder clicks = new StringBuilder();
            boolean fired = false, restored = false, slowVisible = false;
            String slowMode = "";
            try {
                java.awt.Robot r = new java.awt.Robot();
                r.setAutoDelay(12);
                r.mouseMove(6, 6);        // 屏幕左上角空白处，别点到自己窗口
                /* 0) 先探针：左/中/右各点一次，看钩子把按钮报成几号
                      （实测 Robot 的右键被报成 2 —— 手势判定的 R 永远匹配不上） */
                Hooks.lastGestureBuf = "";
                int[] probeMasks = {java.awt.event.InputEvent.BUTTON1_DOWN_MASK,
                        java.awt.event.InputEvent.BUTTON2_DOWN_MASK,
                        java.awt.event.InputEvent.BUTTON3_DOWN_MASK};
                for (int m : probeMasks) {
                    r.mousePress(m); r.mouseRelease(m); sleepQuiet(90);
                }
                sleepQuiet(300);
                log("gesttest 按钮探针(左中右)=[" + Hooks.lastGestureBuf + "]");
                int[] seq = {1, 1, 3, 3, 1, 1};
                for (int b : seq) {
                    clicks.append(b == 1 ? 'L' : 'R');
                    robotClick(r, b);
                    sleepQuiet(110);
                }
                sleepQuiet(1400);
                fired = isHiddenNow();
                log("gesttest 第一次手势 clicks=" + clicks + " hidden=" + fired);
                if (fired) {              // 再点一次同样的手势，应该唤回来
                    /* 临时掐掉"右键/三连击唤回"，让这一步只可能由手势本身完成
                       （否则右键唤回会抢先恢复，测不出手势的效果） */
                    Runnable keepSummon = Hooks.summonBubble;
                    Hooks.summonBubble = null;
                    StringBuilder c2 = new StringBuilder();
                    for (int b : seq) { c2.append(b == 1 ? 'L' : 'R'); robotClick(r, b); sleepQuiet(110); }
                    sleepQuiet(1600);
                    restored = !isHiddenNow();
                    Hooks.summonBubble = keepSummon;
                    log("gesttest 第二次手势 clicks=" + c2 + " visible=" + restored);
                    /* ★ v1.24 慢手势回归：6 拍点慢些（260ms/拍，总 ~1.6s）。
                       以前右键/三连击的唤回定时器会在手势中途执行 —— 窗口以
                       气泡模式弹出来（"LLRRLL 怎么进气泡了"）。现在定时器必须
                       被顺延/作废，手势完成后由 fireBoss 以完整模式唤回。 */
                    runOnFx(this::toggleBoss);          // 先藏起来
                    sleepQuiet(900);
                    Hooks.lastGestureBuf = "";
                    StringBuilder c3 = new StringBuilder();
                    for (int b : seq) { c3.append(b == 1 ? 'L' : 'R'); robotClick(r, b); sleepQuiet(260); }
                    sleepQuiet(1800);                   // 等所有唤回定时器跑完
                    slowVisible = !isHiddenNow();
                    slowMode = oneLine(evalJs("String(app.mode)"));
                    log("gesttest 慢手势 clicks=" + c3 + " visible=" + slowVisible
                            + " mode=" + slowMode);
                }
            } catch (Throwable ex) {
                log("gesttest robot 异常: " + ex);
            }
            String res = !fired ? "MOUSE_DEAD" : (restored ? "MOUSE_OK" : "MOUSE_NO_RESTORE");
            if ("MOUSE_OK".equals(res) && (!slowVisible || !"full".equals(slowMode)))
                res = "MOUSE_SLOW_GEST_WRONG";
            log("gesttest result=" + res + " gestureEnabled=" + Hooks.gestureEnabled
                    + " mousePressed=" + Hooks.mousePressedCount
                    + " buf=" + Hooks.lastGestureBuf);
            System.out.println("GESTTEST " + res);
            if (!"MOUSE_OK".equals(res)) {
                System.out.println("GESTTEST_DIAG clicks=" + clicks + " hidden=" + fired
                        + " restored=" + restored);
            }
            sleepQuiet(300);
            Platform.runLater(this::exitApp);
        });
        t.setDaemon(true);
        t.start();
    }

    /* ══════════════════════════════════════════════════════════════════
       气泡拖动/双击还原 真实鼠标回归（--dragtest）：
       合成事件和 elementFromPoint 都绕过浏览器命中/事件管线，测不出
       "真机点了没反应"。这里用 Robot 发真实 OS 鼠标事件：
       ① 进气泡（小胶囊 resting 态）→ Robot 按住拖动条拖 80,50px → 窗口必须跟着动
       ② 在拖动条上 Robot 双击 → 必须还原完整界面
       拖动条几何由 JS 实测（小胶囊和展开态的条不一样大，别写死）。
       ══════════════════════════════════════════════════════════════════ */
    private void runDragTest() {
        Thread t = new Thread(() -> {
            sleepQuiet(2200);
            boolean dragged = false, restored = false, tiny = false;
            double dx = 0, dy = 0; String mode = "?";
            try {
                java.awt.Robot r = new java.awt.Robot();
                r.setAutoDelay(12);
                runOnFx(() -> evalJs("app.enterBubble()"));
                /* enterBubble 里 60ms 的 setTimeout 在忙时可能拖到 1 秒+ 才跑，
                   布局没就绪就去点只会点空 —— 等窗口真的缩成气泡（<400 宽）再动手 */
                boolean ready = false;
                for (int i = 0; i < 24 && !ready; i++) {
                    sleepQuiet(250);
                    double[] gw = new double[2];
                    runOnFx(() -> { gw[0] = stage.getWidth(); gw[1] = stage.getHeight(); });
                    ready = gw[0] < 400;
                }
                log("dragtest 气泡布局就绪=" + ready);
                /* 窗口钉到已知屏幕位置：排掉历史遗留坐标/看门狗挪窗的干扰 */
                runOnFx(() -> { stage.setX(200); stage.setY(200); stage.toFront(); stage.requestFocus(); });
                sleepQuiet(500);
                tiny = "true".equals(evalJs("String(!!app.bubbleTiny)"));
                log("dragtest diag mode=" + evalJs("String(app.mode)")
                        + " st=" + evalJs("JSON.stringify({bw:app._bw,bh:app._bh,"
                        + "manual:app.cfg.bubbleManualSize,pad:app._bpad,"
                        + "comp:!!app._composing,words:app.wordsVisible,side:app.sidewordOn||false})"));
                double[] g = new double[4];
                runOnFx(() -> { g[0] = stage.getX(); g[1] = stage.getY();
                                g[2] = stage.getWidth(); g[3] = stage.getHeight(); });
                sleepQuiet(200);
                String rect = evalJs("(function(){var s=document.querySelector('.dragstrip');"
                        + "if(!s)return 'NOSTRIP';var b=s.getBoundingClientRect();"
                        + "return JSON.stringify({x:b.left+b.width/2,y:b.top+b.height/2,"
                        + "w:b.width,h:b.height});})()");
                log("dragtest tiny=" + tiny + " 拖动条=" + rect + " 窗口=" + g[2] + "x" + g[3]);
                int[] c = parseXY(rect);
                int sx = (int) g[0] + c[0], sy = (int) g[1] + c[1];
                r.mouseMove(sx, sy); sleepQuiet(250);
                /* 现场取证：点击点的像素颜色 + 全屏截图（看窗口到底在不在那） */
                java.awt.Color pc = r.getPixelColor(sx, sy);
                boolean showing = false; double op = 1;
                final boolean[] sh = new boolean[1]; final double[] opq = new double[1];
                runOnFx(() -> { sh[0] = stage.isShowing(); opq[0] = stage.getOpacity(); });
                showing = sh[0]; op = opq[0];
                log("dragtest 点击点(" + sx + "," + sy + ") 像素=" + pc
                        + " showing=" + showing + " opacity=" + op);
                try {
                    java.awt.image.BufferedImage img = r.createScreenCapture(
                            new java.awt.Rectangle(0, 0, 800, 600));
                    javax.imageio.ImageIO.write(img, "png",
                            new java.io.File("data/selftest/dragtest_screen.png"));
                } catch (Exception ignored) {}
                r.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                sleepQuiet(120);
                for (int i = 1; i <= 10; i++) { r.mouseMove(sx + i * 8, sy + i * 5); sleepQuiet(30); }
                r.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                sleepQuiet(600);
                double[] g2 = new double[4];
                runOnFx(() -> { g2[0] = stage.getX(); g2[1] = stage.getY();
                                g2[2] = stage.getWidth(); g2[3] = stage.getHeight(); });
                dx = g2[0] - g[0]; dy = g2[1] - g[1];
                dragged = Math.abs(dx) > 30 && Math.abs(dy) > 15;
                log("dragtest 拖动 dx=" + dx + " dy=" + dy + " ok=" + dragged);
                /* 双击还原（Robot 两次快速单击=真实双击）——先埋探针看事件到没到 */
                rect = evalJs("(function(){var s=document.querySelector('.dragstrip');"
                        + "var b=s.getBoundingClientRect();"
                        + "return JSON.stringify({x:b.left+b.width/2,y:b.top+b.height/2});})()");
                c = parseXY(rect);
                int cx = (int) g2[0] + c[0], cy = (int) g2[1] + c[1];
                evalJs("window.__ev={any:0,strip:0,dbl:0,exit:0,pts:[]};"
                     + "document.addEventListener('mousedown',function(e){"
                     + "  window.__ev.any++;"
                     + "  if(e.target.closest&&e.target.closest('.dragstrip'))window.__ev.strip++;"
                     + "  window.__ev.pts.push(e.clientX+','+e.clientY);},true);"
                     + "document.addEventListener('dblclick',function(){window.__ev.dbl++;},true);"
                     + "var _ex=app.exitBubble.bind(app);"
                     + "app.exitBubble=function(){window.__ev.exit++;return _ex();};");
                r.mouseMove(cx, cy); sleepQuiet(250);
                robotClick(r, 1); sleepQuiet(80); robotClick(r, 1);
                sleepQuiet(900);
                mode = evalJs("String(window.app?app.mode:'NOAPP')");
                String ev = evalJs("JSON.stringify(window.__ev||{})");
                restored = "full".equals(mode);
                log("dragtest 双击后 mode=" + mode + " 探针=" + ev);
            } catch (Throwable ex) {
                log("dragtest robot 异常: " + ex);
            }
            String res = (dragged && restored) ? "DRAG_OK" : (dragged ? "DRAG_ONLY" : "DRAG_DEAD");
            System.out.println("DRAGTEST " + res + " tiny=" + tiny
                    + " dx=" + dx + " dy=" + dy + " mode=" + mode);
            sleepQuiet(300);
            Platform.runLater(this::exitApp);
        });
        t.setDaemon(true);
        t.start();
    }

    /** 从 "x,y" 或 JSON {x,y} 里抠出两个整数（--dragtest 用） */
    private static int[] parseXY(String s) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(\\d+\\.?\\d*)[^0-9]+(\\d+\\.?\\d*)").matcher(s);
            if (m.find()) return new int[]{(int) Double.parseDouble(m.group(1)),
                                          (int) Double.parseDouble(m.group(2))};
        } catch (Exception ignored) {}
        return new int[]{60, 10};
    }

    private boolean isHiddenNow() {
        final boolean[] v = new boolean[1];
        runOnFx(() -> v[0] = hiddenToTray || !stage.isShowing() || stage.isIconified());
        return v[0];
    }

    private static void robotClick(java.awt.Robot r, int button) {
        int mask = button == 1 ? java.awt.event.InputEvent.BUTTON1_DOWN_MASK
                               : java.awt.event.InputEvent.BUTTON3_DOWN_MASK;
        r.mousePress(mask);
        r.mouseRelease(mask);
    }

    private static void runOnFx(Runnable r) {
        java.util.concurrent.CountDownLatch l = new java.util.concurrent.CountDownLatch(1);
        Platform.runLater(() -> { try { r.run(); } catch (Throwable ignored) {} finally { l.countDown(); } });
        try { l.await(3, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) {}
    }

    /* ══════════════════════════════════════════════════════════════════
       导入字体回归（--fonttest）：把系统里的楷体真的导进来，看
       ① 落盘文件名/MIME 对不对 ② WebView 里 @font-face 到底有没有加载成功。
       判定用"同字号下同串文字的排版宽度"：加载成功一定比兜底字体宽/窄不同。
       ══════════════════════════════════════════════════════════════════ */
    private void runFontTest() {
        Thread t = new Thread(() -> {
            sleepQuiet(2200);
            String src = null;
            for (String p : new String[]{"C:\\Windows\\Fonts\\simkai.ttf", "C:\\Windows\\Fonts\\simhei.ttf",
                    "C:\\Windows\\Fonts\\msyh.ttc", "C:\\Windows\\Fonts\\simsun.ttc"}) {
                if (new File(p).exists()) { src = p; break; }
            }
            String name = "";
            if (src != null) name = bridge.importFontFrom(new File(src));
            log("fonttest 导入 " + src + " -> 族名[" + name + "] path=" + fontPath);
            final String fam = name;
            sleepQuiet(600);
            String js = "(function(){try{"
                    + "var name=" + jsStr(fam) + ";"
                    + "if(!name) return 'NO_NAME';"
                    + "app.fontFamily=name; app._fontData=app.host('getFontData'); app.applyVars();"
                    + "var d=document.getElementById('dynfont');"
                    + "var css=d?d.textContent:'';"
                    + "var m=document.getElementById('__fmeas');"
                    + "if(!m){m=document.createElement('span');m.id='__fmeas';"
                    + "m.style.position='absolute';m.style.left='-9999px';m.style.whiteSpace='nowrap';"
                    + "m.style.fontSize='40px';document.body.appendChild(m);}"
                    + "m.textContent='永和九年岁在癸丑暮春之初会于会稽山阴之兰亭ABCD';"
                    + "function W(f){m.style.fontFamily=f;return Math.round(m.getBoundingClientRect().width);}"
                    + "var w1=W(\"'SnapWriteCustom'\");var wk=W(\"'\"+name+\"'\");"
                    + "var w0=W(\"'Segoe UI','Microsoft YaHei',sans-serif\");var w2=W(\"'__NoSuch__',serif\");"
                    + "return JSON.stringify({name:name,face:css.indexOf('@font-face')>=0,"
                    + "prefix:(app._fontData||'').slice(0,20),w1:w1,wk:wk,w0:w0,w2:w2,"
                    + "fontvar:document.documentElement.style.getPropertyValue('--font').slice(0,26)});"
                    + "}catch(e){return 'ERR '+e}})()";
            String r = evalJs(js);
            log("fonttest 页面结果=" + r);
            System.out.println("FONTTEST name=" + fam + " data=" + r);
            sleepQuiet(200);
            Platform.runLater(this::exitApp);
        });
        t.setDaemon(true);
        t.start();
    }

    /** 简单 JS 字符串字面量转义 */
    private static String jsStr(String s) {
        if (s == null) return "''";
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ") + "'";
    }

    /* ══════════════════════════════════════════════════════════════════
       插件回归（--plugtest）：启动前往 data/plugins 写一个探针插件，
       验证 ① 启动时被加载执行 ② window.snapwrite API 可用 ③ 能真的落字。
       ══════════════════════════════════════════════════════════════════ */
    private void runPlugTest() {
        Thread t = new Thread(() -> {
            sleepQuiet(2600);
            String r = evalJs("JSON.stringify({runs:window.__probeRuns|0,api:!!window.__probeApi,"
                    + "err:window.__probeErr||'',text:(window.app?app.getText():''),"
                    + "count:(window.app&&window.app.plugins?window.app.plugins.length:-1)})");
            log("plugtest 启动加载=" + r);
            /* 再验一次"导入插件"（复制 + 热执行），走的是设置面板同一个方法 */
            final String[] imp = new String[1];
            try {
                File tmp = new File(dataDir(), "hot_probe.js");
                java.nio.file.Files.write(tmp.toPath(),
                        ("window.__hot=1;window.__hotText=(window.snapwrite?window.snapwrite.getText():'');\n")
                                .getBytes(UTF8));
                runOnFx(() -> imp[0] = bridge.importPluginFrom(tmp));
            } catch (Exception e) { log("plugtest 热导入异常 " + e); }
            sleepQuiet(500);
            String r2 = evalJs("JSON.stringify({hot:window.__hot|0,"
                    + "hasText:(window.__hotText||'').length>0,"
                    + "files:(window.app?window.app.plugins.length:-1)})");
            log("plugtest 热导入结果=" + imp[0] + " " + r2);
            System.out.println("PLUGTEST " + r + " HOT=" + r2);
            Platform.runLater(this::exitApp);
        });
        t.setDaemon(true);
        t.start();
    }

    /** 写一个探针插件（--plugtest 用）：验证 加载执行 + snapwrite API + 真落字 */
    private void writeProbePlugin() {
        try {
            File pd = new File(dataDir(), "plugins");
            if (!pd.exists()) pd.mkdirs();
            String js = "window.__probeRuns=(window.__probeRuns||0)+1;\n"
                    + "window.__probeApi=!!(window.snapwrite&&typeof window.snapwrite.insert==='function');\n"
                    + "try{ if(window.snapwrite){ window.snapwrite.setText('插件探针：已生效'); } }\n"
                    + "catch(e){ window.__probeErr=String(e); }\n";
            File f = new File(pd, "zz_probe.js");
            java.nio.file.Files.write(f.toPath(), js.getBytes(UTF8));
            log("plugtest 探针插件已写入 " + f.getAbsolutePath());
        } catch (Exception e) {
            log("plugtest 写探针失败 " + e);
        }
    }

    /**
     * 老板键回归测试（不需要真的按键）：隐藏 → 唤回 → 检查可见性 → 退出。
     *
     * 这条用例正好能钉死那个"隐藏后永远回不来"的 bug：
     * 如果 JavaFX 隐式退出没关掉，afterHide 之后 toggleBoss 根本不会执行，
     * 日志里只有 hide 没有 show，进程还会变僵尸。
     */
    private void runBossTest() {
        final boolean[] shown = new boolean[1];
        Thread t = new Thread(() -> {
            sleepQuiet(2500);
            Platform.runLater(this::hideToTray);
            sleepQuiet(1400);
            Platform.runLater(this::toggleBoss);
            sleepQuiet(1400);
            Platform.runLater(() -> {
                shown[0] = stage.isShowing() && !stage.isIconified();
                double w = stage.getScene() == null ? 0 : stage.getScene().getWidth();
                log("bosstest visible=" + shown[0] + " sceneW=" + (int) w
                        + " opacity=" + stage.getOpacity());
            });
            sleepQuiet(700);
            String r = shown[0] ? "SHOW_OK" : "SHOW_FAIL";
            log("bosstest result=" + r);
            System.out.println("BOSSTEST " + r);
            Platform.runLater(this::exitApp);
        });
        t.setDaemon(true);
        t.start();
    }

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    /**
     * 键盘回归：往 WebView 节点注入真正的 JavaFX KeyEvent（走和真人按键同一条
     * WebKit 输入通道），再回读页面状态。
     *
     * 为什么不能用 DOM 合成事件：合成事件不会触发原生删除/插入，只能验 handler
     * 逻辑；而用户反馈的是"删不掉字""Ctrl+F 没反应"这类真机行为。
     * 为什么用注入而不是 OS 级 Robot：自动化跑的时候窗口不一定拿到系统焦点，
     * Robot 会把键敲到别的窗口去（实测打出空结果）。
     */
    private void runKeyTest() {
        Thread t = new Thread(() -> {
            sleepQuiet(2500);
            StringBuilder out = new StringBuilder();

            /* 页面侧探针：记录真正到达 DOM 的按键，用来区分"事件没到"和"逻辑没跑" */
            evalJs("(function(){window.__keys=[];document.addEventListener('keydown'," +
                   "function(e){window.__keys.push(e.key+'/'+e.code+'/'+e.isTrusted);},true);" +
                   "return 1})()");

            /* 0) 诊断：注入的 JavaFX KeyEvent 到 DOM 后 key/code 是什么
               （实测 WebKit 对"程序注入"的事件不填 key/code，只能验原生编辑，
                 验不了快捷键 —— 所以快捷键另走 Java 端转发，见 forwardKeyToJs） */
            String[] probe = new String[2];
            probe[0] = probeKey(KeyCode.A, "a", "a", false);
            probe[1] = probeKey(KeyCode.F, KeyEvent.CHAR_UNDEFINED, "", true);
            out.append("KEYTEST probe=").append(java.util.Arrays.toString(probe));

            /* 1) 完整模式：先放 3 个字，按两次退格应剩 1 个 */
            setText("abc");
            fireKey(KeyCode.BACK_SPACE, KeyEvent.CHAR_UNDEFINED);
            fireKey(KeyCode.BACK_SPACE, KeyEvent.CHAR_UNDEFINED);
            out.append("\nKEYTEST full del=[").append(oneLine(evalJs("app.getText()")))
               .append("]");

            /* 2) 气泡模式：同样验删除 */
            evalJs("app.enterBubble()");
            sleepQuiet(900);
            setText("xyz");
            fireKey(KeyCode.BACK_SPACE, KeyEvent.CHAR_UNDEFINED);
            fireKey(KeyCode.BACK_SPACE, KeyEvent.CHAR_UNDEFINED);
            fireKey(KeyCode.BACK_SPACE, KeyEvent.CHAR_UNDEFINED);
            out.append("\nKEYTEST bubble del=[").append(oneLine(evalJs("app.getText()")))
               .append("]");

            /* 3) 快捷键（走 Java 端转发，和真人按 Ctrl+F 同一条路径） */
            evalJs("app.exitBubble()");
            sleepQuiet(600);
            evalJs("app.showFind=false;app.showSettings=false");
            evalJs("app.onHostKey('Ctrl+F')");
            String f1 = evalJs("String(app.showFind)");
            evalJs("app.onHostKey('Esc')");
            String f2 = evalJs("String(app.showFind)");
            out.append("\nKEYTEST ctrlF open=").append(f1).append(" afterEsc=").append(f2);

            /* 4) 设置面板：Ctrl+, 打开，Esc 关闭 */
            evalJs("app.showSettings=false");
            evalJs("app.onHostKey('Ctrl+,')");
            String s1 = evalJs("String(app.showSettings)");
            evalJs("app.onHostKey('Esc')");
            String s2 = evalJs("String(app.showSettings)");
            out.append("\nKEYTEST settings open=").append(s1).append(" afterEsc=").append(s2);

            /* 5) 录键通道（真实按键）：点"改"后按 Ctrl+F，只取值不执行动作
                  —— 这条正是"设置里改不了快捷键"的回归用例 */
            evalJs("app.showSettings=true;app.showFind=false");
            sleepQuiet(200);
            evalJs("app.startKeyCapture('export')");
            sleepQuiet(200);
            Platform.runLater(() -> webView.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED,
                    KeyEvent.CHAR_UNDEFINED, "", KeyCode.F, false, true, false, false)));
            sleepQuiet(450);
            out.append("\nKEYTEST record=").append(evalJs("String(app.cfg.keys.export)"))
               .append(" findOpened=").append(evalJs("String(app.showFind)"))
               .append(" capturing=[").append(evalJs("String(app.capturingAction)")).append("]");
            evalJs("app.showSettings=false;app.host('setKey','export','Ctrl+E');" +
                   "app.cfg.keys.export='Ctrl+E';app.showFind=false");

            /* 6) 码+Tab：真实注入 Tab 键。以前 WebView 把 Tab 拿去遍历焦点，
                  页面 DOM 的 keydown 根本收不到，"码+Tab"一点反应都没有；
                  现在 Tab 由 Java 端转发进 onHostKey('Tab')（同一条真实路径） */
            evalJs("app.cfg.wordPrefix='z';" +
                   "app.words=[{id:9601,code:'cq',content:'春秋'}]");
            setText("abcq");
            fireKeyRaw(KeyCode.TAB, KeyEvent.CHAR_UNDEFINED, "", false);
            sleepQuiet(600);
            out.append("\nKEYTEST tab=[").append(oneLine(evalJs("app.getText()")))
               .append("] keys=[").append(oneLine(evalJs("(window.__keys||[]).join('|')")))
               .append("]");
            evalJs("app.words=[];");

            out.append("\nKEYTEST errs=").append(evalJs("(window.__errs||[]).join('|')"));
            System.out.println(out);
            log(out.toString().replace('\n', ' '));
            Platform.runLater(this::exitApp);
        });
        t.setDaemon(true);
        t.start();
    }

    /** 单行化（把换行显示成 \n，方便日志按行 grep） */
    private static String oneLine(String s) {
        return s == null ? "" : s.replace("\r", "").replace("\n", "\\n");
    }

    /** 放一段文字并把 DOM 光标落到末尾 */
    private void setText(String text) {        evalJs("(function(){var e=document.getElementById('editor');e.innerText='" + text + "';" +
               "e.focus();var r=document.createRange();r.selectNodeContents(e);r.collapse(false);" +
               "var s=window.getSelection();s.removeAllRanges();s.addRange(r);" +
               "window.__keys=[];return 1;})()");
        sleepQuiet(250);
    }

    private void fireKey(KeyCode code, String ch) { fireKey(code, ch, false); }
    /** 发一个键并把 DOM 侧看到的 key/code 读回来（用来判断注入是否保真） */
    private String probeKey(KeyCode code, String character, String text, boolean ctrl) {
        evalJs("window.__keys=[]");
        fireKeyRaw(code, character, text, ctrl);
        return evalJs("window.__keys.join('|')");
    }

    private void fireKeyRaw(final KeyCode code, final String character, final String text,
                            final boolean ctrl) {
        Platform.runLater(() -> {
            String c = character == null ? KeyEvent.CHAR_UNDEFINED : character;
            String t = text == null ? "" : text;
            webView.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, c, t, code,
                    false, ctrl, false, false));
            webView.fireEvent(new KeyEvent(KeyEvent.KEY_RELEASED, c, t, code,
                    false, ctrl, false, false));
        });
        sleepQuiet(200);
    }

    /** 直接投递到 WebView 节点（捕获阶段根 → WebView，之后正常冒泡到 document） */
    private void fireKey(final KeyCode code, final String ch, final boolean ctrl) {
        Platform.runLater(() -> {
            String c = ch == null ? "" : ch;
            webView.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, c, c, code,
                    false, ctrl, false, false));
            if (!c.isEmpty() && code != KeyCode.UNDEFINED) {
                webView.fireEvent(new KeyEvent(KeyEvent.KEY_TYPED, c, c, KeyCode.UNDEFINED,
                        false, ctrl, false, false));
            }
            webView.fireEvent(new KeyEvent(KeyEvent.KEY_RELEASED, c, c, code,
                    false, ctrl, false, false));
        });
        sleepQuiet(200);
    }

    /**
     * 自测：把 /selftest.js 丢进页面执行，返回 "PASS/FAIL 用例名" 列表。
     *
     * 为什么要这么干：老板键、快捷键、词库插入这些"手点才知道"的功能，
     * 光看截图验证不出来——必须真的派发键盘事件、真的断言 DOM 里落了字。
     */
    private void runSelfTest() {
        Thread t = new Thread(() -> {
            try { Thread.sleep(1600); } catch (InterruptedException ignored) {}
            String js;
            try {
                java.io.InputStream in = getClass().getResourceAsStream("/selftest.js");
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                in.close();
                js = new String(bo.toByteArray(), UTF8);
            } catch (Throwable ex) {
                System.out.println("SELFTEST_LOAD_FAIL " + ex);
                Runtime.getRuntime().halt(2);
                return;
            }
            String res = evalJs(js);          // 返回 "PENDING" 表示异步段还在跑
            for (int i = 0; i < 30; i++) {
                if (!res.isEmpty() && !"PENDING".equals(res)) break;
                try { Thread.sleep(250); } catch (InterruptedException ignored) {}
                res = evalJs("window.__stResult||''");
            }
            System.out.println("=== SELFTEST ===");
            System.out.println(res);
            System.out.println("=== END ===");
            Runtime.getRuntime().halt(0);
        });
        t.setDaemon(true);
        t.start();
    }

    /** 在 FX 线程执行脚本并把结果同步取回来（跨线程调用 executeScript 会抛异常） */
    private String evalJs(final String js) {
        final Object[] box = new Object[1];
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        Platform.runLater(() -> {
            /* 必须在这一层就转成字符串：JSObject.toString() 内部会检查 FX 线程，
               在调用方线程再 valueOf 会抛 "Not on FX application thread" */
            try { box[0] = String.valueOf(engine.executeScript(js)); }
            catch (Throwable ex) { box[0] = "ERR " + ex; }
            latch.countDown();
        });
        try { latch.await(6, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) {}
        return box[0] == null ? "" : String.valueOf(box[0]);
    }

    /** 把 Java 端 Bridge 暴露为 window.host，并通知前端初始化 */
    private void hookJs() {
        JSObject win = (JSObject) engine.executeScript("window");
        // 注意：必须持有强引用！JSObject.setMember 对普通 Java 对象只持弱引用，
        // 不保存字段的话 Bridge 会被 GC，之后 JS 调用全部静默失效（经典大坑）
        bridge = new Bridge();
        win.setMember("host", bridge);
        engine.executeScript("window.__hostReady = true;");
        // 前端通过 window.__errs 上报 JS 错误（见 index.html 顶部错误捕获），便于诊断
        Object errs = engine.executeScript("window.__errs ? window.__errs.join(' | ') : ''");
        if (errs != null && !errs.toString().isEmpty()) {
            System.out.println("[闪写] 页面 JS 错误: " + errs);
        }
        engine.executeScript("if (window.app && app.initHost) app.initHost();");
        loadPlugins();
    }

    /** 加载 data/plugins/*.js 插件（在 window.snapwrite API 就绪后执行） */
    private void loadPlugins() {
        File pd = new File(dataDir(), "plugins");
        File[] fs = pd.listFiles((d, n) -> n.toLowerCase().endsWith(".js"));
        if (fs == null) return;
        java.util.Arrays.sort(fs);
        for (File pf : fs) {
            try {
                engine.executeScript(readAll(pf));
                System.out.println("[闪写] 插件已加载: " + pf.getName());
            } catch (Exception ex) {
                System.out.println("[闪写] 插件加载失败 " + pf.getName() + ": " + ex.getMessage());
            }
        }
    }

    /**
     * 把 JavaFX 真实按键转成标准组合串交给前端（"Ctrl+F" / "Ctrl+," / "Esc"）。
     *
     * 为什么要走这条路：WebView 里 DOM 的 KeyboardEvent 不可靠 —— 中文输入法开着时
     * e.key 是 "Process"，e.code 有些版本还直接是空串，前端无论怎么兼容都匹配不上，
     * 表现出来就是"设置里明明绑了 Ctrl+F，按了没反应"。JavaFX 这一层拿到的
     * KeyCode / 修饰键永远是真实的，所以在这里组串、交给前端统一分发。
     *
     * 前端 onHostKey 返回 true 时 consume 掉事件，避免"原生 Ctrl+B 加粗 + 我们自己
     * 再加粗一次"这种双触发。
     */
    private void forwardKeyToJs(KeyEvent e) {
        if (engine == null) return;
        KeyCode code = e.getCode();
        String name = keyName(code);
        if (name == null) return;
        /* 设置面板正在录制快捷键：什么键都要转发（包括没有修饰键的 F8、单字母），
           否则前端"改键"永远等不到按键 —— 这是"快捷键改不了"的直接原因。 */
        boolean rec = capturingKey != null && !capturingKey.isEmpty();
        if (rec && (code == KeyCode.CONTROL || code == KeyCode.SHIFT
                || code == KeyCode.ALT || code == KeyCode.META
                || code == KeyCode.WINDOWS)) return;
        boolean mod = e.isControlDown() || e.isAltDown() || e.isMetaDown();
        /* Esc 单键也转发（关弹层要用）；Tab 也转发 —— WebView 里 Tab 会被拿去遍历焦点，
           页面 DOM 的 keydown 收不到，词库"码+Tab"就完全点不动。前端处理完返回 true，
           这里 consume 掉，前端不处理（焦点在输入框）就照旧走焦点遍历。
           其他无修饰单键放给编辑器，别抢输入。 */
        boolean forwardTab = "Tab".equals(name);
        if (!rec && !mod && !"Esc".equals(name) && !forwardTab) return;
        /* 输入法组字中：让候选框自己处理（前端 composition 事件会回报状态） */
        if (composing) return;
        StringBuilder sb = new StringBuilder();
        if (e.isControlDown()) sb.append("Ctrl+");
        if (e.isShiftDown())   sb.append("Shift+");
        if (e.isAltDown())     sb.append("Alt+");
        if (e.isMetaDown())    sb.append("Win+");
        sb.append(name);
        String combo = sb.toString();
        try {
            Object handled = engine.executeScript(
                    "window.app&&app.onHostKey?String(app.onHostKey('" + combo + "')):''");
            if ("true".equals(String.valueOf(handled))) {
                e.consume();          // 已处理：别让 WebKit 再执行一遍原生编辑命令
            }
        } catch (Throwable ignored) {
            /* 页面还没初始化完：静默跳过，DOM 那条路照旧兜底 */
        }
    }

    /** KeyCode -> 前端同款键名（与 index.html 的 keyFromCode 保持一致） */
    private static String keyName(KeyCode c) {
        if (c == null) return null;
        switch (c) {
            case ESCAPE:     return "Esc";
            case SPACE:      return "Space";
            case ENTER:      return "Enter";
            case TAB:        return "Tab";
            case BACK_SPACE: return "Backspace";
            case DELETE:     return "Delete";
            case INSERT:     return "Insert";
            case UP:         return "Up";
            case DOWN:       return "Down";
            case LEFT:       return "Left";
            case RIGHT:      return "Right";
            case HOME:       return "Home";
            case END:        return "End";
            case PAGE_UP:    return "PageUp";
            case PAGE_DOWN:  return "PageDown";
            case COMMA:      return ",";
            case PERIOD:     return ".";
            case SLASH:      return "/";
            case SEMICOLON:  return ";";
            case QUOTE:      return "'";
            case OPEN_BRACKET:  return "[";
            case CLOSE_BRACKET: return "]";
            case BACK_SLASH: return "\\";
            case MINUS:      return "-";
            case EQUALS:     return "=";
            case BACK_QUOTE: return "`";
            default: break;
        }
        String n = c.getName();                       // 字母 "F" / 数字 "1" / "F5"
        if (n == null) return null;
        if (n.length() == 1) return n;
        if (n.length() <= 3 && n.charAt(0) == 'F') return n;
        return null;
    }

    /** 设置界面改配置后，同步到全局钩子 */    private void syncHooksConfig() {
        Hooks.setBossCodes(bossEnabled ? bossCodes : new int[0]);
        Hooks.gesture = gesture;
        Hooks.gestureEnabled = gestureEnabled && bossEnabled;
    }

    /** WebView 背景透明（反射调用内部 API，失败则窗口仍为白色底，不影响功能） */
    private void makeWebViewTransparent(WebView web) {
        try {
            Class<?> acc = Class.forName("com.sun.javafx.webkit.Accessor");
            Object page = acc.getMethod("getPageFor", WebEngine.class).invoke(null, engine);
            page.getClass().getMethod("setPageFill", javafx.scene.paint.Paint.class)
                    .invoke(page, javafx.scene.paint.Color.TRANSPARENT);
        } catch (Throwable t) {
            System.out.println("[闪写] WebView 透明不可用: " + t.getMessage());
        }
    }

    /** IconArt 渲染成 base64 data URL，给 HTML 标题栏用（与 exe/任务栏图标同源） */
    private String iconDataUrl() {
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            ImageIO.write(IconArt.render(32), "png", bo);
            return "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(bo.toByteArray());
        } catch (Exception e) { return ""; }
    }

    /** 文件转 data URL（图片/字体/音频通用） */
    private static String fileToDataUrl(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            String name = f.getName().toLowerCase();
            String mime;
            if (name.endsWith(".png")) mime = "image/png";
            else if (name.endsWith(".gif")) mime = "image/gif";
            else if (name.endsWith(".webp")) mime = "image/webp";
            else if (name.endsWith(".bmp")) mime = "image/bmp";
            else if (name.endsWith(".ttf")) mime = "font/ttf";
            else if (name.endsWith(".otf")) mime = "font/otf";
            else if (name.endsWith(".mp3") || name.endsWith(".m4a")) mime = "audio/mpeg";
            else if (name.endsWith(".wav")) mime = "audio/wav";
            else if (name.endsWith(".ogg")) mime = "audio/ogg";
            else if (name.endsWith(".flac")) mime = "audio/flac";
            else mime = "image/jpeg";
            return "data:" + mime + ";base64,"
                    + java.util.Base64.getEncoder().encodeToString(bo.toByteArray());
        } catch (Exception e) { return ""; }
    }

    /** 任务栏/窗口图标：自定义图标优先，否则 IconArt 渲染 */
    private void setStageIcon() {
        try {
            File f = new File(dataDir(), "icon_tmp.png");
            if (customIconPath != null && !customIconPath.isEmpty()) {
                File c = new File(dataDir(), customIconPath);
                if (c.exists()) {
                    stage.getIcons().add(new javafx.scene.image.Image(c.toURI().toString()));
                    return;
                }
            }
            ImageIO.write(IconArt.render(64), "png", f);
            stage.getIcons().add(new javafx.scene.image.Image(f.toURI().toString()));
        } catch (Exception ignored) {}
    }

    /** 运行时更换窗口/任务栏/托盘图标（设置自定义图片后调用） */
    private void applyAppIcon() {
        try {
            stage.getIcons().clear();
            setStageIcon();
            if (trayIcon != null) trayIcon.setImage(appIconImage(16));
        } catch (Exception ignored) {}
    }

    /* ════════════════ 系统托盘（老板键隐藏后的找回入口） ════════════════
       右键菜单不用 AWT PopupMenu：jlink 裁剪运行时下 AWT 菜单的中文渲染
       不可靠（整排 □□）。改用 JavaFX 自绘弹窗 —— Prism 文字管线渲染
       中文没问题（整个界面都是它画的），绝对不乱码。 */

    private void initTray() {
        try {
            if (!SystemTray.isSupported()) return;
            trayIcon = new TrayIcon(appIconImage(16), "闪写 SnapWrite");
            trayIcon.setImageAutoSize(true);
            trayIcon.addMouseListener(new MouseAdapter() {
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    if (e.getButton() == java.awt.event.MouseEvent.BUTTON3) {
                        /* 右键：在光标处弹自绘菜单（显示/退出） */
                        java.awt.Point p = java.awt.MouseInfo.getPointerInfo().getLocation();
                        Platform.runLater(() -> showTrayPopup(p.x, p.y));
                    } else if (e.getButton() == java.awt.event.MouseEvent.BUTTON1) {
                        /* 左键：显示 / 隐藏窗口（与老板键等效） */
                        Platform.runLater(FxApp.this::toggleBoss);
                    }
                }
            });
            SystemTray.getSystemTray().add(trayIcon);
        } catch (Exception ignored) {}
    }

    /** 托盘右键的自绘菜单（JavaFX 窗口，中文必然正常） */
    private void showTrayPopup(int x, int y) {
        try {
            if (trayPop == null) {
                trayPop = new Stage(StageStyle.TRANSPARENT);
                trayPop.setAlwaysOnTop(true);
                javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(1);
                box.setStyle("-fx-background-color:#232742;-fx-background-radius:12;" +
                        "-fx-padding:6;-fx-border-color:#3A3F63;-fx-border-radius:12;" +
                        "-fx-border-width:1;-fx-effect:dropshadow(gaussian,rgba(0,0,0,.45),18,0,0,6);");
                javafx.scene.control.Label show = new javafx.scene.control.Label("显示 / 隐藏");
                javafx.scene.control.Label quit = new javafx.scene.control.Label("退出");
                for (javafx.scene.control.Label lb : new javafx.scene.control.Label[]{show, quit}) {
                    lb.setStyle("-fx-text-fill:#E8EAF6;-fx-font-size:13px;-fx-padding:7 14 7 14;" +
                            "-fx-background-radius:8;-fx-cursor:hand;");
                    lb.setOnMouseEntered(e ->
                            lb.setStyle("-fx-text-fill:#FFFFFF;-fx-font-size:13px;-fx-padding:7 14 7 14;" +
                                    "-fx-background-radius:8;-fx-cursor:hand;" +
                                    "-fx-background-color:#4F46E5;"));
                    lb.setOnMouseExited(e ->
                            lb.setStyle("-fx-text-fill:#E8EAF6;-fx-font-size:13px;-fx-padding:7 14 7 14;" +
                                    "-fx-background-radius:8;-fx-cursor:hand;"));
                }
                show.setOnMouseClicked(e -> { hideTrayPopup(); Platform.runLater(this::toggleBoss); });
                quit.setOnMouseClicked(e -> { hideTrayPopup(); exitApp(); });
                box.getChildren().addAll(show, quit);
                trayPop.setScene(new Scene(box, 140, 76));
                trayPop.focusedProperty().addListener((o, was, now) -> {
                    if (!now) hideTrayPopup();     // 点别处自动收起
                });
            }
            trayPop.setX(x);
            trayPop.setY(y - 84);   // 弹在光标上方（任务栏通常在底部）
            trayPop.show();
        } catch (Exception ignored) {}
    }

    private void hideTrayPopup() {
        try { if (trayPop != null && trayPop.isShowing()) trayPop.hide(); }
        catch (Exception ignored) {}
    }

    /** 当前应用图标（用户自定义优先，否则 IconArt 默认） */
    private java.awt.Image appIconImage(int size) {
        try {
            if (customIconPath != null && !customIconPath.isEmpty()) {
                File f = new File(dataDir(), customIconPath);
                if (f.exists()) return ImageIO.read(f);
            }
        } catch (Exception ignored) {}
        return IconArt.render(size);
    }

    /* ════════════════ 老板键：显示 <-> 隐藏 ════════════════
     * 坑：不能依赖 stage.isShowing() 判断"当前是否已隐藏"——透明窗口 hide() 后
     * 该值并不总是立即翻转，导致第二次按老板键又走了一次 hide()，窗口永远回不来。
     * 改为自维护 hiddenToTray 状态。 */

    private void toggleBoss() {
        long now = System.currentTimeMillis();
        if (now - lastBossAt < 260) return;    // 去抖：钩子层面已有闩锁，这里再兜一层
        lastBossAt = now;
        // 老板键优先级最高：若快捷键捕获卡住（超时兜底外的异常情形），立即解除
        Hooks.cancelCapture();
        // 三重判定：自维护状态 / 未显示 / 已最小化（窗口最小化时 isShowing 仍为 true）
        boolean needShow = hiddenToTray || !stage.isShowing() || stage.isIconified();
        log("boss toggle -> " + (needShow ? "show" : "hide"));
        if (needShow) showFromTray(false);
        else hideToTray();
    }

    /** 隐藏到托盘（老板键 / 气泡 ✕ / 关闭选择"最小化"都走这里） */
    private void hideToTray() {
        if (bubble) { /* 保持气泡状态，恢复时原样回来 */ }
        if (bridge != null) bridge.stopWatchOnHide();
        stage.hide();
        hiddenToTray = true;
        log("hide -> tray (" + (bubble ? "bubble" : "full") + ")");
    }

    /** 从托盘恢复（重新应用不透明度并强制重排，避免透明窗口复现后白屏） */
    private void showFromTray() { showFromTray(false); }

    /**
     * 恢复窗口。
     *
     * 透明 Stage（StageStyle.TRANSPARENT）在 Windows 上有个老毛病：hide() 之后再 show()，
     * isShowing() 返回 true 但窗口一个像素都不画 —— 这正是"老板键按了没反应"的元凶。
     * 这里用官方 workaround：show 之后微调一次尺寸（±1px）逼窗口管理器重新映射表面。
     */
    private void showFromTray(boolean toBubble) {
        hiddenToTray = false;
        try { stage.setOpacity(Math.min(1.0, winOpacity / 100.0)); } catch (Exception ignored) {}
        try {
            stage.show();
            if (stage.isIconified()) stage.setIconified(false);
            double w = stage.getWidth(), h = stage.getHeight();
            stage.setHeight(h + 1);
            stage.setWidth(w + 1);
            stage.setWidth(w);
            stage.setHeight(h);
            stage.toFront();
            stage.requestFocus();
        } catch (Exception ex) {
            log("show error: " + ex);
        }
        notifyJs("if(window.app&&app.onShown)app.onShown()");
        if (toBubble) notifyJs("if(window.app&&app.enterBubble)app.enterBubble()");
        log("show from tray" + (toBubble ? " (bubble)" : ""));
        // 再兜一层：700ms 后仍然 isShowing=false 就强制重来一次
        final Stage st = stage;
        Thread guard = new Thread(() -> {
            try { Thread.sleep(700); } catch (Exception ignored) {}
            Platform.runLater(() -> {
                if (!st.isShowing() && !hiddenToTray) {
                    log("show retry");
                    try {
                        st.show();
                        st.toFront();
                        double w = st.getWidth(), h = st.getHeight();
                        st.setWidth(w + 1); st.setWidth(w);
                        st.setHeight(h + 1); st.setHeight(h);
                    } catch (Exception ignored) {}
                }
            });
        });
        guard.setDaemon(true);
        guard.start();
    }

    private void notifyJs(final String script) {
        try { engine.executeScript(script); } catch (Exception ignored) {}
    }

    /* ════════════════ 数据持久化（与 Swing 版格式兼容） ════════════════ */

    private File dataDir() {
        File d = new File(dataRoot);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 字体库目录：固定放「我的文档\闪写字体库」，用户在自己电脑上直接能翻到，
     *  把 ttf/otf 拖进去重启就能用。
     *  自测/测试模式仍写到隔离数据根（data/selftest/...），绝不碰用户真实字体库。 */
    private File fontsDir() {
        File d = fontLibraryDir();
        if (!d.exists()) d.mkdirs();
        migrateLegacyFonts(d);
        return d;
    }

    private static boolean fontsMigrated = false;

    /** 字体库真实路径（不创建、不迁移，给 getFontsDir 显示用） */
    private File fontLibraryDir() {
        boolean isolated = !"data".equals(dataRoot.replace('\\', '/'));
        if (isolated) return new File(dataDir(), "fonts");   // 自测：和真实库隔离
        String home = System.getProperty("user.home", "");
        File docs = new File(home, "Documents");
        if (!docs.isDirectory()) docs = new File(home, "文档");
        if (!docs.isDirectory()) return new File(dataDir(), "fonts");   // 找不到文档目录就退回程序目录
        return new File(docs, "闪写字体库");
    }

    /** 老版本导入的字体都在 data/fonts：搬到新字体库，别让用户"导入的字体不见了" */
    private void migrateLegacyFonts(File target) {
        if (fontsMigrated) return;
        fontsMigrated = true;
        try {
            File legacy = new File(dataDir(), "fonts");
            if (legacy.equals(target) || !legacy.isDirectory()) return;
            File[] fs = legacy.listFiles();
            if (fs == null) return;
            for (File f : fs) {
                String n = f.getName().toLowerCase();
                if (!n.endsWith(".ttf") && !n.endsWith(".otf")) continue;
                File dst = new File(target, f.getName());
                if (dst.exists()) continue;
                java.nio.file.Files.copy(f.toPath(), dst.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable ignored) {}
    }

    /** 字体库路径（设置里显示，用户按这个路径能自己在资源管理器里找到） */
    public String getFontsDir() {
        return fontsDir().getAbsolutePath();
    }

    /** 打开字体库文件夹（资源管理器里直接看得到、能往里拖字体） */
    public void openFontsDir() {
        try {
            File d = fontsDir();
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                java.awt.Desktop.getDesktop().open(d);
            } else {
                new ProcessBuilder("explorer.exe", d.getAbsolutePath()).start();
            }
        } catch (Throwable ignored) {}
    }

    /** 文件名清洗：只留中文/字母/数字/_-.，防路径穿越 */
    private static String safeFontFileName(String n) {
        String s = (n == null ? "" : n).replaceAll("[^\\w\\u4e00-\\u9fa5.\\-]", "_");
        return s.isEmpty() ? "font" : s;
    }

    /** 数据根目录：自测模式写到 data/selftest，绝不碰用户真实草稿 */
    private static String dataRoot = "data";

    /** 多文档目录：每个文档一个 .txt，Typora 式文件列表 */
    private File docsDir() {
        File d = new File(dataDir(), "docs");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private void loadAll() {
        try {
            File f = new File(dataDir(), "draft.txt");
            if (f.exists()) draft = readAll(f);
        } catch (Exception ignored) {}
        try {
            File f = new File(dataDir(), "words.txt");
            if (f.exists()) {
                for (String line : readAll(f).split("\n")) {
                    if (line.trim().isEmpty() && !line.contains("\t")) continue;
                    String[] parts = line.split("\t", -1);
                    words.add(new String[]{
                            parts.length > 0 ? parts[0] : "",
                            parts.length > 1 ? parts[1] : "",
                            parts.length > 2 ? parts[2] : ""});
                }
            }
        } catch (Exception ignored) {}
        try {
            Properties c = new Properties();
            File f = new File(dataDir(), "config.properties");
            if (f.exists()) c.load(new InputStreamReader(new FileInputStream(f), UTF8));
            bossEnabled = Boolean.parseBoolean(c.getProperty("bossEnabled", "true"));
            int[] parsed = Hooks.parseCombo(c.getProperty("boss", "Ctrl+Shift+Z+X"));
            if (parsed != null) bossCodes = parsed;
            gesture = c.getProperty("gesture", "LLRRLL").toUpperCase();
            if (!gesture.matches("[LR]{3,8}")) gesture = "LLRRLL";
            gestureEnabled = Boolean.parseBoolean(c.getProperty("gestureEnabled", "true"));
            /* 一次性迁移：旧版本鼠标手势 100% 不触发（右键按钮号错位，见 Hooks.handleGesture），
               用户当时关掉它并不是"真不想要"——修好后默认重新打开一次，之后尊重用户选择。 */
            gestureFix2 = "1".equals(c.getProperty("gestureFix2", ""));
            if (!gestureFix2) {
                gestureEnabled = true;
                gestureFix2 = true;
            }
            bubbleMaxChars = clamp(parseInt(c.getProperty("bubbleMaxChars"), 120), 40, 5000);
            bubbleCharsPerLine = clamp(parseInt(c.getProperty("bubbleCharsPerLine"), 20), 10, 40);
            accent = c.getProperty("accent", "#6366F1");
            accent2 = c.getProperty("accent2", "#22D3EE");
            themeMode = c.getProperty("themeMode", "gradient");
            if (!themeMode.matches("gradient|solid")) themeMode = "gradient";
            closeAction = c.getProperty("closeAction", "ask");
            if (!closeAction.matches("ask|tray|exit")) closeAction = "ask";
            for (String[] kv : DEFAULT_KEYS)
                keyMap.put(kv[0], c.getProperty("key." + kv[0], kv[1]));
            fontSize = clamp(parseInt(c.getProperty("fontSize"), 16), 12, 30);
            bold = Boolean.parseBoolean(c.getProperty("bold", "false"));
            wallpaperPath = c.getProperty("wallpaper", "");
            wallpaperOpacity = clamp(parseInt(c.getProperty("wallpaperOpacity"), 40), 5, 100);
            winOpacity = clamp(parseInt(c.getProperty("winOpacity"), 100), 30, 100);
            fontPath = c.getProperty("font", "");
            fontName = c.getProperty("fontName", "");
            fontWest = sanitizeFontName(c.getProperty("fontWest", ""));
            /* 文档 → 本地文件关联（key 前缀 link.） */
            docLinks.clear();
            for (String k : c.stringPropertyNames())
                if (k.startsWith("link.") && !c.getProperty(k, "").isEmpty())
                    docLinks.put(k.substring(5), c.getProperty(k));
            /* 旧版字体是单文件 data/customfont.ttf|otf：迁进 data/fonts/ 目录 */
            for (String old : new String[]{"customfont.ttf", "customfont.otf"}) {
                File o = new File(dataDir(), old);
                if (o.exists()) {
                    File nd = fontsDir();
                    File dst = new File(nd, old);
                    try {
                        java.nio.file.Files.move(o.toPath(), dst.toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        if (fontPath.equals(old)) fontPath = "fonts/" + old;
                    } catch (Exception ignored) {}
                }
            }
            if (fontPath.startsWith("customfont")) fontPath = "fonts/" + fontPath;
            fontUi = c.getProperty("fontUi", "");
            h1Size = clamp(parseInt(c.getProperty("h1Size"), 28), 12, 60);
            h2Size = clamp(parseInt(c.getProperty("h2Size"), 22), 12, 60);
            h3Size = clamp(parseInt(c.getProperty("h3Size"), 18), 12, 60);
            h1Bold = Boolean.parseBoolean(c.getProperty("h1Bold", "true"));
            h2Bold = Boolean.parseBoolean(c.getProperty("h2Bold", "true"));
            h3Bold = Boolean.parseBoolean(c.getProperty("h3Bold", "true"));
            pFont = sanitizeFontName(c.getProperty("pFont", ""));
            h1Font = sanitizeFontName(c.getProperty("h1Font", ""));
            h2Font = sanitizeFontName(c.getProperty("h2Font", ""));
            h3Font = sanitizeFontName(c.getProperty("h3Font", ""));
            pItalic = Boolean.parseBoolean(c.getProperty("pItalic", "false"));
            h1Italic = Boolean.parseBoolean(c.getProperty("h1Italic", "false"));
            h2Italic = Boolean.parseBoolean(c.getProperty("h2Italic", "false"));
            h3Italic = Boolean.parseBoolean(c.getProperty("h3Italic", "false"));
            tagDefsJson = c.getProperty("tagDefs", "");
            tocSize = clamp(parseInt(c.getProperty("tocSize"), 16), 10, 40);
            decorImgPath = c.getProperty("decorImg", "");
            bubbleCloud = Boolean.parseBoolean(c.getProperty("bubbleCloud", "true"));
            bubbleRhythm = Boolean.parseBoolean(c.getProperty("bubbleRhythm", "true"));
            bubbleCloudKind = c.getProperty("bubbleCloudKind", "cloud");
            cloudOpacity = clamp(parseInt(c.getProperty("cloudOpacity"), 60), 0, 100);
            cloudMargin = clamp(parseInt(c.getProperty("cloudMargin"), 26), 0, 80);
            bgmPath = c.getProperty("bgm", "");
            wallpaperFit = c.getProperty("wallpaperFit", "cover");
            if (!wallpaperFit.matches("cover|contain|tile|center")) wallpaperFit = "cover";
            bubbleLines = clamp(parseInt(c.getProperty("bubbleLines"), 3), 1, 12);
            bubbleW = clamp(parseInt(c.getProperty("bubbleW"), 420), 120, 2000);
            bubbleH = clamp(parseInt(c.getProperty("bubbleH"), 132), 50, 1200);
            bubbleManualSize = Boolean.parseBoolean(c.getProperty("bubbleManualSize", "false"));
            bubbleCustomChars = c.getProperty("bubbleCustomChars", "");
            customIconPath = c.getProperty("customIcon", "");
            skin = c.getProperty("skin", "light");
            if (!skin.matches("light|dark")) skin = "light";
            bubbleDblRestore = Boolean.parseBoolean(c.getProperty("bubbleDblRestore", "true"));
            bubbleAutoSize = Boolean.parseBoolean(c.getProperty("bubbleAutoSize", "true"));
            indentFirst = Boolean.parseBoolean(c.getProperty("indentFirst", "true"));
            bubbleIntrude = Boolean.parseBoolean(c.getProperty("bubbleIntrude", "false"));
            intrudeOpacity = clamp(parseInt(c.getProperty("intrudeOpacity"), 40), 5, 100);
            intrudeSize = clamp(parseInt(c.getProperty("intrudeSize"), 100), 20, 200);
            autoSave = Boolean.parseBoolean(c.getProperty("autoSave", "true"));
            alwaysWords = Boolean.parseBoolean(c.getProperty("alwaysWords", "false"));
            String wp = c.getProperty("wordPrefix", "z").trim().toLowerCase();
            wordPrefix = wp.isEmpty() ? "z" : wp.substring(0, 1);
            musicFollow = Boolean.parseBoolean(c.getProperty("musicFollow", "false"));
            currentDoc = c.getProperty("currentDoc", "未命名.txt");
            if (currentDoc.trim().isEmpty()) currentDoc = "未命名.txt";
        } catch (Exception ignored) {}
        /* 多文档：优先读当前文档；老用户只有 draft.txt，迁移成第一个文档 */
        try {
            File docFile = new File(docsDir(), currentDoc);
            if (docFile.exists()) {
                draft = readAll(docFile);
            } else if (draft != null && !draft.isEmpty()) {
                writeAll(docFile, draft);           // 首启迁移：把老草稿变成文档
            }
        } catch (Exception ignored) {}
        loadHistory();
        today0 = loadToday();
        total0 = countChars(draft);
    }

    /** 文档同名伴生 HTML：存格式（标题/底纹/标签/颜色），.txt 只存纯文字（统计/快照/兼容） */
    private File htmlFileOf(String docName) {
        String base = (docName == null ? "" : docName);
        if (base.toLowerCase().endsWith(".txt")) base = base.substring(0, base.length() - 4);
        return new File(docsDir(), base + ".html");
    }

    private void saveDraft(String text) { saveDraft(text, null); }

    private void saveDraft(String text, String html) {
        draft = text == null ? "" : text;
        try {
            writeAll(new File(dataDir(), "draft.txt"), draft);
            writeAll(new File(docsDir(), currentDoc), draft);   // 多文档：写入当前文档
            /* 格式持久化：把编辑器 HTML 存进伴生文件，重启/切回来都能还原标题、
               底纹、标签、颜色 —— 以前只存纯文字，格式重启就丢 */
            if (html != null) writeAll(htmlFileOf(currentDoc), html);
            String linked = docLinks.get(currentDoc);
            if (linked != null) {                                // 关联了本地文件：实时写回
                File lf = new File(linked);
                if (linked.toLowerCase().endsWith(".html") && html != null) writeAll(lf, html);
                else writeAll(lf, draft);
            }
            docDirty = false;
            appendHistory(draft, histForceOnce);
            histForceOnce = false;
            int total = countChars(draft);
            int today = loadToday() + (total - total0);
            if (today < 0) today = 0;
            Properties m = new Properties();
            m.setProperty("date", new SimpleDateFormat("yyyy-MM-dd").format(new Date()));
            m.setProperty("today", String.valueOf(today));
            m.store(new OutputStreamWriter(
                    new FileOutputStream(new File(dataDir(), "meta.properties")), UTF8), null);
            total0 = total;
            final int tt = today;
            Platform.runLater(() -> notifyJs("if(window.app&&app.setStats)app.setStats("
                    + tt + "," + total + ")"));
        } catch (Exception ignored) {}
    }

    /** 当前文档的伴生 HTML（没有返回空串） */
    private String getDraftHtml() {
        try {
            File f = htmlFileOf(currentDoc);
            return f.exists() ? readAll(f) : "";
        } catch (Exception e) { return ""; }
    }

    /* ---------- 文字历史快照（Typora 式丢失寻回，按文档独立存放） ---------- */

    /* 快照配额与节奏。
       ★ 老实现是"内容一变就记一条 + 只留 200 条"：自动保存是每次输入都调的，
         一次写作几分钟就能把 200 条灌满，一两天前的稿子根本留不下来。
       现在：普通快照 40 秒一条（强制快照如切文档/退出不受限），并按时间分层抽稀 ——
         10 分钟内逐条留、1 小时内每 2 分钟、24 小时内每 20 分钟、
         7 天内每 2 小时、30 天内每天、更早每周一条。 */
    private static final int HISTORY_MAX = 900;
    private static final long HIST_MIN_GAP = 40000L;
    private long lastHistAt = 0L;
    private boolean histForceOnce = false;   // 下一次 saveDraft 强制留快照（切文档用）

    /** 每个文档一份历史：data/hist/<文档名>.hist（文件名里的非法字符替换掉） */
    private File histDir() {
        File d = new File(dataDir(), "hist");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private File histFile(String doc) {
        String n = (doc == null || doc.trim().isEmpty()) ? "未命名.txt" : doc.trim();
        n = n.replaceAll("[\\\\/:*?\"<>|]", "_");
        return new File(histDir(), n + ".hist");
    }

    /** 内容有变化时追加一条快照（写入当前文档自己的历史文件） */
    private void appendHistory(String text) { appendHistory(text, false); }

    /** force=true：切文档 / 退出 / 恢复前等关键节点，不受 40 秒节奏限制 */
    private void appendHistory(String text, boolean force) {
        if (text == null || text.isEmpty() || text.equals(lastHistText)) return;
        long now = System.currentTimeMillis();
        /* 节流只针对"敲几个字"这种小改动；粘贴一大段 / 换文档这种大变化立刻留档 */
        if (!force && lastHistAt > 0 && now - lastHistAt < HIST_MIN_GAP
                && smallEdit(lastHistText, text)) {
            lastHistText = text;      // 内容记下，等下一个时间片再存
            return;
        }
        lastHistText = text;
        lastHistAt = now;
        history.add(new String[]{String.valueOf(now), text});
        boolean trimmed = pruneHistory();
        try {
            File f = histFile(currentDoc);
            if (trimmed || !f.exists()) rewriteHistoryFile(f);
            else {
                String[] e = history.get(history.size() - 1);
                StringBuilder sb = new StringBuilder();
                sb.append("###TS### ").append(e[0]).append('\n').append(e[1]).append("\n###END###\n");
                try (FileOutputStream fo = new FileOutputStream(f, true)) {
                    fo.write(sb.toString().getBytes(UTF8));
                }
            }
        } catch (Exception ignored) {}
    }

    /** 是否只是"改了几个字"（用来决定节流：小改动攒着，大改动立刻存） */
    private static boolean smallEdit(String a, String b) {
        if (a == null || b == null) return false;
        int n = Math.min(a.length(), b.length());
        int p = 0;
        while (p < n && a.charAt(p) == b.charAt(p)) p++;
        int s = 0;
        while (s < n - p && a.charAt(a.length() - 1 - s) == b.charAt(b.length() - 1 - s)) s++;
        int changed = Math.max(a.length(), b.length()) - p - s;
        return changed <= 12;
    }

    /**
     * 分层抽稀：越久远的快照留得越稀，但绝不整段丢光。
     * 10 分钟内逐条、1 小时内每 2 分钟、24 小时内每 20 分钟、
     * 7 天内每 2 小时、30 天内每天、更早每周一条。
     * 返回 true 表示列表被改动过（需要重写历史文件）。
     */
    private boolean pruneHistory() {
        if (history.size() < 3) return false;
        final long MIN = 60000L, HOUR = 60 * MIN, DAY = 24 * HOUR;
        long now = System.currentTimeMillis();
        java.util.List<String[]> keep = new ArrayList<String[]>();
        long lastKeptTs = Long.MAX_VALUE;
        for (int i = history.size() - 1; i >= 0; i--) {
            String[] e = history.get(i);
            long ts;
            try { ts = Long.parseLong(e[0]); } catch (Exception ex) { ts = now; }
            long age = now - ts;
            long gap = age < 10 * MIN ? 0L
                     : age < HOUR     ? 2 * MIN
                     : age < DAY      ? 20 * MIN
                     : age < 7 * DAY  ? 2 * HOUR
                     : age < 30 * DAY ? DAY
                     : 7 * DAY;
            if (gap == 0 || lastKeptTs == Long.MAX_VALUE || lastKeptTs - ts >= gap) {
                keep.add(0, e);
                lastKeptTs = ts;
            }
        }
        if (keep.size() > HISTORY_MAX)
            keep = new ArrayList<String[]>(keep.subList(keep.size() - HISTORY_MAX, keep.size()));
        if (keep.size() == history.size()) return false;
        history.clear();
        history.addAll(keep);
        return true;
    }

    private void rewriteHistoryFile(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String[] e : history)
            sb.append("###TS### ").append(e[0]).append('\n').append(e[1]).append("\n###END###\n");
        writeAll(f, sb.toString());
    }

    /** 解析一份历史文件（不改变当前内存里的 history） */
    private java.util.List<String[]> parseHistoryFile(File f) {
        java.util.List<String[]> out = new ArrayList<String[]>();
        if (f == null || !f.exists()) return out;
        try {
            String[] lines = readAll(f).split("\n", -1);
            String ts = null;
            StringBuilder cur = new StringBuilder();
            for (String line : lines) {
                if (line.startsWith("###TS### ")) {
                    ts = line.substring(9).trim();
                    cur.setLength(0);
                } else if (line.equals("###END###")) {
                    if (ts != null) {
                        String txt = cur.toString();
                        if (txt.endsWith("\n")) txt = txt.substring(0, txt.length() - 1);
                        out.add(new String[]{ts, txt});
                    }
                    ts = null; cur.setLength(0);
                } else if (ts != null) {
                    cur.append(line).append('\n');
                }
            }
        } catch (Exception ignored) {}
        while (out.size() > HISTORY_MAX) out.remove(0);
        return out;
    }

    /** 载入当前文档的历史快照（启动 / 切文档时调用） */
    private void loadHistory() {
        history.clear();
        File f = histFile(currentDoc);
        /* 老版本只有一个全局 data/history.txt：迁移到当前文档名下，原文件改名留档 */
        File legacy = new File(dataDir(), "history.txt");
        if (!f.exists() && legacy.exists()) {
            java.util.List<String[]> old = parseHistoryFile(legacy);
            if (!old.isEmpty()) {
                history.addAll(old);
                try { rewriteHistoryFile(f); } catch (Exception ignored) {}
                try { legacy.renameTo(new File(dataDir(), "history.migrated.txt")); } catch (Exception ignored) {}
                log("history 迁移 " + old.size() + " 条 -> " + currentDoc);
            }
        } else {
            history.addAll(parseHistoryFile(f));
        }
        if (!history.isEmpty()) lastHistText = history.get(history.size() - 1)[1];
    }

    private int loadToday() {        try {
            Properties m = new Properties();
            File f = new File(dataDir(), "meta.properties");
            if (f.exists()) m.load(new InputStreamReader(new FileInputStream(f), UTF8));
            String today = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
            if (!today.equals(m.getProperty("date", ""))) return 0;   // 跨天清零
            return Integer.parseInt(m.getProperty("today", "0"));
        } catch (Exception e) { return 0; }
    }

    private void saveWords() {
        try {
            StringBuilder sb = new StringBuilder();
            for (String[] w : words)
                sb.append(w[0]).append('\t').append(w[1]).append('\t')
                  .append(w.length > 2 ? w[2] : "").append('\n');
            writeAll(new File(dataDir(), "words.txt"), sb.toString());
        } catch (Exception ignored) {}
    }

    private void saveConfig() {
        try {
            Properties c = new Properties();
            c.setProperty("bossEnabled", String.valueOf(bossEnabled));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < bossCodes.length; i++) {
                if (i > 0) b.append('+');
                b.append(Hooks.codeName(bossCodes[i]));
            }
            c.setProperty("boss", b.toString());
            c.setProperty("gesture", gesture);
            c.setProperty("gestureEnabled", String.valueOf(gestureEnabled));
            if (gestureFix2) c.setProperty("gestureFix2", "1");
            c.setProperty("bubbleMaxChars", String.valueOf(bubbleMaxChars));
            c.setProperty("bubbleCharsPerLine", String.valueOf(bubbleCharsPerLine));
            c.setProperty("accent", accent);
            c.setProperty("accent2", accent2);
            c.setProperty("themeMode", themeMode);
            c.setProperty("closeAction", closeAction);
            for (java.util.Map.Entry<String, String> e : keyMap.entrySet())
                c.setProperty("key." + e.getKey(), e.getValue());
            c.setProperty("fontSize", String.valueOf(fontSize));
            c.setProperty("bold", String.valueOf(bold));
            c.setProperty("wallpaper", wallpaperPath);
            c.setProperty("wallpaperOpacity", String.valueOf(wallpaperOpacity));
            c.setProperty("winOpacity", String.valueOf(winOpacity));
            c.setProperty("font", fontPath);
            c.setProperty("fontName", fontName);
            for (java.util.Map.Entry<String, String> en : docLinks.entrySet())
                c.setProperty("link." + en.getKey(), en.getValue());
            c.setProperty("fontUi", fontUi);
            c.setProperty("h1Size", String.valueOf(h1Size));
            c.setProperty("h2Size", String.valueOf(h2Size));
            c.setProperty("h3Size", String.valueOf(h3Size));
            c.setProperty("h1Bold", String.valueOf(h1Bold));
            c.setProperty("h2Bold", String.valueOf(h2Bold));
            c.setProperty("h3Bold", String.valueOf(h3Bold));
            c.setProperty("pFont", pFont);
            c.setProperty("h1Font", h1Font);
            c.setProperty("h2Font", h2Font);
            c.setProperty("h3Font", h3Font);
            c.setProperty("fontWest", fontWest);
            c.setProperty("pItalic", String.valueOf(pItalic));
            c.setProperty("h1Italic", String.valueOf(h1Italic));
            c.setProperty("h2Italic", String.valueOf(h2Italic));
            c.setProperty("h3Italic", String.valueOf(h3Italic));
            c.setProperty("tagDefs", tagDefsJson);
            c.setProperty("tocSize", String.valueOf(tocSize));
            c.setProperty("decorImg", decorImgPath);
            c.setProperty("bubbleCloud", String.valueOf(bubbleCloud));
            c.setProperty("bubbleRhythm", String.valueOf(bubbleRhythm));
            c.setProperty("bubbleCloudKind", bubbleCloudKind);
            c.setProperty("cloudOpacity", String.valueOf(cloudOpacity));
            c.setProperty("cloudMargin", String.valueOf(cloudMargin));
            c.setProperty("bgm", bgmPath);
            c.setProperty("wallpaperFit", wallpaperFit);
            c.setProperty("bubbleLines", String.valueOf(bubbleLines));
            c.setProperty("bubbleW", String.valueOf(bubbleW));
            c.setProperty("bubbleH", String.valueOf(bubbleH));
            c.setProperty("bubbleManualSize", String.valueOf(bubbleManualSize));
            c.setProperty("bubbleCustomChars", bubbleCustomChars);
            c.setProperty("customIcon", customIconPath);
            c.setProperty("skin", skin);
            c.setProperty("bubbleDblRestore", String.valueOf(bubbleDblRestore));
            c.setProperty("bubbleAutoSize", String.valueOf(bubbleAutoSize));
            c.setProperty("indentFirst", String.valueOf(indentFirst));
            c.setProperty("bubbleIntrude", String.valueOf(bubbleIntrude));
            c.setProperty("intrudeOpacity", String.valueOf(intrudeOpacity));
            c.setProperty("intrudeSize", String.valueOf(intrudeSize));
            c.setProperty("autoSave", String.valueOf(autoSave));
            c.setProperty("alwaysWords", String.valueOf(alwaysWords));
            c.setProperty("wordPrefix", wordPrefix);
            c.setProperty("musicFollow", String.valueOf(musicFollow));
            c.setProperty("currentDoc", currentDoc);
            c.store(new OutputStreamWriter(
                    new FileOutputStream(new File(dataDir(), "config.properties")), UTF8), null);
        } catch (Exception ignored) {}
    }

    /* ════════════════ 多文档（Typora 式文件管理） ════════════════ */

    /** 字体族名消毒：去掉引号/反斜杠，限长 —— 防止把 CSS/JSON 搞坏 */
    private static String sanitizeFontName(String name) {
        String n = name == null ? "" : name.replace("['\"\\\\]", "").trim();
        return n.length() > 60 ? n.substring(0, 60) : n;
    }

    /** 文件名消毒：Windows 非法字符 + 路径穿越 */
    private static String safeDocName(String name) {
        String n = name == null ? "" : name.trim();
        n = n.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (n.isEmpty()) n = "未命名";
        if (!n.toLowerCase().endsWith(".txt") && !n.toLowerCase().endsWith(".md")) n += ".txt";
        return n;
    }

    /** 同名时自动加序号：章节-2.txt */
    private String uniqueDocName(String base, String ignore) {
        String n = safeDocName(base);
        String stem = n.replaceAll("\\.(txt|md)$", "");
        String ext = n.substring(stem.length());
        String cand = n;
        int i = 2;
        while (new File(docsDir(), cand).exists() && !cand.equals(ignore)) {
            cand = stem + "-" + (i++) + ext;
        }
        return cand;
    }

    /** 退出前落盘 + 快照（即使用户忘了 Ctrl+S 也不会丢稿） */
    private void saveSnapshotOnExit() {
        try {
            if (autoSave && draft != null && !draft.isEmpty()) {
                writeAll(new File(docsDir(), currentDoc), draft);
            }
            appendHistory(draft, true);
        } catch (Exception ignored) {}
    }

    /** 文档列表 JSON：[{name,chars,mtime}]，按修改时间倒序 */
    private String docListJson() {
        StringBuilder sb = new StringBuilder("[");
        File[] fs = docsDir().listFiles((d, n) -> {
            String l = n.toLowerCase();
            return l.endsWith(".txt") || l.endsWith(".md");
        });
        if (fs != null) {
            java.util.Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            boolean first = true;
            for (File f : fs) {
                if (!first) sb.append(',');
                first = false;
                String content = "";
                try { content = readAll(f); } catch (Exception ignored) {}
                sb.append("{\"name\":\"").append(esc(f.getName()))
                  .append("\",\"chars\":").append(countChars(content))
                  .append(",\"mtime\":").append(f.lastModified())
                  .append(",\"hist\":").append(parseHistoryFile(histFile(f.getName())).size())
                  .append(",\"current\":").append(f.getName().equals(currentDoc))
                  .append(",\"preview\":\"").append(esc(preview(content, 40))).append("\"}");
            }
        }
        return sb.append(']').toString();
    }

    private static String preview(String s, int n) {
        if (s == null) return "";
        String t = s.replace("\n", " ").replace("\r", " ").trim();
        return t.length() <= n ? t : t.substring(0, n) + "…";
    }

    private void exitApp() {
        log("exit requested");
        // 退出前抓一次正文；engine 若卡死也不能拦住退出（下面有看门狗）
        try {
            Object t = engine.executeScript("if(window.app&&app.getText)app.getText();else ''");
            if (t != null) saveDraft(t.toString());
        } catch (Exception ex) {
            log("exit save failed: " + ex);
        }
        saveSnapshotOnExit();
        // 看门狗：不管卡在 Hooks.unregister 还是 Platform.exit，1.2 秒后硬退
        Thread dog = new Thread(() -> {
            try { Thread.sleep(1200); } catch (Exception ignored) {}
            Runtime.getRuntime().halt(0);
        });
        dog.setDaemon(true);
        dog.start();
        try { Hooks.unregister(); } catch (Exception ignored) {}
        try { SystemTray.getSystemTray().remove(trayIcon); } catch (Exception ignored) {}
        try { Platform.exit(); } catch (Exception ignored) {}
        System.exit(0);
    }

    /* ════════════════ 工具 ════════════════ */

    private static int countChars(String s) {
        return s == null ? 0 : s.replaceAll("\\s", "").length();
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    private static String readAll(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        InputStreamReader r = new InputStreamReader(new FileInputStream(f), UTF8);
        char[] buf = new char[4096];
        int n;
        while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        r.close();
        return sb.toString();
    }

    private static void writeAll(File f, String s) throws Exception {
        OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), UTF8);
        w.write(s);
        w.close();
    }

    /** JSON 字符串转义 */
    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t");
    }

    /* ════════════════ 截图测试（自动化视觉验证） ════════════════ */

    private void runShotTest() {
        javafx.animation.PauseTransition wait = new javafx.animation.PauseTransition(
                javafx.util.Duration.seconds(4));
        wait.setOnFinished(e -> {
            try {
                if (FxMain.shotBubbleWords) {
                    engine.executeScript("if(window.app&&app.testFill)app.testFill();");
                    engine.executeScript("if(window.app){app.cfg.alwaysWords=true;app.enterBubble();}");
                } else if (FxMain.shotBubble) {
                    engine.executeScript("if(window.app&&app.testFill)app.testFill();");
                    engine.executeScript("if(window.app&&app.enterBubble)app.enterBubble();");
                    /* 光标贴到文末：验证"平时输入 = 小胶囊随字数变长"的默认态 */
                    engine.executeScript("(function(){var ed=document.getElementById('editor');" +
                        "var r=document.createRange();r.selectNodeContents(ed);r.collapse(false);" +
                        "var s=window.getSelection();s.removeAllRanges();s.addRange(r);" +
                        "if(window.app){app._composing=false;app.applyBubbleSizeNow();}})()");
                } else if (FxMain.shotCtx) {
                    /* 右键菜单：光标停在标题里，验证"标题也能右键改字体/字号/格式" */
                    engine.executeScript("if(window.app&&app.testFill)app.testFill();");
                    engine.executeScript(
                        "(function(){var app=window.app,ed=document.getElementById('editor');" +
                        "ed.innerHTML='<h3>第一回 夜行</h3><p>他推开门，风从廊下穿过来，吹得纸页乱翻。</p>';" +
                        "var h=ed.querySelector('h3');var r=document.createRange();" +
                        "r.setStart(h.firstChild,2);r.collapse(true);" +
                        "var s=window.getSelection();s.removeAllRanges();s.addRange(r);" +
                        "if(app){app._range=r.cloneRange();app.ctxOpen({clientX:150,clientY:190});" +
                        (FxMain.shotCtxStyle ? "app.ctxStyleOpen('h1');" : "") +
                        "}})()");
                } else if (FxMain.shotBubbleCfg) {
                    /* 设置 → 气泡悬浮模式（含"自己定大小"两条滑杆） */
                    engine.executeScript("if(window.app){app.showSettings=true;" +
                        "app.cfg.bubbleManualSize=true;app.cfg.bubbleW=520;app.cfg.bubbleH=180;}");
                    Object r = engine.executeScript(
                        "setTimeout(function(){var m=document.querySelector('.modal');" +
                        "if(!m)return;var hs=m.querySelectorAll('h4');" +
                        "for(var i=0;i<hs.length;i++)" +
                        "if(hs[i].textContent.indexOf('气泡悬浮模式')>=0){" +
                        "m.scrollTop=hs[i].getBoundingClientRect().top" +
                        "-m.getBoundingClientRect().top+m.scrollTop-20;break;}" +
                        "},420); 'scheduled'");
                    System.out.println("[shot] bubblecfg=" + r);
                } else if (FxMain.shotSettings) {
                    engine.executeScript("if(window.app&&app.testFill)app.testFill();");
                    engine.executeScript("if(window.app&&app.toggleWords)app.toggleWords();");
                    engine.executeScript("if(window.app)app.showSettings=true;");
                    if (FxMain.shotData) {
                        /* 造点数据：几条快照 + 一个回收站条目，再滚到"数据与恢复" */
                        engine.executeScript("if(window.app){var app=window.app;" +
                            "app.host('saveNow',app.getText());" +
                            "app.host('newDoc','废弃草稿.txt');app.host('saveNow','随手写废的一段。');" +
                            "app.host('deleteDoc','废弃草稿.txt');app.refreshDataPanel();}");
                        Object r2 = engine.executeScript(
                            "setTimeout(function(){var m=document.querySelector('.modal');" +
                            "if(!m)return;var hs=m.querySelectorAll('h4');" +
                            "for(var i=0;i<hs.length;i++)" +
                            "if(hs[i].textContent.indexOf('数据与恢复')>=0){" +
                            "m.scrollTop=hs[i].getBoundingClientRect().top" +
                            "-m.getBoundingClientRect().top+m.scrollTop-30;break;}" +
                            "},500); 'scheduled'");
                        System.out.println("[shot] data-scroll=" + r2);
                    } else if (FxMain.shotSettingsScroll) {
                        /* 滚到"外观"区：验证标题格式设置 */
                        Object r = engine.executeScript(
                            "setTimeout(function(){var m=document.querySelector('.modal');" +
                            "if(!m)return;var hs=m.querySelectorAll('h4');" +
                            "for(var i=0;i<hs.length;i++)" +
                            "if(hs[i].textContent.indexOf('外观')>=0){" +
                            "m.scrollTop=hs[i].getBoundingClientRect().top" +
                            "-m.getBoundingClientRect().top+m.scrollTop-46;break;}" +
                            "},420); 'scheduled'");
                        System.out.println("[shot] scroll=" + r);
                    }
                } else if (FxMain.shotImport) {
                    engine.executeScript("if(window.app&&app.testFill)app.testFill();");
                    engine.executeScript("if(window.app)app.showImport('示例文档.docx'," +
                        "'第一章 起风\\n\\n他把那封信放进抽屉，像是把整段夏夜都收了进去。\\n" +
                        "窗外的槐树在风里翻着叶子，沙沙地响。');");
                } else if (FxMain.shotFind) {
                    /* 查找条开着 + 正文里已有匹配（高亮可见） */
                    engine.executeScript("if(window.app&&app.testFill)app.testFill();");
                    engine.executeScript("if(window.app){app.findText='沈砚之';app.replText='';" +
                            "app.openFind();}");
                } else if (FxMain.shotHist) {
                    /* 寻回弹窗：造两条快照再打开（多文件下拉也在里面） */
                    engine.executeScript("if(window.app&&app.testFill)app.testFill();");
                    engine.executeScript("if(window.app){var t=app.getText();" +
                            "app.host('saveDraft',t+'\\n\\n（一稿）');" +
                            "app.host('saveDraft',t+'\\n\\n（二稿，改了两处）');" +
                            "app.host('saveDraft',t);app.showHistFor(app.cfg.docName);}");
                } else if (FxMain.shotDocs) {
                    /* 文件面板：多一个文件 + 每行带"寻回 N 条" */
                    engine.executeScript("if(window.app&&app.testFill)app.testFill();");
                    engine.executeScript("if(window.app){var t=app.getText(),cur=app.cfg.docName;" +
                            "app.host('saveDraft',t);" +
                            "app.host('newDoc','第二章 旧事.txt');" +
                            "app.host('switchDoc',cur);app.refreshDocs();app.showDocs=true;}");
                } else if (FxMain.shotExport) {
                    /* 导出面板：填正文 + 打开格式卡片 */
                    engine.executeScript("if(window.app&&app.testFill)app.testFill();");
                    engine.executeScript("if(window.app){app.showExport=true;}");
                } else
                    engine.executeScript("if(window.app&&app.testFill)app.testFill();");
            } catch (Exception ex) {
                System.out.println("SHOT_SCRIPT_ERR " + ex);
            }
            javafx.animation.PauseTransition wait2 = new javafx.animation.PauseTransition(
                    javafx.util.Duration.seconds(1));
            wait2.setOnFinished(e2 -> {
                try {
                    System.out.println("DEBUG wpop=" + engine.executeScript(
                            "(function(){var p=document.querySelector('.wpop');" +
                            "if(!p)return 'none';var r=p.getBoundingClientRect();" +
                            "return Math.round(r.left)+','+Math.round(r.top)+','+" +
                            "Math.round(r.width)+','+Math.round(r.height)+" +
                            "' disp='+getComputedStyle(p).display;})()"));
                    Object errs = engine.executeScript(
                            "window.__errs ? window.__errs.join(' | ') : ''");
                    if (errs != null && !errs.toString().isEmpty())
                        System.out.println("[闪写] 页面 JS 错误: " + errs);
                    WritableImage img = stage.getScene().snapshot(null);
                    ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png",
                            new File("ui_hybrid.png"));
                    System.out.println("SNAPSHOT_OK");
                } catch (Exception ex) {
                    System.out.println("SNAPSHOT_FAIL " + ex);
                }
                Platform.exit();
                System.exit(0);
            });
            wait2.play();
        });
        wait.play();
    }

    /* ══════════════════════════════════════════════════════════
       JS 桥：WebView 里的 Vue 通过 window.host 调用这些公开方法
       （JS -> Java 在 FX 线程执行，可直接操作 Stage）
       ══════════════════════════════════════════════════════════ */
    public class Bridge {

        /* ---- 万能调度 ----
         * 实测 JavaFX WebView 的 LiveConnect 派发对"无返回值方法"会静默失败
         * （getter 正常、void 方法不执行也不报错）。
         * 因此 JS 端所有动作一律走 js('方法名','参数1',...)，返回 "ok"/数据/"ERR:..."。 */
        public String js(String name, String a1, String a2, String a3, String a4,
                         String a5, String a6) {
            try {
                switch (name == null ? "" : name) {
                    /* 数据 */
                    case "getConfig":    return getConfig();
                    case "getWords":     return getWords();
                    case "getDraft":     return getDraft();
                    case "getDraftHtml": return getDraftHtml();
                    case "getDocLink":   return docLinks.get(currentDoc) == null ? "" : docLinks.get(currentDoc);
                    case "linkDocTo":    return linkDocTo(a1);
                    case "unlinkDoc":    unlinkDoc(); break;
                    case "setComposing": setComposing(a1); return "";
                    case "setCapturing": setCapturing(a1); return "";
                    case "getIcon":      return getIcon();
                    case "getWallpaper": return getWallpaper();
                    case "getFontData":  return getFontData();
                    case "getFontName":  return getFontName();
                    case "listImportedFonts": return listImportedFonts();
                    case "listSystemFonts": return listSystemFonts();
                    case "getFontsDir":   return getFontsDir();
                    case "openFontsDir":  openFontsDir(); break;
                    case "getFontDataByFile": return getFontDataByFile(a1);
                    case "deleteFont":   return deleteFont(a1);
                    case "setWestFont":  setWestFont(a1); break;
                    case "setStyleItalics": setStyleItalics(a1, a2, a3, a4); break;
                    case "setTagDefs":   setTagDefs(a1); break;
                    case "setTocSize":   setTocSize(parseIntS(a1)); break;
                    case "getBgm":       return getBgm();
                    case "isAutoStart":  return String.valueOf(isAutoStart());
                    case "saveDraft":    saveDraft(a1, a2); break;
                    case "popNav":       popNav(a1); break;
                    case "updateNav":    updateNav(a1); break;
                    case "closeNav":     if (navStage != null) navStage.hide(); break;
                    /* 多文档 */
                    case "getDocs":      return getDocs();
                    case "getDocName":   return getDocName();
                    case "newDoc":       return newDoc(a1);
                    case "switchDoc":    return switchDoc(a1);
                    case "renameDoc":    return renameDoc(a1, a2);
                    case "deleteDoc":    deleteDoc(a1); break;
                    case "importDocFile":return importDocFile();
                    case "saveNow":      return saveNow(a1, a2);
                    case "setAutoSave":  setAutoSave(Boolean.parseBoolean(a1)); break;
                    case "setWordsAlways": setWordsAlways(Boolean.parseBoolean(a1)); break;
                    /* 词库 */
                    case "addWord":      addWord(a1, a2, a3); break;
                    case "deleteWord":   deleteWord(parseIntS(a1)); break;
                    case "updateWord":   updateWord(parseIntS(a1), a2, a3, a4); break;
                    /* 快捷键 */
                    case "beginCapture":   beginCapture(); break;
                    case "setBoss":        return setBoss(a1);
                    case "setBossEnabled": setBossEnabled(Boolean.parseBoolean(a1)); break;
                    case "setGesture":     return setGesture(a1, Boolean.parseBoolean(a2));
                    /* 气泡 */
                    case "exportAs":       return exportAs(a1, a2, a3, a4);
                    case "clearAll":       clearAll(); break;
                    case "setBubbleConfig": setBubbleConfig(parseIntS(a1), parseIntS(a2)); break;
                    case "enterBubble":     enterBubble(); break;
                    case "exitBubble":      exitBubble(); break;
                    case "resizeBubble":    resizeBubble(parseDoubleS(a1), parseDoubleS(a2)); break;
                    case "setBubbleCloudConfig":
                        setBubbleCloudConfig(Boolean.parseBoolean(a1), Boolean.parseBoolean(a2),
                                a3, parseIntS(a4)); break;
                    case "pickMusic": return pickMusic();
                    case "clearMusic": clearMusic(); break;
                    /* 外观 */
                    case "setAppearance": setAppearance(a1, parseIntS(a2), Boolean.parseBoolean(a3)); break;
                    case "setAutoStart":  setAutoStart(Boolean.parseBoolean(a1)); break;
                    case "pickWallpaper": return pickWallpaper();
                    case "clearWallpaper": clearWallpaper(); break;
                    case "pickFont":  return pickFont();
                    case "clearFont": clearFont(); break;
                    case "setFontUi":     setFontUi(a1); break;
                    case "setHeadingCfg": setHeadingCfg(a1, a2, a3, a4, a5, a6); break;
                    case "setStyleFonts": setStyleFonts(a1, a2, a3, a4); break;
                    case "pickDecorImage":  return pickDecorImage();
                    case "getDecorImage":   return getDecorImage();
                    case "clearDecorImage": clearDecorImage(); break;
                    case "setWallpaperOpacity": setWallpaperOpacity(parseIntS(a1)); break;
                    case "setWinOpacityPercent": setWinOpacityPercent(parseIntS(a1)); break;
                    /* 窗口 */
                    case "moveBy":         moveBy(parseDoubleS(a1), parseDoubleS(a2)); break;
                    case "setSize":        setSize(parseDoubleS(a1), parseDoubleS(a2)); break;
                    case "setOpacity":     setOpacity(parseDoubleS(a1) / 100.0); break;
                    case "minimize":       minimize(); break;
                    case "toggleMaximize": toggleMaximize(); break;
                    case "togglePin":      togglePin(); break;
                    case "closeApp":       closeApp(); break;
                    case "hideWindow":     hideToTray(); break;
                    /* 主题 / 快捷键 / 关闭行为 */
                    case "setTheme":       setTheme(a1, a2, a3); break;
                    case "setSkin":        setSkin(a1); break;
                    case "setBubbleFlags": setBubbleFlags(Boolean.parseBoolean(a1),
                            Boolean.parseBoolean(a2)); break;
                    case "setIndentCfg":   setIndentCfg(Boolean.parseBoolean(a1)); break;
                    case "setBubbleIntrude": setBubbleIntrude(Boolean.parseBoolean(a1),
                            parseIntS(a2), parseIntS(a3)); break;
                    case "setKey":         setKey(a1, a2); break;
                    case "resetKeys":      resetKeys(); break;
                    case "setCloseAction": setCloseAction(a1); break;
                    /* 寻回 */
                    case "listHistory":    return listHistory();
                    case "restoreHistory": return restoreHistory(parseIntS(a1));
                    case "listDocHistory": return listDocHistory(a1);
                    case "histCount":      return histCount(a1);
                    case "getHistoryText": return getHistoryText(a1, parseIntS(a2));
                    case "restoreDocHistory": return restoreDocHistory(a1, parseIntS(a2));
                    case "listDeleted":    return listDeleted();
                    case "restoreDeleted": return restoreDeleted(a1);
                    case "listHistDocs":   return listHistDocs();
                    case "purgeDocHistory":return purgeDocHistory(a1);
                    case "purgeAllHistory":return purgeAllHistory();
                    case "purgeDeleted":   return purgeDeleted(a1);
                    /* 文档导入 */
                    case "importDoc":      importDoc(); break;
                    /* 图标 / 配置扩展 */
                    case "pickCustomIcon":  return pickCustomIcon();
                    case "pickExeIcon":     return pickExeIcon();
                    case "resetCustomIcon": resetCustomIcon(); break;
                    case "getCustomIcon":   return getCustomIcon();
                    case "setWallpaperFit":
                        if (a1 != null && a1.matches("cover|contain|tile|center")) {
                            wallpaperFit = a1; saveConfig();
                        } break;
                    case "setBubbleLines":
                        bubbleLines = clamp(parseIntS(a1), 1, 12); saveConfig(); break;
                    /* 气泡手动大小（用户自己拉宽/拉高）：w, h, manual(1/0) */
                    case "setBubbleSizeCfg":
                        bubbleW = clamp(parseIntS(a1), 120, 2000);
                        bubbleH = clamp(parseIntS(a2), 50, 1200);
                        bubbleManualSize = "1".equals(a3) || "true".equals(a3);
                        saveConfig(); break;
                    /* 气泡边缘拉伸：JS 在边缘热区按下 → 拖动中持续上报屏幕坐标 → 松开结算 */
                    case "bubbleResizeStart":
                        rszDrag = true;
                        rszDir = a1 == null ? "" : a1;
                        rszSX = parseD(a2); rszSY = parseD(a3);
                        rszX0 = stage.getX(); rszY0 = stage.getY();
                        rszW0 = stage.getWidth(); rszH0 = stage.getHeight();
                        break;
                    case "bubbleResizeMove": {
                        if (!rszDrag) break;
                        double dx = parseD(a1) - rszSX, dy = parseD(a2) - rszSY;
                        boolean east = rszDir.contains("e"), west = rszDir.contains("w"),
                                south = rszDir.contains("s"), north = rszDir.contains("n");
                        double x = rszX0, y = rszY0, w = rszW0, h = rszH0;
                        if (east)  w = Math.max(120, Math.min(2000, rszW0 + dx));
                        if (west)  { w = Math.max(120, Math.min(2000, rszW0 - dx)); x = rszX0 + rszW0 - w; }
                        if (south) h = Math.max(50, Math.min(1200, rszH0 + dy));
                        if (north) { h = Math.max(50, Math.min(1200, rszH0 - dy)); y = rszY0 + rszH0 - h; }
                        stage.setX(x); stage.setY(y);
                        stage.setWidth(w); stage.setHeight(h);
                        break;
                    }
                    case "bubbleResizeEnd": {
                        rszDrag = false;
                        bubbleW = clamp((int) stage.getWidth(), 120, 2000);
                        bubbleH = clamp((int) stage.getHeight(), 50, 1200);
                        bubbleManualSize = true;     // 手拉过的尺寸=手动定大小，打字不再自动改
                        saveConfig();
                        return bubbleW + "," + bubbleH;   // JS 拿去同步滑杆显示
                    }
                    case "setBubbleCustom":
                        bubbleCustomChars = a1 == null ? "" : a1.trim(); saveConfig(); break;
                    /* 环绕元素边距 / 跟随音乐 */
                    case "setCloudMargin":
                        cloudMargin = clamp(parseIntS(a1), 0, 80); saveConfig(); break;
                    case "setMusicFollow":
                        musicFollow = Boolean.parseBoolean(a1);
                        if (musicFollow) VolumeMeter.start();
                        saveConfig(); break;
                    case "getSysPeak":   return String.valueOf(VolumeMeter.peak());
                    case "cancelCapture": Hooks.cancelCapture(); break;
                    /* 插件 */
                    case "listPlugins":    return listPlugins();
                    case "importPlugin":   return importPlugin();
                    case "openPluginsDir":
                        try {
                            File pd = new File(dataDir(), "plugins");
                            if (!pd.exists()) pd.mkdirs();
                            Runtime.getRuntime().exec(new String[]{"explorer.exe", pd.getAbsolutePath()});
                        } catch (Exception ignored) {}
                        break;
                    default: return "ERR:no-such-method:" + name;
                }
                return "ok";
            } catch (Exception e) {
                return "ERR:" + e.getMessage();
            }
        }

        private static int parseIntS(String s) {
            try { return (int) Double.parseDouble(s.trim()); } catch (Exception e) { return 0; }
        }

        private static double parseD(String s) {
            try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0; }
        }

        private static double parseDoubleS(String s) {
            try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0; }
        }

        /* ---- 数据 ---- */
        public String getDraft() { return draft; }

        /** 输入法组字状态回报：组字期间 Java 端不转发 Esc/Ctrl 组合，避免打断候选框 */
        public void setComposing(String v) { composing = "1".equals(v) || "true".equals(v); }

        /** 前端开始/结束录制快捷键：录键期间 Java 只转发、不执行动作 */
        public void setCapturing(String id) { capturingKey = (id == null) ? "" : id.trim(); }

        /** 注意：必须显式写 FxApp.this.saveDraft —— 否则解析到本方法自身，无限递归 */
        public void saveDraft(String t) { FxApp.this.saveDraft(t); }
        public void saveDraft(String t, String html) { FxApp.this.saveDraft(t, html); }

        /* ---- 多文档 ---- */
        public String getDocs() { return docListJson(); }
        public String getDocName() { return currentDoc; }

        /** 新建文档并切换（返回新文档名） */
        public String newDoc(String name) {
            FxApp.this.saveDraft(draft);            // 先保住当前这篇
            String n = uniqueDocName(name, null);
            try { writeAll(new File(docsDir(), n), ""); } catch (Exception ignored) {}
            currentDoc = n;
            draft = "";
            lastHistText = "";
            total0 = 0;                             // 今日字数基准跟文档走，否则切到短文会倒扣
            loadHistory();                          // 新文档：历史也是新的一份
            saveConfig();
            return n;
        }

        /** 切换文档：保存当前，载入目标，返回目标正文（失败返回 "__ERR__"） */
        public String switchDoc(String name) {
            if (name == null) return "__ERR__";
            File f = new File(docsDir(), name);
            if (!f.exists()) return "__ERR__";
            FxApp.this.histForceOnce = true;     // 切文档：当前文档一定要留一条
            FxApp.this.saveDraft(draft);
            currentDoc = name;
            try { draft = readAll(f); } catch (Exception e) { draft = ""; }
            total0 = countChars(draft);             // 今日字数按新文档重新计基准（否则切到
                                                    // 更短的文档会把今日字数倒扣成 0，
                                                    // 切到更长的会凭空虚增）
            loadHistory();                          // 切到该文档自己的寻回历史
            saveConfig();
            return draft;
        }

        public String renameDoc(String from, String to) {
            File src = new File(docsDir(), from);
            if (!src.exists()) return "__ERR__";
            String n = uniqueDocName(to, from);
            File dst = new File(docsDir(), n);
            if (!src.renameTo(dst)) return "__ERR__";
            try {                                  // 历史快照跟着文件改名
                File hs = histFile(from), hd = histFile(n);
                if (hs.exists() && !hd.exists()) hs.renameTo(hd);
            } catch (Exception ignored) {}
            File hs2 = htmlFileOf(from);           // 伴生 HTML（格式）跟着改名
            if (hs2.exists()) {
                File hd2 = htmlFileOf(n);
                if (hd2.exists()) hd2.delete();
                hs2.renameTo(hd2);
            }
            String link = docLinks.remove(from);   // 本地关联跟着改名
            if (link != null) docLinks.put(n, link);
            if (from.equals(currentDoc)) { currentDoc = n; saveConfig(); }
            return n;
        }

        public void deleteDoc(String name) {
            if (name == null) return;
            File f = new File(docsDir(), name);
            /* 先留住正文再删文件：以前没有快照的文件（刚建就删、或还没到 40 秒节流点）
               会被直接删掉且没有 .deleted 档 —— 回收站里根本找不到，等于永久丢失 */
            String text = "";
            try {
                if (name.equals(currentDoc)) text = draft;
                else if (f.exists()) text = readAll(f);
            } catch (Exception ignored) {}
            if (f.exists()) f.delete();
            File hf = htmlFileOf(name);            // 伴生 HTML 一并删
            if (hf.exists()) hf.delete();
            docLinks.remove(name);                 // 本地关联解除
            try {
                File hs = histFile(name);
                File del = new File(histDir(), name + ".deleted.hist");
                if (hs.exists()) hs.renameTo(del);
                else if (text != null && !text.isEmpty()) {
                    writeAll(del, "###TS### " + System.currentTimeMillis() + "\n"
                                  + text + "\n###END###\n");
                }
            } catch (Exception ignored) {}
            if (name.equals(currentDoc)) {
                // 删掉当前文档后自动落到第一篇（没有就新建一篇）
                File[] fs = docsDir().listFiles((d, n) -> n.toLowerCase().endsWith(".txt"));
                if (fs != null && fs.length > 0) {
                    currentDoc = fs[0].getName();
                    try { draft = readAll(fs[0]); } catch (Exception ignored) { draft = ""; }
                } else {
                    currentDoc = "未命名.txt";
                    draft = "";
                    try { writeAll(new File(docsDir(), currentDoc), ""); } catch (Exception ignored) {}
                }
                total0 = countChars(draft);          // 今日字数基准跟文档走
                loadHistory();
                saveConfig();
            }
        }

        /** 从磁盘导入文档到文档库：txt / md / docx / doc / text 等，统一转成纯文本。
         *  返回文档名（取消返回 ""）。docx/doc 用现成解析器抽取文字，Word 式体验不变。 */
        public String importDocFile() {
            FileChooser fc = new FileChooser();
            fc.setTitle("打开文档");
            fc.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("全部支持",
                            "*.txt", "*.md", "*.text", "*.docx", "*.doc"),
                    new FileChooser.ExtensionFilter("文本文档", "*.txt", "*.md", "*.text"),
                    new FileChooser.ExtensionFilter("Word 文档", "*.docx", "*.doc"),
                    new FileChooser.ExtensionFilter("所有文件", "*.*"));
            File f = fc.showOpenDialog(stage);
            if (f == null) return "";
            try {
                String n = f.getName().toLowerCase();
                String text;
                if (n.endsWith(".docx")) text = parseDocx(f);
                else if (n.endsWith(".doc")) text = parseDocBin(f);
                else text = parseTxtSmart(f);      // txt / md / text / 其他都按文本读
                if (text == null || text.isEmpty()) return "";
                FxApp.this.saveDraft(draft);
                // 文档库统一存 .txt（内容已是纯文本），名字保留原名主干
                String base = f.getName().replaceFirst("(?i)\\.(txt|md|text|docx|doc)$", "");
                String doc = uniqueDocName(base, null);
                writeAll(new File(docsDir(), doc), text);
                currentDoc = doc;
                draft = text;
                lastHistText = "";
                loadHistory();
                saveConfig();
                return doc;
            } catch (Exception e) {
                return "";
            }
        }

        /** 立即保存（Ctrl+S）：写盘并返回字数 */
        public String saveNow(String text, String html) {
            /* 手动保存（Ctrl+S）就是一个"留档点"：不受 40 秒节流限制 */
            FxApp.this.histForceOnce = true;
            FxApp.this.saveDraft(text, html);
            return String.valueOf(countChars(draft));
        }

        /** "存到桌面"：把当前文档关联到用户选的本地文件，此后每次修改实时写回。
         *  html=当前编辑器 HTML（选 .html 时存格式，其他扩展名存纯文字）。返回所选路径。 */
        public String linkDocTo(String html) {
            FileChooser fc = new FileChooser();
            fc.setTitle("把这篇文档存到本地");
            fc.setInitialFileName(currentDoc == null ? "未命名.txt" : currentDoc);
            File desk = new File(System.getProperty("user.home"), "Desktop");
            if (desk.isDirectory()) fc.setInitialDirectory(desk);
            fc.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("文本文档", "*.txt"),
                    new FileChooser.ExtensionFilter("网页（保留格式）", "*.html"),
                    new FileChooser.ExtensionFilter("所有文件", "*.*"));
            File f = fc.showSaveDialog(stage);
            if (f == null) return "";
            try {
                boolean asHtml = f.getName().toLowerCase().endsWith(".html");
                writeAll(f, asHtml && html != null ? html : (draft == null ? "" : draft));
                docLinks.put(currentDoc, f.getAbsolutePath());
                saveConfig();
                log("doc linked: " + currentDoc + " -> " + f);
                return f.getAbsolutePath();
            } catch (Exception e) {
                log("linkDocTo fail: " + e);
                return "";
            }
        }

        /** 解除当前文档的本地关联（文档仍在文档库，只是不再写回外部文件） */
        public void unlinkDoc() {
            docLinks.remove(currentDoc);
            saveConfig();
        }

        /* ---- 导航悬浮窗格：弹出为独立置顶小窗，可钉在屏幕最左/最右 ---- */

        private Stage navStage;
        private javafx.scene.control.ListView<String[]> navListView;
        private double navDragX, navDragY;

        /** 弹出/更新导航窗。payload：条目用 \u0001 分隔，字段 type/lv/color/text 用 \u0002 分隔
         *  （项目里没有 JSON 解析器，沿用导出用的 \u0002 约定） */
        public void popNav(String payload) {
            if (navStage == null) buildNavWindow();
            renderNavItems(payload);
            if (!navStage.isShowing()) {
                positionNavSide(true);
                navStage.show();
            }
            navStage.toFront();
        }

        /** 正文变化时同步刷新（面板没开就忽略） */
        public void updateNav(String payload) {
            if (navStage != null && navStage.isShowing()) renderNavItems(payload);
        }

        private void buildNavWindow() {
            navStage = new Stage(StageStyle.TRANSPARENT);
            navStage.setAlwaysOnTop(true);
            javafx.scene.layout.VBox root = new javafx.scene.layout.VBox();
            root.setStyle("-fx-background-color:rgba(251,251,253,0.97);-fx-background-radius:12;"
                    + "-fx-border-color:#d9dde9;-fx-border-width:1;-fx-border-radius:12;"
                    + "-fx-effect:dropshadow(gaussian,rgba(40,50,90,0.28),14,0.15,0,5);"
                    + "-fx-padding:8;");
            /* 标题条：拖动移动 + 贴边 + 关闭 */
            javafx.scene.control.Label ttl = new javafx.scene.control.Label("导航");
            ttl.setStyle("-fx-font-size:13px;-fx-font-weight:bold;-fx-text-fill:#333;");
            javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
            javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
            javafx.scene.control.Button bl = new javafx.scene.control.Button("‹");
            javafx.scene.control.Button br = new javafx.scene.control.Button("›");
            javafx.scene.control.Button bx = new javafx.scene.control.Button("✕");
            for (javafx.scene.control.Button b : new javafx.scene.control.Button[]{bl, br, bx}) {
                b.setStyle("-fx-background-color:transparent;-fx-cursor:hand;"
                        + "-fx-font-size:12px;-fx-text-fill:#666;-fx-padding:2 6;");
                b.setOnMouseEntered(e -> b.setStyle("-fx-background-color:#e8ebf5;-fx-cursor:hand;"
                        + "-fx-font-size:12px;-fx-text-fill:#333;-fx-background-radius:6;-fx-padding:2 6;"));
                b.setOnMouseExited(e -> b.setStyle("-fx-background-color:transparent;-fx-cursor:hand;"
                        + "-fx-font-size:12px;-fx-text-fill:#666;-fx-padding:2 6;"));
            }
            bl.setTooltip(new javafx.scene.control.Tooltip("钉到屏幕最左"));
            br.setTooltip(new javafx.scene.control.Tooltip("钉到屏幕最右"));
            bl.setOnAction(e -> positionNavSide(true));
            br.setOnAction(e -> positionNavSide(false));
            bx.setOnAction(e -> {
                navStage.hide();
                /* 用户主动点关闭：从 UI 线程 executeScript 安全（非 JS 回调重入） */
                try { engine.executeScript("if(window.app&&app.navPopClose)app.navPopClose()"); }
                catch (Throwable ignored) {}
            });
            javafx.scene.layout.HBox bar = new javafx.scene.layout.HBox(4, ttl, spacer, bl, br, bx);
            bar.setStyle("-fx-padding:2 2 6 4;-fx-alignment:center-left;");
            bar.setOnMousePressed(e -> { navDragX = navStage.getX() - e.getScreenX();
                                         navDragY = navStage.getY() - e.getScreenY(); });
            bar.setOnMouseDragged(e -> { navStage.setX(e.getScreenX() + navDragX);
                                         navStage.setY(e.getScreenY() + navDragY); });
            /* 条目列表 */
            navListView = new javafx.scene.control.ListView<>();
            navListView.setStyle("-fx-background-color:transparent;-fx-border-color:transparent;"
                    + "-fx-font-size:12.5px;");
            navListView.setCellFactory(lv -> new javafx.scene.control.ListCell<String[]>() {
                @Override protected void updateItem(String[] it, boolean empty) {
                    super.updateItem(it, empty);
                    if (empty || it == null) { setText(null); setGraphic(null); return; }
                    int lvInd;
                    try { lvInd = Integer.parseInt(it[1]); } catch (Exception e2) { lvInd = 0; }
                    String base = "-fx-padding:3 4 3 " + (4 + lvInd * 14)
                            + ";-fx-background-radius:6;-fx-cursor:hand;-fx-alignment:center-left;";
                    javafx.scene.layout.HBox box = new javafx.scene.layout.HBox(6);
                    box.setStyle(base);
                    if ("h".equals(it[0])) {
                        javafx.scene.control.Label l = new javafx.scene.control.Label(it[3]);
                        l.setStyle("-fx-text-fill:#222;-fx-font-weight:"
                                + (lvInd <= 0 ? "bold" : "normal") + ";");
                        box.getChildren().add(l);
                    } else {
                        javafx.scene.layout.Region dot = new javafx.scene.layout.Region();
                        dot.setStyle("-fx-min-width:9;-fx-min-height:9;-fx-max-width:9;-fx-max-height:9;"
                                + "-fx-background-radius:5;-fx-background-color:" + it[2] + ";");
                        javafx.scene.control.Label l = new javafx.scene.control.Label(it[3]);
                        l.setStyle("-fx-text-fill:#333;");
                        box.getChildren().addAll(dot, l);
                    }
                    setOnMouseEntered(e -> box.setStyle(base + "-fx-background-color:#e9edf8;"));
                    setOnMouseExited(e -> box.setStyle(base));
                    setGraphic(box);
                    setText(null);
                }
            });
            navListView.setOnMouseClicked(e -> {
                int i = navListView.getSelectionModel().getSelectedIndex();
                if (i < 0) return;
                try { engine.executeScript("if(window.app&&app.gotoNavIndex)app.gotoNavIndex(" + i + ")"); }
                catch (Throwable ignored) {}
            });
            root.getChildren().addAll(bar, navListView);
            javafx.scene.layout.VBox.setVgrow(navListView, javafx.scene.layout.Priority.ALWAYS);
            navStage.setScene(new Scene(root, 236, 520));
        }

        /** 贴边定位：left=true 钉屏幕最左，false 钉最右 */
        private void positionNavSide(boolean left) {
            javafx.geometry.Rectangle2D vb =
                    javafx.stage.Screen.getPrimary().getVisualBounds();
            double w = 236, h = Math.min(560, vb.getHeight() * 0.62);
            navStage.setWidth(w); navStage.setHeight(h);
            navStage.setX(left ? vb.getMinX() + 6 : vb.getMaxX() - w - 6);
            navStage.setY(vb.getMinY() + Math.max(60, vb.getHeight() * 0.16));
        }

        private void renderNavItems(String payload) {
            if (navListView == null) return;
            javafx.collections.ObservableList<String[]> items =
                    javafx.collections.FXCollections.observableArrayList();
            if (payload != null && !payload.isEmpty()) {
                for (String row : payload.split("\u0001")) {
                    String[] f = row.split("\u0002", -1);
                    if (f.length < 4) continue;
                    String lv = f[1];
                    items.add(new String[]{f[0], Integer.toString(lv.matches("\\d+") ? Integer.parseInt(lv) - 1 : 0),
                                           f[2], f[3]});
                }
            }
            navListView.setItems(items);
            if (items.isEmpty()) {
                javafx.scene.control.Label empty = new javafx.scene.control.Label("还没有标题（H1/H2/H3）或标签");
                empty.setStyle("-fx-text-fill:#999;-fx-font-size:11.5px;");
                navListView.setPlaceholder(empty);
            }
        }

        public void setAutoSave(boolean v) { autoSave = v; saveConfig(); }
        public void setWordsAlways(boolean v) { alwaysWords = v; saveConfig(); }

        /** 词库 JSON：[{"id":0,"code":"hzy","content":"..","cat":"人物"}] */
        public String getWords() {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < words.size(); i++) {
                if (i > 0) sb.append(',');
                String cat = words.get(i).length > 2 ? words.get(i)[2] : "";
                sb.append("{\"id\":").append(i)
                  .append(",\"code\":\"").append(esc(words.get(i)[0]))
                  .append("\",\"content\":\"").append(esc(words.get(i)[1]))
                  .append("\",\"cat\":\"").append(esc(cat)).append("\"}");
            }
            return sb.append(']').toString();
        }

        public void addWord(String code, String content, String cat) {
            words.add(new String[]{code == null ? "" : code.trim(),
                    content == null ? "" : content,
                    cat == null ? "" : cat.trim()});
            saveWords();
        }

        public void deleteWord(int id) {
            if (id >= 0 && id < words.size()) { words.remove(id); saveWords(); }
        }

        public void updateWord(int id, String code, String content, String cat) {
            if (id >= 0 && id < words.size()) {
                words.set(id, new String[]{code == null ? "" : code.trim(),
                        content == null ? "" : content,
                        cat == null ? "" : cat.trim()});
                saveWords();
            }
        }

        /* ---- 配置 ---- */
        public String getConfig() {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < bossCodes.length; i++) {
                if (i > 0) b.append('+');
                b.append(Hooks.codeName(bossCodes[i]));
            }
            return "{\"boss\":\"" + b + "\",\"bossEnabled\":" + bossEnabled
                    + ",\"gesture\":\"" + gesture + "\",\"gestureEnabled\":" + gestureEnabled
                    + ",\"bubbleMaxChars\":" + bubbleMaxChars
                    + ",\"bubbleCharsPerLine\":" + bubbleCharsPerLine
                    + ",\"accent\":\"" + accent + "\",\"fontSize\":" + fontSize
                    + ",\"bold\":" + bold
                    + ",\"wallpaperOpacity\":" + wallpaperOpacity
                    + ",\"winOpacity\":" + winOpacity
                    + ",\"fontName\":\"" + esc(fontName) + "\""
                    + ",\"fontUi\":\"" + esc(fontUi) + "\""
                    + ",\"h1Size\":" + h1Size + ",\"h2Size\":" + h2Size + ",\"h3Size\":" + h3Size
                    + ",\"h1Bold\":" + h1Bold + ",\"h2Bold\":" + h2Bold + ",\"h3Bold\":" + h3Bold
                    + ",\"pFont\":\"" + esc(pFont) + "\""
                    + ",\"h1Font\":\"" + esc(h1Font) + "\""
                    + ",\"h2Font\":\"" + esc(h2Font) + "\""
                    + ",\"h3Font\":\"" + esc(h3Font) + "\""
                    + ",\"fontWest\":\"" + esc(fontWest) + "\""
                    + ",\"pItalic\":" + pItalic + ",\"h1Italic\":" + h1Italic
                    + ",\"h2Italic\":" + h2Italic + ",\"h3Italic\":" + h3Italic
                    + ",\"tagDefs\":" + (tagDefsJson.isEmpty() ? "[]" : tagDefsJson)
                    + ",\"tocSize\":" + tocSize
                    + ",\"bubbleCloud\":" + bubbleCloud
                    + ",\"bubbleRhythm\":" + bubbleRhythm
                    + ",\"musicFollow\":" + musicFollow
                    + ",\"bubbleCloudKind\":\"" + bubbleCloudKind + "\""
                    + ",\"cloudOpacity\":" + cloudOpacity
                    + ",\"cloudMargin\":" + cloudMargin
                    + ",\"wallpaperFit\":\"" + wallpaperFit + "\""
                    + ",\"bubbleLines\":" + bubbleLines
                    + ",\"bubbleW\":" + bubbleW
                    + ",\"bubbleH\":" + bubbleH
                    + ",\"bubbleManualSize\":" + bubbleManualSize
                    + ",\"bubbleCustomChars\":\"" + esc(bubbleCustomChars) + "\""
                    + ",\"themeMode\":\"" + themeMode + "\",\"accent2\":\"" + accent2 + "\""
                    + ",\"closeAction\":\"" + closeAction + "\""
                    + ",\"skin\":\"" + skin + "\""
                    + ",\"bubbleDblRestore\":" + bubbleDblRestore
                    + ",\"bubbleAutoSize\":" + bubbleAutoSize
                    + ",\"indentFirst\":" + indentFirst
                    + ",\"bubbleIntrude\":" + bubbleIntrude
                    + ",\"intrudeOpacity\":" + intrudeOpacity
                    + ",\"intrudeSize\":" + intrudeSize
                    + ",\"autoSave\":" + autoSave
                    + ",\"alwaysWords\":" + alwaysWords
                    + ",\"wordPrefix\":\"" + esc(wordPrefix) + "\""
                    + ",\"docName\":\"" + esc(currentDoc) + "\""
                    + ",\"keys\":" + keysJson() + "}";
        }

        /** 快捷键表 JSON：{"undo":"Ctrl+Z",...} */
        private String keysJson() {
            StringBuilder sb = new StringBuilder("{");
            for (java.util.Map.Entry<String, String> e : keyMap.entrySet()) {
                if (sb.length() > 1) sb.append(',');
                sb.append('"').append(e.getKey()).append("\":\"")
                  .append(esc(e.getValue())).append('"');
            }
            return sb.append('}').toString();
        }

        /** 保存老板键组合（可读名），非法返回 "invalid" */
        public String setBoss(String combo) {
            int[] codes = Hooks.parseCombo(combo);
            if (codes == null) return "invalid";
            bossCodes = codes;
            bossEnabled = true;
            syncHooksConfig();
            saveConfig();
            return "ok";
        }

        public void setBossEnabled(boolean v) { bossEnabled = v; syncHooksConfig(); saveConfig(); }

        public String setGesture(String g, boolean enabled) {
            g = g == null ? "" : g.trim().toUpperCase();
            if (!g.matches("[LR]{3,8}")) return "invalid";
            gesture = g;
            gestureEnabled = enabled;
            syncHooksConfig();
            saveConfig();
            return "ok";
        }

        public void setBubbleConfig(int maxChars, int perLine) {
            bubbleMaxChars = clamp(maxChars, 40, 5000);
            bubbleCharsPerLine = clamp(perLine, 10, 40);
            saveConfig();
        }

        /* ---- 开始捕获新组合：结果通过 app.onCapture(name) 异步回调 ---- */
        public void beginCapture() {
            Hooks.beginCapture(combo -> Platform.runLater(() -> {
                if (combo == null) notifyJs("if(window.app&&app.onCapture)app.onCapture(null)");
                else notifyJs("if(window.app&&app.onCapture)app.onCapture('" + combo + "')");
            }));
        }

        /* ---- 外观 ---- */
        public void setAppearance(String acc, int fs, boolean bd) {
            if (acc != null && acc.matches("#[0-9A-Fa-f]{6}")) accent = acc;
            fontSize = clamp(fs, 12, 30);
            bold = bd;
            saveConfig();
        }

        /* ---- 主题：单色 / 双拼渐变 ---- */
        public void setTheme(String mode, String a1c, String a2c) {
            if ("solid".equals(mode) || "gradient".equals(mode)) themeMode = mode;
            if (a1c != null && a1c.matches("#[0-9A-Fa-f]{6}")) accent = a1c;
            if (a2c != null && a2c.matches("#[0-9A-Fa-f]{6}")) accent2 = a2c;
            saveConfig();
        }

        /* ---- 皮肤：浅色 / 深色（黑色主题） ---- */
        public void setSkin(String s) {
            if (s != null && s.matches("light|dark")) {
                skin = s;
                saveConfig();
            }
        }

        /* ---- 气泡开关：双击还原 / 大小自适应 ---- */
        public void setBubbleFlags(boolean dblRestore, boolean autoSize) {
            bubbleDblRestore = dblRestore;
            bubbleAutoSize = autoSize;
            saveConfig();
        }

        /* ---- 全文首行缩进开关（段落 CSS text-indent，不算"格式"） ---- */
        public void setIndentCfg(boolean on) {
            indentFirst = on;
            saveConfig();
        }

        /* ---- 环绕元素侵入文本框：开关 + 透明度 + 大小 ---- */
        public void setBubbleIntrude(boolean on, int opacity, int size) {
            bubbleIntrude = on;
            intrudeOpacity = clamp(opacity, 5, 100);
            intrudeSize = clamp(size, 20, 200);
            saveConfig();
        }

        /* ---- 界面快捷键（存 key.<动作>，重启保持） ---- */
        public void setKey(String action, String combo) {
            if (action == null || !keyMap.containsKey(action)) return;
            if (combo == null || combo.trim().isEmpty()) return;
            keyMap.put(action, combo.trim());
            saveConfig();
        }

        public void resetKeys() {
            for (String[] kv : DEFAULT_KEYS) keyMap.put(kv[0], kv[1]);
            saveConfig();
        }

        /* ---- 关闭按钮行为 ---- */
        public void setCloseAction(String a) {
            if (a != null && a.matches("ask|tray|exit")) closeAction = a;
            saveConfig();
        }

        /* ---- 外观扩展：标题图标 / 背景图 / 字体 / 透明度 ---- */

        /** 标题栏小图标（base64，与 exe/任务栏图标同源） */
        public String getIcon() { return iconDataUrl(); }

        /** 弹文件框选背景图，成功返回 data URL，取消返回 "" */
        public String pickWallpaper() {
            FileChooser fc = new FileChooser();
            fc.setTitle("选择背景图片");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                    "图片", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.webp"));
            File f = fc.showOpenDialog(stage);
            if (f == null) return "";
            try {
                File dst = new File(dataDir(), "wallpaper.img");
                java.nio.file.Files.copy(f.toPath(), dst.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                wallpaperPath = "wallpaper.img";
                saveConfig();
                return fileToDataUrl(dst);
            } catch (Exception e) { return ""; }
        }

        public void clearWallpaper() {
            wallpaperPath = "";
            saveConfig();
            new File(dataDir(), "wallpaper.img").delete();
        }

        public String getWallpaper() {
            if (wallpaperPath == null || wallpaperPath.isEmpty()) return "";
            File f = new File(dataDir(), wallpaperPath);
            return f.exists() ? fileToDataUrl(f) : "";
        }

        /** 选择并导入 .ttf/.otf 字体，返回字体族名；失败/取消返回 "" */
        public String pickFont() {
            FileChooser fc = new FileChooser();
            fc.setTitle("导入字体文件");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("字体", "*.ttf", "*.otf"));
            File f = fc.showOpenDialog(stage);
            if (f == null) return "";
            return importFontFrom(f);
        }

        /**
         * 真正把字体文件落进 data/fonts/ 字体库并注册到 JVM。
         *
         * ★ 必须保留原扩展名：fileToDataUrl 按扩展名判 MIME，以前存成
         *   data/customfont.bin → MIME 落到默认值 image/jpeg → WebView 拿到
         *   "data:image/jpeg;base64,<ttf 字节>" 直接拒绝当字体用，
         *   表现就是"导入字体没反应"。
         * ★ v1.8 起支持多字体：每个字体单独成文件，listImportedFonts 可列出。
         */
        public String importFontFrom(File f) {
            if (f == null || !f.exists()) return "";
            try {
                String low = f.getName().toLowerCase();
                String ext = low.endsWith(".otf") ? ".otf" : ".ttf";
                String base = safeFontFileName(
                        f.getName().replaceAll("\\.(ttf|otf|bin)$", ""));
                File dst = new File(fontsDir(), base + "_" + System.currentTimeMillis() + ext);
                java.nio.file.Files.copy(f.toPath(), dst.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                fontPath = "fonts/" + dst.getName();
                javafx.scene.text.Font loaded = javafx.scene.text.Font.loadFont(f.toURI().toString(), 16);
                fontName = loaded == null ? "" : loaded.getFamily();
                saveConfig();
                log("font import ok: " + fontPath + " family=" + fontName);
                return fontName;
            } catch (Exception e) {
                log("font import fail: " + e);
                return "";
            }
        }

        public void clearFont() {
            if (fontPath.startsWith("fonts/")) new File(dataDir(), fontPath).delete();
            fontPath = "";
            fontName = "";
            saveConfig();
            /* 顺手清掉旧版单文件残留 */
            new File(dataDir(), "customfont.ttf").delete();
            new File(dataDir(), "customfont.otf").delete();
            new File(dataDir(), "customfont.bin").delete();
        }

        /** 本机"真的装了"的字体族名（前端字体下拉只列这些）。
         *  以前前端写死了一份字体名单，里面隶书/幼圆之类很多机器根本没有，
         *  选了等于没反应。这里问 JDK 要系统字体清单，但只留**常用字体**：
         *  本机一装两三百个字体，花里胡哨的装饰字体/符号字体列出来根本没法用。
         *  用户想要别的字体，丢进「文档\闪写字体库」即可（走导入字体）。 */
        private String sysFontsCache = null;
        public String listSystemFonts() {
            if (sysFontsCache != null) return sysFontsCache;
            java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
            try {
                String[] names = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getAvailableFontFamilyNames(java.util.Locale.SIMPLIFIED_CHINESE);
                for (String n : names) {
                    if (n == null) continue;
                    n = n.trim();
                    if (n.isEmpty() || n.charAt(0) == '@') continue;   // @ 开头=竖排变体，别列
                    set.add(n);
                }
            } catch (Throwable ignored) {}
            /* 常用字体白名单（本机装了才列，顺序就是下拉里的顺序） */
            String[] common = {
                    "微软雅黑", "Microsoft YaHei", "微软雅黑 Light",
                    "宋体", "SimSun", "新宋体", "NSimSun", "黑体", "SimHei",
                    "楷体", "KaiTi", "仿宋", "FangSong", "等线", "DengXian",
                    "思源黑体", "Source Han Sans SC", "思源宋体", "Source Han Serif SC",
                    "霞鹜文楷", "LXGW WenKai", "华文中宋", "STZhongsong",
                    "华文细黑", "STXihei", "华文楷体", "STKaiti",
                    "Segoe UI", "Arial", "Times New Roman", "Georgia", "Calibri", "Consolas"};
            java.util.List<String> out = new java.util.ArrayList<>();
            for (String p : common) if (set.remove(p)) out.add(p);
            /* 一个常用字体都没匹配到（极简/英文系统）：退回按字母序取前 20 个可用字体 */
            if (out.isEmpty()) {
                java.util.List<String> rest = new java.util.ArrayList<>(set);
                rest.sort(String.CASE_INSENSITIVE_ORDER);
                out.addAll(rest.subList(0, Math.min(20, rest.size())));
            }
            StringBuilder b = new StringBuilder("[");
            for (int i = 0; i < out.size(); i++) {
                if (i > 0) b.append(',');
                b.append('"').append(esc(out.get(i))).append('"');
            }
            sysFontsCache = b.append(']').toString();
            return sysFontsCache;
        }

        /** 字体库列表：[{file:"xxx.ttf", name:"楷体"}]（族名现场从字体文件读） */        public String listImportedFonts() {
            StringBuilder b = new StringBuilder("[");
            File[] fs = fontsDir().listFiles();
            boolean first = true;
            if (fs != null) {
                java.util.Arrays.sort(fs, (a, c) -> Long.compare(c.lastModified(), a.lastModified()));
                for (File f : fs) {
                    String n = f.getName().toLowerCase();
                    if (!n.endsWith(".ttf") && !n.endsWith(".otf")) continue;
                    String fam = fontFamilyCache(f);
                    if (first) first = false; else b.append(',');
                    b.append("{\"file\":\"").append(esc(f.getName()))
                     .append("\",\"name\":\"").append(esc(fam)).append("\"}");
                }
            }
            return b.append(']').toString();
        }

        /** 族名缓存（Font.loadFont 有开销，别每刷新一遍全读一遍） */
        private final java.util.Map<String, String> fontFamilyCache = new java.util.HashMap<>();
        private String fontFamilyCache(File f) {
            String key = f.getName();
            String hit = fontFamilyCache.get(key);
            if (hit != null) return hit;
            String fam = "";
            try {
                javafx.scene.text.Font loaded =
                        javafx.scene.text.Font.loadFont(f.toURI().toString(), 16);
                if (loaded != null) fam = loaded.getFamily();
            } catch (Exception ignored) {}
            if (fam.isEmpty()) fam = key.replaceAll("\\.(ttf|otf)$", "");
            fontFamilyCache.put(key, fam);
            return fam;
        }

        /** 取字体库里某个字体的 dataUrl（文件名必须干净，防穿越） */
        public String getFontDataByFile(String file) {
            if (file == null || file.contains("/") || file.contains("\\") || file.contains(".."))
                return "";
            File f = new File(fontsDir(), file);
            return f.exists() ? fileToDataUrl(f) : "";
        }

        /** 删除字体库里某个字体；若是当前激活字体连带清掉 */
        public String deleteFont(String file) {
            if (file == null || file.contains("/") || file.contains("\\") || file.contains(".."))
                return "ERR";
            File f = new File(fontsDir(), file);
            if (f.exists() && !f.delete()) return "ERR";
            if (fontPath.equals("fonts/" + file)) { fontPath = ""; fontName = ""; saveConfig(); }
            return "ok";
        }

        public String getFontData() {
            if (fontPath == null || fontPath.isEmpty()) return "";
            File f = new File(dataDir(), fontPath);
            return f.exists() ? fileToDataUrl(f) : "";
        }

        public String getFontName() { return fontName; }

        /** 工具栏选的系统字体族名（空=默认字体栈） */
        public void setFontUi(String name) {
            fontUi = name == null ? "" : name.trim();
            saveConfig();
        }

        /** 标题分级格式：h1s,h1b,h2s,h2b,h3s,h3b（顺序传参） */
        public void setHeadingCfg(String h1s, String h1b, String h2s, String h2b,
                                  String h3s, String h3b) {
            h1Size = clamp(parseIntS(h1s), 12, 60); h1Bold = "1".equals(h1b) || "true".equals(h1b);
            h2Size = clamp(parseIntS(h2s), 12, 60); h2Bold = "1".equals(h2b) || "true".equals(h2b);
            h3Size = clamp(parseIntS(h3s), 12, 60); h3Bold = "1".equals(h3b) || "true".equals(h3b);
            saveConfig();
        }

        /** 样式字体：正文/H1/H2/H3 各自的字体族（空字符串=跟随默认字体栈） */
        public void setStyleFonts(String p, String f1, String f2, String f3) {
            pFont = sanitizeFontName(p);
            h1Font = sanitizeFontName(f1);
            h2Font = sanitizeFontName(f2);
            h3Font = sanitizeFontName(f3);
            saveConfig();
        }

        /** 西文字体：英文/数字优先用它，中文落到中文字体（空=不区分） */
        public void setWestFont(String name) {
            fontWest = sanitizeFontName(name);
            saveConfig();
        }

        /** 样式斜体：p,h1,h2,h3（顺序传参） */
        public void setStyleItalics(String p, String i1, String i2, String i3) {
            pItalic = "1".equals(p) || "true".equals(p);
            h1Italic = "1".equals(i1) || "true".equals(i1);
            h2Italic = "1".equals(i2) || "true".equals(i2);
            h3Italic = "1".equals(i3) || "true".equals(i3);
            saveConfig();
        }

        /** 标签定义（[{name,color}] JSON 全量替换） */
        public void setTagDefs(String json) {
            String j = json == null ? "" : json.trim();
            /* 只收合法的 JSON 数组，防止把乱七八糟的东西写进配置 */
            if (j.startsWith("[") && j.endsWith("]")) tagDefsJson = j;
            saveConfig();
        }

        /** 目录字号 */
        public void setTocSize(int n) {
            tocSize = clamp(n, 10, 40);
            saveConfig();
        }

        /* ---- 环绕元素：自定义小图片（data/decorimg.<ext>，保留扩展名好判 MIME） ---- */
        public String pickDecorImage() {
            FileChooser fc = new FileChooser();
            fc.setTitle("选择环绕元素图片（建议 100×100 以内的小图）");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                    "图片", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp", "*.bmp"));
            File f = fc.showOpenDialog(stage);
            if (f == null) return "";
            try {
                String nm = f.getName().toLowerCase();
                String ext = ".jpg";
                int dot = nm.lastIndexOf('.');
                if (dot >= 0) ext = nm.substring(dot);
                /* 只留安全的图片扩展名 */
                if (!ext.matches("\\.(png|jpe?g|gif|webp|bmp)")) ext = ".jpg";
                File dst = new File(dataDir(), "decorimg" + ext);
                /* 换格式后旧文件清掉，免得残留 */
                for (String e2 : new String[]{".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp"}) {
                    if (!e2.equals(ext)) new File(dataDir(), "decorimg" + e2).delete();
                }
                java.nio.file.Files.copy(f.toPath(), dst.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                decorImgPath = dst.getName();
                saveConfig();
                return fileToDataUrl(dst);
            } catch (Exception e) { return ""; }
        }

        public String getDecorImage() {
            if (decorImgPath == null || decorImgPath.isEmpty()) return "";
            File f = new File(dataDir(), decorImgPath);
            return f.exists() ? fileToDataUrl(f) : "";
        }

        public void clearDecorImage() {
            decorImgPath = "";
            saveConfig();
            new File(dataDir(), "decorimg.bin").delete();
        }

        /** 背景图浓度（%） */
        public void setWallpaperOpacity(int v) {
            wallpaperOpacity = clamp(v, 5, 100);
            saveConfig();
        }

        /** 窗口整体不透明度（30~100%） */
        public void setWinOpacityPercent(int v) {
            winOpacity = clamp(v, 30, 100);
            try { stage.setOpacity(winOpacity / 100.0); } catch (Exception ignored) {}
            saveConfig();
        }

        /** 气泡环绕配置：开关、元素种类、不透明度 */
        public void setBubbleCloudConfig(boolean cloud, boolean rhythm, String kind, int opacity) {
            bubbleCloud = cloud;
            bubbleRhythm = rhythm;
            if (kind != null && kind.matches("cloud|star|petal|note")) bubbleCloudKind = kind;
            cloudOpacity = clamp(opacity, 10, 100);
            saveConfig();
        }

        /** 选择背景音乐，返回 data URL；取消返回 "" */
        public String pickMusic() {
            FileChooser fc = new FileChooser();
            fc.setTitle("选择背景音乐");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                    "音频", "*.mp3", "*.wav", "*.ogg", "*.flac", "*.m4a"));
            File f = fc.showOpenDialog(stage);
            if (f == null) return "";
            try {
                File dst = new File(dataDir(), "bgm.bin");
                java.nio.file.Files.copy(f.toPath(), dst.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                bgmPath = "bgm.bin";
                saveConfig();
                return fileToDataUrl(dst);
            } catch (Exception e) { return ""; }
        }

        public void clearMusic() {
            bgmPath = "";
            saveConfig();
            new File(dataDir(), "bgm.bin").delete();
        }

        public String getBgm() {
            if (bgmPath == null || bgmPath.isEmpty()) return "";
            File f = new File(dataDir(), bgmPath);
            return f.exists() ? fileToDataUrl(f) : "";
        }

        /* ---- 窗口控制 ---- */
        public void moveBy(double dx, double dy) {
            stage.setX(stage.getX() + dx);
            stage.setY(stage.getY() + dy);
        }

        public void setSize(double w, double h) {
            stage.setWidth(w);
            stage.setHeight(h);
        }

        public void minimize() { stage.setIconified(true); }

        public void toggleMaximize() { stage.setMaximized(!stage.isMaximized()); }

        public void setOpacity(double v) {
            try { stage.setOpacity(v); } catch (Exception ignored) {}
        }

        public void togglePin() { stage.setAlwaysOnTop(!stage.isAlwaysOnTop()); }

        public void closeApp() { exitApp(); }

        /* ---- 气泡悬浮模式 ---- */
        public void enterBubble() {
            if (!bubble) {
                preW = stage.getWidth();
                preH = stage.getHeight();
                if (preW < 200 || preH < 200) { preW = 880; preH = 600; } // 布局未就绪兜底
                bubble = true;
                stage.setAlwaysOnTop(true);
            }
            /* 小胶囊态要能缩到一两行高 —— 完整模式的 300x120 最小尺寸必须先放开 */
            stage.setMinWidth(60);
            stage.setMinHeight(40);
            /* 兜底 1：最小化/图标化状态下进气泡，窗口仍是最小化的 —— 看起来就像消失了 */
            try {
                if (stage.isIconified()) stage.setIconified(false);
                if (!stage.isShowing()) stage.show();
                stage.setOpacity(Math.min(1.0, winOpacity / 100.0));
            } catch (Exception ignored) {}
            /* 兜底 2：透明窗口"有画面但屏幕上看不见"（任务栏缩略图正常）
               —— 逼窗口管理器重新映射一次表面，并把窗口拉回屏幕内 */
            remapStage();
            fixBubbleInvisible("enter");
            javafx.animation.PauseTransition later =
                    new javafx.animation.PauseTransition(javafx.util.Duration.millis(420));
            later.setOnFinished(ev -> { if (!composing) { remapStage(); fixBubbleInvisible("enter+420"); } });
            later.play();
            startBubbleWatch();
        }

        public void exitBubble() {
            bubble = false;
            stopBubbleWatch();
            stage.setAlwaysOnTop(false);
            stage.setMinWidth(300);   // 还原完整模式的最小尺寸
            stage.setMinHeight(120);
            if (preW >= 200 && preH >= 200) {
                stage.setWidth(preW);
                stage.setHeight(preH);
            }
            try {
                if (stage.isIconified()) stage.setIconified(false);
                if (!stage.isShowing()) stage.show();
                stage.toFront();
            } catch (Exception ignored) {}
        }

        /** 微调 1px 逼窗口管理器重新映射表面（透明 Stage 的老毛病，见 showFromTray 注释） */
        private void remapStage() {
            try {
                double w = stage.getWidth(), h = stage.getHeight();
                stage.setWidth(w + 1); stage.setWidth(w);
                stage.setHeight(h + 1); stage.setHeight(h);
            } catch (Exception ignored) {}
        }

        /** 窗口矩形是否和任意一块屏幕相交（false = 整个窗口在屏幕外，人眼就是"不见了"） */
        private boolean onAnyScreen() {
            double x = stage.getX(), y = stage.getY();
            double w = Math.max(1, stage.getWidth()), h = Math.max(1, stage.getHeight());
            for (javafx.stage.Screen s : javafx.stage.Screen.getScreens()) {
                if (s.getVisualBounds().intersects(x, y, w, h)) return true;
            }
            return false;
        }

        /** 只在"真的看不见"时动手修复：图标化 / 未显示 / 整个跑出屏幕 / 不透明度为 0 */
        private boolean fixBubbleInvisible(String why) {
            if (!bubble || hiddenToTray) return false;
            boolean fixed = false;
            try {
                if (stage.isIconified()) { stage.setIconified(false); fixed = true; }
                if (!stage.isShowing()) { stage.show(); fixed = true; }
                if (stage.getOpacity() < 0.05) {
                    stage.setOpacity(Math.min(1.0, winOpacity / 100.0)); fixed = true;
                }
                if (!onAnyScreen()) {
                    javafx.geometry.Rectangle2D vb = javafx.stage.Screen.getPrimary().getVisualBounds();
                    stage.setX(vb.getMinX() + Math.max(0, (vb.getWidth() - stage.getWidth()) / 2));
                    stage.setY(vb.getMinY() + Math.max(0, (vb.getHeight() - stage.getHeight()) / 2));
                    fixed = true;
                }
                if (fixed) {
                    stage.toFront();
                    remapStage();
                    log("bubble 可见性修复(" + why + ") iconified=" + stage.isIconified()
                            + " showing=" + stage.isShowing()
                            + " pos=" + (int) stage.getX() + "," + (int) stage.getY()
                            + " size=" + (int) stage.getWidth() + "x" + (int) stage.getHeight());
                }
            } catch (Exception ignored) {}
            return fixed;
        }

        /** 气泡模式看门狗：1.6 秒巡检一次，只在异常时动手（正常时零开销、不抢焦点） */
        private void startBubbleWatch() {
            if (bubbleWatch == null) {
                bubbleWatch = new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                        javafx.util.Duration.seconds(1.6), ev -> fixBubbleInvisible("watch")));
                bubbleWatch.setCycleCount(javafx.animation.Animation.INDEFINITE);
            }
            if (bubbleWatch.getStatus() != javafx.animation.Animation.Status.RUNNING)
                bubbleWatch.play();
        }

        private void stopBubbleWatch() {
            try { if (bubbleWatch != null) bubbleWatch.stop(); } catch (Exception ignored) {}
        }

        /** 隐藏到托盘时停掉看门狗（外部调用） */
        public void stopWatchOnHide() { stopBubbleWatch(); }

        /** JS 计算好气泡尺寸后调用（空文字=小气泡，随文字变宽，满行后封顶） */
        public void resizeBubble(double w, double h) {
            if (!bubble) return;
            /* 窗口没显示时先补显示，不然"算完尺寸就再也没人管窗口了" */
            if (!stage.isShowing() || stage.isIconified()) fixBubbleInvisible("resize");
            /* 输入法组字中绝不改窗口尺寸：Windows 上这么干会打断 IME 会话，
               表现就是"打着打着字删不掉了" */
            if (composing) return;
            if (!stage.isShowing()) return;
            w = Math.max(48, w);
            h = Math.max(34, h);
            /* ★ 一步到位 + 只在真变了才动窗口：程序化改窗口尺寸时 WebView 会
               拿旧帧糊屏（多帧动画试过，画面整帧错乱）——"一闪一闪"的最大
               来源是频繁重设窗口，这里合并掉小变化就是最有效的稳帧手段。 */
            double cw = stage.getWidth(), ch = stage.getHeight();
            if (Math.abs(cw - w) < 0.5 && Math.abs(ch - h) < 0.5) return;
            stage.setWidth(w);
            stage.setHeight(h);
            /* 兜底 2：气泡拖到屏幕边缘后再缩放，可能整个窗口跑出可视区。
               保证至少 60px 留在屏幕内。 */
            try {
                double x = stage.getX(), y = stage.getY();
                double nx = x, ny = y;
                javafx.geometry.Rectangle2D vb = javafx.stage.Screen.getPrimary().getVisualBounds();
                for (javafx.stage.Screen s : javafx.stage.Screen.getScreens()) {
                    if (s.getVisualBounds().contains(x + w / 2, y + h / 2)) { vb = s.getVisualBounds(); break; }
                }
                if (x + w < vb.getMinX() + 60) nx = vb.getMinX() + 60 - w;
                if (x > vb.getMaxX() - 60) nx = vb.getMaxX() - 60;
                if (y + h < vb.getMinY() + 60) ny = vb.getMinY() + 60 - h;
                if (y > vb.getMaxY() - 60) ny = vb.getMaxY() - 60;
                if (nx != x || ny != y) { stage.setX(nx); stage.setY(ny); }
            } catch (Exception ignored) {}
        }

        public boolean inBubble() { return bubble; }

        /* ---- 开机自启动（注册表 HKCU…Run，指向 闪写.exe） ---- */
        public boolean isAutoStart() {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{
                        "reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run", "/v", "SnapWrite"});
                byte[] out = new byte[4096];
                int n = p.getInputStream().read(out);
                return n > 0;
            } catch (Exception e) { return false; }
        }

        public void setAutoStart(boolean v) {
            try {
                File exe = new File("闪写.exe").getAbsoluteFile();
                if (v) {
                    Runtime.getRuntime().exec(new String[]{
                            "reg", "add", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                            "/v", "SnapWrite", "/t", "REG_SZ", "/d", "\"" + exe.getPath() + "\"", "/f"});
                } else {
                    Runtime.getRuntime().exec(new String[]{
                            "reg", "delete", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                            "/v", "SnapWrite", "/f"});
                }
            } catch (Exception ignored) {}
        }

        /* ---- 导出（多格式） ----
           payload 由 JS 侧把正文拆成「段落 → 带样式的片段」后传进来：
           在 JS 回调里再 executeScript 是重入，WebKit 下可能直接抛异常或静默失败。 */
        public String exportAs(String fmt, String title, String payload, String withTitle) {
            return exportCommon(fmt, title, payload, withTitle, docBaseName());
        }

        /** 从文件面板导出任意文档（读磁盘上的正文，纯文本排版） */
        public String exportDocAs(String fmt, String docName, String withTitle) {
            if (docName == null || docName.trim().isEmpty()) return "err:没有指定文档";
            File f = new File(docsDir(), docName);
            if (!f.isFile()) return "err:找不到文档 " + docName;
            String text;
            try { text = readAll(f); } catch (Exception e) { return "err:读取失败"; }
            StringBuilder sb = new StringBuilder();
            for (String line : text.split("\n", -1)) {
                if (sb.length() > 0) sb.append('\u0002');
                if (!line.isEmpty()) sb.append("0|").append(line);
            }
            String base = docName;
            int p = base.lastIndexOf('.');
            if (p > 0) base = base.substring(0, p);
            return exportCommon(fmt, base, sb.toString(), withTitle, base);
        }

        private String exportCommon(String fmt, String title, String payload,
                                    String withTitle, String baseName) {
            String m = fmt == null ? "txt" : fmt.toLowerCase();
            String ext, label;
            switch (m) {
                case "md":   ext = "md";   label = "Markdown"; break;
                case "html": ext = "html"; label = "网页 HTML"; break;
                case "docx": ext = "docx"; label = "Word 文档"; break;
                case "rtf":  ext = "rtf";  label = "RTF"; break;
                case "pdf":  ext = "pdf";  label = "PDF"; break;
                default:     m = "txt"; ext = "txt"; label = "TXT"; break;
            }
            FileChooser fc = new FileChooser();
            fc.setTitle("导出 " + label);
            File dir = (lastExportDir != null && lastExportDir.isDirectory())
                    ? lastExportDir : desktopDir();
            if (dir != null && dir.isDirectory()) fc.setInitialDirectory(dir);
            fc.setInitialFileName(baseName + "." + ext);
            if (!"txt".equals(m))
                fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(label + " (*." + ext + ")", "*." + ext));
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("所有文件", "*.*"));
            File f = fc.showSaveDialog(stage);
            if (f == null) return "cancel";
            if (!f.getName().toLowerCase().endsWith("." + ext))
                f = new File(f.getAbsolutePath() + "." + ext);
            /* 防呆：别把导出结果写回正在编辑的文档，也别写进文档库目录 */
            try {
                File parent = f.getParentFile();
                if (parent != null && parent.getCanonicalPath().equals(docsDir().getCanonicalPath()))
                    f = new File(parent, "导出_" + f.getName());
            } catch (Exception ignored) {}
            try {
                Exporter.export(f, m, payload, title, !"false".equalsIgnoreCase(String.valueOf(withTitle)));
                lastExportDir = f.getParentFile();
                log("export " + m + " -> " + f.getAbsolutePath() + " (" + f.length() + "B)");
                return "ok:" + f.getName();
            } catch (Throwable ex) {
                log("export FAIL " + ex);
                ex.printStackTrace();
                String msg = String.valueOf(ex.getMessage());
                if (ex instanceof NoClassDefFoundError || ex instanceof ClassNotFoundException
                        || msg.contains("NoClassDefFound"))
                    msg = "缺少 PDF 组件（app/lib/pdfbox 等），请重新下载完整安装包";
                return "err:" + msg.replace("\n", " ");
            }
        }

        /** 文档名去掉扩展名，作为导出文件默认名 */
        private String docBaseName() {
            String n = currentDoc == null ? "未命名" : currentDoc;
            int p = n.lastIndexOf('.');
            if (p > 0) n = n.substring(0, p);
            return n.isEmpty() ? "未命名" : n;
        }

        private File desktopDir() {
            try {
                File d = new File(System.getProperty("user.home"), "Desktop");
                if (d.isDirectory()) return d;
                d = new File(System.getProperty("user.home"), "桌面");
                if (d.isDirectory()) return d;
            } catch (Exception ignored) {}
            return null;
        }

        /** 字符串 -> 安全的 JS 字符串字面量（日志/toast 用） */
        private static String escJson(String s) {
            if (s == null) return "''";
            return "'" + s.replace("\\", "\\\\").replace("'", "\\'")
                          .replace("\n", "\\n").replace("\r", "") + "'";
        }

        /* ---- 清空 ---- */
        public void clearAll() {
            draft = "";
            total0 = 0;
            today0 = loadToday();
            try { new File(dataDir(), "draft.txt").delete(); } catch (Exception ignored) {}
            notifyJs("if(window.app&&app.onCleared)app.onCleared()");
        }

        /* ---- 文字丢失寻回（按文档，每个文件各自一份历史） ---- */
        /** JSON: [{"id":12,"time":"10-31 09:12","chars":1234,"preview":"..."}]（新→旧）
         *  id 是"在该文档历史数组里的下标"，配合 restoreDocHistory(doc,id) 使用 */
        public String listHistory() { return listDocHistory(currentDoc); }

        /** 指定文档的历史列表（文件面板里给任意文件用，不动当前文档内存状态） */
        public String listDocHistory(String doc) {
            String name = (doc == null || doc.trim().isEmpty()) ? currentDoc : doc.trim();
            java.util.List<String[]> list = name.equals(currentDoc)
                    ? history : parseHistoryFile(histFile(name));
            SimpleDateFormat df = new SimpleDateFormat("MM-dd HH:mm");
            StringBuilder sb = new StringBuilder("[");
            for (int i = list.size() - 1; i >= 0; i--) {
                String[] e = list.get(i);
                String txt = e[1];
                /* 预览折叠所有空白：换行符原样塞进 JSON，WebView 会把它渲染成
                   "缺字形的豆腐块"（寻回面板里一闪一闪的 □）—— 折成空格就干净了 */
                String prev = txt.length() > 60 ? txt.substring(0, 60) : txt;
                prev = prev.replaceAll("\\s+", " ").trim();
                if (sb.length() > 1) sb.append(',');
                sb.append("{\"id\":").append(i)
                  .append(",\"doc\":\"").append(esc(name))
                  .append("\",\"time\":\"").append(df.format(new Date(Long.parseLong(e[0]))))
                  .append("\",\"chars\":").append(countChars(txt))
                  .append(",\"preview\":\"").append(esc(prev)).append("\"}");
            }
            return sb.append(']').toString();
        }

        /** 某篇文档有几个快照（文件列表上显示） */
        public String histCount(String doc) {
            String name = (doc == null || doc.trim().isEmpty()) ? currentDoc : doc.trim();
            int n = name.equals(currentDoc) ? history.size() : parseHistoryFile(histFile(name)).size();
            return String.valueOf(n);
        }

        /** 设置 → 数据与恢复：所有有快照的文档 [{name,hist,chars,time}]（按最近快照倒序） */
        public String listHistDocs() {
            SimpleDateFormat df = new SimpleDateFormat("MM-dd HH:mm");
            File[] fs = histDir().listFiles((d, n) ->
                    n.endsWith(".hist") && !n.endsWith(".deleted.hist"));
            java.util.List<File> files = new ArrayList<File>();
            if (fs != null) for (File f : fs) if (f.isFile() && f.length() > 0) files.add(f);
            files.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            StringBuilder sb = new StringBuilder("[");
            for (File f : files) {
                String base = f.getName().substring(0, f.getName().length() - ".hist".length());
                boolean isCur = f.equals(histFile(currentDoc));
                java.util.List<String[]> snaps = isCur ? history : parseHistoryFile(f);
                if (snaps.isEmpty()) continue;
                String[] last = snaps.get(snaps.size() - 1);
                if (sb.length() > 1) sb.append(',');
                sb.append("{\"name\":\"").append(esc(isCur ? currentDoc : base)).append('"')
                  .append(",\"hist\":").append(snaps.size())
                  .append(",\"chars\":").append(countChars(last[1]))
                  .append(",\"time\":\"").append(df.format(new Date(f.lastModified()))).append("\"}");
            }
            return sb.append(']').toString();
        }

        /** 永久删除某文档的全部快照（不可恢复；正文不动） */
        public String purgeDocHistory(String doc) {
            String name = (doc == null || doc.trim().isEmpty()) ? currentDoc : doc.trim();
            File f = histFile(name);
            boolean ok = !f.exists() || f.delete();
            if (name.equals(currentDoc)) {
                history.clear(); lastHistText = null; lastHistAt = 0;
            }
            log("history 永久删除 " + name);
            return ok ? "ok" : "__ERR__:io";
        }

        /** 永久删除所有快照（回收站 .deleted.hist 不动，那是"已删文档"） */
        public String purgeAllHistory() {
            File[] fs = histDir().listFiles((d, n) -> n.endsWith(".hist"));
            int fail = 0;
            if (fs != null) for (File f : fs) if (!f.delete()) fail++;
            history.clear(); lastHistText = null; lastHistAt = 0;
            log("history 清空全部快照 fail=" + fail);
            return fail == 0 ? "ok" : "__ERR__:io";
        }

        /** 回收站里彻底删除一个已删文档 */
        public String purgeDeleted(String name) {
            if (name == null || name.trim().isEmpty()) return "__ERR__:bad-name";
            File del = new File(histDir(), name.trim() + ".deleted.hist");
            if (!del.isFile()) return "__ERR__:not-found";
            log("deleted 永久删除 " + name);
            return del.delete() ? "ok" : "__ERR__:io";
        }

        /**
         * 恢复某条快照。文本直接作为返回值交给前端落字 ——
         * 以前是在这里 notifyJs(setEditorText)，桥接里反向 executeScript 是重入，
         * WebKit 下静默失败，表现就是"点恢复没反应"（寻回功能坏的真正原因）。
         */
        public String restoreHistory(int id) { return restoreDocHistory(currentDoc, id); }

        /** 恢复任意文档的某条快照：写回该文档文件；当前文档还会同步内存与正文 */
        public String restoreDocHistory(String doc, int id) {
            String name = (doc == null || doc.trim().isEmpty()) ? currentDoc : doc.trim();
            boolean isCur = name.equals(currentDoc);
            java.util.List<String[]> list = isCur ? history : parseHistoryFile(histFile(name));
            if (id < 0 || id >= list.size()) return "__ERR__:bad-id";
            String text = list.get(id)[1];
            /* 恢复前先把"现在的内容"存一条，恢复到一半反悔也能再找回来 */
            if (isCur && !draft.isEmpty() && !draft.equals(text)) appendHistory(draft, true);
            try {
                writeAll(new File(docsDir(), name), text);
                if (isCur) {
                    draft = text;
                    lastHistText = text;      // 恢复本身不再记快照
                    total0 = countChars(text);
                    try { writeAll(new File(dataDir(), "draft.txt"), draft); } catch (Exception ignored) {}
                } else {
                    File copy = histFile(name);
                    java.util.List<String[]> keep = new ArrayList<String[]>(list);
                    if (keep.size() < HISTORY_MAX) {
                        keep.add(new String[]{String.valueOf(System.currentTimeMillis()), text});
                        StringBuilder sb = new StringBuilder();
                        for (String[] e : keep)
                            sb.append("###TS### ").append(e[0]).append('\n').append(e[1]).append("\n###END###\n");
                        writeAll(copy, sb.toString());
                    }
                }
            } catch (Exception e) { return "__ERR__:io"; }
            log("history 恢复 " + name + " #" + id + " (" + text.length() + " 字)");
            return text;
        }

        /* ---- 已删除文档找回（文件面板用） ---- */

        /** JSON: [{"name":"xx.txt","time":"11-02 15:30","chars":1234,"snaps":8}]（新→旧） */
        public String listDeleted() {
            SimpleDateFormat df = new SimpleDateFormat("MM-dd HH:mm");
            File[] fs = histDir().listFiles((d, n) -> n.endsWith(".deleted.hist"));
            StringBuilder sb = new StringBuilder("[");
            if (fs != null) {
                /* 按删除时间（文件修改时间）倒序 */
                java.util.Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                for (File f : fs) {
                    String name = f.getName();
                    name = name.substring(0, name.length() - ".deleted.hist".length());
                    java.util.List<String[]> snaps = parseHistoryFile(f);
                    String text = snaps.isEmpty() ? "" : snaps.get(snaps.size() - 1)[1];
                    if (sb.length() > 1) sb.append(',');
                    sb.append("{\"name\":\"").append(esc(name)).append('"')
                      .append(",\"time\":\"").append(df.format(new Date(f.lastModified()))).append('"')
                      .append(",\"chars\":").append(countChars(text))
                      .append(",\"snaps\":").append(snaps.size()).append('}');
                }
            }
            return sb.append(']').toString();
        }

        /** 把误删的文档恢复回来（正文用最后一条快照，历史档案跟着改名回来） */
        public String restoreDeleted(String name) {
            if (name == null || name.trim().isEmpty()) return "__ERR__:bad-name";
            File del = new File(histDir(), name.trim() + ".deleted.hist");
            if (!del.isFile()) return "__ERR__:not-found";
            /* 同名文档已存在时落到一个新名字，别覆盖别人的 */
            String target = name.trim();
            if (new File(docsDir(), target).exists()) {
                String base = target;
                String ext = "";
                int p = base.lastIndexOf('.');
                if (p > 0) { base = base.substring(0, p); ext = target.substring(p); }
                for (int i = 2; ; i++) {
                    String cand = base + "（恢复" + i + "）" + ext;
                    File f = histFile(cand);
                    File doc = new File(docsDir(), cand);
                    if (!doc.exists() && !f.exists() && !new File(histDir(), cand + ".deleted.hist").exists()) {
                        target = cand;
                        break;
                    }
                    if (i > 99) return "__ERR__:no-name";
                }
            }
            try {
                java.util.List<String[]> snaps = parseHistoryFile(del);
                String text = snaps.isEmpty() ? "" : snaps.get(snaps.size() - 1)[1];
                writeAll(new File(docsDir(), target), text);
                del.renameTo(histFile(target));      // 历史档案跟着回来
                log("deleted 恢复 " + name + " -> " + target);
                return target;
            } catch (Exception e) {
                return "__ERR__:io";
            }
        }

        /** 取某条快照的全文（预览用，不落盘） */
        public String getHistoryText(String doc, int id) {
            String name = (doc == null || doc.trim().isEmpty()) ? currentDoc : doc.trim();
            java.util.List<String[]> list = name.equals(currentDoc) ? history : parseHistoryFile(histFile(name));
            if (id < 0 || id >= list.size()) return "";
            return list.get(id)[1];
        }

        /* ---- 文档导入（txt / docx / doc） ---- */
        public void importDoc() {
            FileChooser fc = new FileChooser();
            fc.setTitle("导入文档");
            fc.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("全部支持", "*.txt", "*.docx", "*.doc"),
                    new FileChooser.ExtensionFilter("文本文档", "*.txt"),
                    new FileChooser.ExtensionFilter("Word 文档", "*.docx", "*.doc"));
            File f = fc.showOpenDialog(stage);
            if (f == null) return;
            String text = "";
            try {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".docx")) text = parseDocx(f);
                else if (n.endsWith(".doc")) text = parseDocBin(f);
                else text = parseTxtSmart(f);
            } catch (Exception ex) {
                notifyJs("if(window.app&&app.toast)app.toast('导入失败：" + esc(ex.getMessage()) + "')");
                return;
            }
            if (text.isEmpty()) {
                notifyJs("if(window.app&&app.toast)app.toast('未提取到文字内容')");
                return;
            }
            notifyJs("if(window.app&&app.showImport)app.showImport('" + esc(f.getName())
                    + "','" + esc(text) + "')");
        }

        /** docx = zip 包里的 word/document.xml，提取 <w:t> 文本、</w:p> 转换行 */
        private String parseDocx(File f) throws Exception {
            try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(f)) {
                java.util.zip.ZipEntry e = z.getEntry("word/document.xml");
                if (e == null) return "";
                java.io.InputStream in = z.getInputStream(e);
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                in.close();
                String xml = new String(bo.toByteArray(), UTF8);
                xml = xml.replaceAll("(?i)<w:tab[^>]*/>", "\t")
                         .replaceAll("(?i)</w:p>", "\n")
                         .replaceAll("(?i)<w:br[^>]*/>", "\n");
                StringBuilder sb = new StringBuilder();
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("<w:t[^>]*>(.*?)</w:t>", java.util.regex.Pattern.DOTALL)
                        .matcher(xml);
                int last = 0;
                while (m.find()) {
                    // 标签间隙里的换行（段落边界）补进去
                    String gap = xml.substring(last, m.start());
                    if (gap.contains("\n")) sb.append('\n');
                    sb.append(xmlUnescape(m.group(1)));
                    last = m.end();
                }
                return sb.toString().replaceAll("\n{3,}", "\n\n").trim();
            }
        }

        /** 老版 .doc 二进制：尽力提取 GBK 可读文本段（无第三方库的启发式方案） */
        private String parseDocBin(File f) throws Exception {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            }
            String s = new String(bo.toByteArray(), "GBK");
            StringBuilder out = new StringBuilder();
            StringBuilder cur = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                boolean ok = (c >= 0x4E00 && c <= 0x9FFF) || (c >= 'A' && c <= 'Z')
                        || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                        || "，。！？；：、（）《》…—·%.,!?;:()[]{}@#$^&*+=/\\<>|~`\" \t"
                           .replace("Q", "\u201C").replace("q", "\u201D")
                           .replace("N", "\u2018").replace("n", "\u2019")
                           .replace("S", "\u3000").indexOf(c) >= 0;
                if (c == '\n' || c == '\r') {
                    if (cur.length() >= 4) { out.append(cur).append('\n'); }
                    cur.setLength(0);
                } else if (ok) {
                    cur.append(c);
                } else {
                    if (cur.length() >= 4) out.append(cur).append(c == 0 ? '\n' : ' ');
                    cur.setLength(0);
                }
            }
            if (cur.length() >= 4) out.append(cur);
            return out.toString().replaceAll("\n{3,}", "\n\n").trim();
        }

        /** txt：UTF-8 优先，失败按 GBK */
        private String parseTxtSmart(File f) throws Exception {
            byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
            try {
                java.nio.charset.CharsetDecoder dec = UTF8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT);
                return dec.decode(java.nio.ByteBuffer.wrap(data)).toString();
            } catch (Exception e) {
                return new String(data, "GBK");
            }
        }

        private static String xmlUnescape(String s) {
            return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                    .replace("&apos;", "'").replace("&amp;", "&");
        }

        /* ---- 自定义应用图标（窗口/任务栏/托盘即时生效；exe 文件图标重打包时启用） ---- */
        public String pickCustomIcon() {
            FileChooser fc = new FileChooser();
            fc.setTitle("选择应用图标图片");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                    "图片", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.webp"));
            File f = fc.showOpenDialog(stage);
            if (f == null) return "";
            try {
                BufferedImage src = ImageIO.read(f);
                if (src == null) return "";
                int S = 256;
                BufferedImage out = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g = out.createGraphics();
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                // 等比缩放居中，正方形画布留透明边
                double k = Math.min((double) S / src.getWidth(), (double) S / src.getHeight());
                int w = (int) (src.getWidth() * k), h = (int) (src.getHeight() * k);
                g.drawImage(src, (S - w) / 2, (S - h) / 2, w, h, null);
                g.dispose();
                File dst = new File(dataDir(), "custom_icon.png");
                ImageIO.write(out, "png", dst);
                customIconPath = "custom_icon.png";
                saveConfig();
                applyAppIcon();
                return fileToDataUrl(dst);
            } catch (Exception e) { return ""; }
        }

        public void resetCustomIcon() {
            customIconPath = "";
            saveConfig();
            new File(dataDir(), "custom_icon.png").delete();
            applyAppIcon();
        }

        /** 提取别的 exe 文件的图标当作应用图标（窗口/任务栏/托盘即时生效）。
         *  走 java.desktop 公开 API FileSystemView.getSystemIcon(File,w,h)（JDK12+，
         *  Windows 上直接向 shell 要指定尺寸的图标，不碰 sun.* 内部类）。 */
        public String pickExeIcon() {
            FileChooser fc = new FileChooser();
            fc.setTitle("选择要提取图标的 exe 文件");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("exe 文件", "*.exe"));
            File f = fc.showOpenDialog(stage);
            if (f == null) return "";
            try {
                javax.swing.filechooser.FileSystemView fv =
                        javax.swing.filechooser.FileSystemView.getFileSystemView();
                javax.swing.Icon icon = fv.getSystemIcon(f, 256, 256);
                if (icon == null || icon.getIconWidth() <= 0) return "";
                java.awt.Image img;
                if (icon instanceof javax.swing.ImageIcon) {
                    img = ((javax.swing.ImageIcon) icon).getImage();
                } else {
                    BufferedImage bi = new BufferedImage(icon.getIconWidth(),
                            icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
                    java.awt.Graphics2D gg = bi.createGraphics();
                    icon.paintIcon(null, gg, 0, 0);
                    gg.dispose();
                    img = bi;
                }
                int S = 256;
                BufferedImage out = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g = out.createGraphics();
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                double k = Math.min((double) S / img.getWidth(null),
                                    (double) S / img.getHeight(null));
                int w = Math.max(1, (int) (img.getWidth(null) * k));
                int h = Math.max(1, (int) (img.getHeight(null) * k));
                g.drawImage(img, (S - w) / 2, (S - h) / 2, w, h, null);
                g.dispose();
                File dst = new File(dataDir(), "custom_icon.png");
                ImageIO.write(out, "png", dst);
                customIconPath = "custom_icon.png";
                saveConfig();
                applyAppIcon();
                return fileToDataUrl(dst);
            } catch (Exception e) { return ""; }
        }

        public String getCustomIcon() {
            if (customIconPath == null || customIconPath.isEmpty()) return "";
            File f = new File(dataDir(), customIconPath);
            return f.exists() ? fileToDataUrl(f) : "";
        }

        /* ---- 插件系统 ---- */
        /** JSON: ["插件名.js", ...] */
        public String listPlugins() {
            File pd = new File(dataDir(), "plugins");
            File[] fs = pd.listFiles((d, n) -> n.toLowerCase().endsWith(".js"));
            StringBuilder sb = new StringBuilder("[");
            if (fs != null) {
                java.util.Arrays.sort(fs);
                for (int i = 0; i < fs.length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append('"').append(esc(fs[i].getName())).append('"');
                }
            }
            return sb.append(']').toString();
        }

        /** 导入插件（复制 .js 到 data/plugins，下次启动或刷新时生效） */
        public String importPlugin() {
            FileChooser fc = new FileChooser();
            fc.setTitle("导入插件（.js）");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("闪写插件", "*.js"));
            File f = fc.showOpenDialog(stage);
            if (f == null) return "";
            return importPluginFrom(f);
        }

        /** 复制插件到 data/plugins 并立即执行（导入弹窗与 --plugtest 共用同一条路） */
        public String importPluginFrom(File f) {
            if (f == null || !f.exists()) return "";
            try {
                File pd = new File(dataDir(), "plugins");
                if (!pd.exists()) pd.mkdirs();
                File dst = new File(pd, f.getName());
                if (!dst.getAbsolutePath().equals(f.getAbsolutePath()))
                    java.nio.file.Files.copy(f.toPath(), dst.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                // 热加载：立即执行
                engine.executeScript(readAll(dst));
                log("plugin imported: " + dst.getName());
                notifyJs("if(window.app&&app.toast)app.toast('插件已导入并生效：" + esc(f.getName()) + "')");
                return f.getName();
            } catch (Exception e) {
                log("plugin import fail: " + e);
                notifyJs("if(window.app&&app.toast)app.toast('插件导入失败')");
                return "";
            }
        }
    }

    /* 3 秒自动保存定时器（应用启动即生效） */
    {
        java.util.Timer timer = new java.util.Timer("autosave", true);
        timer.schedule(new java.util.TimerTask() {
            public void run() {
                Platform.runLater(() -> {
                    try {
                        Object t = engine.executeScript(
                                "if(window.app&&app.getText)app.getText();else null");
                        if (t != null) saveDraft(t.toString());
                    } catch (Exception ignored) {}
                });
            }
        }, 3000, 3000);
    }
}
