import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import com.github.kwhat.jnativehook.mouse.NativeMouseEvent;
import com.github.kwhat.jnativehook.mouse.NativeMouseListener;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 全局键鼠钩子（混合版）：系统级监听，任何软件前台时均生效。
 *
 * 职责：
 *  1. 老板键：默认 Ctrl+Shift+Z+X 四键同按触发（低级钩子 + 按下键集合判定，
 *     系统 API 不支持注册四键热键）
 *  2. 鼠标手势：默认 LLRRLL（左左右右左左），1.2 秒内连按生效
 *  3. 快捷键捕获：设置界面"开始捕获"后，用户按下的组合实时回调给上层
 *
 * 注意：JNativeHook 2.2.x 的包名是 com.github.kwhat.jnativehook（不是 org.jnativehook，
 * 2.x 早期版本的旧包名，别踩这个坑）。
 */
public final class Hooks {

    /** 捕获回调：返回捕获到的组合可读名（如 "Ctrl+Shift+Z+X"），Esc 取消时传 null */
    public interface CaptureCb { void on(String combo); }

    /** 老板键触发回调 */
    private static Runnable bossAction;
    /** 当前老板键组合（Native 键码数组） */
    private static int[] bossCodes;
    /** 当前按下的键集合（四键组合判定用） */
    private static final Set<Integer> pressed = new HashSet<Integer>();
    /** 一次性闩锁：按住不放时系统会重复派发 keyPressed，不锁会连续开关窗口 */
    private static boolean bossLatched = false;

    /* ---------- 捕获状态 ---------- */
    private static volatile boolean capturing = false;
    private static CaptureCb captureCb;
    /** 捕获开始时间：超时自动取消，防止捕获态卡死把老板键全部吞掉（用户点了"修改组合"
     *  之后又没按任何键就去干别的，老板键就再也无法唤回窗口——经典事故现场） */
    private static volatile long captureStartAt = 0;
    private static final long CAPTURE_TIMEOUT_MS = 12000;

    /* ---------- 鼠标手势 ---------- */
    public static String gesture = "LLRRLL";
    public static boolean gestureEnabled = true;
    /** 诊断用：全局鼠标钩子到底收到了几次按下（0 = 钩子没拿到鼠标事件） */
    public static volatile int mousePressedCount = 0;
    public static volatile String lastGestureBuf = "";
    private static final StringBuilder gestureBuf = new StringBuilder();
    private static long firstGestureAt = 0;
    private static long lastClickAt = 0;
    /** 唤回气泡：窗口隐藏时，三连击左键 / 单击右键 直接以气泡模式唤回 */
    public static Runnable summonBubble;
    private static long lastLeftAt = 0;
    private static int leftStreak = 0;
    private static int lastLeftX = -9999, lastLeftY = -9999;
    /** 最近一次手势/老板键触发时刻：右键唤回的延迟定时器见到它就自杀，
     *  避免"窗口隐藏时做 LLRRLL 手势，第 3 拍（右键）先把窗口弹回来"——
     *  手势还没打完窗口就显示了，用户看到的就是'鼠标老板键失灵/乱跳' */
    private static long lastFireAt = 0;
    private static java.util.Timer summonTimer;
    /** 托盘图标点击保护窗口（到这个时刻之前的唤回一律作废）。
     *  真凶：隐藏后右键托盘图标，AWT 弹菜单的同时 JNativeHook 也收到一次右键，
     *  600ms 后 scheduleSummon 把窗口自己唤回来 —— 用户看到的就是
     *  "右键也能打开软件"。点托盘 = 只走 AWT 菜单 / 左键 toggle，不许全局钩子插手。 */
    private static volatile long trayClickUntil = 0;

    /** AWT TrayIcon 收到点击（左/右键都算）时调用：取消待执行的唤回，
     *  并在 1.2 秒内屏蔽全局钩子对这次点击的右键/三连击唤回。
     *  注意事件顺序不保证（JNativeHook 可能先于 AWT 也可能后于），
     *  所以「立刻取消 + 保护窗口过滤 + 定时器执行前再查一次」三道都要有。 */
    public static void noteTrayClick() {
        trayClickUntil = System.currentTimeMillis() + 1200;
        leftStreak = 0;
        gestureBuf.setLength(0);
        cancelSummon();
    }

    public static boolean trayGuardActive() {
        return System.currentTimeMillis() < trayClickUntil;
    }

