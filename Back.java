// This program cannot be run on Windows 11 Mode

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class Back {

    // ===== 可配置项 =====
    private static final LocalDate TARGET_DATE = LocalDate.of(2027, 6, 7);
    private static final String FONT_FAMILY = "微软雅黑";
    private static final Color TEXT_COLOR = new Color(255, 215, 0);
    private static final Color TEXT_SHADOW = new Color(0, 0, 0, 160);
    private static final Color BG_COLOR = new Color(20, 30, 60);

    /**
     * 设为 true 时走 color-key 透明（纯黑区域 -> 透明，露出壁纸）。
     * 子窗口不支持 per-pixel 透明，color-key 是子窗口下唯一可靠的透明方式。
     * 代价：文字抗锯齿边缘可能带黑边，可用加粗字体 + 阴影缓解。
     */
    private static final boolean USE_COLOR_KEY = false;

    private static final int WM_SPAWN_WORKER = 0x052C;

    // ===== 运行时状态 =====
    private static volatile Timer repaintTimer;
    private static volatile ScheduledExecutorService watchdog;
    private static volatile WinDef.HWND parentHwnd;

    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            System.err.println("[Countdown] 程序内部异常: " + e);
            e.printStackTrace();
        });
        try {
            launch();
        } catch (Throwable t) {
            System.err.println("[Countdown] 启动失败，程序退出: " + t);
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static void launch() throws Exception {
        // 1. 屏幕尺寸（JDK 9+ 默认 DPI-aware，这里的值是物理像素）
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        Rectangle bounds = ge.getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        final int W = bounds.width;
        final int H = bounds.height;

        // 2. 创建窗口：java.awt.Window + Canvas，无 contentPane 层级
        final Window[] windowRef = new Window[1];
        final Canvas[] canvasRef = new Canvas[1];

        SwingUtilities.invokeAndWait(() -> {
            Canvas canvas = new Canvas() {
                @Override
                public void paint(Graphics g) {
                    doPaint(g, getWidth(), getHeight());
                }

                @Override
                public void update(Graphics g) {
                    paint(g); // 关闭 update 清屏，消除闪烁
                }
            };
            canvas.setSize(W, H);

            Window window = new Window(null);
            window.add(canvas);
            window.setBounds(0, 0, W, H);
            window.setVisible(true);
            window.validate(); // 确保首次布局完成，Canvas 尺寸正确

            windowRef[0] = window;
            canvasRef[0] = canvas;
        });

        final Window window = windowRef[0];
        final Canvas canvas = canvasRef[0];

        // 3. 嵌入 WorkerW（探测 -> 触发 -> 轮询 -> SetParent）
        boolean embedded;
        try {
            embedded = embedIntoWorkerW(window, canvas);
        } catch (Throwable t) {
            System.err.println("[Countdown] 嵌入异常: " + t);
            t.printStackTrace();
            embedded = false;
        }

        if (!embedded) {
            SwingUtilities.invokeLater(() -> {
                try { window.dispose(); } catch (Throwable ignored) { }
                JOptionPane.showMessageDialog(null,
                        "无法将倒计时嵌入桌面底层。\n" +
                        "可能原因：系统版本不匹配、Explorer 未响应、或安全软件拦截。\n" +
                        "程序将退出，桌面未受到任何修改。\n\n" +
                        "提示：若使用 JDK 9+，请确保启动参数包含：\n" +
                        "  --add-opens java.desktop/sun.awt.windows=ALL-UNNAMED",
                        "倒计时壁纸", JOptionPane.WARNING_MESSAGE);
                System.exit(2);
            });
            return;
        }

        // 4. 定时重绘（保留引用，便于退出时 stop）
        repaintTimer = new Timer(1000, e -> canvas.repaint());
        repaintTimer.start();

        // 5. Explorer 守护：父窗口失效（Explorer 重启）时尝试重新嵌入
        startExplorerWatchdog(window, canvas);

        System.out.println("[Countdown] 已嵌入桌面底层，正常运行。");
    }

    // ===================== 绘制 =====================

    private static void doPaint(Graphics g, int w, int h) {
        if (w <= 0 || h <= 0) return;
        Graphics2D g2d = (Graphics2D) g;
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);

        // 背景：color-key 模式下纯黑（会被 API 转透明）；否则深色底
        g2d.setColor(USE_COLOR_KEY ? Color.BLACK : BG_COLOR);
        g2d.fillRect(0, 0, w, h);

        long days = ChronoUnit.DAYS.between(LocalDate.now(), TARGET_DATE);
        String text = days >= 0 ? "距离高考 " + days + " 天" : "高考已结束";

        int fontSize = Math.max(1, Math.min(w, h) / 7);
        g2d.setFont(new Font(FONT_FAMILY, Font.BOLD, fontSize));
        FontMetrics fm = g2d.getFontMetrics();
        int x = (w - fm.stringWidth(text)) / 2;
        int y = (h - fm.getHeight()) / 2 + fm.getAscent();

        g2d.setColor(TEXT_SHADOW);
        for (int dx = -2; dx <= 2; dx += 2)
            for (int dy = -2; dy <= 2; dy += 2)
                if (dx != 0 || dy != 0) g2d.drawString(text, x + dx, y + dy);

        g2d.setColor(TEXT_COLOR);
        g2d.drawString(text, x, y);
    }

    // ===================== 嵌入逻辑 =====================

    private static boolean embedIntoWorkerW(Window window, Canvas canvas) {
        User32 user32 = User32.INSTANCE;

        WinDef.HWND javaHwnd = getNativeHWND(window);
        if (javaHwnd == null) {
            System.err.println("[Countdown] 获取 HWND 失败，请检查 JDK 及 --add-opens 参数");
            return false;
        }

        // === 阶段 1：探测现有 WorkerW ===
        WinDef.HWND workerW = findWallpaperWorkerW(user32);
        if (workerW != null && attachToWorker(user32, javaHwnd, workerW, canvas)) {
            parentHwnd = workerW;
            return true;
        }

        // === 阶段 2：探测失败 -> 触发创建 + 轮询 ===
        WinDef.HWND progman = user32.FindWindow("Progman", null);
        if (progman == null) {
            System.err.println("[Countdown] 找不到 Progman 窗口");
            return false;
        }

        int[][] combos = {{0xD, 0}, {0xD, 1}, {0, 0}};
        for (int[] c : combos) {
            try {
                user32.SendMessageTimeout(progman, WM_SPAWN_WORKER,
                        new WinDef.WPARAM(c[0]), new WinDef.LPARAM(c[1]),
                        User32.SMTO_NORMAL, 500, new IntByReference());
            } catch (Throwable t) {
                System.err.println("[Countdown] SendMessageTimeout [" + c[0] + "," + c[1]
                        + "] 失败: " + t.getMessage());
            }
        }

        // 每 100ms 轮询一次，最多 3 秒
        for (int i = 0; i < 30; i++) {
            workerW = findWallpaperWorkerW(user32);
            if (workerW != null && attachToWorker(user32, javaHwnd, workerW, canvas)) {
                parentHwnd = workerW;
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * 枚举顶层窗口，找到“包含 SHELLDLL_DefView 的窗口”的下一个同级 WorkerW。
     * 该 WorkerW 才是承载壁纸的容器，SetParent 到它下面窗口才会位于图标之下。
     */
    private static WinDef.HWND findWallpaperWorkerW(User32 user32) {
        AtomicReference<WinDef.HWND> ref = new AtomicReference<>(null);
        user32.EnumWindows((hwnd, data) -> {
            // 该顶层窗口内含 SHELLDLL_DefView 才算图标宿主
            if (user32.FindWindowEx(hwnd, null, "SHELLDLL_DefView", null) == null)
                return true;
            // 壁纸容器是图标宿主的“下一个同级 WorkerW”
            WinDef.HWND w = user32.FindWindowEx(null, hwnd, "WorkerW", null);
            if (w != null) {
                ref.set(w);
                return false;
            }
            return true;
        }, null);
        return ref.get();
    }

    private static boolean attachToWorker(User32 user32, WinDef.HWND javaHwnd,
                                          WinDef.HWND workerW, Canvas canvas) {
        // 1. 样式：转为子窗口，去掉所有顶层装饰
        int style = user32.GetWindowLong(javaHwnd, WinUser.GWL_STYLE);
        style &= ~(WinUser.WS_POPUP | WinUser.WS_CAPTION | WinUser.WS_THICKFRAME
                | WinUser.WS_SYSMENU | WinUser.WS_MAXIMIZEBOX | WinUser.WS_MINIMIZEBOX);
        style |= WinUser.WS_CHILD;
        user32.SetWindowLong(javaHwnd, WinUser.GWL_STYLE, style);

        int exStyle = user32.GetWindowLong(javaHwnd, WinUser.GWL_EXSTYLE);
        exStyle |= WinUser.WS_EX_NOACTIVATE;  // 点击不抢焦点
        exStyle &= ~WinUser.WS_EX_APPWINDOW;   // 不在任务栏出现
        if (USE_COLOR_KEY) exStyle |= WinUser.WS_EX_LAYERED; // 子窗口分层（只能用 color-key）
        user32.SetWindowLong(javaHwnd, WinUser.GWL_EXSTYLE, exStyle);

        // 2. SetParent：先清 LastError，NULL + err==0 视为成功
        Kernel32.INSTANCE.SetLastError(0);
        WinDef.HWND old = user32.SetParent(javaHwnd, workerW);
        int err = Kernel32.INSTANCE.GetLastError();
        if (old == null && err != 0) {
            System.err.println("[Countdown] SetParent 失败, err=" + err);
            return false;
        }

        // 3. color-key 透明：纯黑 -> 透明
        if (USE_COLOR_KEY) {
            boolean ok = user32.SetLayeredWindowAttributes(
                    javaHwnd, 0x000000, (byte) 0, 0x00000001 /* LWA_COLORKEY */);
            if (!ok) System.err.println("[Countdown] SetLayeredWindowAttributes 失败");
        }

        // 4. 用 Win32 设置尺寸位置（此后不要再调用任何 AWT setBounds/setSize）
        int w = canvas.getWidth();
        int h = canvas.getHeight();
        if (w <= 0 || h <= 0) {
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            Rectangle b = ge.getDefaultScreenDevice().getDefaultConfiguration().getBounds();
            w = b.width;
            h = b.height;
        }
        user32.SetWindowPos(javaHwnd, null, 0, 0, w, h,
                WinUser.SWP_NOZORDER | WinUser.SWP_NOACTIVATE | WinUser.SWP_FRAMECHANGED);

        return true;
    }

    /**
     * 获取 AWT 窗口的原生 HWND。
     * 全程走反射：JDK 8 与 JDK 9+ 通用，不直接引用 sun.awt.windows.WComponentPeer 内部类。
     * JDK 9+ 需启动参数 --add-opens java.desktop/sun.awt.windows=ALL-UNNAMED，
     * 否则 setAccessible(true) 抛 InaccessibleObjectException，此方法返回 null，由调用方干净退出。
     */
    private static WinDef.HWND getNativeHWND(Window window) {
        try {
            Object peer = window.getPeer();
            if (peer == null) {
                System.err.println("[Countdown] window peer 为 null（窗口尚未可见？）");
                return null;
            }
            Method m = peer.getClass().getMethod("getHWnd");
            m.setAccessible(true);
            Number n = (Number) m.invoke(peer);
            long hwnd = n.longValue();
            return hwnd == 0 ? null : new WinDef.HWND(new Pointer(hwnd));
        } catch (Throwable t) {
            System.err.println("[Countdown] getNativeHWND 失败: " + t);
            return null;
        }
    }

    // ===================== Explorer 守护 =====================

    private static void startExplorerWatchdog(Window window, Canvas canvas) {
        watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ExplorerWatchdog");
            t.setDaemon(true);
            return t;
        });

        AtomicBoolean reembedding = new AtomicBoolean(false);

        watchdog.scheduleAtFixedRate(() -> {
            if (reembedding.get()) return;
            WinDef.HWND parent = parentHwnd;
            if (parent == null) return;

            // IsWindow 返回 0 表示句柄已失效（Explorer 重启）
            if (User32.INSTANCE.IsWindow(parent)) return;

            if (!reembedding.compareAndSet(false, true)) return;
            try {
                System.err.println("[Countdown] 父窗口失效（Explorer 可能重启），尝试重新嵌入…");
                boolean ok = embedIntoWorkerW(window, canvas);
                if (ok) {
                    System.err.println("[Countdown] 重新嵌入成功");
                } else {
                    System.err.println("[Countdown] 重新嵌入失败，程序退出");
                    SwingUtilities.invokeLater(() -> {
                        if (repaintTimer != null) repaintTimer.stop();
                        try { window.dispose(); } catch (Throwable ignored) { }
                        System.exit(3);
                    });
                }
            } finally {
                reembedding.set(false);
            }
        }, 5, 5, TimeUnit.SECONDS);
    }
}