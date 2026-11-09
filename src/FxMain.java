import javafx.application.Application;

/**
 * 独立 main 入口（不继承 Application）。
 *
 * 为什么要这个空壳：JavaFX 8 有个著名坑——直接以"继承 Application 的类"作为
 * Main-Class 启动（java -jar / launch4j）时，JavaFX 启动器会错误地把 main 方法
 * 里的参数当 JavaFX 参数解析，导致报 "Missing JavaFX application class" 或
 * 直接无响应。官方解法就是用一个不继承 Application 的类做入口。
 */
public class FxMain {

    /** 截图测试模式：启动后自动填充示例内容并保存 ui_hybrid.png（自动化视觉验证用） */
    public static boolean shotMode = false;
    /** 截图测试变体：填充后进入气泡模式再截图 */
    public static boolean shotBubble = false;
    /** 截图测试变体：填充后打开设置面板再截图 */
    public static boolean shotSettings = false;
    /** 自测模式：在 WebView 里跑 assert（快捷键 / 词库插入 / Esc），结果打到 stdout */
    public static boolean selfTest = false;
    /** 气泡 + 词库常驻截图（验证词库被挪到输入框外侧） */
    public static boolean shotBubbleWords = false;
    /** 老板键回归测试：隐藏 → 唤回 → 退出，结果写 app.log / stdout */
    public static boolean bossTest = false;
    /** 真实键盘回归：Robot 敲真键验证删除/快捷键/Esc（合成事件验不出的真机故障） */
    public static boolean keyTest = false;
    /** 设置面板滚到中段截图（验证标题栏 ✕ 冻结） */
    public static boolean shotSettingsScroll = false;
    /** 导入弹窗截图（验证"只添加选中段落"这一排按钮） */
    public static boolean shotImport = false;
    /** 查找替换悬浮条截图（验证高亮 + 不遮罩可编辑） */
    public static boolean shotFind = false;
    /** 寻回弹窗截图（按文件的历史 + 文件下拉） */
    public static boolean shotHist = false;
    /** 文件面板截图（多文件 + 每行"寻回 N 条"） */
    public static boolean shotDocs = false;
    /** 导出面板截图（多格式卡片） */
    public static boolean shotExport = false;
    /** 右键样式菜单截图（标题也能改字体/字号/格式） */
    public static boolean shotCtx = false;
    /** 设置里的"数据与恢复"（快照管理 + 回收站）截图 */
    public static boolean shotData = false;
    /** 鼠标手势回归：用 Robot 真的点出 LLRRLL，验证 钩子→手势→老板键 整条链路 */
    public static boolean gestTest = false;
    /** 托盘左右键分工回归：右键只弹菜单不打开软件、左键能打开、普通右键仍能唤回 */
    public static boolean trayTest = false;
    /** 导入字体回归：把系统字体真的导进来，验证存盘格式 / @font-face 是否真的加载 */
    public static boolean fontTest = false;
    /** 插件回归：写一个探针插件进 data/plugins，验证加载与 window.snapwrite API */
    public static boolean plugTest = false;
    /** 右键菜单变体：直接停在"改样式"面板 */
    public static boolean shotCtxStyle = false;
    /** 设置截图变体：滚到"气泡悬浮模式"（含自己定大小滑杆） */
    public static boolean shotBubbleCfg = false;
    /** 气泡拖动/双击还原真实鼠标回归：Robot 拖拖动条 + 双击，验证真机事件管线 */
    public static boolean dragTest = false;
    /** 外部网页截图：左键列表 / 右键添加面板 */
    public static boolean shotWeb = false;
    public static boolean shotWebEdit = false;
    /** 正文超链接截图：设置面板 / 管理面板 / 点击卡片 / 编辑当前链接 */
    public static boolean shotLink = false;
    public static boolean shotLinkMgr = false;
    public static boolean shotLinkCard = false;
    public static boolean shotLinkEdit = false;
    /** 格式刷 / 粘贴选项截图 */
    public static boolean shotBrush = false;
    public static boolean shotPaste = false;
    /** v1.29 字符格式 toggle 截图：工具条亮起 / 右键菜单五个格式按钮亮起 */
    public static boolean shotFmt = false;
    public static boolean shotFmtCtx = false;
    /** v1.30 斜体渲染对照 / 格式刷全格式截图 */
    public static boolean shotItalic = false;
    public static boolean shotBrushAll = false;