    /** 测试专用：不经过真实鼠标，直接喂一次点击给手势/唤回逻辑（--traytest）。 */
    public static void testClick(int btn, int x, int y) { handleGesture(btn, x, y); }

    /* ---------- 键码 <-> 可读名 映射（反射读取 VC_ 常量表，勿手写推算：
       JNativeHook 的键码不是 ASCII 排列，Z 不是 'Z'，直接算会错） ---------- */
    private static final Map<String, Integer> NAME_TO_CODE = new HashMap<String, Integer>();
    private static final Map<Integer, String> CODE_TO_NAME = new HashMap<Integer, String>();

    static {
        try {
            for (Field f : NativeKeyEvent.class.getFields()) {
                String n = f.getName();
                if (!n.startsWith("VC_") || f.getType() != int.class) continue;
                int v = f.getInt(null);
                String name = null;
                if (n.equals("VC_CONTROL")) name = "Ctrl";
                else if (n.equals("VC_SHIFT")) name = "Shift";
                else if (n.equals("VC_ALT")) name = "Alt";
                else if (n.equals("VC_META")) name = "Win";
                else if (n.equals("VC_ESCAPE")) name = "Esc";
                else if (n.equals("VC_SPACE")) name = "Space";
                else if (n.equals("VC_TAB")) name = "Tab";
                else if (n.equals("VC_ENTER")) name = "Enter";
                else if (n.equals("VC_BACKSPACE")) name = "Backspace";
                else if (n.matches("VC_[A-Z]")) name = n.substring(3);
                else if (n.matches("VC_[0-9]")) name = n.substring(3);
                else if (n.matches("VC_F(1[0-2]?|[1-9])")) name = n.substring(3);
                if (name != null) {
                    NAME_TO_CODE.put(name, v);
                    CODE_TO_NAME.put(v, name);
                }
            }
        } catch (Exception ignored) {}
    }

