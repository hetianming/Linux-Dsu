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
import android.widget.CheckBox;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * DNA · 分解 bin 独立二级页（v3.30.22）。
 * 流程：选文件 → 🔍开始解析（root CLI / Java 直读 payload.bin / OTA zip）
 *      → 弹窗勾选分区（仅名称 + 大小，不显示哈希）→ 底部「确定」即开始提取，
 *        日志实时显示正在提取的 img 与进度（页面不再展开分区列表）。
 * v3.40.19 提取走 libpayload_extract.so（pie 可执行，root shell 直跑）：
 * root 直读输入（bin/zip 原路径）、root 直写输出工程，无 FUSE 权限障碍、零复制。
 */
public final class DnaBinActivity extends DnaBaseActivity {

    private static final int PICK_SOURCE_FILE = 3410;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService io =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "dna-bin");
                t.setDaemon(true);
                return t;
            });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);
    private final AtomicLong nativeTokenSeq = new AtomicLong(1);
    private volatile long activeNativeToken = -1L;

    // 数据状态
    private String project;                       // 当前工程（输出目标）
    private String binPath;                       // 选中的 payload.bin / zip 绝对路径
    private PayloadExtractor extractor;           // JNI 句柄（解析成功后保留供提取用）
    private String openInput;                     // 实际打开的路径（无直读权限时为 cache 兜底）
    private final List<PayloadExtractor.PartitionInfo> partitions = new ArrayList<>();
    private final Set<String> checked = new LinkedHashSet<>();

    // UI
    private TextView projectName, projectOut;
    private LinearLayout sourceList;
    private TextView sourceCount, sourceEmpty;
    private TextView status;
    private FrameLayout progressTrack;
    private View progressFill;
    private TextView consoleText;
    private ScrollView consoleScroll, pageScroll;
    private LinearLayout consoleCard;
    private android.app.Dialog consoleDialog;
    private Button parseBtn, runBtn;
    private CheckBox deleteSource;

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
        project = DnaTools.currentProject(this);
        // v3.30.31：通知栏实时同步（Android 13+ 需运行时通知授权）
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 3407);
        ensureNoteChannel();
        buildUi();
        refreshSources();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        cancelNote();
        if (consoleDialog != null && consoleDialog.isShowing()) consoleDialog.dismiss();
        consoleDialog = null;
        if (extractor != null) { try { extractor.close(); } catch (Exception ignored) {} }
    }

    // ================= 通知栏同步（v3.30.31：进度实时，不延迟） =================

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_SOURCE_FILE || resultCode != RESULT_OK || data == null) return;
        String path = data.getStringExtra(com.mcai.ubuntudsu.RootfsFilesActivity.RESULT_FILE_PATH);
        if (path == null || !path.startsWith("/")) return;
        binPath = path;
        renderSourceSelection();
        log("📥 " + path.substring(path.lastIndexOf('/') + 1));
    }

    private static final String NOTE_CHANNEL = "dna_tools_progress";
    private static final int NOTE_ID = 3407;

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
            android.app.Notification.Builder b = new android.app.Notification.Builder(this, NOTE_CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(t("分解 BIN", "Unpack BIN"))
                    .setContentText(text)
                    .setOngoing(ongoing)
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(!ongoing)
                    .setProgress(max, progress, indeterminate);
            Intent it = new Intent(this, DnaBinActivity.class);
            it.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            b.setContentIntent(android.app.PendingIntent.getActivity(this, NOTE_ID, it,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE));
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTE_ID, b.build());
        } catch (Exception ignored) {}
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

    // ================= UI 构建 =================

    private LinearLayout glassCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0x33FFFFFF);
        bg.setCornerRadius(dp(18));
        bg.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
        c.setBackground(bg);
        return c;
    }

    private void buildUi() {
        pal = com.mcai.ubuntudsu.ui.Ui.INSTANCE.dnaPalette(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackground(pal.bgDrawable());
        com.mcai.ubuntudsu.ui.Ui.INSTANCE.animateLiquidBackground(root);
        com.mcai.ubuntudsu.ui.Ui.INSTANCE.applyContentInsets(root, 8, 0);

        ScrollView page = new ScrollView(this);
        pageScroll = page;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(6), dp(16), dp(20));
        page.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        // ---- 标题栏 ----
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(t("DNA · 分解 bin", "DNA · Extract bin"));
        title.setTextSize(19);
        title.setTypeface(null, 1);
        title.setTextColor(pal.title);
        title.setPadding(dp(12), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView badge = new TextView(this);
        badge.setText("🧬 PDNA");
        badge.setTextSize(12f);
        badge.setTypeface(null, 1);
        badge.setTextColor(com.mcai.ubuntudsu.ui.Ui.INSTANCE.isDark(this) ? 0xFFFFFFFF : 0xFF000000);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(12), 0, dp(12), 0);
        android.graphics.drawable.GradientDrawable badgeBg = new android.graphics.drawable.GradientDrawable();
        badgeBg.setCornerRadius(dp(18));
        badgeBg.setColor(0x73eafff5);
        badgeBg.setStroke(Math.max(1, dp(1)), 0x802f9c8f);
        badge.setBackground(badgeBg);
        bar.addView(badge, new LinearLayout.LayoutParams(-2, dp(36)));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, -2);
        barLp.bottomMargin = dp(12);
        content.addView(bar, barLp);

        // ---- 流程提示条 ----
        TextView flow = new TextView(this);
        flow.setText("① " + t("选择文件", "Pick") + "  →  ② " + t("开始解析", "Parse")
                + "  →  ③ " + t("弹窗勾选", "Select") + "  →  ④ " + t("确定提取", "Extract"));
        flow.setTextSize(12.5f);
        flow.setTextColor(pal.success);
        flow.setTypeface(null, 1);
        flow.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable flowBg = new android.graphics.drawable.GradientDrawable();
        flowBg.setColor(0x26FFFFFF);
        flowBg.setCornerRadius(dp(14));
        flow.setBackground(flowBg);
        flow.setPadding(dp(10), dp(9), dp(10), dp(9));
        content.addView(flow, new LinearLayout.LayoutParams(-1, -2));

        // ---- 工程卡 ----
        LinearLayout projCard = glassCard();
        projCard.setOnClickListener(v -> { Haptics.perform(v); showProjectPicker(); });
        LinearLayout.LayoutParams pcLp = new LinearLayout.LayoutParams(-1, -2);
        pcLp.topMargin = dp(10);
        content.addView(projCard, pcLp);
        TextView projLabel = new TextView(this);
        projLabel.setText(t("输出工程（点击切换）", "Output project (tap to switch)"));
        projLabel.setTextSize(11.5f);
        projLabel.setTextColor(pal.subtitle);
        projCard.addView(projLabel, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout projRow = new LinearLayout(this);
        projRow.setOrientation(LinearLayout.HORIZONTAL);
        projRow.setGravity(Gravity.CENTER_VERTICAL);
        projectName = new TextView(this);
        projectName.setTextSize(15f);
        projectName.setTypeface(null, 1);
        projectName.setTextColor(pal.title);
        projectName.setSingleLine(true);
        projectName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        projRow.addView(projectName, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView arrow = new TextView(this);
        arrow.setText("▼");
        arrow.setTextSize(11f);
        arrow.setTextColor(pal.subtitle);
        projRow.addView(arrow, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams prLp = new LinearLayout.LayoutParams(-1, -2);
        prLp.topMargin = dp(3);
        projCard.addView(projRow, prLp);
        projectOut = new TextView(this);
        projectOut.setTextSize(11f);
        projectOut.setTextColor(pal.subtitle);
        projectOut.setSingleLine(true);
        projectOut.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams poLp = new LinearLayout.LayoutParams(-1, -2);
        poLp.topMargin = dp(2);
        projCard.addView(projectOut, poLp);

        // ---- 源文件卡 ----
        LinearLayout srcCard = glassCard();
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(-1, -2);
        scLp.topMargin = dp(10);
        content.addView(srcCard, scLp);
        LinearLayout srcHead = new LinearLayout(this);
        srcHead.setOrientation(LinearLayout.HORIZONTAL);
        srcHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView srcTitle = new TextView(this);
        srcTitle.setText(t("源文件", "Source"));
        srcTitle.setTextSize(14f);
        srcTitle.setTypeface(null, 1);
        srcTitle.setTextColor(pal.title);
        srcHead.addView(srcTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        sourceCount = new TextView(this);
        sourceCount.setTextSize(11.5f);
        sourceCount.setTextColor(pal.success);
        srcHead.addView(sourceCount, new LinearLayout.LayoutParams(-2, -2));
        Button browseBtn = pillButton("📂", 15, pal.accent, dp(38), dp(32));
        browseBtn.setOnClickListener(v -> {
            Haptics.perform(v);
            startActivityForResult(
                    com.mcai.ubuntudsu.RootfsFilesActivity.createPickIntent(
                            this, t("选择 payload.bin / OTA zip", "Pick payload.bin / OTA zip"),
                            new String[]{"payload.bin", ".zip", ".zip2"}),
                    PICK_SOURCE_FILE);
        });
        android.widget.LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(dp(38), dp(32));
        brLp.leftMargin = dp(8);
        srcHead.addView(browseBtn, brLp);
        srcCard.addView(srcHead, new LinearLayout.LayoutParams(-1, -2));

        sourceEmpty = new TextView(this);
        sourceEmpty.setText(t("工程内暂无 payload.bin / zip，点 📂 浏览选择文件", "No payload.bin / zip in project, tap 📂 to browse"));
        sourceEmpty.setTextSize(12f);
        sourceEmpty.setTextColor(pal.subtitle);
        sourceEmpty.setPadding(dp(2), dp(8), 0, dp(4));
        srcCard.addView(sourceEmpty, new LinearLayout.LayoutParams(-1, -2));

        sourceList = new LinearLayout(this);
        sourceList.setOrientation(LinearLayout.VERTICAL);
        srcCard.addView(sourceList, new LinearLayout.LayoutParams(-1, -2));

        // ---- 手动路径输入框已移除（v3.30.23：已有 📂 浏览按钮，无需再显示路径框） ----

        // ---- 解析按钮（v3.30.22：分区选择移入弹窗，页面不再展开列表） ----
        parseBtn = gradientButton("🔍  " + t("开始解析", "Parse"), new int[]{0xFF7C4DFF, 0xFF5633CC}, dp(16));
        parseBtn.setOnClickListener(v -> { Haptics.perform(v); parseFile(); });
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(-1, dp(46));
        pbLp.topMargin = dp(12);
        content.addView(parseBtn, pbLp);

        // ---- 选项 ----
        deleteSource = new CheckBox(this);
        deleteSource.setText(t("提取后删除源文件", "Delete source after extraction"));
        deleteSource.setTextSize(12.5f);
        deleteSource.setTextColor(pal.title);
        deleteSource.setPadding(dp(2), 0, 0, 0);
        LinearLayout.LayoutParams dsLp = new LinearLayout.LayoutParams(-1, -2);
        dsLp.topMargin = dp(10);
        content.addView(deleteSource, dsLp);

        // ---- 状态 + 进度 ----
        status = new TextView(this);
        status.setTextSize(12.5f);
        status.setTextColor(pal.subtitle);
        status.setPadding(dp(4), dp(10), dp(4), dp(2));
        content.addView(status, new LinearLayout.LayoutParams(-1, -2));

        progressTrack = new FrameLayout(this);
        progressTrack.setBackgroundResource(R.drawable.dna_progress_track);
        progressTrack.setVisibility(View.GONE);
        progressFill = new View(this);
        progressFill.setBackgroundResource(R.drawable.dna_progress_fill);
        progressTrack.addView(progressFill, new FrameLayout.LayoutParams(dp(84), android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(progressTrack, new LinearLayout.LayoutParams(-1, dp(12)));

        // ---- 提取入口（解析完成后弹窗选择，确定即提取；运行中为取消） ----
        runBtn = gradientButton("▶  " + t("选择分区并提取", "Select & Extract"), new int[]{0xFF2f9c8f, 0xFF1d6b46}, dp(18));
        runBtn.setOnClickListener(v -> {
            Haptics.perform(v);
            if (running.get()) {
                cancelFlag.set(true);
                long token = activeNativeToken;
                if (token >= 0L && extractor != null) {
                    try { extractor.cancelExtract(token); } catch (Throwable ignored) {}
                }
                log(t("正在取消 ...", "Cancelling..."));
                return;
            }
            if (extractor == null || partitions.isEmpty()) {
                toast(t("请先点「开始解析」", "Tap Parse first"));
                return;
            }
            showPartitionDialog();
        });
        LinearLayout.LayoutParams rbLp = new LinearLayout.LayoutParams(-1, dp(54));
        rbLp.topMargin = dp(10);
        rbLp.bottomMargin = dp(10);
        content.addView(runBtn, rbLp);
        // ---- 控制台（v3.41.14：执行任务时弹出小窗口，不再嵌入页面）----
        buildConsole();
        renderProject();
        log(t("提示：选择文件 → 开始解析 → 弹窗勾选分区 → 确定提取", "Tip: pick → parse → select → extract"));
    }

    /** 日志卡片 (v3.41.15:弹出小窗口；标题执行任务，右侧复制日志/清除日志/关闭窗 ✕) */
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
            Haptics.perform(v);
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("log", consoleText.getText()));
            toast(t("日志已复制", "Log copied"));
        });
        actions.addView(copy);
        Button clear = pillButton(t("清除日志", "Clear Log"), 11f, pal.danger, dp(64), dp(28));
        clear.setOnClickListener(v -> {
            Haptics.perform(v);
            consoleText.setText("");
        });
        android.widget.LinearLayout.LayoutParams clLp = new LinearLayout.LayoutParams(dp(64), dp(28));
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
        // v3.30.25 修复滑动：移除 setTextIsSelectable（它会接管触摸事件导致 ScrollView 无法滚动）
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

    private Button pillButton(String label, float size, int color, int w, int h) {
        Button b = new Button(this, null, 0);
        b.setText(label);
        b.setTextSize(size);
        b.setAllCaps(false);
        b.setTextColor(color);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, 0, 0, 0);
        boolean dark = com.mcai.ubuntudsu.ui.Ui.INSTANCE.isDark(this);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(dark ? 0x593A4355 : 0x59FFFFFF);
        bg.setCornerRadius(Math.min(w, h) / 2);
        bg.setStroke(Math.max(1, dp(1)), dark ? 0x80FFFFFF : 0x80FFFFFF);
        b.setBackground(bg);
        b.setStateListAnimator(null);
        return b;
    }

    private Button gradientButton(String label, int[] colors, int radius) {
        Button b = new Button(this, null, 0);
        b.setText(label);
        b.setTextSize(15f);
        b.setTypeface(null, 1);
        b.setAllCaps(false);
        b.setTextColor(android.graphics.Color.WHITE);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, 0, 0, 0);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TL_BR);
        bg.setColors(colors);
        bg.setCornerRadius(radius);
        bg.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
        b.setBackground(bg);
        b.setStateListAnimator(null);
        return b;
    }

    // ================= 数据加载 =================

    private void renderProject() {
        projectName.setText(project != null ? project : t("未选择工程", "No project"));
        projectOut.setText(project != null
                ? "➜ " + DnaTools.WORK_ROOT + "/" + project
                : t("点此选择要输出的工程", "Tap to pick an output project"));
    }

    /** 异步扫描工程内 payload.bin / zip（root 列目录带大小，不阻塞 UI） */
    private void refreshSources() {
        final String proj = project;
        io.execute(() -> {
            List<DnaTools.BrowseEntry> files = new ArrayList<>();
            if (proj != null) {
                for (DnaTools.BrowseEntry e : DnaTools.browseDir(DnaTools.WORK_ROOT + "/" + proj)) {
                    String n = e.getName().toLowerCase(Locale.ROOT);
                    if (e.isDir()) continue;
                    if (n.endsWith("payload.bin") || n.endsWith(".zip") || n.endsWith(".zip2"))
                        files.add(e);
                }
            }
            final List<DnaTools.BrowseEntry> result = files;
            main.post(() -> renderSources(result));
        });
    }

    private void renderSources(List<DnaTools.BrowseEntry> files) {
        sourceList.removeAllViews();
        sourceEmpty.setVisibility(files.isEmpty() ? View.VISIBLE : View.GONE);
        for (final DnaTools.BrowseEntry e : files) {
            final String fullPath = DnaTools.WORK_ROOT + "/" + project + "/" + e.getName();
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(10), dp(10), dp(10));
            android.graphics.drawable.GradientDrawable rb = new android.graphics.drawable.GradientDrawable();
            rb.setColor(0x22FFFFFF);
            rb.setCornerRadius(dp(14));
            rb.setStroke(Math.max(1, dp(1)), 0x33FFFFFF);
            row.setBackground(rb);
            row.setTag(fullPath);
            // 图标徽章
            FrameLayout icon = new FrameLayout(this);
            android.graphics.drawable.GradientDrawable ib = new android.graphics.drawable.GradientDrawable();
            ib.setCornerRadius(dp(11));
            ib.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TL_BR);
            ib.setColors(e.getName().toLowerCase(Locale.ROOT).endsWith("payload.bin")
                    ? new int[]{0xFF35A8C4, 0xFF0E7D95} : new int[]{0xFF8E6FC7, 0xFF2E86AB});
            icon.setBackground(ib);
            TextView ie = new TextView(this);
            ie.setText(e.getName().toLowerCase(Locale.ROOT).endsWith("payload.bin") ? "🧬" : "📦");
            ie.setTextSize(13);
            ie.setGravity(Gravity.CENTER);
            icon.addView(ie, new FrameLayout.LayoutParams(-1, -1));
            row.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(34)));
            // 名称 + 路径
            LinearLayout textBox = new LinearLayout(this);
            textBox.setOrientation(LinearLayout.VERTICAL);
            textBox.setPadding(dp(10), 0, dp(8), 0);
            TextView name = new TextView(this);
            name.setText(e.getName());
            name.setTextSize(13.5f);
            name.setTypeface(null, 1);
            name.setTextColor(pal.title);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            textBox.addView(name, new LinearLayout.LayoutParams(-1, -2));
            TextView path = new TextView(this);
            path.setText(fullPath);
            path.setTextSize(10f);
            path.setTextColor(pal.subtitle);
            path.setSingleLine(true);
            path.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            textBox.addView(path, new LinearLayout.LayoutParams(-1, -2));
            row.addView(textBox, new LinearLayout.LayoutParams(0, -2, 1f));
            // 大小 + 选中标记
            LinearLayout right = new LinearLayout(this);
            right.setOrientation(LinearLayout.VERTICAL);
            right.setGravity(Gravity.END);
            TextView size = new TextView(this);
            size.setText(fmtSize(e.getSize()));
            size.setTextSize(11.5f);
            size.setTypeface(null, 1);
            size.setTextColor(pal.success);
            right.addView(size, new LinearLayout.LayoutParams(-2, -2));
            TextView check = new TextView(this);
            check.setText("✓");
            check.setTextSize(14);
            check.setTypeface(null, 1);
            check.setTextColor(pal.success);
            check.setVisibility(View.GONE);
            right.addView(check, new LinearLayout.LayoutParams(-2, -2));
            row.addView(right, new LinearLayout.LayoutParams(-2, -2));
            row.setOnClickListener(v -> {
                Haptics.perform(v);
                // v3.30.23：点选再点取消（toggle）
                if (fullPath.equals(binPath)) {
                    binPath = null;
                    log("⊘ " + t("已取消选择", "Deselected") + " " + e.getName());
                } else {
                    binPath = fullPath;
                    log("📥 " + e.getName() + " · " + fmtSize(e.getSize()));
                }
                renderSourceSelection();
            });
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(-1, -2);
            rLp.topMargin = dp(6);
            sourceList.addView(row, rLp);
        }
        renderSourceSelection();
    }

    /** 刷新源文件行选中态 */
    private void renderSourceSelection() {
        for (int i = 0; i < sourceList.getChildCount(); i++) {
            View c = sourceList.getChildAt(i);
            if (!(c instanceof LinearLayout) || !(c.getTag() instanceof String)) continue;
            boolean on = c.getTag().equals(binPath);
            android.graphics.drawable.GradientDrawable rb = new android.graphics.drawable.GradientDrawable();
            rb.setColor(on ? 0x3335A8C4 : 0x22FFFFFF);
            rb.setCornerRadius(dp(14));
            rb.setStroke(Math.max(1, dp(1)), on ? pal.accent : 0x33FFFFFF);
            c.setBackground(rb);
            LinearLayout right = (LinearLayout) ((LinearLayout) c).getChildAt(2);
            right.getChildAt(1).setVisibility(on ? View.VISIBLE : View.GONE);
        }
        int total = sourceList.getChildCount();
        sourceCount.setText(total == 0 ? "" : " " + (binPath != null ? 1 : 0) + "/" + total);
    }

    // ================= 解析（payload.bin 快速解析 → APK 内置 Rust dumper） =================

    /** 解析 Rust payload-dumper 的列表输出：支持「system (1.5 GiB)」及表格格式。 */
    private static List<PayloadExtractor.PartitionInfo> parseDumperList(String output) {
        List<PayloadExtractor.PartitionInfo> out = new ArrayList<>();
        if (output == null) return out;
        for (String raw : output.replaceAll("\\u001B\\[[;\\d]*[ -/]*[@-~]", "").split("[\\r\\n]+")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("---") || line.startsWith("Payload ")
                    || line.startsWith("Partition Name") || line.startsWith("Name ")
                    || line.toLowerCase(Locale.ROOT).startsWith("partitions:")) continue;
            // Rust 版 --list 示例为「system (1.5 GiB)」；兼容旧表格「system   1.00 MB」。
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^([A-Za-z0-9_.\\-]+)\\s+(?:\\(([^()]*)\\)|(.+))$").matcher(line);
            if (!m.matches()) continue;
            String name = m.group(1);
            if (name.equalsIgnoreCase("Partition") || name.equalsIgnoreCase("Payload")
                    || name.equalsIgnoreCase("Version") || name.equalsIgnoreCase("Found")) continue;
            String sizeText = m.group(2) != null ? m.group(2).trim() : m.group(3).trim();
            if (!sizeText.equalsIgnoreCase("Unknown")
                    && !sizeText.matches("(?i)^[\\d,.]+\\s*(?:B|KB|KiB|MB|MiB|GB|GiB|TB|TiB|bytes?)$")) continue;
            out.add(new PayloadExtractor.PartitionInfo(name, readableToBytes(sizeText), null));
        }
        return out;
    }

    /** 「1.00 MB / 465.29 GB / Unknown」→ 字节数（1024 进位；Unknown → 0） */
    private static long readableToBytes(String s) {
        if (s == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^([\\d,.]+)\\s*(B|KB|KIB|MB|MIB|GB|GIB|TB|TIB|BYTES?)$", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(s.trim());
        if (!m.matches()) return 0;
        try {
            double v = Double.parseDouble(m.group(1).replace(",", ""));
            String u = m.group(2).toUpperCase(Locale.ROOT);
            long mul = 1;
            if (u.startsWith("T")) mul = 1L << 40;
            else if (u.startsWith("G")) mul = 1L << 30;
            else if (u.startsWith("M")) mul = 1L << 20;
            else if (u.startsWith("K")) mul = 1L << 10;
            return (long) (v * mul);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void parseFile() {
        if (running.get()) { toast(t("正在执行中", "Busy")); return; }
        if (binPath == null || binPath.isEmpty()) {
            toast(t("请先选择 payload.bin / OTA zip 文件", "Pick a payload.bin / OTA zip file first"));
            return;
        }
        final String path = binPath;
        log("🔍 " + t("开始解析", "Parse") + ": " + path);
        expandConsole();
        running.set(true);
        cancelFlag.set(false);
        parseBtn.setEnabled(false);
        status.setText(t("正在解析 ...", "Parsing..."));
        status.setTextColor(pal.subtitle);
        notify(t("正在解析 ...", "Parsing..."), true, true, 0, 0);
        io.execute(() -> {
            try {
                if (extractor != null) { try { extractor.close(); } catch (Exception ignored) {} extractor = null; }
                String input = path;
                log("… " + t("正在读取 payload", "Reading payload") + " ...");
                // 解析链：① 快速读取裸 payload.bin manifest；② APK 内置 Rust dumper 原地解析
                // payload.bin 或 ZIP（不下载/解压 DNA 工具链、不整包复制、不误用不存在的 so）。
                DnaTools.rootRelaxForApp(path,
                        msg -> { main.post(() -> log(msg)); return kotlin.Unit.INSTANCE; });
                List<PayloadExtractor.PartitionInfo> parts = PayloadExtractor.fastListPartitions(input);
                final boolean incremental = PayloadExtractor.fastIsIncremental(input);
                if (parts == null || parts.isEmpty()) {
                    log("… " + t("改用 payload_dumper 解析", "payload_dumper fallback") + " ...");
                    DnaTools.Result r = DnaTools.payloadListCli(this, path,
                            line -> kotlin.Unit.INSTANCE, () -> cancelFlag.get(), 120000);
                    parts = r.getSuccess() ? parseDumperList(r.getOutput()) : null;
                    if (parts == null || parts.isEmpty()) {
                        String detail = r.getOutput() == null ? "" : r.getOutput().trim();
                        if (detail.length() > 1200) detail = detail.substring(detail.length() - 1200);
                        throw new IllegalStateException(t("未能读取到分区列表（文件无 payload.bin、权限不足或列表格式不兼容）",
                                "No partitions found (missing payload.bin, access denied, or unsupported list format)")
                                + (r.getSuccess() ? "" : " · " + r.getMessage())
                                + (detail.isEmpty() ? "" : "\n" + detail));
                    }
                }
                // 后续提取通过 payloadExtractCli 完成；此对象只用于保持页面生命周期兼容。
                extractor = new PayloadExtractor();
                final String finalInput = input;
                final List<PayloadExtractor.PartitionInfo> fParts = parts;
                main.post(() -> {
                    openInput = finalInput;
                    partitions.clear();
                    checked.clear();
                    if (fParts != null) partitions.addAll(fParts);
                    if (incremental)
                        log("⚠ " + t("检测到增量（delta）包，请使用「分解增量包」功能", "Delta payload detected, use the Incremental page"));
                    log("✓ " + t("解析完成", "Parsed") + " · " + partitions.size()
                            + t(" 个分区，请在弹窗勾选要提取的 img", " partitions, select img in dialog"));
                    status.setText("✓ " + t("解析完成", "Parsed") + " · " + partitions.size() + t(" 个分区", " partitions"));
                    status.setTextColor(pal.success);
                    notifyDone(true, t("解析完成", "Parsed") + " · " + partitions.size() + " 个分区");
                    if (partitions.isEmpty()) {
                        status.setText("✗ " + t("未发现可提取分区", "No extractable partitions"));
                        status.setTextColor(pal.danger);
                        notifyDone(false, t("未发现可提取分区", "No extractable partitions"));
                    } else {
                        showPartitionDialog();
                    }
                });
            } catch (final Exception e) {
                final String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                main.post(() -> {
                    log("✗ " + t("解析失败", "Parse failed") + ": " + msg);
                    status.setText("✗ " + t("解析失败", "Parse failed"));
                    status.setTextColor(pal.danger);
                    notifyDone(false, t("解析失败", "Parse failed"));
                    toast(t("解析失败", "Parse failed"));
                });
            } finally {
                main.post(() -> { running.set(false); parseBtn.setEnabled(true); });
            }
        });
    }

    // ================= 分区选择弹窗（v3.30.22） =================

    /** 解析完成后弹窗勾选分区（仅名称 + 大小，不显示哈希），底部「确定」即开始提取 */
    private void showPartitionDialog() {
        // v3.30.25 修复 BadTokenException：解析完成回调时页面可能已退出，此时不能再弹窗
        if (isFinishing() || isDestroyed()) return;
        if (partitions.isEmpty()) {
            toast(t("请先点「开始解析」", "Tap Parse first"));
            return;
        }
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.setCancelable(true);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(16), dp(18), dp(14));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        boolean dark = com.mcai.ubuntudsu.ui.Ui.INSTANCE.isDark(this);
        bg.setColor(dark ? 0xF22A3546 : 0xF2e9f0f7);
        bg.setCornerRadius(dp(24));
        bg.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
        panel.setBackground(bg);

        // 列表容器 + 渲染器（头部按钮也复用）
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        final Runnable[] render = new Runnable[1];

        // 标题
        TextView title = new TextView(this);
        title.setText("🧬 " + t("选择要提取的分区", "Select partitions to extract"));
        title.setTextSize(16);
        title.setTypeface(null, 1);
        title.setTextColor(pal.title);
        title.setPadding(dp(2), 0, 0, dp(10));
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));

        // 头部仅显示计数；批量操作统一放到底部操作栏
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        final TextView count = new TextView(this);
        count.setTextSize(12f);
        count.setTypeface(null, 1);
        count.setTextColor(pal.success);
        head.addView(count, new LinearLayout.LayoutParams(0, -2, 1f));
        panel.addView(head, new LinearLayout.LayoutParams(-1, -2));

        // 可滚动分区列表：圆点勾选 + 名称 + 大小（不显示哈希）
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        render[0] = () -> {
            list.removeAllViews();
            for (final PayloadExtractor.PartitionInfo p : partitions) {
                final boolean on = checked.contains(p.getName());
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(11), dp(10), dp(10), dp(10));
                android.graphics.drawable.GradientDrawable rb = new android.graphics.drawable.GradientDrawable();
                if (dark) {
                    rb.setColor(on ? 0x4D2f9c8f : 0x332A3546);
                    rb.setCornerRadius(dp(14));
                    rb.setStroke(Math.max(1, dp(1)), on ? pal.accent : 0x26FFFFFF);
                } else {
                    rb.setColor(on ? 0x3335A8C4 : 0x22FFFFFF);
                    rb.setCornerRadius(dp(14));
                    rb.setStroke(Math.max(1, dp(1)), on ? pal.accent : 0x33FFFFFF);
                }
                row.setBackground(rb);
                // 勾选圆点
                View dot = new View(this);
                android.graphics.drawable.GradientDrawable db = new android.graphics.drawable.GradientDrawable();
                db.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                if (on) { db.setColor(pal.accent); db.setStroke(Math.max(1, dp(1)), 0xB3FFFFFF); }
                else { db.setColor(0x00000000); db.setStroke(Math.max(1, dp(1)), dark ? 0x665a728f : 0x668fa1b8); }
                dot.setBackground(db);
                row.addView(dot, new LinearLayout.LayoutParams(dp(18), dp(18)));
                // 名称
                TextView name = new TextView(this);
                name.setText(p.getName() + ".img");
                name.setTextSize(13.5f);
                name.setTypeface(null, on ? 1 : 0);
                name.setTextColor(on ? pal.success : pal.title);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                name.setPadding(dp(10), 0, dp(6), 0);
                row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
                // 大小
                TextView size = new TextView(this);
                size.setText(fmtSize(p.getSize()));
                size.setTextSize(11.5f);
                size.setTypeface(null, 1);
                size.setTextColor(pal.success);
                row.addView(size, new LinearLayout.LayoutParams(-2, -2));
                row.setOnClickListener(v -> {
                    Haptics.perform(v);
                    if (checked.contains(p.getName())) checked.remove(p.getName());
                    else checked.add(p.getName());
                    render[0].run();
                });
                LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(-1, -2);
                rLp.topMargin = dp(6);
                list.addView(row, rLp);
            }
            count.setText(t("已选", "Selected") + " " + checked.size() + "/" + partitions.size());
        };
        render[0].run();

        // 底部同排：全选、取消、清空、确定（确定即开始提取）
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        Button allBtn = pillButton(t("全选", "All"), 11.5f, pal.success, dp(52), dp(42));
        allBtn.setOnClickListener(v -> {
            Haptics.perform(v);
            checked.clear();
            for (PayloadExtractor.PartitionInfo p : partitions) checked.add(p.getName());
            render[0].run();
        });
        Button noneBtn = pillButton(t("清空", "None"), 11.5f, pal.danger, dp(52), dp(42));
        noneBtn.setOnClickListener(v -> {
            Haptics.perform(v);
            checked.clear();
            render[0].run();
        });
        Button cancel = pillButton(t("取消", "Cancel"), 12f, pal.subtitle, dp(64), dp(42));
        cancel.setOnClickListener(v -> { Haptics.perform(v); dialog.dismiss(); });
        Button ok = gradientButton("✓  " + t("确定", "Extract"), new int[]{0xFF2f9c8f, 0xFF1d6b46}, dp(16));
        ok.setOnClickListener(v -> {
            Haptics.perform(v);
            if (checked.isEmpty()) {
                toast(t("请先勾选要提取的分区", "Check partitions first"));
                return;
            }
            dialog.dismiss();
            extract();
        });
        Button[] bottomButtons = {allBtn, cancel, noneBtn, ok};
        float[] weights = {0.9f, 1.0f, 0.9f, 1.35f};
        for (int i = 0; i < bottomButtons.length; i++) {
            LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(0, dp(42), weights[i]);
            if (i > 0) buttonLp.leftMargin = dp(6);
            btnRow.addView(bottomButtons[i], buttonLp);
        }
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(-1, -2);
        brLp.topMargin = dp(12);
        panel.addView(btnRow, brLp);

        dialog.setContentView(panel, new LinearLayout.LayoutParams(-1, dp(500)));
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        try {
            dialog.show();
            dialog.getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.94f), dp(500));
        } catch (Exception e) {
            // v3.30.25：页面销毁竞态下静默放弃弹窗，不崩溃
        }
    }

    // ================= 提取（v3.30.36：payload_dumper root 二进制，彻底绕开 FUSE） =================

    private void extract() {
        if (project == null) {
            toast(t("请先选择输出工程", "Select an output project first"));
            showProjectPicker();
            return;
        }
        if (partitions.isEmpty() || openInput == null) {
            toast(t("请先点「开始解析」", "Tap Parse first"));
            parseFile();
            return;
        }
        if (checked.isEmpty()) {
            toast(t("请先勾选要提取的分区", "Check partitions to extract first"));
            return;
        }
        final List<String> ordered = new ArrayList<>();
        for (PayloadExtractor.PartitionInfo p : partitions)
            if (checked.contains(p.getName())) ordered.add(p.getName());
        final String outDir = DnaTools.WORK_ROOT + "/" + project;
        // v1.8.17：优先使用独立 Rust/JNI 路径，在每个分区内并行处理 payload 操作；
        // 解析/操作不兼容或 JNI 失败时安全回退到既有 root CLI。成功日志仅在任务真正结束后发出。
        log("▶ " + t("开始提取", "Extracting") + " " + ordered.size() + t(" 个分区 → ", " partition(s) → ") + project);
        expandConsole();
        running.set(true);
        cancelFlag.set(false);
        runBtn.setText("■  " + t("取消", "Cancel"));
        parseBtn.setEnabled(false);
        status.setText(t("正在提取 ...", "Extracting..."));
        status.setTextColor(pal.subtitle);

        io.execute(() -> {
            final long startMs = System.currentTimeMillis();
            final String rawInput = binPath != null ? binPath : openInput;
            // 兼容回退：一次性提交全部勾选分区，避免逐分区重启 CLI 并重复扫描 ZIP/manifest。
            // 旧逐分区调 N 次：每次进程冷启动 + 重扫 ZIP + 重解析 manifest，--threads 单分区无效；
            // 批量后 metadata 只解析一次，多分区并行解压。失败分区逐个单分区重试拿精确错误。
            int okCount = 0;
            String lastErr = null;
            final java.util.List<String> failed = new ArrayList<>();
            StringBuilder rmAll = new StringBuilder();
            for (String n : ordered)
                rmAll.append("rm -f ").append(DnaTools.quote(new File(outDir, n + ".img").getAbsolutePath())).append("; ");
            com.topjohnwu.superuser.Shell.cmd(rmAll + "true").exec();

            final String jniInput = DnaTools.resolveJniReadable(this, rawInput,
                    msg -> { main.post(() -> log(msg)); return kotlin.Unit.INSTANCE; });
            final boolean useNative = extractor != null && jniInput != null
                    && DnaTools.ensureAppWritable(outDir,
                    msg -> { main.post(() -> log(msg)); return kotlin.Unit.INSTANCE; });
            notify(t("正在并行提取", "Extracting in parallel") + " " + ordered.size()
                    + t(" 个分区", " partition(s)"), true, false, 0, 0);

            if (useNative) {
                main.post(() -> {
                    log("⚡ " + t("启动独立 JNI 分区内并行引擎", "Independent JNI intra-partition engine")
                            + " · " + Math.min(8, Math.max(1, Runtime.getRuntime().availableProcessors()))
                            + t(" 线程", " threads"));
                    status.setText("⏳ " + t("正在提取", "Extracting") + " · 0/" + ordered.size());
                });
                final PayloadExtractor activeExtractor = extractor;
                final int nativeThreads = Math.min(8, Math.max(1, Runtime.getRuntime().availableProcessors()));
                for (int i = 0; i < ordered.size(); i++) {
                    if (cancelFlag.get()) break;
                    final String n = ordered.get(i);
                    final int position = i + 1;
                    final long token = nativeTokenSeq.incrementAndGet();
                    activeNativeToken = token;
                    final File outFile = new File(outDir, n + ".img");
                    final java.util.concurrent.atomic.AtomicBoolean pollDone =
                            new java.util.concurrent.atomic.AtomicBoolean(false);
                    final Runnable progressPoll = new Runnable() {
                        @Override public void run() {
                            if (pollDone.get() || cancelFlag.get()) return;
                            android.util.Pair<Integer, Integer> progress = null;
                            try { progress = activeExtractor.getExtractProgress(token); } catch (Throwable ignored) {}
                            if (progress != null && progress.second != null && progress.second >= 0) {
                                final int percent = Math.min(100, progress.second / 10);
                                status.setText("⏳ " + t("正在提取", "Extracting") + " " + n + ".img · "
                                        + percent + "% · " + position + "/" + ordered.size());
                            }
                            if (!pollDone.get()) main.postDelayed(this, 500);
                        }
                    };
                    main.post(() -> {
                        log("⏳ " + t("并行提取", "Extracting") + " [" + position + "/" + ordered.size() + "] " + n + ".img");
                        status.setText("⏳ " + t("正在提取", "Extracting") + " " + n + ".img · 0% · "
                                + position + "/" + ordered.size());
                        main.postDelayed(progressPoll, 200);
                    });
                    try {
                        activeExtractor.extractPartition(jniInput, outDir, n, nativeThreads, false, token);
                        long size = outFile.isFile() ? outFile.length() : 0L;
                        if (size <= 0L) throw new IllegalStateException("JNI returned without a complete image file");
                        okCount++;
                        final long imageSize = size;
                        final int completed = okCount;
                        main.post(() -> {
                            log("✓ " + n + ".img (" + fmtSizeShort(imageSize) + ") " + t("提取完成", "extracted"));
                            status.setText("⏳ " + t("正在提取", "Extracting") + " · " + completed + "/" + ordered.size());
                        });
                    } catch (Throwable nativeError) {
                        com.topjohnwu.superuser.Shell.cmd("rm -f " + DnaTools.quote(outFile.getAbsolutePath())).exec();
                        if (cancelFlag.get()) break;
                        String reason = nativeError.getMessage();
                        if (reason == null) reason = nativeError.toString();
                        final String nativeReason = reason;
                        main.post(() -> log("… " + n + ".img " + t("切换兼容提取器", "falling back to compatible extractor")
                                + ": " + nativeReason));
                        try {
                            DnaTools.Result fallback = DnaTools.payloadExtractCli(this, rawInput, outDir, n,
                                    line -> { if (line != null && !line.trim().isEmpty()) main.post(() -> log("  " + line.trim())); return kotlin.Unit.INSTANCE; },
                                    () -> cancelFlag.get(), 30 * 60_000L);
                            long size = fallback.getSuccess() && outFile.isFile() ? outFile.length() : 0L;
                            if (size > 0L) {
                                okCount++;
                                final long imageSize = size;
                                main.post(() -> log("✓ " + n + ".img (" + fmtSizeShort(imageSize) + ") " + t("提取完成", "extracted")));
                            } else {
                                lastErr = DnaTools.briefOf(fallback);
                                final String message = lastErr;
                                main.post(() -> log("✗ " + n + ".img " + t("提取失败", "failed") + ": " + message));
                            }
                        } catch (Throwable fallbackError) {
                            lastErr = fallbackError.getMessage() != null ? fallbackError.getMessage() : fallbackError.toString();
                            final String message = lastErr;
                            main.post(() -> log("✗ " + n + ".img " + t("提取失败", "failed") + ": " + message));
                        }
                    } finally {
                        activeNativeToken = -1L;
                        pollDone.set(true);
                        main.post(() -> main.removeCallbacks(progressPoll));
                    }
                }
            } else {
            main.post(() -> {
                log("⏳ " + t("正在并行提取", "Extracting in parallel") + " " + ordered.size()
                        + t(" 个分区…", " partition(s)..."));
                status.setText("⏳ " + t("正在提取", "Extracting") + " · 0/" + ordered.size());
            });

            // root shell 监控 dumper 的 /proc/PID/fd；单个镜像文件关闭即实时回报完成。
            final Set<String> liveLogged = java.util.concurrent.ConcurrentHashMap.newKeySet();
            final java.util.concurrent.atomic.AtomicInteger liveDone = new java.util.concurrent.atomic.AtomicInteger(0);

            // 进度条刷新行不进日志，只解析 x/y 刷新状态栏
            final java.util.regex.Matcher[] hold = new java.util.regex.Matcher[1];
            DnaTools.Result r = DnaTools.payloadExtractCli(this, rawInput, outDir,
                    String.join(",", ordered),
                    line -> {
                        String s = line == null ? "" : line;
                        if (s.startsWith("__DNA_IMAGE_DONE__")) {
                            String[] fields = s.substring("__DNA_IMAGE_DONE__".length()).split("\\|", 2);
                            if (fields.length == 2 && fields[0].endsWith(".img")) {
                                String name = fields[0].substring(0, fields[0].length() - 4);
                                long size = 0;
                                try { size = Long.parseLong(fields[1]); } catch (NumberFormatException ignored) {}
                                if (size > 0 && ordered.contains(name) && liveLogged.add(name)) {
                                    final long imageSize = size;
                                    int done = liveDone.incrementAndGet();
                                    main.post(() -> {
                                        log("✓ " + name + ".img (" + fmtSizeShort(imageSize) + ") " + t("提取完成", "extracted"));
                                        status.setText("⏳ " + t("正在提取", "Extracting") + " · " + done + "/" + ordered.size());
                                    });
                                }
                            }
                            return kotlin.Unit.INSTANCE;
                        }
                        java.util.regex.Matcher m = java.util.regex.Pattern
                                .compile("(\\d+)\\s*/\\s*(\\d+)").matcher(s);
                        if (m.find()) hold[0] = m;
                        if (s.indexOf('█') >= 0 || s.indexOf('░') >= 0 || s.indexOf('▓') >= 0)
                            return kotlin.Unit.INSTANCE;
                        String t2 = s.trim();
                        if (!t2.isEmpty() && hold[0] == null) {
                            final String fl = t2;
                            main.post(() -> log("  " + fl));
                        }
                        return kotlin.Unit.INSTANCE;
                    },
                    () -> cancelFlag.get(), 30 * 60_000L);
            if (cancelFlag.get()) {
                main.post(() -> {
                    running.set(false);
                    parseBtn.setEnabled(true);
                    runBtn.setText("▶  " + t("选择分区并提取", "Select & Extract"));
                    log("■ " + t("已取消", "Cancelled"));
                    status.setText("■ " + t("已取消", "Cancelled"));
                    status.setTextColor(0xffa33b3b);
                    notifyDone(false, t("已取消", "Cancelled"));
                });
                return;
            }
            // 按落盘文件统计成功；失败的逐个单分区重跑一次拿精确错误（正常全成功零开销）
            for (int i = 0; i < ordered.size(); i++) {
                final String n = ordered.get(i);
                final File outFile = new File(outDir, n + ".img");
                long sz = outFile.isFile() ? outFile.length() : 0;
                if (sz > 0) {
                    okCount++;
                    if (liveLogged.add(n)) {
                        final long size = sz;
                        main.post(() -> log("✓ " + n + ".img (" + fmtSizeShort(size) + ") " + t("提取完成", "extracted")));
                    }
                } else {
                    failed.add(n);
                }
            }
            for (int i = 0; i < failed.size(); i++) {
                final String n = failed.get(i);
                main.post(() -> log("⏳ " + t("重试", "Retry") + " " + n + ".img"));
                try {
                    DnaTools.Result rr = DnaTools.payloadExtractCli(this, rawInput, outDir, n,
                            null, () -> cancelFlag.get(), 10 * 60_000L);
                    long sz = rr.getSuccess() ? new File(outDir, n + ".img").length() : 0;
                    if (sz > 0) {
                        okCount++;
                        final long size = sz;
                        main.post(() -> log("✓ " + n + ".img (" + fmtSizeShort(size) + ") " + t("提取完成", "extracted")));
                    } else {
                        String brief = DnaTools.briefOf(rr);
                        lastErr = brief;
                        final String msg = brief;
                        main.post(() -> {
                            log("✗ " + n + ".img " + t("提取失败", "failed") + ": " + msg);
                            if (msg.contains("ifferential") || msg.toLowerCase(Locale.ROOT).contains("incremental")
                                    || msg.contains("source")) {
                                log("  " + t("提示：增量包请用「分解增量包」", "Hint: use Incremental unpack"));
                            }
                        });
                    }
                } catch (Throwable e) {
                    String m = e.getMessage();
                    if (m == null) m = e.toString();
                    lastErr = m;
                    final String msg = m;
                    main.post(() -> log("✗ " + n + ".img " + t("提取失败", "failed") + ": " + msg));
                }
            }
            }
            final boolean cancelled = cancelFlag.get();
            final int ok = okCount;
            final String err = lastErr;
            final long elapsed = (System.currentTimeMillis() - startMs) / 1000;
            main.post(() -> {
                running.set(false);
                parseBtn.setEnabled(true);
                runBtn.setText("▶  " + t("选择分区并提取", "Select & Extract"));
                if (cancelled) {
                    log("■ " + t("已取消", "Cancelled"));
                    status.setText("■ " + t("已取消", "Cancelled"));
                    status.setTextColor(pal.danger);
                    notifyDone(false, t("已取消", "Cancelled"));
                } else if (ok == ordered.size()) {
                    log("✓ " + t("提取完成，文件位于", "Extraction done, files at") + ": " + outDir);
                    log("ℹ " + t("耗时", "Time") + " " + elapsed + "s · " + ok + t(" 个镜像", " image(s)"));
                    status.setText("✓ " + t("提取完成", "Done") + " · " + ok);
                    status.setTextColor(pal.success);
                    notifyDone(true, t("提取完成", "Done") + " · " + ok + t(" 个镜像", " image(s)"));
                    toast(t("提取完成", "Done"));
                    if (deleteSource != null && deleteSource.isChecked() && binPath != null) {
                        com.topjohnwu.superuser.Shell.cmd("rm -f " + DnaTools.quote(binPath)).exec();
                        log(t("已删除源文件", "Source deleted") + ": " + binPath);
                    }
                } else if (ok > 0) {
                    log("⚠ " + t("部分分区提取失败", "Some partitions failed") + ": "
                            + (ordered.size() - ok) + "/" + ordered.size());
                    status.setText("⚠ " + t("部分提取完成", "Partial") + " · " + ok + "/" + ordered.size());
                    status.setTextColor(pal.warning);
                    notifyDone(false, t("部分分区提取失败", "Some partitions failed") + " "
                            + (ordered.size() - ok) + "/" + ordered.size());
                } else {
                    log("✗ " + t("提取失败", "Extraction failed") + ": " + (err != null ? err : "unknown"));
                    status.setText("✗ " + t("提取失败", "Failed"));
                    status.setTextColor(pal.danger);
                    notifyDone(false, t("提取失败", "Failed"));
                    toast(t("提取失败", "Failed"));
                }
                refreshSources();
            });
        });
    }

    /** dna 风格短格式：465.3M / 9.1G */
    private String fmtSizeShort(long bytes) {
        if (bytes < 1024) return bytes + "B";
        double v = bytes / 1024.0;
        String[] u = {"K", "M", "G", "T"};
        int i = 0;
        while (v >= 1024 && i < 3) { v /= 1024; i++; }
        return String.format(Locale.US, "%.1f%s", v, u[i]);
    }

    // ================= 工程 / 工具 =================

    private void showProjectPicker() {
        // v3.30.25：页面销毁后不弹窗
        if (isFinishing() || isDestroyed()) return;
        List<String> projects = DnaTools.listProjects();
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.setCancelable(true);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(16), dp(18), dp(16));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        boolean dark = com.mcai.ubuntudsu.ui.Ui.INSTANCE.isDark(this);
        bg.setColor(dark ? 0xF22A3546 : 0xF2e9f0f7);
        bg.setCornerRadius(dp(24));
        bg.setStroke(Math.max(1, dp(1)), dark ? 0x33FFFFFF : 0x66FFFFFF);
        panel.setBackground(bg);
        TextView title = new TextView(this);
        title.setText(t("选择工程", "Select project"));
        title.setTextSize(16);
        title.setTypeface(null, 1);
        title.setTextColor(pal.title);
        title.setPadding(0, 0, 0, dp(10));
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));
        ScrollView ls = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ls.addView(list, new ScrollView.LayoutParams(-1, -2));
        if (projects.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(t("暂无工程，请先在 DNA 页新建工程", "No projects yet. Create one on DNA page"));
            empty.setTextSize(13);
            empty.setTextColor(pal.subtitle);
            empty.setPadding(0, dp(8), 0, dp(8));
            list.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        }
        for (final String name : projects) {
            boolean cur = name.equals(project);
            TextView row = new TextView(this);
            row.setText((cur ? "● " : "○ ") + name);
            row.setTextSize(14);
            row.setTextColor(cur ? pal.success : pal.title);
            row.setSingleLine(true);
            row.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(13), dp(12), dp(13));
            android.graphics.drawable.GradientDrawable rb = new android.graphics.drawable.GradientDrawable();
            if (dark) {
                rb.setColor(cur ? 0x4D2f9c8f : 0x332A3546);
                rb.setCornerRadius(dp(14));
                rb.setStroke(Math.max(1, dp(1)), cur ? 0x662f9c8f : 0x26FFFFFF);
            } else {
                rb.setColor(cur ? 0x332f9c8f : 0x22FFFFFF);
                rb.setCornerRadius(dp(14));
                rb.setStroke(Math.max(1, dp(1)), cur ? 0x662f9c8f : 0x33FFFFFF);
            }
            row.setBackground(rb);
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(-1, -2);
            rLp.bottomMargin = dp(6);
            list.addView(row, rLp);
            row.setOnClickListener(v -> {
                Haptics.perform(v);
                project = name;
                DnaTools.setCurrentProject(this, name);
                dialog.dismiss();
                renderProject();
                binPath = null;
                refreshSources();
                partitions.clear();
                checked.clear();
                log(t("已切换工程", "Project switched") + ": " + name);
            });
        }
        panel.addView(ls, new LinearLayout.LayoutParams(-1, 0, 1f));
        Button close = pillButton(t("关闭", "Close"), 13f, pal.accent, -1, dp(44));
        close.setOnClickListener(v -> dialog.dismiss());
        panel.addView(close, new LinearLayout.LayoutParams(-1, dp(44)));
        dialog.setContentView(panel, new LinearLayout.LayoutParams(-1, dp(460)));
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        try {
            dialog.show();
            dialog.getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.94f), dp(460));
        } catch (Exception e) {
            // v3.30.25：页面销毁竞态下静默放弃弹窗，不崩溃
        }
    }

    private void log(String line) {
        main.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            appendConsole(line);
        });
    }

    private void appendConsole(String line) {
        if (isFinishing() || isDestroyed() || consoleText == null || consoleScroll == null) return;
        consoleText.append(line + "\n");
        scrollConsoleToBottom();
    }

    private void scrollConsoleToBottom() {
        if (consoleText == null || consoleScroll == null) return;
        consoleText.requestLayout();
        consoleText.post(() -> {
            if (isFinishing() || isDestroyed() || consoleScroll == null) return;
            consoleScroll.fullScroll(View.FOCUS_DOWN);
        });
    }

    /** 大小格式化：整数值省小数（392 KB / 8 MB / 1.5 GB） */
    private static String fmtSize(long b) {
        if (b < 1024) return b + " B";
        double kb = b / 1024.0;
        if (kb < 1024) return kb < 10 && kb != Math.floor(kb)
                ? String.format(Locale.US, "%.1f KB", kb) : String.format(Locale.US, "%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return mb < 10 && mb != Math.floor(mb)
                ? String.format(Locale.US, "%.1f MB", mb) : String.format(Locale.US, "%.0f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }
}
