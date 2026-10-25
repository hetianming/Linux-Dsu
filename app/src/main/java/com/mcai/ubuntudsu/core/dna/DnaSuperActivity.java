package com.mcai.ubuntudsu.core.dna;

import com.mcai.ubuntudsu.R;
import com.mcai.ubuntudsu.core.DnaTools;
import com.mcai.ubuntudsu.core.RootShell;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DNA · 分解 SUPER 独立二级页。
 * - 直接读取当前工程下的 super.img（无文件选择、无多余选项，对齐原版 DNA）；
 *   顶栏可切换工程（v3.30.32），选择后直接解析新工程的 super.img
 * - 解析：dna lpunpack --list（root 链路，实测快且稳）。原始输出行静默解析，
 *   日志只留 开始/汇总 两行（v3.30.30 的「整行当分区名」bug 已修：value = | 前基名）
 * - 提取：dna lpunpack --partition 'a,b' --delete 0 --auto 0（stderr 已并入，实时日志）；
 *   结束后 root 核对输出目录实际新增/更新的 .img，杜绝假成功
 */
public final class DnaSuperActivity extends DnaBaseActivity {

    public static final String EXTRA_SUPER = "super_path";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService io =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "dna-super");
                t.setDaemon(true);
                return t;
            });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);

    private static final class Part {
        final String name;
        final long size;
        Part(String n, long s) { name = n; size = s; }
    }

    private String project;
    private String superPath;
    private final List<Part> partitions = new ArrayList<>();
    private final Set<String> checked = new LinkedHashSet<>();

    // UI
    private TextView infoName, infoPath, infoMeta;
    private TextView status;
    private FrameLayout progressTrack;
    private View progressFill;
    private TextView consoleText;
    private ScrollView consoleScroll;
    private LinearLayout consoleCard;
    private android.app.Dialog consoleDialog;
    private Button runBtn, reparseBtn, projectBtn;
    private android.animation.ObjectAnimator marquee;

    /** DNA 界面夜间适配色板（按 Ui.isDark 分流） */
    private com.mcai.ubuntudsu.ui.Ui.DnaPalette pal;

    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + 0.5f); }
    private String t(String zh, String en) {
        return getResources().getConfiguration().locale.getLanguage().startsWith("zh") ? zh : en;
    }
    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        Haptics.onTouch(getWindow().getDecorView(), e);
        return super.dispatchTouchEvent(e);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0x00000000);
        com.mcai.ubuntudsu.ui.Ui.INSTANCE.enableEdgeToEdge(this, getWindow().getDecorView());
        // v3.30.31：通知栏实时同步（Android 13+ 需运行时通知授权）
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 3406);
        ensureNoteChannel();
        project = DnaTools.currentProject(this);
        // v3.30.30：直接读工程 super.img（入口已检测），不再提供文件选择
        String extra = getIntent().getStringExtra(EXTRA_SUPER);
        if (extra != null && new File(extra).isFile()) superPath = extra;
        else if (project != null) {
            String def = DnaTools.WORK_ROOT + "/" + project + "/super.img";
            if (new File(def).isFile()) superPath = def;
        }
        buildUi();
        if (superPath != null) parseSuper();
        else {
            String expect = project != null
                    ? DnaTools.WORK_ROOT + "/" + project + "/super.img"
                    : DnaTools.WORK_ROOT + "/<工程>/super.img";
            status.setText("✗ " + t("当前工程未找到 super.img", "No super.img in project"));
            status.setTextColor(pal.danger);
            log("✗ " + t("未找到", "Not found") + ": " + expect);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        cancelNote();
        if (marquee != null) marquee.cancel();
        if (consoleDialog != null && consoleDialog.isShowing()) consoleDialog.dismiss();
        consoleDialog = null;
    }

    // ================= UI 构建 =================

    private LinearLayout glassCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        c.setBackground(com.mcai.ubuntudsu.ui.Ui.INSTANCE.dnaGlassCard(this, 18f));
        return c;
    }

    private Button pillButton(String label, float size, int color, int w, int h) {
        Button b = new Button(this, null, 0);
        b.setText(label);
        b.setTextSize(size);
        b.setAllCaps(false);
        b.setTextColor(color);
        b.setTypeface(null, 1);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinHeight(0);
        boolean dark = com.mcai.ubuntudsu.ui.Ui.INSTANCE.isDark(this);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(dark ? 0x593A4355 : 0x59FFFFFF);
        bg.setCornerRadius(dp(15));
        bg.setStroke(Math.max(1, dp(1)), dark ? 0x80FFFFFF : 0x66FFFFFF);
        b.setBackground(bg);
        b.setStateListAnimator(null);
        b.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        return b;
    }

    private Button gradientButton(String label, int c1, int c2) {
        Button b = new Button(this, null, 0);
        b.setText(label);
        b.setTextSize(15f);
        b.setAllCaps(false);
        b.setTextColor(android.graphics.Color.WHITE);
        b.setTypeface(null, 1);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinHeight(0);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TL_BR);
        bg.setColors(new int[]{c1, c2});
        bg.setCornerRadius(dp(18));
        bg.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
        b.setBackground(bg);
        b.setStateListAnimator(null);
        return b;
    }

    private String fmtSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024f);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.2f MB", bytes / 1048576f);
        return String.format(Locale.US, "%.2f GB", bytes / 1073741824f);
    }

    /** dna 风格短格式：465.3M / 9.1G（完成列表显示格式） */
    private String fmtSizeShort(long bytes) {
        if (bytes < 1024) return bytes + "B";
        double v = bytes / 1024.0;
        String[] u = {"K", "M", "G", "T"};
        int i = 0;
        while (v >= 1024 && i < 3) { v /= 1024; i++; }
        return String.format(Locale.US, "%.1f%s", v, u[i]);
    }

    private void buildUi() {
        pal = com.mcai.ubuntudsu.ui.Ui.INSTANCE.dnaPalette(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{pal.bgTop, pal.bgBottom}));
        com.mcai.ubuntudsu.ui.Ui.INSTANCE.animateLiquidBackground(root);
        com.mcai.ubuntudsu.ui.Ui.INSTANCE.applyContentInsets(root, 8, 0);

        ScrollView page = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(6), dp(16), dp(16));
        page.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        // ---- 顶栏：返回 + 标题 ----
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setPadding(dp(10), 0, 0, 0);
        TextView title = new TextView(this);
        title.setText("🧩 " + t("分解 SUPER", "Unpack SUPER"));
        title.setTextSize(19);
        title.setTypeface(null, 1);
        title.setTextColor(pal.title);
        titleBox.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView sub = new TextView(this);
        sub.setText(t("工程 super.img → 分区镜像 · lpunpack", "project super.img → partitions"));
        sub.setTextSize(11.5f);
        sub.setTextColor(pal.subtitle);
        titleBox.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        top.addView(titleBox, new LinearLayout.LayoutParams(0, -2, 1f));
        // v3.30.32：顶部切换工程按钮（root 列 /sdcard/Linux-Dsu/PDNA 下工程，选择后直接解析其 super.img）
        projectBtn = pillButton("📁 " + (project != null ? project.replaceFirst("^PDNA_", "") : t("工程", "Proj")),
                11f, pal.accent, dp(96), dp(32));
        projectBtn.setSingleLine(true);
        projectBtn.setEllipsize(android.text.TextUtils.TruncateAt.END);
        projectBtn.setOnClickListener(v -> {
            Haptics.perform(v);
            if (!running.get()) showProjectDialog();
        });
        top.addView(projectBtn, new LinearLayout.LayoutParams(dp(96), dp(32)));
        content.addView(top, new LinearLayout.LayoutParams(-1, -2));

        // ---- super.img 信息卡（无文件选择，只读展示 + 重新解析） ----
        LinearLayout infoCard = glassCard();
        LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(-1, -2);
        icLp.topMargin = dp(12);
        content.addView(infoCard, icLp);

        LinearLayout infoHead = new LinearLayout(this);
        infoHead.setOrientation(LinearLayout.HORIZONTAL);
        infoHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView lbl = new TextView(this);
        lbl.setText(t("SUPER 镜像（当前工程）", "SUPER image (current project)"));
        lbl.setTextSize(11.5f);
        lbl.setTypeface(null, 1);
        lbl.setTextColor(pal.subtitle);
        infoHead.addView(lbl, new LinearLayout.LayoutParams(0, -2, 1f));
        reparseBtn = pillButton("🔄 " + t("重新解析", "Re-parse"), 11f, pal.success, dp(88), dp(30));
        reparseBtn.setOnClickListener(v -> {
            Haptics.perform(v);
            if (!running.get()) parseSuper();
        });
        infoHead.addView(reparseBtn, new LinearLayout.LayoutParams(dp(88), dp(30)));
        infoCard.addView(infoHead, new LinearLayout.LayoutParams(-1, -2));

        infoName = new TextView(this);
        infoName.setText(superPath != null
                ? "💽 " + new File(superPath).getName() + (new File(superPath).length() > 0 ? " · " + fmtSize(new File(superPath).length()) : "")
                : "💽 super.img");
        infoName.setTextSize(14.5f);
        infoName.setTypeface(null, 1);
        infoName.setTextColor(pal.title);
        infoName.setSingleLine(true);
        infoName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(-1, -2);
        inLp.topMargin = dp(6);
        infoCard.addView(infoName, inLp);

        infoPath = new TextView(this);
        infoPath.setText(superPath != null ? superPath : "-");
        infoPath.setTextSize(11.5f);
        infoPath.setTextColor(pal.subtitle);
        infoPath.setSingleLine(true);
        infoPath.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams ipLp = new LinearLayout.LayoutParams(-1, -2);
        ipLp.topMargin = dp(1);
        infoCard.addView(infoPath, ipLp);

        infoMeta = new TextView(this);
        infoMeta.setText("…");
        infoMeta.setTextSize(12f);
        infoMeta.setTypeface(null, 1);
        infoMeta.setTextColor(pal.success);
        LinearLayout.LayoutParams imLp = new LinearLayout.LayoutParams(-1, -2);
        imLp.topMargin = dp(6);
        infoCard.addView(infoMeta, imLp);

        // ---- 主按钮（解析完成前禁用 → 修复此前两按钮同时可点） ----
        runBtn = gradientButton("▶  " + t("选择分区并提取", "Select & Extract"), 0xFF2f9c8f, 0xFF1d6b46);
        runBtn.setEnabled(false);
        runBtn.setAlpha(0.5f);
        runBtn.setOnClickListener(v -> {
            Haptics.perform(v);
            if (running.get()) { cancelFlag.set(true); log(t("正在取消 ...", "Cancelling...")); return; }
            if (partitions.isEmpty()) { parseSuper(); return; }
            showPartitionDialog();
        });
        LinearLayout.LayoutParams rbLp = new LinearLayout.LayoutParams(-1, dp(52));
        rbLp.topMargin = dp(12);
        rbLp.bottomMargin = dp(10);
        content.addView(runBtn, rbLp);

        // ---- 状态 + 进度 ----
        status = new TextView(this);
        status.setText(t("正在解析 ...", "Parsing..."));
        status.setTextSize(12.5f);
        status.setTextColor(pal.subtitle);
        content.addView(status, new LinearLayout.LayoutParams(-1, -2));
        progressTrack = new FrameLayout(this);
        android.graphics.drawable.GradientDrawable pt = new android.graphics.drawable.GradientDrawable();
        pt.setColor(0x33FFFFFF);
        pt.setCornerRadius(dp(6));
        progressTrack.setBackground(pt);
        progressTrack.setVisibility(View.GONE);
        progressFill = new View(this);
        android.graphics.drawable.GradientDrawable pf = new android.graphics.drawable.GradientDrawable();
        pf.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT);
        pf.setColors(new int[]{0xFF2f9c8f, 0xFF35A8C4});
        pf.setCornerRadius(dp(6));
        progressFill.setBackground(pf);
        // 不确定进度：lpunpack 无百分比回调，用 30% 宽度往返跑马灯
        progressTrack.addView(progressFill, new FrameLayout.LayoutParams(0, dp(12), Gravity.START | Gravity.CENTER_VERTICAL));
        LinearLayout.LayoutParams ptLp = new LinearLayout.LayoutParams(-1, dp(12));
        ptLp.topMargin = dp(6);
        content.addView(progressTrack, ptLp);

        // ---- 浅色控制台（v3.30.31：与状态/进度拉开间距；v3.41.14 改为弹出小窗口）----
        // 不再 addView 到页面 content；由 expandConsole() 弹出 Dialog 承载
    }

    /** 浅色磨砂控制台卡（可滚动 / 复制 / 清除 / 关闭窗；弹出小窗口承载） */
    private LinearLayout buildConsole() {
        consoleCard = new LinearLayout(this);
        LinearLayout card = consoleCard;
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        boolean dark = com.mcai.ubuntudsu.ui.Ui.INSTANCE.isDark(this);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        // v3.41.14：日志弹窗背景同步 APP 主体日/夜间模式主题
        bg.setColor(dark ? 0xFF111927 : 0xFFF7FAFF);
        bg.setCornerRadius(dp(18));
        bg.setStroke(Math.max(1, dp(1)), 0x80FFFFFF);
        card.setBackground(bg);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, 0, 0, dp(8));
        TextView title = new TextView(this);
        title.setText(t("执行任务", "Run Task"));
        title.setTextSize(14f);
        title.setTypeface(null, 1);
        title.setTextColor(pal.title);
        title.setSingleLine(true);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        Button copy = pillButton(t("复制日志", "Copy Log"), 11f, pal.success, dp(64), dp(28));
        copy.setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("log", consoleText.getText()));
            toast(t("日志已复制", "Log copied"));
        });
        actions.addView(copy);
        Button clear = pillButton(t("清除日志", "Clear Log"), 11f, pal.danger, dp(64), dp(28));
        clear.setOnClickListener(v -> consoleText.setText(""));
        LinearLayout.LayoutParams clLp = new LinearLayout.LayoutParams(dp(64), dp(28));
        clLp.leftMargin = dp(6);
        actions.addView(clear, clLp);
        Button close = pillButton("✕", 12.5f, pal.subtitle, dp(40), dp(28));
        close.setOnClickListener(v -> {
            if (consoleDialog != null && consoleDialog.isShowing()) consoleDialog.dismiss();
        });
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(dp(40), dp(28));
        closeLp.leftMargin = dp(6);
        actions.addView(close, closeLp);
        head.addView(actions, new LinearLayout.LayoutParams(-2, -2));
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));

        // v3.41.11：BoundedScrollView（OTG 触摸模型，整条祖先链独占）替换裸 ScrollView
        consoleScroll = new DnaActivity.BoundedScrollView(this, 0);
        android.graphics.drawable.GradientDrawable bodyBg = new android.graphics.drawable.GradientDrawable();
        bodyBg.setColor(dark ? 0xFF0E141F : 0xFFE8EEF7);
        bodyBg.setCornerRadius(dp(16));
        bodyBg.setStroke(Math.max(1, dp(1)), dark ? 0x59FFFFFF : 0x59FFFFFF);
        consoleScroll.setBackground(bodyBg);
        consoleText = new TextView(this);
        consoleText.setTypeface(android.graphics.Typeface.MONOSPACE);
        consoleText.setTextSize(9.5f);
        consoleText.setTextColor(dark ? 0xFFEAF0F8 : 0xFF1F3352);
        consoleText.setLineSpacing(dp(2), 1f);
        consoleText.setHorizontallyScrolling(false);
        consoleText.setPadding(dp(10), dp(8), dp(10), dp(8));
        consoleScroll.addView(consoleText, new ScrollView.LayoutParams(-1, -2));
        card.addView(consoleScroll, new LinearLayout.LayoutParams(-1, dp(420)));
        return card;
    }

    /** 任务开始时弹出日志小窗口（标题执行任务；右侧复制/清除/关闭窗），已弹出则复用 */
    private void expandConsole() {
        if (consoleCard == null) return;
        if (consoleDialog == null) {
            consoleDialog = new android.app.Dialog(this);
            consoleDialog.requestWindowFeature(0);
            consoleDialog.setContentView(consoleCard, new android.view.ViewGroup.LayoutParams(-1, dp(470)));
            consoleDialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    com.mcai.ubuntudsu.ui.Ui.INSTANCE.isDark(this) ? 0x80000000 : 0x592A3447));
            consoleDialog.setCanceledOnTouchOutside(true);
        }
        if (!consoleDialog.isShowing()) consoleDialog.show();
        scrollConsoleToBottom();
    }

    // ================= 状态渲染 =================

    private void log(String line) {
        main.post(() -> {
            if (isFinishing() || isDestroyed() || consoleText == null || consoleScroll == null) return;
            consoleText.append(line + "\n");
            scrollConsoleToBottom();
        });
    }

    private void scrollConsoleToBottom() {
        if (consoleText == null || consoleScroll == null) return;
        consoleText.requestLayout();
        consoleText.post(() -> {
            if (isFinishing() || isDestroyed() || consoleScroll == null) return;
            consoleScroll.fullScroll(ScrollView.FOCUS_DOWN);
        });
    }

    /** 跑马灯进度（lpunpack 无百分比） */
    private void showMarquee(boolean on) {
        progressTrack.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) {
            progressTrack.post(() -> {
                int track = progressTrack.getWidth();
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) progressFill.getLayoutParams();
                lp.width = Math.max(dp(40), track * 3 / 10);
                progressFill.setLayoutParams(lp);
                if (marquee == null) {
                    marquee = android.animation.ObjectAnimator.ofFloat(progressFill, "translationX",
                            0f, Math.max(1, track - lp.width));
                    marquee.setDuration(1100);
                    marquee.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                    marquee.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                }
                marquee.setFloatValues(0f, Math.max(1, track - lp.width));
                marquee.start();
            });
        } else if (marquee != null) {
            marquee.cancel();
            progressFill.setTranslationX(0f);
        }
    }

    // ================= 通知栏同步（v3.30.31：实时，不节流不延迟） =================

    private static final String NOTE_CHANNEL = "dna_tools_progress";
    private static final int NOTE_ID = 3406;

    private void ensureNoteChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    NOTE_CHANNEL, "DNA 工具箱进度", android.app.NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("显示 DNA 分解 / 提取任务实时状态");
            channel.setShowBadge(false);
            getSystemService(android.app.NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private void notify(String text, boolean ongoing, boolean indeterminate, int progress, int max) {
        try {
            // 有确定进度就传真实百分比，系统（ColorOS 流体云 / 灵动胶囊）才能显示进度
            Integer pct = !ongoing
                    ? Integer.valueOf(100)
                    : (indeterminate || max <= 0 ? null : Integer.valueOf(Math.min(100, progress * 100 / max)));
            Intent it = new Intent(this, DnaSuperActivity.class);
            it.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, NOTE_ID, it,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            android.app.Notification n = com.mcai.ubuntudsu.core.ProgressNotification.INSTANCE.build(
                    this, NOTE_CHANNEL, t("分解 SUPER", "Unpack SUPER"), text, pct, ongoing, pi, !text.startsWith("✗"));
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTE_ID, n);
        } catch (Exception ignored) {
        }
    }

    private void notifyDone(boolean success, String message) {
        notify((success ? "✓ " : "✗ ") + message, false, false, 0, 0);
    }

    private void cancelNote() {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTE_ID);
        } catch (Exception ignored) {}
    }

    // ================= 解析（dna lpunpack --list，root 链路） =================

    private void parseSuper() {
        if (running.get()) return;
        if (superPath == null) return;
        expandConsole();
        running.set(true);
        cancelFlag.set(false);
        runBtn.setEnabled(false);
        runBtn.setAlpha(0.5f);
        reparseBtn.setEnabled(false);
        status.setText("🔍 " + t("正在读取 super 分区 ...", "Reading super partitions..."));
        status.setTextColor(pal.subtitle);
        showMarquee(true);
        notify(t("正在解析 super.img ...", "Parsing super.img..."), true, true, 0, 0);
        log("🔍 " + t("开始解析", "Parse") + ": " + superPath);
        final String img = superPath;
        io.execute(() -> {
            // v3.30.32：直接用 dna lpunpack --list（root 链路，实测快且稳）。
            // 原始输出行（「odm|odm(465.3M)」）静默解析，不刷进日志 —— 日志只留
            // 开始/汇总两行（此前逐行透传 + EACCES 直读失败提示 = 日志「犯病」的根源）。
            DnaTools.Result r = DnaTools.run(this,
                    "dna lpunpack --list " + DnaTools.quote(img),
                    line -> kotlin.Unit.INSTANCE,
                    () -> cancelFlag.get(), 180000);
            List<Part> parts = r.getSuccess() ? parseDnaList(r.getOutput()) : null;
            String err = r.getSuccess() ? null : r.getMessage();
            final List<Part> fParts = parts;
            final String fErr = err;
            main.post(() -> {
                running.set(false);
                reparseBtn.setEnabled(true);
                showMarquee(false);
                partitions.clear();
                checked.clear();
                if (fParts == null || fParts.isEmpty()) {
                    runBtn.setEnabled(false);
                    runBtn.setAlpha(0.5f);
                    status.setText("✗ " + t("解析失败，请确认 super.img 有效", "Parse failed, invalid super.img?"));
                    status.setTextColor(pal.danger);
                    log("✗ " + t("解析失败", "Parse failed") + (fErr != null ? ": " + fErr : ""));
                    notifyDone(false, t("解析失败", "Parse failed"));
                    return;
                }
                partitions.addAll(fParts);
                for (Part p : fParts) checked.add(p.name);   // 默认全选（对齐原版常用流程）
                runBtn.setEnabled(true);
                runBtn.setAlpha(1f);
                infoMeta.setText("✓ " + fParts.size() + t(" 个分区 · 共 ", " partitions · ")
                        + fmtSizeShort(totalSize(fParts)));
                status.setText("✓ " + fParts.size() + t(" 个分区，点下方按钮勾选提取", " partitions, tap button below"));
                status.setTextColor(pal.success);
                log("✓ " + t("解析完成", "Parsed") + " · " + fParts.size() + t(" 个分区", " partitions")
                        + " · " + fmtSizeShort(totalSize(fParts)));
                notifyDone(true, t("解析完成", "Parsed") + " · " + fParts.size() + t(" 个分区", " partitions"));
                showPartitionDialog();
            });
        });
    }

    private static long totalSize(List<Part> parts) {
        long s = 0;
        for (Part p : parts) s += p.size;
        return s;
    }

    /**
     * dna lpunpack --list 输出解析：「value|label(size)」。
     * value（| 前部分）才是 --partition 的合法取值；大小从 label 的 (465.3M) 提取。
     * dna 输出的 value 已是去槽位后缀的基名（odm_a → odm），直接使用。
     */
    private static final Pattern SIZE_P = Pattern.compile("\\(([\\d.]+)\\s*([KMGT]?)\\)");

    private List<Part> parseDnaList(String output) {
        List<Part> out = new ArrayList<>();
        for (String raw : output.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith(">") || line.startsWith("=") || line.startsWith("__")) continue;
            int bar = line.indexOf('|');
            String val, label;
            if (bar > 0) { val = line.substring(0, bar).trim(); label = line.substring(bar + 1).trim(); }
            else { val = line; label = line; }
            if (val.isEmpty()) continue;
            long size = 0;
            Matcher m = SIZE_P.matcher(label);
            if (m.find()) {
                double v = Double.parseDouble(m.group(1));
                String u = m.group(2);
                long mul = u.equals("K") ? 1024L : u.equals("M") ? 1048576L
                        : u.equals("G") ? 1073741824L : u.equals("T") ? 1099511627776L : 1L;
                size = (long) (v * mul);
            }
            out.add(new Part(val, size));
        }
        return out;
    }

    // ================= 分区选择弹窗 =================

    private void showPartitionDialog() {
        if (isFinishing() || isDestroyed()) return;
        if (partitions.isEmpty()) {
            toast(t("分区列表为空，请先解析", "Parse first"));
            return;
        }
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.setCancelable(true);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(14), dp(16), dp(12));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        boolean dark = com.mcai.ubuntudsu.ui.Ui.INSTANCE.isDark(this);
        bg.setColor(dark ? 0xF22A3546 : 0xF2e9f0f7);
        bg.setCornerRadius(dp(24));
        bg.setStroke(Math.max(1, dp(1)), dark ? 0x33FFFFFF : 0x66FFFFFF);
        panel.setBackground(bg);

        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        final Runnable[] render = new Runnable[1];

        TextView title = new TextView(this);
        title.setText("🧩 " + t("选择要提取的分区", "Select partitions to extract"));
        title.setTextSize(16);
        title.setTypeface(null, 1);
        title.setTextColor(pal.title);
        title.setPadding(dp(2), 0, 0, dp(6));
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        final TextView count = new TextView(this);
        count.setTextSize(12f);
        count.setTypeface(null, 1);
        count.setTextColor(pal.success);
        head.addView(count, new LinearLayout.LayoutParams(0, -2, 1f));
        Button allBtn = pillButton(t("全选", "All"), 12f, pal.success, dp(52), dp(30));
        allBtn.setOnClickListener(v -> {
            Haptics.perform(v);
            checked.clear();
            for (Part p : partitions) checked.add(p.name);
            render[0].run();
        });
        head.addView(allBtn, new LinearLayout.LayoutParams(dp(52), dp(30)));
        Button noneBtn = pillButton(t("清空", "None"), 12f, pal.danger, dp(52), dp(30));
        noneBtn.setOnClickListener(v -> {
            Haptics.perform(v);
            checked.clear();
            render[0].run();
        });
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(dp(52), dp(30));
        nLp.leftMargin = dp(6);
        head.addView(noneBtn, nLp);
        panel.addView(head, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        // v3.30.30：行高收紧 + 显示分区大小（解析直读时已知）
        render[0] = () -> {
            list.removeAllViews();
            for (final Part p : partitions) {
                final boolean on = checked.contains(p.name);
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(10), dp(7), dp(10), dp(7));
                android.graphics.drawable.GradientDrawable rb = new android.graphics.drawable.GradientDrawable();
                if (dark) {
                    rb.setColor(on ? 0x4D2f9c8f : 0x332A3546);
                    rb.setCornerRadius(dp(12));
                    rb.setStroke(Math.max(1, dp(1)), on ? pal.accent : 0x26FFFFFF);
                } else {
                    rb.setColor(on ? 0x3335A8C4 : 0x22FFFFFF);
                    rb.setCornerRadius(dp(12));
                    rb.setStroke(Math.max(1, dp(1)), on ? pal.accent : 0x33FFFFFF);
                }
                row.setBackground(rb);
                View dot = new View(this);
                android.graphics.drawable.GradientDrawable db = new android.graphics.drawable.GradientDrawable();
                db.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                if (on) { db.setColor(pal.accent); db.setStroke(Math.max(1, dp(1)), 0xB3FFFFFF); }
                else { db.setColor(0x00000000); db.setStroke(Math.max(1, dp(1)), dark ? 0x665a728f : 0x668fa1b8); }
                dot.setBackground(db);
                row.addView(dot, new LinearLayout.LayoutParams(dp(16), dp(16)));
                TextView nameView = new TextView(this);
                nameView.setText(p.name + ".img");
                nameView.setTextSize(13f);
                nameView.setTypeface(null, on ? 1 : 0);
                nameView.setTextColor(on ? pal.success : pal.title);
                nameView.setSingleLine(true);
                nameView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                nameView.setPadding(dp(10), 0, dp(6), 0);
                row.addView(nameView, new LinearLayout.LayoutParams(0, -2, 1f));
                TextView sizeView = new TextView(this);
                sizeView.setText(p.size > 0 ? fmtSize(p.size) : "");
                sizeView.setTextSize(11.5f);
                sizeView.setTypeface(null, 1);
                sizeView.setTextColor(pal.success);
                row.addView(sizeView, new LinearLayout.LayoutParams(-2, -2));
                row.setOnClickListener(v -> {
                    Haptics.perform(v);
                    if (checked.contains(p.name)) checked.remove(p.name);
                    else checked.add(p.name);
                    render[0].run();
                });
                LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(-1, -2);
                rLp.topMargin = dp(5);
                list.addView(row, rLp);
            }
            count.setText(t("已选", "Selected") + " " + checked.size() + "/" + partitions.size());
        };
        render[0].run();

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        Button cancel = pillButton(t("取消", "Cancel"), 13f, pal.subtitle, dp(72), dp(44));
        cancel.setOnClickListener(v -> { Haptics.perform(v); dialog.dismiss(); });
        btnRow.addView(cancel, new LinearLayout.LayoutParams(dp(72), dp(44)));
        Button ok = gradientButton("✓  " + t("确定", "Extract"), 0xFF2f9c8f, 0xFF1d6b46);
        ok.setOnClickListener(v -> {
            Haptics.perform(v);
            if (checked.isEmpty()) {
                toast(t("请先勾选要提取的分区", "Check partitions first"));
                return;
            }
            dialog.dismiss();
            extractSuper();
        });
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        okLp.leftMargin = dp(10);
        btnRow.addView(ok, okLp);
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(-1, -2);
        brLp.topMargin = dp(10);
        panel.addView(btnRow, brLp);

        dialog.setContentView(panel, new LinearLayout.LayoutParams(-1, dp(440)));
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        try {
            dialog.show();
            dialog.getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.92f), dp(440));
        } catch (Exception ignored) {
        }
    }

    // ================= 切换工程（v3.30.32：顶栏按钮） =================

    /** root 列 /sdcard/Linux-Dsu/PDNA 下所有工程（PDNA_ 前缀），弹窗单选；选择后直接解析其 super.img */
    private void showProjectDialog() {
        if (isFinishing() || isDestroyed()) return;
        List<String> projects = new ArrayList<>();
        try {
            com.topjohnwu.superuser.Shell.Result r = com.topjohnwu.superuser.Shell.cmd(
                    "ls -1 " + DnaTools.quote(DnaTools.WORK_ROOT) + " | grep '^PDNA_'").exec();
            for (String line : r.getOut()) {
                String n = line.trim();
                if (!n.isEmpty()) projects.add(n);
            }
        } catch (Exception ignored) {}
        java.util.Collections.sort(projects);
        if (projects.isEmpty()) {
            toast(t("未找到工程（/sdcard/Linux-Dsu/PDNA/PDNA_*）", "No projects found"));
            return;
        }

        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.setCancelable(true);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(14), dp(16), dp(12));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        boolean dark = com.mcai.ubuntudsu.ui.Ui.INSTANCE.isDark(this);
        bg.setColor(dark ? 0xF22A3546 : 0xF2e9f0f7);
        bg.setCornerRadius(dp(24));
        bg.setStroke(Math.max(1, dp(1)), dark ? 0x33FFFFFF : 0x66FFFFFF);
        panel.setBackground(bg);

        TextView title = new TextView(this);
        title.setText("📁 " + t("切换工程", "Switch Project"));
        title.setTextSize(16);
        title.setTypeface(null, 1);
        title.setTextColor(pal.title);
        title.setPadding(dp(2), 0, 0, dp(6));
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        for (final String p : projects) {
            final boolean current = p.equals(project);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            android.graphics.drawable.GradientDrawable rb = new android.graphics.drawable.GradientDrawable();
            if (dark) {
                rb.setColor(current ? 0x4D2f9c8f : 0x332A3546);
                rb.setCornerRadius(dp(12));
                rb.setStroke(Math.max(1, dp(1)), current ? 0x662f9c8f : 0x26FFFFFF);
            } else {
                rb.setColor(current ? 0x3335A8C4 : 0x22FFFFFF);
                rb.setCornerRadius(dp(12));
                rb.setStroke(Math.max(1, dp(1)), current ? pal.accent : 0x33FFFFFF);
            }
            row.setBackground(rb);
            View dot = new View(this);
            android.graphics.drawable.GradientDrawable db = new android.graphics.drawable.GradientDrawable();
            db.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            if (current) { db.setColor(pal.accent); db.setStroke(Math.max(1, dp(1)), 0xB3FFFFFF); }
            else { db.setColor(0x00000000); db.setStroke(Math.max(1, dp(1)), dark ? 0x665a728f : 0x668fa1b8); }
            dot.setBackground(db);
            row.addView(dot, new LinearLayout.LayoutParams(dp(14), dp(14)));
            TextView nameView = new TextView(this);
            nameView.setText(p.replaceFirst("^PDNA_", "") + (current ? " · " + t("当前", "current") : ""));
            nameView.setTextSize(13.5f);
            nameView.setTypeface(null, current ? 1 : 0);
            nameView.setTextColor(current ? pal.success : pal.title);
            nameView.setSingleLine(true);
            nameView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            nameView.setPadding(dp(10), 0, 0, 0);
            row.addView(nameView, new LinearLayout.LayoutParams(0, -2, 1f));
            row.setOnClickListener(v -> {
                Haptics.perform(v);
                dialog.dismiss();
                switchProject(p);
            });
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(-1, -2);
            rLp.topMargin = dp(5);
            list.addView(row, rLp);
        }

        Button cancel = pillButton(t("取消", "Cancel"), 13f, pal.subtitle, dp(72), dp(42));
        cancel.setOnClickListener(v -> { Haptics.perform(v); dialog.dismiss(); });
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(dp(72), dp(42));
        cLp.topMargin = dp(10);
        cLp.gravity = Gravity.CENTER_HORIZONTAL;
        panel.addView(cancel, cLp);

        dialog.setContentView(panel, new LinearLayout.LayoutParams(-1, dp(420)));
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        try {
            dialog.show();
            dialog.getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.86f), dp(420));
        } catch (Exception ignored) {
        }
    }

    private void switchProject(String p) {
        if (p.equals(project)) return;
        project = p;
        DnaTools.setCurrentProject(this, p);
        projectBtn.setText("📁 " + p.replaceFirst("^PDNA_", ""));
        String path = DnaTools.WORK_ROOT + "/" + p + "/super.img";
        partitions.clear();
        checked.clear();
        consoleText.setText("");
        if (new File(path).isFile()) {
            superPath = path;
            refreshInfoCard();
            parseSuper();
        } else {
            superPath = null;
            refreshInfoCard();
            runBtn.setEnabled(false);
            runBtn.setAlpha(0.5f);
            infoMeta.setText("✗ " + t("该工程无 super.img", "No super.img in this project"));
            infoMeta.setTextColor(pal.danger);
            status.setText("✗ " + t("该工程未找到 super.img", "No super.img in this project"));
            status.setTextColor(pal.danger);
            log("✗ " + path);
        }
    }

    private void refreshInfoCard() {
        File f = superPath != null ? new File(superPath) : null;
        infoName.setText(f != null
                ? "💽 " + f.getName() + (f.length() > 0 ? " · " + fmtSize(f.length()) : "")
                : "💽 super.img");
        infoPath.setText(superPath != null ? superPath : "-");
        infoMeta.setText("…");
        infoMeta.setTextColor(pal.success);
    }

    // ================= 提取 =================

    /** root 列目录 .img：name → {size, mtimeSec}（工程目录为 root 属主时 Java listFiles 拿不到） */
    private java.util.Map<String, long[]> listImgRoot(File dir) {
        java.util.Map<String, long[]> map = new java.util.HashMap<>();
        if (dir == null) return map;
        try {
            com.topjohnwu.superuser.Shell.Result r = com.topjohnwu.superuser.Shell.cmd(
                    "for f in " + DnaTools.quote(dir.getAbsolutePath()) + "/*.img; do "
                            + "[ -f \"$f\" ] && echo \"$(basename \"$f\") $(stat -c '%s %Y' \"$f\")\"; done").exec();
            for (String line : r.getOut()) {
                String[] sp = line.trim().split("[ \\t]+");
                if (sp.length >= 3) {
                    try {
                        map.put(sp[0], new long[]{Long.parseLong(sp[1]), Long.parseLong(sp[2])});
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (Exception ignored) {}
        if (map.isEmpty()) {   // 兜底：app 属主目录直接 Java 列
            File[] fs = dir.isDirectory() ? dir.listFiles() : null;
            if (fs != null) for (File f : fs) {
                String n = f.getName();
                if (f.isFile() && n.toLowerCase(Locale.ROOT).endsWith(".img"))
                    map.put(n, new long[]{f.length(), f.lastModified() / 1000});
            }
        }
        return map;
    }

    private void extractSuper() {
        if (superPath == null || superPath.isEmpty()) {
            toast(t("未找到 super.img", "No super.img"));
            return;
        }
        running.set(true);
        cancelFlag.set(false);
        runBtn.setText("■  " + t("取消", "Cancel"));
        reparseBtn.setEnabled(false);
        status.setText(t("正在提取 ...", "Extracting..."));
        status.setTextColor(pal.subtitle);
        showMarquee(true);
        final int totalParts = checked.size();
        notify(t("开始提取", "Extracting") + " " + totalParts + t(" 个分区", " partitions"),
                true, false, 0, totalParts);

        StringBuilder parts = new StringBuilder();
        for (Part p : partitions) {
            if (!checked.contains(p.name)) continue;
            if (parts.length() > 0) parts.append(",");
            parts.append(p.name);
        }
        // 原版 dna.xml：dna lpunpack --partition 'a,b' --delete 0 --auto 0 <super.img>
        // partition 取值 = 解析出的去槽位分区名（odm），非整行「odm|odm(465.3M)」
        final String command = "dna lpunpack"
                + " --partition " + DnaTools.quote(parts.toString())
                + " --delete 0 --auto 0 "
                + DnaTools.quote(superPath);
        final File outDir = new File(superPath).getParentFile();
        // v3.30.31：root 列目录做前后快照（工程目录 root 属主，Java listFiles 为空 → 此前误报「未检测到新镜像」）
        final long startSec = System.currentTimeMillis() / 1000 - 2;
        final java.util.Map<String, long[]> before = listImgRoot(outDir);
        log("$ " + command);
        log("▶ " + t("开始提取", "Extracting") + " " + checked.size() + t(" 个分区", " partitions") + " → " + outDir.getAbsolutePath());
        io.execute(() -> {
            // v3.30.31：每行日志即时同步状态栏与通知栏（不节流），按「开始提取：」推进进度
            final int[] started = {0};
            DnaTools.Result result = DnaTools.run(this, command,
                    line -> {
                        log(line);
                        String s = line.trim();
                        if (s.contains("开始提取") || s.toLowerCase(Locale.ROOT).contains("extracting")) {
                            started[0]++;
                            String pn = s.contains("：") ? s.substring(s.indexOf("：") + 1).trim()
                                    : (s.contains(":") ? s.substring(s.indexOf(":") + 1).trim() : s);
                            final int cur = Math.min(started[0], totalParts);
                            main.post(() -> status.setText("[" + cur + "/" + totalParts + "] " + pn));
                            notify("[" + cur + "/" + totalParts + "] " + pn, true, false, cur, totalParts);
                        } else if (!s.isEmpty()) {
                            notify(s, true, true, 0, 0);
                        }
                        return kotlin.Unit.INSTANCE;
                    },
                    () -> cancelFlag.get());
            // 结果核对（root）：输出目录新增/更新的 .img → 「odm.img (465.3M)」
            List<String> fresh = new ArrayList<>();
            java.util.Map<String, long[]> after = listImgRoot(outDir);
            for (java.util.Map.Entry<String, long[]> e : after.entrySet()) {
                String n = e.getKey();
                if (n.equalsIgnoreCase("super.img")) continue;
                long[] old = before.get(n);
                if (old == null || e.getValue()[1] >= startSec)
                    fresh.add(n + " (" + fmtSizeShort(e.getValue()[0]) + ")");
            }
            java.util.Collections.sort(fresh);
            // root 也列不到（极端权限）但 dna 明确报告完成 → 按所选分区列出（解析时已知大小）
            if (fresh.isEmpty() && result.getSuccess() && result.getOutput() != null
                    && (result.getOutput().contains("提取完成") || result.getOutput().contains("文件位于"))) {
                for (Part p : partitions)
                    if (checked.contains(p.name)) fresh.add(p.name + ".img (" + fmtSizeShort(p.size) + ")");
            }
            final List<String> fFresh = fresh;
            final boolean cancelled = cancelFlag.get();
            main.post(() -> {
                running.set(false);
                runBtn.setText("▶  " + t("选择分区并提取", "Select & Extract"));
                reparseBtn.setEnabled(true);
                showMarquee(false);
                if (cancelled) {
                    status.setText("■ " + t("已取消", "Cancelled"));
                    status.setTextColor(pal.danger);
                    log("■ " + t("已取消", "Cancelled"));
                    notifyDone(false, t("已取消", "Cancelled"));
                } else if (result.getSuccess() && !fFresh.isEmpty()) {
                    status.setText("✓ " + t("提取完成", "Done") + " · " + fFresh.size() + t(" 个镜像", " image(s)"));
                    status.setTextColor(pal.success);
                    log("✓ " + t("提取完成，文件位于", "Done, files at") + ": " + outDir.getAbsolutePath());
                    for (String f : fFresh) log("  ✓ " + f);
                    log("ℹ " + t("如需提取其他分区，点「🔄 重新解析」重新勾选即可", "Tap Re-parse to extract other partitions"));
                    notifyDone(true, t("提取完成", "Done") + " · " + fFresh.size() + t(" 个镜像", " image(s)"));
                    toast(t("提取完成", "Done"));
                } else if (result.getSuccess()) {
                    // dna 退出码 0 但没有新镜像 → 明确告警，不再假成功
                    status.setText("⚠ " + t("结束但未检测到新镜像", "Finished but no new images"));
                    status.setTextColor(pal.warning);
                    log("⚠ " + t("命令返回成功，但输出目录没有新增/更新的 .img", "exit 0 but no new .img in output dir"));
                    log("⚠ " + t("请截图日志反馈", "Please report the log above"));
                    notifyDone(false, t("未检测到新镜像", "No new images"));
                    toast(t("未检测到新镜像，请查看日志", "No new images, check log"));
                } else {
                    status.setText("✗ " + t("提取失败", "Failed"));
                    status.setTextColor(pal.danger);
                    log("✗ " + result.getMessage());
                    notifyDone(false, t("提取失败", "Failed"));
                    toast(t("提取失败", "Failed"));
                }
            });
        });
    }
}