    public static void main(String[] args) {
        for (String a : args) {
            if ("--exporttest".equals(a)) { Exporter.selfTest(); return; }
            if ("--shot".equals(a)) shotMode = true;
            if ("--shot-bubble".equals(a)) { shotMode = true; shotBubble = true; }
            if ("--shot-settings".equals(a)) { shotMode = true; shotSettings = true; }
            if ("--selftest".equals(a)) { shotMode = true; selfTest = true; }
            if ("--shot-bubble-words".equals(a)) { shotMode = true; shotBubbleWords = true; }
            if ("--bosstest".equals(a)) { shotMode = true; bossTest = true; }
            if ("--keytest".equals(a)) { shotMode = true; keyTest = true; }
            if ("--shot-settings-scroll".equals(a)) {
                shotMode = true; shotSettings = true; shotSettingsScroll = true;
            }
            if ("--shot-import".equals(a)) { shotMode = true; shotImport = true; }
            if ("--shot-find".equals(a)) { shotMode = true; shotFind = true; }
            if ("--shot-hist".equals(a)) { shotMode = true; shotHist = true; }
            if ("--shot-docs".equals(a)) { shotMode = true; shotDocs = true; }
            if ("--shot-export".equals(a)) { shotMode = true; shotExport = true; }
            if ("--shot-ctx".equals(a)) { shotMode = true; shotCtx = true; }
            if ("--shot-ctx-style".equals(a)) { shotMode = true; shotCtx = true; shotCtxStyle = true; }
            if ("--shot-bubble-cfg".equals(a)) { shotMode = true; shotBubbleCfg = true; }
            if ("--shot-web".equals(a)) { shotMode = true; shotWeb = true; }
            if ("--shot-web-edit".equals(a)) { shotMode = true; shotWebEdit = true; }
            if ("--shot-link".equals(a)) { shotMode = true; shotLink = true; }
            if ("--shot-link-mgr".equals(a)) { shotMode = true; shotLinkMgr = true; }
            if ("--shot-link-card".equals(a)) { shotMode = true; shotLinkCard = true; }
            if ("--shot-link-edit".equals(a)) { shotMode = true; shotLinkEdit = true; }
            if ("--shot-brush".equals(a)) { shotMode = true; shotBrush = true; }
            if ("--shot-paste".equals(a)) { shotMode = true; shotPaste = true; }
            if ("--shot-fmt".equals(a)) { shotMode = true; shotFmt = true; }
            if ("--shot-fmt-ctx".equals(a)) { shotMode = true; shotFmtCtx = true; }
            if ("--shot-italic".equals(a)) { shotMode = true; shotItalic = true; }
            if ("--shot-brush-all".equals(a)) { shotMode = true; shotBrushAll = true; }
            if ("--shot-data".equals(a)) { shotMode = true; shotSettings = true; shotData = true; }
            if ("--gesttest".equals(a)) { shotMode = true; gestTest = true; }
            if ("--fonttest".equals(a)) { shotMode = true; fontTest = true; }
            if ("--plugtest".equals(a)) { shotMode = true; plugTest = true; }
            if ("--dragtest".equals(a)) { shotMode = true; dragTest = true; }
            if ("--traytest".equals(a)) { shotMode = true; trayTest = true; }
        }
        /* 单实例：已经在跑就直接把那个窗口叫出来，自己静默退出。
           这样不会出现"关不掉→反复双击→任务管理器一堆 javaw"的情况。 */
        if (!shotMode && !FxApp.claimSingleInstance()) {
            FxApp.signalPrimary();
            return;
        }
        Application.launch(FxApp.class, args);
    }
}
