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

/**
 * DNA · 分解增量包独立二级页（v3.30.36）。
 * 对齐原版 DNA incremental.sh：增量（delta）OTA 只含与上一版的差异，
 * 需要旧版本完整包提取出的镜像目录，payload_dumper 自动校验旧分区哈希 →
 * 应用 delta 补丁 → 生成新镜像。
 * 全程 root 二进制链路（libpayload_dumper.so）：解析 --list、提取 --source-dir，
 * 无 FUSE 权限问题；进度按输出文件字节数实时推进（页面 + 通知栏同步）。
 */
public final class DnaIncrementalActivity extends DnaBaseActivity {

    private static final int PICK_SOURCE_FILE = 3411;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService io =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "dna-inc");
                t.setDaemon(true);
                return t;
            });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);

    private static final String NOTE_CHANNEL = "dna_inc_channel";
    private static final int NOTE_ID = 4021;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_SOURCE_FILE || resultCode != RESULT_OK || data == null) return;
        String path = data.getStringExtra(com.mcai.ubuntudsu.RootfsFilesActivity.RESULT_FILE_PATH);
        if (path == null || !path.startsWith("/")) return;
        binPath = path;
        renderSources(null);
        log("📦 " + new File(path).getName());
    }

    // 数据
    private String project;          // 输出工程
    private String binPath;          // 增量 payload.bin / OTA zip
    private String incDir;           // 旧镜像目录
    private final List<PayloadExtractor.PartitionInfo> partitions = new ArrayList<>();
    private final Set<String> checked = new LinkedHashSet<>();

    // UI
    private TextView projectName, projectOut;
    private LinearLayout sourceList;
    private TextView sourceEmpty, incDirText, status;
    private FrameLayout progressTrack;
    private View progressFill;
    private TextView consoleText;
    private LinearLayout consoleCard;
    private ScrollView consoleScroll;
    private android.app.Dialog consoleDialog;
    private Button parseBtn, runBtn, incPickBtn;

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
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0x00000000);
        com.mcai.ubuntudsu.ui.Ui.INSTANCE.enableEdgeToEdge(this, getWindow().getDecorView());
        project = DnaTools.currentProject(this);
        createNoteChannel();
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
    }

    // ================= 通知 =================

    private void createNoteChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    NOTE_CHANNEL, "DNA 增量分解进度", android.app.NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("显示增量包分解任务实时状态");
            channel.setShowBadge(false);
            getSystemService(android.app.NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private void notify(String text, boolean ongoing, boolean indeterminate, int progress, int max) {
        try {
            android.app.Notification.Builder b = new android.app.Notification.Builder(this, NOTE_CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(t("分解增量包", "Incremental unpack"))
                    .setContentText(text)
                    .setOngoing(ongoing)
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(!ongoing)
                    .setProgress(max, progress, indeterminate);
            Intent it = new Intent(this, DnaIncrementalActivity.class);
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

    // ================= UI =================

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
        bg.setCornerRadius(Math.min(Math.max(w, 1), Math.max(h, 1)) / 2);
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

    private void buildUi() {
        pal = com.mcai.ubuntudsu.ui.Ui.INSTANCE.dnaPalette(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackground(pal.bgDrawable());
        com.mcai.ubuntudsu.ui.Ui.INSTANCE.animateLiquidBackground(root);
        com.mcai.ubuntudsu.ui.Ui.INSTANCE.applyContentInsets(root, 8, 0);

        ScrollView page = new ScrollView(this);
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
        title.setText(t("DNA · 分解增量包", "DNA · Incremental"));
        title.setTextSize(19);
        title.setTypeface(null, 1);
        title.setTextColor(pal.title);
        title.setPadding(dp(12), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView badge = new TextView(this);
        badge.setText("⚡ PDNA");
        badge.setTextSize(12f);
        badge.setTypeface(null, 1);
        badge.setTextColor(pal.warning);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(12), 0, dp(12), 0);
        android.graphics.drawable.GradientDrawable badgeBg = new android.graphics.drawable.GradientDrawable();
        badgeBg.setCornerRadius(dp(18));
        badgeBg.setColor(0x73fff4e0);
        badgeBg.setStroke(Math.max(1, dp(1)), 0x80c8963c);
        badge.setBackground(badgeBg);
        bar.addView(badge, new LinearLayout.LayoutParams(-2, dp(36)));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, -2);
        barLp.bottomMargin = dp(12);
        content.addView(bar, barLp);

        // ---- 流程提示条 ----
        TextView flow = new TextView(this);
        flow.setText("① " + t("选增量包", "Delta pkg") + "  →  ② " + t("旧镜像目录", "Old imgs")
                + "  →  ③ " + t("解析勾选", "Parse") + "  →  ④ " + t("提取", "Extract"));
        flow.setTextSize(12.5f);
        flow.setTextColor(0xffC08A2D);
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
        projLabel.setText("📤 " + t("输出工程（点击切换）", "Output project (tap to switch)"));
        projLabel.setTextSize(11.5f);
        projLabel.setTextColor(pal.subtitle);
        projCard.addView(projLabel, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout projRow = new LinearLayout(this);
        projRow.setOrientation(LinearLayout.HORIZONTAL);
        projRow.setGravity(Gravity.CENTER_VERTICAL);
        projectName = new TextView(this);
        projectName.setTextSize(15f);
        projectName.setTypeface(null, 1);
        projectName.setTextColor(0xff17334f);
        projectName.setSingleLine(true);
        projectName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        projRow.addView(projectName, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView arrow = new TextView(this);
        arrow.setText("▼");
        arrow.setTextSize(11f);
        arrow.setTextColor(pal.subtitle);
        projRow.addView(arrow, new LinearLayout.LayoutParams(-2, -2));
        projCard.addView(projRow, new LinearLayout.LayoutParams(-1, -2));
        projectOut = new TextView(this);
        projectOut.setTextSize(11f);
        projectOut.setTextColor(pal.subtitle);
        projectOut.setSingleLine(true);
        projectOut.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        projCard.addView(projectOut, new LinearLayout.LayoutParams(-1, -2));
        renderProject();

        // ---- 增量包卡 ----
        LinearLayout srcCard = glassCard();
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(-1, -2);
        scLp.topMargin = dp(10);
        content.addView(srcCard, scLp);
        LinearLayout srcHead = new LinearLayout(this);
        srcHead.setOrientation(LinearLayout.HORIZONTAL);
        srcHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView srcTitle = new TextView(this);
        srcTitle.setText("📦 " + t("增量包（payload.bin / OTA zip）", "Delta package (payload.bin / OTA zip)"));
        srcTitle.setTextSize(14f);
        srcTitle.setTypeface(null, 1);
        srcTitle.setTextColor(0xff17334f);
        srcHead.addView(srcTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        Button browseBtn = pillButton("📂", 15, pal.accent, dp(38), dp(32));
        browseBtn.setOnClickListener(v -> {
            Haptics.perform(v);
            startActivityForResult(
                    com.mcai.ubuntudsu.RootfsFilesActivity.createPickIntent(
                            this, t("选择增量 payload.bin / OTA zip", "Pick delta payload.bin / OTA zip"),
                            new String[]{"payload.bin", ".zip", ".zip2"}),
                    PICK_SOURCE_FILE);
        });
        android.widget.LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(dp(38), dp(32));
        brLp.leftMargin = dp(8);
        srcHead.addView(browseBtn, brLp);
        srcCard.addView(srcHead, new LinearLayout.LayoutParams(-1, -2));

        sourceEmpty = new TextView(this);
        sourceEmpty.setText(t("工程内暂无 payload.bin / zip，点 📂 浏览选择", "No payload.bin / zip in project, tap 📂 to browse"));
        sourceEmpty.setTextSize(12f);
        sourceEmpty.setTextColor(pal.subtitle);
        sourceEmpty.setPadding(dp(2), dp(8), 0, dp(4));
        srcCard.addView(sourceEmpty, new LinearLayout.LayoutParams(-1, -2));
        sourceList = new LinearLayout(this);
        sourceList.setOrientation(LinearLayout.VERTICAL);
        srcCard.addView(sourceList, new LinearLayout.LayoutParams(-1, -2));

        // ---- 旧镜像目录卡 ----
        LinearLayout dirCard = glassCard();
        LinearLayout.LayoutParams dcLp = new LinearLayout.LayoutParams(-1, -2);
        dcLp.topMargin = dp(10);
        content.addView(dirCard, dcLp);
        TextView dirLabel = new TextView(this);
        dirLabel.setText("🗂 " + t("旧镜像目录（上一版完整包提取的 img 所在目录）", "Old images dir (extracted from the previous full OTA)"));
        dirLabel.setTextSize(11.5f);
        dirLabel.setTextColor(pal.subtitle);
        dirCard.addView(dirLabel, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout dirRow = new LinearLayout(this);
        dirRow.setOrientation(LinearLayout.HORIZONTAL);
        dirRow.setGravity(Gravity.CENTER_VERTICAL);
        incPickBtn = pillButton("📂 " + t("选择目录", "Pick dir"), 13f, pal.accent, dp(96), dp(38));
        incPickBtn.setOnClickListener(v -> { Haptics.perform(v); showIncDirPicker(); });
        dirRow.addView(incPickBtn, new LinearLayout.LayoutParams(dp(96), dp(38)));
        incDirText = new TextView(this);
        incDirText.setTextSize(12f);
        incDirText.setTextColor(pal.accent);
        incDirText.setSingleLine(true);
        incDirText.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        incDirText.setPadding(dp(10), 0, 0, 0);
        incDirText.setText(t("未选择", "Not set"));
        dirRow.addView(incDirText, new LinearLayout.LayoutParams(0, -2, 1f));
        android.widget.LinearLayout.LayoutParams drLp = new LinearLayout.LayoutParams(-1, dp(38));
        drLp.topMargin = dp(6);
        dirCard.addView(dirRow, drLp);

        // ---- 解析按钮 ----
        parseBtn = gradientButton("🔍  " + t("开始解析", "Parse"), new int[]{0xFF7C4DFF, 0xFF5633CC}, dp(16));
        parseBtn.setOnClickListener(v -> { Haptics.perform(v); parseFile(); });
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(-1, dp(46));
        pbLp.topMargin = dp(12);
        content.addView(parseBtn, pbLp);

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

        // ---- 提取按钮 ----
        runBtn = gradientButton("⚡  " + t("选择分区并提取", "Select & Extract"), new int[]{0xFFE08A39, 0xFFB85C10}, dp(18));
        runBtn.setOnClickListener(v -> {
            Haptics.perform(v);
            if (running.get()) { cancelFlag.set(true); log(t("正在取消 ...", "Cancelling...")); return; }
            if (partitions.isEmpty()) {
                toast(t("请先点「开始解析」", "Tap Parse first"));
                return;
            }
            showPartitionDialog();
        });
        LinearLayout.LayoutParams rbLp = new LinearLayout.LayoutParams(-1, dp(54));
        rbLp.topMargin = dp(10);
        content.addView(runBtn, rbLp);

        // ---- 控制台（v3.41.14：执行任务时弹出小窗口，不再嵌入页面）----
        buildConsole();

        log(t("提示：增量包仅含与上一版的差异，需提供旧版本完整包提取出的镜像目录", "Note: delta OTA needs old images from the previous full OTA"));
    }

    // ================= 数据渲染 =================

    private void renderProject() {
        projectName.setText(project != null ? project : t("未选择工程", "No project"));
        projectOut.setText(project != null
                ? "➜ " + DnaTools.WORK_ROOT + "/" + project
                : t("点此选择要输出的工程", "Tap to pick an output project"));
    }

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
        // 手动选择的文件（浏览选中的）
        if (files == null && binPath != null) {
            sourceEmpty.setVisibility(View.GONE);
            addSourceRow(new File(binPath).getName(), binPath, true);
            return;
        }
        if (files == null) files = new ArrayList<>();
        if (files.isEmpty()) {
            sourceEmpty.setVisibility(View.VISIBLE);
            return;
        }
        sourceEmpty.setVisibility(View.GONE);
        // 默认选最大的（通常是完整/增量 payload）
        DnaTools.BrowseEntry best = null;
        for (DnaTools.BrowseEntry e : files)
            if (best == null || e.getSize() > best.getSize()) best = e;
        for (final DnaTools.BrowseEntry e : files) {
            boolean selected = e == best;
            if (selected) binPath = DnaTools.WORK_ROOT + "/" + project + "/" + e.getName();
            addSourceRow(e.getName() + "  ·  " + fmtSizeShort(e.getSize()),
                    DnaTools.WORK_ROOT + "/" + project + "/" + e.getName(), selected);
        }
    }

    private void addSourceRow(String label, final String path, boolean selected) {
        TextView row = new TextView(this);
        row.setText((selected ? "◉ " : "○ ") + label);
        row.setTextSize(12.5f);
        row.setTextColor(selected ? pal.success : pal.subtitle);
        row.setSingleLine(true);
        row.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        row.setPadding(dp(2), dp(7), dp(2), dp(7));
        row.setOnClickListener(v -> {
            Haptics.perform(v);
            binPath = path;
            renderSources(null);
        });
        sourceList.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void log(String line) {
        final String stamp = new java.text.SimpleDateFormat("HH:mm:ss", Locale.US)
                .format(new java.util.Date());
        main.post(() -> {
            if (isFinishing() || isDestroyed() || consoleText == null || consoleScroll == null) return;
            consoleText.append(stamp + "  " + line + "\n");
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

    private String fmtSizeShort(long bytes) {
        if (bytes < 1024) return bytes + "B";
        double v = bytes / 1024.0;
        String[] u = {"K", "M", "G", "T"};
        int i = 0;
        while (v >= 1024 && i < 3) { v /= 1024; i++; }
        return String.format(Locale.US, "%.1f%s", v, u[i]);
    }

    private String dumperPath() {
        return new File(getApplicationInfo().nativeLibraryDir, "libpayload_dumper.so").getAbsolutePath();
    }

    // ================= 解析 =================

    private void parseFile() {
        if (running.get()) { toast(t("正在执行中", "Busy")); return; }
        if (binPath == null || binPath.isEmpty()) {
            toast(t("请先选择增量包", "Pick a delta package first"));
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
                // root 放行（供 Java 直读 manifest）
                com.topjohnwu.superuser.Shell.cmd(
                        "chmod 666 " + DnaTools.quote(path)
                                + "; chown " + android.os.Process.myUid() + " " + DnaTools.quote(path)
                                + "; true").exec();
                final boolean incremental = PayloadExtractor.fastIsIncremental(path);
                List<PayloadExtractor.PartitionInfo> parts = PayloadExtractor.fastListPartitions(path);
                if (parts == null || parts.isEmpty()) {
                    DnaTools.Result r = DnaTools.run(this,
                            DnaTools.quote(dumperPath()) + " --list " + DnaTools.quote(path),
                            line -> kotlin.Unit.INSTANCE,
                            () -> cancelFlag.get(), 120000);
                    if (r.getSuccess()) parts = parseDumperList(r.getOutput());
                }
                final List<PayloadExtractor.PartitionInfo> fParts = parts;
                main.post(() -> {
                    if (fParts == null || fParts.isEmpty()) {
                        log("✗ " + t("解析失败（损坏或非 payload 镜像）", "Parse failed (corrupt or not a payload)"));
                        status.setText("✗ " + t("解析失败", "Parse failed"));
                        status.setTextColor(pal.danger);
                        notifyDone(false, t("解析失败", "Parse failed"));
                        return;
                    }
                    partitions.clear();
                    checked.clear();
                    partitions.addAll(fParts);
                    if (incremental) {
                        log("✓ " + t("已确认增量（delta）payload", "Confirmed delta payload"));
                    } else {
                        log("ℹ " + t("未检测到增量标记（可能为完整包，无需旧镜像目录）",
                                "No delta markers (probably a full OTA)"));
                    }
                    log("✓ " + t("解析完成", "Parsed") + " · " + partitions.size()
                            + t(" 个分区，请在弹窗勾选要提取的 img", " partitions, select img in dialog"));
                    status.setText("✓ " + t("解析完成", "Parsed") + " · " + partitions.size() + t(" 个分区", " partitions"));
                    status.setTextColor(0xff1d7a4f);
                    notifyDone(true, t("解析完成", "Parsed") + " · " + partitions.size() + t(" 个分区", " partitions"));
                    showPartitionDialog();
                });
            } catch (final Exception e) {
                final String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                main.post(() -> {
                    log("✗ " + t("解析失败", "Parse failed") + ": " + msg);
                    status.setText("✗ " + t("解析失败", "Parse failed"));
                    status.setTextColor(pal.danger);
                    notifyDone(false, t("解析失败", "Parse failed"));
                });
            } finally {
                main.post(() -> { running.set(false); parseBtn.setEnabled(true); });
            }
        });
    }

    /** 解析 payload_dumper --list 表格输出 */
    private static List<PayloadExtractor.PartitionInfo> parseDumperList(String output) {
        List<PayloadExtractor.PartitionInfo> out = new ArrayList<>();
        if (output == null) return out;
        for (String raw : output.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("Partition Name") || line.startsWith("---")) continue;
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^([A-Za-z0-9_.\\-]+)\\s+(.+)$").matcher(line);
            if (!m.matches()) continue;
            String name = m.group(1);
            if (name.equalsIgnoreCase("Partition")) continue;
            out.add(new PayloadExtractor.PartitionInfo(name, readableToBytes(m.group(2)), null));
        }
        return out;
    }

    private static long readableToBytes(String s) {
        if (s == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^([\\d.]+)\\s*(B|KB|MB|GB|TB)$", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(s.trim());
        if (!m.matches()) return 0;
        try {
            double v = Double.parseDouble(m.group(1));
            String u = m.group(2).toUpperCase(Locale.ROOT);
            long mul = 1;
            switch (u) {
                case "TB": mul = 1L << 40; break;
                case "GB": mul = 1L << 30; break;
                case "MB": mul = 1L << 20; break;
                case "KB": mul = 1L << 10; break;
            }
            return (long) (v * mul);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    // ================= 分区弹窗 =================

    private void showPartitionDialog() {
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
        bg.setStroke(Math.max(1, dp(1)), dark ? 0x33FFFFFF : 0x66FFFFFF);
        panel.setBackground(bg);

        TextView title = new TextView(this);
        title.setText("⚡ " + t("选择要提取的分区", "Select partitions to extract"));
        title.setTextSize(15.5f);
        title.setTypeface(null, 1);
        title.setTextColor(pal.title);
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        final java.util.Map<String, android.widget.CheckBox> boxes = new java.util.LinkedHashMap<>();
        for (PayloadExtractor.PartitionInfo p : partitions) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            final android.widget.CheckBox cb = new android.widget.CheckBox(this);
            cb.setText(p.getName());
            cb.setTextSize(13.5f);
            cb.setTextColor(pal.title);
            cb.setChecked(true);
            checked.add(p.getName());
            boxes.put(p.getName(), cb);
            row.addView(cb, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView size = new TextView(this);
            size.setText(fmtSizeShort(p.getSize()));
            size.setTextSize(12f);
            size.setTextColor(pal.subtitle);
            row.addView(size, new LinearLayout.LayoutParams(-2, -2));
            row.setPadding(0, dp(4), 0, dp(4));
            list.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        Button all = pillButton(t("全选", "All"), 13f, pal.success, dp(64), dp(42));
        all.setOnClickListener(v -> {
            for (android.widget.CheckBox cb : boxes.values()) cb.setChecked(true);
        });
        btnRow.addView(all, new LinearLayout.LayoutParams(dp(64), dp(42)));
        Button none = pillButton(t("全不选", "None"), 13f, pal.danger, dp(76), dp(42));
        none.setOnClickListener(v -> {
            for (android.widget.CheckBox cb : boxes.values()) cb.setChecked(false);
        });
        android.widget.LinearLayout.LayoutParams nnLp = new LinearLayout.LayoutParams(dp(76), dp(42));
        nnLp.leftMargin = dp(6);
        btnRow.addView(none, nnLp);
        android.widget.Space sp = new android.widget.Space(this);
        btnRow.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        Button ok = gradientButton("⚡ " + t("开始提取", "Extract"), new int[]{0xFFE08A39, 0xFFB85C10}, dp(12));
        ok.setOnClickListener(v -> {
            Haptics.perform(v);
            checked.clear();
            for (PayloadExtractor.PartitionInfo p : partitions)
                if (boxes.get(p.getName()) != null && boxes.get(p.getName()).isChecked())
                    checked.add(p.getName());
            dialog.dismiss();
            if (checked.isEmpty()) toast(t("未勾选任何分区", "Nothing selected"));
            else extract();
        });
        btnRow.addView(ok, new LinearLayout.LayoutParams(0, dp(42), 1.5f));
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(-1, dp(42));
        brLp.topMargin = dp(10);
        panel.addView(btnRow, brLp);

        dialog.setContentView(panel, new LinearLayout.LayoutParams(-1, dp(480)));
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        dialog.setOnCancelListener(d -> checked.clear());
        try {
            dialog.show();
            dialog.getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.88f), dp(480));
        } catch (Exception ignored) {
        }
    }

    // ================= 提取（payload_dumper --source-dir，root 链路） =================

    private void extract() {
        if (project == null) {
            toast(t("请先选择输出工程", "Select an output project first"));
            showProjectPicker();
            return;
        }
        if (incDir == null || incDir.isEmpty()) {
            toast(t("请先选择旧镜像目录", "Pick the old images dir first"));
            showIncDirPicker();
            return;
        }
        final List<String> ordered = new ArrayList<>();
        for (PayloadExtractor.PartitionInfo p : partitions)
            if (checked.contains(p.getName())) ordered.add(p.getName());
        final String outDir = DnaTools.WORK_ROOT + "/" + project;
        expandConsole();
        // v3.30.39：逐分区顺序提取（同分解 bin 页，取消进度条）：
        // ⏳ 正在提取 [i/n] xxx.img → ✓ xxx.img (大小) 提取完成，依次推进
        log("⚡ " + t("开始增量提取", "Incremental extract") + " " + ordered.size()
                + t(" 个分区 → ", " partition(s) → ") + project);
        running.set(true);
        cancelFlag.set(false);
        runBtn.setText("■  " + t("取消", "Cancel"));
        parseBtn.setEnabled(false);
        status.setText("⚡ " + t("增量提取中 ...", "Incremental extracting..."));
        status.setTextColor(pal.subtitle);

        io.execute(() -> {
            // v3.30.39：逐分区顺序提取 —— 每分区一次 payload_dumper --source-dir 调用，
            // ⏳ 前置 + ✓/✗ 后置，日志严格按分区推进（无进度条、无字节轮询）
            final long startMs = System.currentTimeMillis();
            int okCount = 0;
            String lastErr = null;
            for (int i = 0; i < ordered.size(); i++) {
                if (cancelFlag.get()) break;
                final String n = ordered.get(i);
                final int no = i + 1;
                main.post(() -> {
                    log("⏳ " + t("正在提取", "Extracting") + " [" + no + "/" + ordered.size() + "] " + n + ".img");
                    status.setText("⏳ " + t("正在提取", "Extracting") + " " + n + ".img [" + no + "/" + ordered.size() + "]");
                });
                notify(t("正在提取", "Extracting") + " " + n + ".img [" + no + "/" + ordered.size() + "]",
                        true, false, 0, 0);
                final DnaTools.Result r = DnaTools.run(this,
                        DnaTools.quote(dumperPath()) + " " + DnaTools.quote(binPath)
                                + " --source-dir " + DnaTools.quote(incDir)
                                + " -o " + DnaTools.quote(outDir)
                                + " -i " + DnaTools.quote(n) + " -n",
                        line -> kotlin.Unit.INSTANCE,   // 静默：进度行由本层打印
                        () -> cancelFlag.get());
                if (cancelFlag.get()) break;
                if (r.getSuccess()) {
                    long size = 0;
                    try {
                        com.topjohnwu.superuser.Shell.Result sr = com.topjohnwu.superuser.Shell.cmd(
                                "stat -c '%s' " + DnaTools.quote(outDir + "/" + n + ".img") + " 2>/dev/null").exec();
                        if (!sr.getOut().isEmpty()) size = Long.parseLong(sr.getOut().get(0).trim());
                    } catch (Exception ignored) {}
                    final long sz = size;
                    main.post(() -> log("✓ " + n + ".img (" + fmtSizeShort(sz) + ") " + t("提取完成", "extracted")));
                    okCount++;
                } else {
                    lastErr = r.getMessage();
                    final String msg = lastErr;
                    main.post(() -> log("✗ " + n + ".img " + t("提取失败", "failed") + ": " + msg));
                }
            }
            final boolean cancelled = cancelFlag.get();
            final int ok = okCount;
            final String err = lastErr;
            final long elapsed = (System.currentTimeMillis() - startMs) / 1000;
            main.post(() -> {
                running.set(false);
                parseBtn.setEnabled(true);
                runBtn.setText("⚡  " + t("选择分区并提取", "Select & Extract"));
                if (cancelled) {
                    log("■ " + t("已取消", "Cancelled"));
                    status.setText("■ " + t("已取消", "Cancelled"));
                    status.setTextColor(pal.danger);
                    notifyDone(false, t("已取消", "Cancelled"));
                } else if (ok == ordered.size()) {
                    log("✓ " + t("增量提取完成，文件位于", "Incremental done, files at") + ": " + outDir);
                    log("ℹ " + t("耗时", "Time") + " " + elapsed + "s · " + ok + t(" 个镜像", " image(s)"));
                    status.setText("✓ " + t("增量提取完成", "Incremental done") + " · " + ok);
                    status.setTextColor(0xff1d7a4f);
                    notifyDone(true, t("增量提取完成", "Incremental done") + " · " + ok + t(" 个镜像", " image(s)"));
                    toast(t("增量提取完成", "Incremental done"));
                } else if (ok > 0) {
                    log("⚠ " + t("部分分区提取失败", "Some partitions failed") + ": "
                            + (ordered.size() - ok) + "/" + ordered.size());
                    status.setText("⚠ " + t("部分提取完成", "Partial") + " · " + ok + "/" + ordered.size());
                    status.setTextColor(pal.warning);
                    notifyDone(false, t("部分分区提取失败", "Some partitions failed") + " "
                            + (ordered.size() - ok) + "/" + ordered.size());
                } else {
                    log("✗ " + t("增量提取失败", "Incremental failed") + ": " + (err != null ? err : "unknown"));
                    status.setText("✗ " + t("增量提取失败", "Incremental failed"));
                    status.setTextColor(pal.danger);
                    notifyDone(false, t("增量提取失败", "Incremental failed"));
                    toast(t("增量提取失败", "Incremental failed"));
                }
                refreshSources();
            });
        });
    }

    // ================= 目录选择弹窗（root 列目录） =================

    private void showIncDirPicker() {
        if (isFinishing() || isDestroyed()) return;
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.setCancelable(true);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(14), dp(16), dp(12));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        boolean dark = com.mcai.ubuntudsu.ui.Ui.INSTANCE.isDark(this);
        bg.setColor(dark ? 0xF22A3546 : 0xF2e9f0f7);
        bg.setCornerRadius(dp(24));
        bg.setStroke(Math.max(1, dp(1)), dark ? 0x66FFFFFF : 0x66FFFFFF);
        panel.setBackground(bg);

        TextView title = new TextView(this);
        title.setText("🗂 " + t("选择旧镜像目录", "Pick old images dir"));
        title.setTextSize(15f);
        title.setTypeface(null, 1);
        title.setTextColor(dark ? 0xFFEAF0F8 : 0xff17334f);
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));

        final TextView cwd = new TextView(this);
        cwd.setTextSize(11.5f);
        cwd.setTextColor(pal.accent);
        cwd.setSingleLine(true);
        cwd.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        cwd.setPadding(0, dp(4), 0, dp(6));
        panel.addView(cwd, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        final java.util.concurrent.atomic.AtomicReference<String> cur =
                new java.util.concurrent.atomic.AtomicReference<>(
                        incDir != null ? incDir : DnaTools.WORK_ROOT);

        final Runnable[] load = new Runnable[1];
        load[0] = () -> {
            list.removeAllViews();
            final String dir = cur.get();
            cwd.setText(dir);
            java.util.List<String> dirs = new ArrayList<>();
            try {
                com.topjohnwu.superuser.Shell.Result r = com.topjohnwu.superuser.Shell.cmd(
                        "for d in " + DnaTools.quote(dir) + "/*/; do [ -d \"$d\" ] && echo \"${d%/}\"; done").exec();
                dirs.addAll(r.getOut());
            } catch (Exception ignored) {}
            java.util.Collections.sort(dirs);
            if (!"/storage/emulated/0".equals(dir) && dir.lastIndexOf('/') > 0) {
                final String parent = dir.substring(0, dir.lastIndexOf('/'));
                Button up = pillButton("⬆ " + t("上级", "Up"), 12f, pal.subtitle, dp(72), dp(38));
                up.setOnClickListener(v -> { cur.set(parent); load[0].run(); });
                LinearLayout.LayoutParams uLp = new LinearLayout.LayoutParams(dp(72), dp(38));
                uLp.bottomMargin = dp(4);
                list.addView(up, uLp);
            }
            for (final String d : dirs) {
                Button b = pillButton("📂 " + d.substring(d.lastIndexOf('/') + 1), 12.5f, pal.accent, -2, dp(40));
                b.setOnClickListener(v -> { cur.set(d); load[0].run(); });
                LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(-1, dp(40));
                bLp.topMargin = dp(3);
                list.addView(b, bLp);
            }
            if (dirs.isEmpty()) {
                TextView empty = new TextView(this);
                empty.setText(t("（无子目录，可直接选定此目录）", "(no subdirs, you can pick this dir)"));
                empty.setTextSize(12f);
                empty.setTextColor(pal.subtitle);
                empty.setPadding(dp(4), dp(8), 0, 0);
                list.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            }
        };
        load[0].run();

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        Button cancel = pillButton(t("取消", "Cancel"), 13f, pal.subtitle, dp(72), dp(42));
        cancel.setOnClickListener(v -> { Haptics.perform(v); dialog.dismiss(); });
        btnRow.addView(cancel, new LinearLayout.LayoutParams(dp(72), dp(42)));
        android.widget.Space sp = new android.widget.Space(this);
        btnRow.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        Button ok = gradientButton("✓ " + t("选定此目录", "Pick this dir"), new int[]{0xFF2f9c8f, 0xFF1d6b46}, dp(10));
        ok.setOnClickListener(v -> {
            Haptics.perform(v);
            incDir = cur.get();
            incDirText.setText(incDir);
            dialog.dismiss();
            log("🗂 " + t("旧镜像目录", "Old images dir") + ": " + incDir);
        });
        btnRow.addView(ok, new LinearLayout.LayoutParams(0, dp(42), 1.6f));
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(-1, dp(42));
        brLp.topMargin = dp(10);
        panel.addView(btnRow, brLp);

        dialog.setContentView(panel, new LinearLayout.LayoutParams(-1, dp(440)));
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        try {
            dialog.show();
            dialog.getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.88f), dp(440));
        } catch (Exception ignored) {
        }
    }

    // ================= 工程选择 =================

    private void showProjectPicker() {
        if (isFinishing() || isDestroyed()) return;
        List<String> projects = DnaTools.listProjects();
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.setCancelable(true);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(14), dp(16), dp(12));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        boolean dark = com.mcai.ubuntudsu.ui.Ui.INSTANCE.isDark(this);
        bg.setColor(dark ? 0xF22A3546 : 0xF2e9f0f7);
        bg.setCornerRadius(dp(24));
        bg.setStroke(Math.max(1, dp(1)), dark ? 0x66FFFFFF : 0x66FFFFFF);
        panel.setBackground(bg);

        TextView title = new TextView(this);
        title.setText("📂 " + t("选择输出工程", "Pick output project"));
        title.setTextSize(15f);
        title.setTypeface(null, 1);
        title.setTextColor(dark ? 0xFFEAF0F8 : 0xff17334f);
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        if (projects.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(t("暂无工程，请先在 DNA 工具箱创建", "No projects yet"));
            empty.setTextSize(12.5f);
            empty.setTextColor(pal.subtitle);
            empty.setPadding(dp(4), dp(10), 0, 0);
            list.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        }
        for (final String p : projects) {
            Button b = pillButton((p.equals(project) ? "● " : "○ ") + p, 13f,
                    p.equals(project) ? pal.success : pal.subtitle, -2, dp(42));
            b.setOnClickListener(v -> {
                Haptics.perform(v);
                project = p;
                DnaTools.setCurrentProject(this, p);
                renderProject();
                refreshSources();
                dialog.dismiss();
            });
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(-1, dp(42));
            bLp.topMargin = dp(4);
            list.addView(b, bLp);
        }

        dialog.setContentView(panel, new LinearLayout.LayoutParams(-1, dp(400)));
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        try {
            dialog.show();
            dialog.getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.86f), dp(400));
        } catch (Exception ignored) {
        }
    }
}