    /** 可读组合名 -> Native 键码数组（如 "Ctrl+Shift+Z+X"）；解析失败返回 null */
    public static int[] parseCombo(String combo) {
        if (combo == null) return null;
        String[] parts = combo.split("\\+");
        int[] codes = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            Integer c = NAME_TO_CODE.get(parts[i].trim());
            if (c == null) return null;
            codes[i] = c;
        }
        return codes;
    }

    /** Native 键码 -> 可读名（Ctrl / Shift / Z / F1 ...） */
    public static String codeName(int code) {
        String n = CODE_TO_NAME.get(code);
        return n != null ? n : ("Key" + code);
    }

    /** 注册全局钩子。bossCombo: 键码数组；onBoss: 老板键/手势触发回调 */
    public static void register(int[] bossCombo, Runnable onBoss) {
        bossCodes = bossCombo;
        bossAction = onBoss;
        // 关闭 JNativeHook 的日志噪音
        Logger lg = Logger.getLogger(GlobalScreen.class.getPackage().getName());
        lg.setLevel(Level.OFF);
        try {
            for (java.util.logging.Handler h : lg.getHandlers()) h.setLevel(Level.OFF);
        } catch (Exception ignored) {}

        try {
            GlobalScreen.registerNativeHook();
        } catch (NativeHookException e) {
            throw new RuntimeException("全局钩子注册失败: " + e.getMessage(), e);
        }
        GlobalScreen.addNativeKeyListener(new NativeKeyListener() {
            public void nativeKeyPressed(NativeKeyEvent e) {
                if (pressed.size() > 8) pressed.clear();   // 兜底：丢释放事件时防集合无限堆积
                pressed.add(e.getKeyCode());
                if (capturing) {
                    // 捕获超过 12 秒自动作废，恢复老板键/手势的正常判定
                    if (System.currentTimeMillis() - captureStartAt > CAPTURE_TIMEOUT_MS) {
                        capturing = false;
                        CaptureCb cb = captureCb;
                        captureCb = null;
                        if (cb != null) cb.on(null);
                    } else {
                        handleCapture(e);
                        return;
                    }
                }
                // 组合命中后闩锁，直到组合被拆散才重新生效（防系统连发触发反复开关）
                if (matchBoss()) {
                    if (!bossLatched) {
                        bossLatched = true;
                        fireBoss();
                    }
                }
            }
            public void nativeKeyReleased(NativeKeyEvent e) {
                pressed.remove(e.getKeyCode());
                // 只要组合不再完整成立就重新上膛。旧版要求 pressed 全空才解锁，
                // 万一某个键的释放事件被系统吞掉，pressed 永不清空，老板键就永久失灵
                if (!matchBoss()) bossLatched = false;
            }
            public void nativeKeyTyped(NativeKeyEvent e) { /* 不需要 */ }
        });
        GlobalScreen.addNativeMouseListener(new NativeMouseListener() {
            public void nativeMousePressed(NativeMouseEvent e) {
                mousePressedCount++;
                try { handleGesture(e.getButton(), e.getX(), e.getY()); } catch (Throwable ignored) {}
            }
            public void nativeMouseReleased(NativeMouseEvent e) { /* 不需要 */ }
            public void nativeMouseClicked(NativeMouseEvent e) { /* 不需要 */ }
        });
    }

    public static void unregister() {
        try { GlobalScreen.unregisterNativeHook(); } catch (Exception ignored) {}
    }

    /** 改老板键后同步给钩子（否则钩子仍持有启动时的旧组合，改键不生效） */
    public static void setBossCodes(int[] codes) {
        bossCodes = codes;
        bossLatched = false;
        pressed.clear();
    }

    /** 开始捕获新组合（设置界面调用）；Esc 结束捕获并回调 null */
    public static void beginCapture(CaptureCb cb) {
        capturing = true;
        captureCb = cb;
        captureStartAt = System.currentTimeMillis();
        pressed.clear();
    }

    public static void cancelCapture() {
        capturing = false;
        captureCb = null;
    }

    /** 四键组合判定：按下键集合与老板键组合完全一致 */
    private static boolean matchBoss() {
        if (bossCodes == null || pressed.size() != bossCodes.length) return false;
        for (int c : bossCodes) if (!pressed.contains(c)) return false;
        return true;
    }

    /** 捕获中：修饰键只更新等待，按下主键后组装完整组合回调 */
    private static void handleCapture(NativeKeyEvent e) {
        int c = e.getKeyCode();
        if (c == NativeKeyEvent.VC_ESCAPE) {
            capturing = false;
            CaptureCb cb = captureCb;
            captureCb = null;
            if (cb != null) cb.on(null);
            return;
        }
        boolean isModifier = c == NativeKeyEvent.VC_CONTROL || c == NativeKeyEvent.VC_SHIFT
                || c == NativeKeyEvent.VC_ALT || c == NativeKeyEvent.VC_META;
        if (isModifier) return;   // 只按修饰键时不结束捕获

        StringBuilder sb = new StringBuilder();
        if (pressed.contains(NativeKeyEvent.VC_CONTROL)) sb.append("Ctrl+");
        if (pressed.contains(NativeKeyEvent.VC_SHIFT))   sb.append("Shift+");
        if (pressed.contains(NativeKeyEvent.VC_ALT))     sb.append("Alt+");
        if (pressed.contains(NativeKeyEvent.VC_META))    sb.append("Win+");
        sb.append(codeName(c));

        capturing = false;
        CaptureCb cb = captureCb;
        captureCb = null;
        if (cb != null) cb.on(sb.toString());
    }

    /** 手势判定 + 三连击/右键唤回气泡。
     *
     *  旧实现用"距第一次点击 1.2 秒"判定，6 连击（LLRRLL）几乎不可能在 1.2 秒内点完，
     *  所以手势一直不生效——改成"相邻两次点击间隔超过 420ms 才断开"，整串最长 3 秒。
     *
     *  ★★ 鼠标手势"完全没反应"的真凶（本轮实锤）：JNativeHook 在 Windows 上报告的
     *  按钮号是 1=左 / 2=右 / 3=中（跟 AWT 的 BUTTON2=中、BUTTON3=右 正好错位）。
     *  旧代码只认 BUTTON1 和 BUTTON3，于是真实右键被当成"不认识的中键"，
     *  直接 `b == 0 → gestureBuf 清空`，LLRRLL 永远凑不齐 → 手势 100% 不触发。
     *  实测（--gesttest Robot 点击）：左中右 = [1,3,2]。
     *  现在 2 和 3 都当右键（中键极少用，最多让序列多一个 R，不会误触发长序列）。 */
    private static void handleGesture(int btn, int x, int y) {
        if (capturing) return;
        char b = 0;
        if (btn == 1) b = 'L';
        else if (btn == 2 || btn == 3) b = 'R';
        if (lastGestureBuf.length() < 24) lastGestureBuf = lastGestureBuf + btn;

        long now = System.currentTimeMillis();

        /* 右键：窗口隐藏时唤回气泡。延迟 600ms 执行：单击右键 = 稍等半秒唤回；
           但若这个右键其实是手势序列的第 3/4 拍（LLRRLL 里有 R），序列完成时
           fireBoss 先 toggle，定时器看到 lastFireAt 很近就自动作废，不打架。
           ★ 顺延（v1.24）：6 拍手势点得稍慢（>1.2s）时，600ms 定时器会在手势
             还没点完时就执行 —— 窗口以气泡模式弹出来（"LLRRLL 怎么进气泡了"）。
             所以定时器触发时若 550ms 内还有过点击（手势仍在继续）就再等 550ms，
             直到 fireBoss 作废它，或用户真的停手（孤立右键/三连击 → 召唤）。 */
        if (b == 'R' && summonBubble != null && !trayGuardActive()) {
            cancelSummon();
            scheduleSummon(summonBubble, 600);
        }
        /* 左键三连击（同一位置 ±10px、间隔 <700ms）也唤回气泡。
           ★ 同样必须延迟+作废：LLRRLL 手势点快了（~110ms/拍），第 1/2/5 拍的
             三次左键正好凑成三连击，第 5 拍瞬间唤回气泡、第 6 拍又把它藏回去 ——
             这就是"手势变成了进气泡"的另一半真凶。统一走 600ms 延迟。 */
        if (b == 'L' && !trayGuardActive()) {
            boolean sameSpot = Math.abs(x - lastLeftX) <= 10 && Math.abs(y - lastLeftY) <= 10;
            if (now - lastLeftAt < 700 && sameSpot) leftStreak++;
            else leftStreak = 1;
            lastLeftAt = now; lastLeftX = x; lastLeftY = y;
            if (leftStreak >= 3) {
                leftStreak = 0;
                if (summonBubble != null) {
                    cancelSummon();
                    scheduleSummon(summonBubble, 600);
                }
            }
        }

        if (!gestureEnabled) return;
        if (b == 0) { gestureBuf.setLength(0); return; }   // 中键打断手势序列

        if (gestureBuf.length() == 0 || now - lastClickAt > 420) {
            gestureBuf.setLength(0);                       // 相邻两次间隔过长，重新计
        }
        lastClickAt = now;
        gestureBuf.append(b);
        if (gestureBuf.length() > gesture.length()) gestureBuf.deleteCharAt(0);  // 只保留最近 N 次
        if (gestureBuf.toString().equalsIgnoreCase(gesture)) {
            gestureBuf.setLength(0);
            fireBoss();
        }
    }

    /** 触发回调：异常绝不能抛回钩子线程 —— 一旦抛出，Windows 会认为钩子卡死并静默移除它，
     *  表现就是"老板键用着用着突然不灵了，重启才好"。 */
    private static void fireBoss() {
        lastFireAt = System.currentTimeMillis();
        Runnable r = bossAction;
        if (r == null) return;
        try { r.run(); } catch (Throwable ignored) {}
    }

    private static void cancelSummon() {
        if (summonTimer != null) { summonTimer.cancel(); summonTimer = null; }
    }

    /** 唤回气泡的延迟执行（右键单击 / 左键三连击共用）。
     *  触发时四道闸：
     *  1) lastFireAt 很近 → 手势刚好完成、fireBoss 已处理 → 作废；
     *  2) 刚点过托盘图标 → 只弹菜单，不许唤回（右键打开软件的 bug）；
     *  3) 550ms 内还有过点击 → 手势仍在继续（6 拍点得慢）→ 顺延 550ms 再看；
     *  4) 都不是 = 用户真的停手了（孤立右键 / 三连击）→ 召唤。 */
    private static void scheduleSummon(final Runnable sb, long delay) {
        if (summonTimer == null) summonTimer = new java.util.Timer("summon", true);
        try {
            summonTimer.schedule(new java.util.TimerTask() {
                public void run() {
                    try {
                        long t = System.currentTimeMillis();
                        if (t - lastFireAt < 600) return;
                        /* 第 4 道闸：JNativeHook 若比 AWT 托盘事件更早，定时器已经排上了，
                           执行时再查一次保护窗口，别把窗口从菜单底下唤回来。 */
                        if (t < trayClickUntil) return;
                        if (t - lastClickAt < 550) { scheduleSummon(sb, 550); return; }
                        sb.run();
                    } catch (Exception ignored) {}
                }
            }, delay);
        } catch (Exception ignored) {}   // timer 刚被 cancel 的竞态：下一次点击会重排
    }
}
