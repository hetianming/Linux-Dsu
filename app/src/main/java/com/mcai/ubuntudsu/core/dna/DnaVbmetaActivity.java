package com.mcai.ubuntudsu.core.dna;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.mcai.ubuntudsu.core.DnaTools;
import com.mcai.ubuntudsu.ui.Ui;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DNA · 禁用 AVB / dm-verity 独立二级页（vbmeta_a / vbmeta_b 处理 + 本地镜像处理）。
 * 流程：选本机分区（vbmeta_a/vbmeta_b）或自选 vbmeta.img → 开始执行（hexpatch 改 magic → 刷回/输出）
 *      → 页面内日志面板实时输出；15 分钟超时。
 */
public final class DnaVbmetaActivity extends DnaBaseActivity {

    private static final String PREFS = "dna_tools";
    private static final int PICK_VBMETA = 3412;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dna-vbmeta");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);

    // 选择状态
    private String selectedPart = null;
    // 本地文件处理状态
    private String localFile = null;

    // UI
    private TextView statusText, localFileText;
    private Button runBtn;
    private TextView logDisplay;
    private ScrollView logScroll;
    private LinearLayout logCardView;
    private android.app.Dialog logDialog;
    private Ui.DnaPalette pal;

    // 分区行按钮对（用于互斥刷新）
    private final java.util.List<Object[]> partBtnPairs = new java.util.ArrayList<>();

    private int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String t(String zh, String en) {
        return getResources().getConfiguration().locale.getLanguage().startsWith("zh") ? zh : en;
    }

    private boolean isDark() {
        return Ui.INSTANCE.isDark(this);
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        Haptics.onTouch(getWindow().getDecorView(), e);
        return super.dispatchTouchEvent(e);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0x00000000);
        Ui.INSTANCE.enableEdgeToEdge(this, getWindow().getDecorView());
        pal = Ui.INSTANCE.dnaPalette(this);
        // 从首页按钮进入时携带预选
        String prePart = getIntent().getStringExtra("extra_part");
        if (prePart != null && !prePart.isEmpty()) {
            selectedPart = prePart;
        }
        buildUi(prePart != null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_VBMETA || resultCode != RESULT_OK || data == null) return;
        String path = data.getStringExtra(com.mcai.ubuntudsu.RootfsFilesActivity.RESULT_FILE_PATH);
        if (path == null || !path.startsWith("/")) return;
        localFile = path;
        localFileText.setText(path);
        localFileText.setTextColor(pal.success);
        runBtn.setEnabled(true);
        statusText.setText(t("已选本地文件 " + new File(path).getName(), "Local file " + new File(path).getName()));
    }

    @Override
    protected void onDestroy() {
        if (logDialog != null && logDialog.isShowing()) logDialog.dismiss();
        super.onDestroy();
        io.shutdownNow();
    }

    // ================= UI 构建 =================

    private LinearLayout glassCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x33FFFFFF);
        bg.setCornerRadius(dp(16));
        bg.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
        c.setBackground(bg);
        return c;
    }

    private void buildUi(boolean preselected) {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(pal.bgDrawable());
        Ui.INSTANCE.animateLiquidBackground(root);
        Ui.INSTANCE.applyContentInsets(root, 8, 0);

        ScrollView page = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(4), dp(14), dp(16));
        page.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        // ---- 标题栏 ----
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(t("DNA · 禁用 AVB / dm-verity", "DNA · Disable AVB / dm-verity"));
        title.setTextSize(17);
        title.setTypeface(null, 1);
        title.setTextColor(pal.title);
        title.setPadding(dp(10), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView badge = new TextView(this);
        badge.setText("🔒");
        badge.setTextSize(13f);
        badge.setTextColor(isDark() ? 0xFFFFFFFF : 0xFF000000);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(12), 0, dp(12), 0);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setCornerRadius(dp(16));
        badgeBg.setColor(0x73eafff5);
        badgeBg.setStroke(Math.max(1, dp(1)), 0x802f9c8f);
        badge.setBackground(badgeBg);
        bar.addView(badge, new LinearLayout.LayoutParams(-2, dp(32)));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, -2);
        barLp.bottomMargin = dp(8);
        content.addView(bar, barLp);

        // ---- 流程提示条 ----
        TextView flow = new TextView(this);
        flow.setText("① " + t("选择本机分区或镜像", "Pick") + "  →  ② "
                + t("开始执行", "Run") + "  →  ③ " + t("重启生效", "Reboot"));
        flow.setTextSize(11.5f);
        flow.setTextColor(pal.success);
        flow.setTypeface(null, 1);
        LinearLayout.LayoutParams flowLp = new LinearLayout.LayoutParams(-1, -2);
        flowLp.bottomMargin = dp(8);
        content.addView(flow, flowLp);

        // ---- 状态卡 ----
        LinearLayout infoCard = glassCard();
        String cur = DnaTools.currentProject(this);
        infoCard.addView(textView(
                t("当前工程", "Project") + "：" + (cur != null ? cur : t("未选择", "None")),
                12f, pal.subtitle));
        statusText = textView(preselected ? t("已选 " + selectedPart, "Selected " + selectedPart) : t("待执行", "Idle"),
                11.5f, pal.subtitle);
        statusText.setTypeface(null, 1);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
        statusLp.topMargin = dp(3);
        infoCard.addView(statusText, statusLp);
        String slot = DnaTools.slotSuffix(this);
        String slotLabel = (slot != null && !slot.isEmpty())
                ? slot.toUpperCase()
                : t("未识别（可能 A-only 设备）", "Not detected (A-only)");
        TextView slotText = textView(t("当前活跃槽位: " + slotLabel, "Active slot: " + slotLabel), 10.5f, pal.subtitle);
        LinearLayout.LayoutParams slotLp = new LinearLayout.LayoutParams(-1, -2);
        slotLp.topMargin = dp(3);
        infoCard.addView(slotText, slotLp);

        // 检测 vbmeta / AVB / dm-verity 状态
        TextView vbmetaStatusText = textView(
                t("检测 vbmeta 状态 …", "Detecting vbmeta status …"), 10.5f, pal.subtitle);
        LinearLayout.LayoutParams vbLp = new LinearLayout.LayoutParams(-1, -2);
        vbLp.topMargin = dp(3);
        infoCard.addView(vbmetaStatusText, vbLp);
        io.execute(() -> {
            String verityMode = "UNKNOWN";
            String verifiedBoot = "UNKNOWN";
            try {
                // dm-verity: AOSP 无专用 ro.boot.verity 属性,用 ro.boot.veritymode 或 /proc/cmdline
                String[] verityProps = {
                        "ro.boot.veritymode",    // eio / restart
                        "ro.boot.verity",       // 部分厂商
                };
                boolean found = false;
                for (String prop : verityProps) {
                    String val = com.mcai.ubuntudsu.core.RootShell.INSTANCE
                            .exec("getprop " + prop + " 2>/dev/null", 5000, null)
                            .getStdout().trim().toUpperCase();
                    if (!val.isEmpty()) {
                        verityMode = val;
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    // 从 /proc/cmdline 检测 androidboot.veritymode
                    String cmdline = com.mcai.ubuntudsu.core.RootShell.INSTANCE
                            .exec("cat /proc/cmdline 2>/dev/null", 5000, null)
                            .getStdout().trim().toUpperCase();
                    if (cmdline.contains("ANDROIDBOOT.VERITYMODE")) {
                        int idx = cmdline.indexOf("ANDROIDBOOT.VERITYMODE=");
                        if (idx >= 0) {
                            int end = cmdline.indexOf(' ', idx + 25);
                            verityMode = (end < 0)
                                    ? cmdline.substring(idx + 25)
                                    : cmdline.substring(idx + 25, end);
                        }
                    }
                }
            } catch (Exception ignored) {}
            try {
                String val = com.mcai.ubuntudsu.core.RootShell.INSTANCE
                        .exec("getprop ro.boot.verifiedbootstate 2>/dev/null", 5000, null)
                        .getStdout().trim().toUpperCase();
                if (!val.isEmpty()) verifiedBoot = val;
            } catch (Exception ignored) {}
            String verityLabel;
            switch (verityMode) {
                case "EIO": verityLabel = t("dm-verity: 错误忽略 (eio)", "dm-verity: eio"); break;
                case "RESTART": verityLabel = t("dm-verity: 重启模式 (restart)", "dm-verity: restart"); break;
                default:
                    if (verityMode.equals("UNKNOWN")) verityLabel = t("dm-verity: 未知", "dm-verity: unknown");
                    else verityLabel = t("dm-verity: " + verityMode, "dm-verity: " + verityMode);
                    break;
            }
            String vbLabel;
            switch (verifiedBoot) {
                case "GREEN": vbLabel = t("Verified Boot: 绿色 (通过)", "Verified Boot: green (passed)"); break;
                case "ORANGE": vbLabel = t("Verified Boot: 橙色 (降级)", "Verified Boot: orange (degraded)"); break;
                case "RED": vbLabel = t("Verified Boot: 红色 (阻断)", "Verified Boot: red (blocked)"); break;
                case "YELLOW": vbLabel = t("Verified Boot: 黄色 (锁定)", "Verified Boot: yellow (locked)"); break;
                default: vbLabel = t("Verified Boot: 未知", "Verified Boot: unknown"); break;
            }
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                vbmetaStatusText.setText(verityLabel + "  ·  " + vbLabel);
            });
        });
        TextView warn = textView(
                t("hexpatch 改 vbmeta magic → dd 刷回。需 ROOT；误改可能无法开机。",
                        "hexpatch vbmeta magic → dd flash. Root required; wrong ops may break boot."),
                13f, pal.danger);
        warn.setPadding(0, dp(6), 0, 0);
        infoCard.addView(warn);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(-1, -2);
        infoLp.topMargin = dp(6);
        content.addView(infoCard, infoLp);

        // ---- 本机分区选择卡 ----
        LinearLayout pickCard = glassCard();
        TextView pickTitle = textView(t("本机分区模式", "Device Partition Mode"), 13f, pal.title);
        pickTitle.setTypeface(null, 1);
        pickCard.addView(pickTitle);
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(0, dp(8), 0, 0);
        buildPartRows(group);
        pickCard.addView(group);
        LinearLayout.LayoutParams pickLp = new LinearLayout.LayoutParams(-1, -2);
        pickLp.topMargin = dp(8);
        content.addView(pickCard, pickLp);

        // ---- 自选镜像文件处理 ----
        buildLocalFileCard(content);

        // ---- 执行按钮 ----
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        runBtn = new Button(this);
        runBtn.setText(t("开始执行", "Run"));
        runBtn.setAllCaps(false);
        runBtn.setTextSize(13f);
        runBtn.setTypeface(null, 1);
        runBtn.setTextColor(0xFFFFFFFF);
        runBtn.setGravity(Gravity.CENTER);
        runBtn.setPadding(dp(18), 0, dp(18), 0);
        GradientDrawable runBg = new GradientDrawable();
        runBg.setCornerRadius(dp(16));
        runBg.setColor(0xF211998E);
        runBg.setStroke(Math.max(1, dp(1)), 0x80FFFFFF);
        runBtn.setBackground(runBg);
        runBtn.setOnClickListener(v -> {
            Haptics.perform(v);
            startRun();
        });
        btnRow.addView(runBtn, new LinearLayout.LayoutParams(0, dp(44), 1f));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(-1, -2);
        btnLp.topMargin = dp(8);
        btnLp.bottomMargin = dp(8);
        content.addView(btnRow, btnLp);

        // ---- 日志面板（默认隐藏，执行时弹出，对齐 DnaActivity expandLog） ----
        buildLogCard();
    }

    private TextView textView(String text, float size, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(size);
        tv.setTextColor(color);
        return tv;
    }

    /** 本机分区行（vbmeta_a / vbmeta_b） */
    private void buildPartRows(LinearLayout container) {
        boolean isDark = isDark();
        container.addView(groupLabel(t("分区", "Partitions"), isDark));
        container.addView(partRow("vbmeta_a"));
        container.addView(partRow("vbmeta_b"));
    }

    private View groupLabel(String text, boolean isDark) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(10.5f);
        tv.setTypeface(null, 1);
        tv.setTextColor(isDark ? 0x88FFFFFF : 0x88334455);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(3);
        tv.setLayoutParams(lp);
        return tv;
    }

    /** 单分区行：分区名 + 执行按钮（选中互斥） */
    private View partRow(String part) {
        boolean isDark = isDark();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.topMargin = dp(3);
        row.setLayoutParams(rowLp);

        TextView name = new TextView(this);
        name.setText(part);
        name.setTextSize(12f);
        name.setTypeface(null, 1);
        name.setTextColor(isDark ? 0xFFFFFFFF : 0xFF1F3352);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));

        Button runBtn = new Button(this);
        runBtn.setText(t("执行", "Run"));
        runBtn.setAllCaps(false);
        runBtn.setTextSize(11f);
        runBtn.setTypeface(null, 1);
        runBtn.setGravity(Gravity.CENTER);
        runBtn.setPadding(dp(12), dp(5), dp(12), dp(5));
        runBtn.setBackground(dimModeBg(isDark));
        runBtn.setTextColor(isDark ? 0x99FFFFFF : 0x991F3352);
        runBtn.setOnClickListener(v -> {
            Haptics.perform(v);
            selectedPart = part;
            runBtn.setEnabled(true);
            statusText.setText(t("已选择 " + part, "Selected " + part));
            refreshAllPartRows();
        });
        LinearLayout.LayoutParams runLp = new LinearLayout.LayoutParams(-2, -2);
        runLp.leftMargin = dp(8);
        row.addView(runBtn, runLp);

        partBtnPairs.add(new Object[]{part, runBtn});
        return row;
    }

    private void refreshAllPartRows() {
        for (Object[] p : partBtnPairs) {
            String part = (String) p[0];
            Button runBtn = (Button) p[1];
            boolean rowSelected = part.equals(selectedPart);
            applyModeBg(runBtn, rowSelected);
        }
    }

    private void applyModeBg(Button b, boolean selected) {
        boolean isDark = isDark();
        if (selected) {
            b.setBackground(selModeBg(isDark));
            b.setTextColor(0xFFFFFFFF);
        } else {
            b.setBackground(dimModeBg(isDark));
            b.setTextColor(isDark ? 0x99FFFFFF : 0x991F3352);
        }
    }

    /** 选中态：浓天蓝色背景（白字） */
    private GradientDrawable selModeBg(boolean isDark) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(isDark ? 0xF00277BD : 0xF00288D1);
        g.setCornerRadius(dp(14));
        g.setStroke(Math.max(1, dp(1)), isDark ? 0xE64FC3F7 : 0xE64FC3F7);
        return g;
    }

    /** 未选中态：模糊浅黑背景 */
    private GradientDrawable dimModeBg(boolean isDark) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x331A2535);
        g.setCornerRadius(dp(14));
        g.setStroke(Math.max(1, dp(1)), 0x2A4A5568);
        return g;
    }

    private GradientDrawable logPillBg() {
        boolean dark = isDark();
        GradientDrawable g = new GradientDrawable();
        g.setColor(dark ? 0x593A4355 : 0x59FFFFFF);
        g.setCornerRadius(dp(15));
        g.setStroke(Math.max(1, dp(1)), dark ? 0x80FFFFFF : 0x66FFFFFF);
        return g;
    }

    private GradientDrawable rippleBg(boolean isDark) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(isDark ? 0x332A3546 : 0x1A1E2A3A);
        g.setCornerRadius(dp(14));
        g.setStroke(Math.max(1, dp(1)), isDark ? 0x59FFFFFF : 0x2A1E2A3A);
        return g;
    }

    // ================= 日志面板（默认隐藏，执行时弹出） =================

    private void buildLogCard() {
        boolean isDark = isDark();
        LinearLayout logCard = new LinearLayout(this);
        logCard.setOrientation(LinearLayout.VERTICAL);
        logCard.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable logCardBg = new GradientDrawable();
        logCardBg.setColor(isDark ? 0xFF111927 : 0xFFF7FAFF);
        logCardBg.setCornerRadius(dp(18));
        logCardBg.setStroke(Math.max(1, dp(1)), 0x80FFFFFF);
        logCard.setBackground(logCardBg);
        logCard.setElevation(0f);

        LinearLayout logHead = new LinearLayout(this);
        logHead.setOrientation(LinearLayout.HORIZONTAL);
        logHead.setGravity(Gravity.CENTER_VERTICAL);
        logHead.setPadding(0, 0, 0, dp(8));
        TextView logTitle = textView(t("执行日志", "Log"), 14f, pal.title);
        logTitle.setTypeface(null, 1);
        logHead.addView(logTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        Button copyBtn = new Button(this);
        copyBtn.setText(t("复制", "Copy"));
        copyBtn.setAllCaps(false);
        copyBtn.setTextSize(11.5f);
        copyBtn.setTypeface(null, 1);
        copyBtn.setTextColor(pal.success);
        copyBtn.setGravity(Gravity.CENTER);
        copyBtn.setPadding(dp(10), 0, dp(10), 0);
        copyBtn.setBackground(logPillBg());
        copyBtn.setOnClickListener(v -> {
            Haptics.perform(v);
            if (logDisplay == null) return;
            CharSequence text = logDisplay.getText();
            if (text.length() == 0) {
                toast(t("暂无日志", "Nothing to copy"));
                return;
            }
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("vbmeta log", text));
            toast(t("已复制全部日志", "Log copied"));
        });
        logHead.addView(copyBtn, new LinearLayout.LayoutParams(-2, dp(30)));
        Button closeBtn = new Button(this);
        closeBtn.setText("✕");
        closeBtn.setAllCaps(false);
        closeBtn.setTextSize(12.5f);
        closeBtn.setTextColor(pal.subtitle);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setPadding(dp(10), 0, dp(10), 0);
        closeBtn.setBackground(logPillBg());
        closeBtn.setOnClickListener(v -> {
            Haptics.perform(v);
            if (logDialog != null && logDialog.isShowing()) logDialog.dismiss();
        });
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(dp(40), dp(30));
        closeLp.leftMargin = dp(6);
        logHead.addView(closeBtn, closeLp);
        logCard.addView(logHead, new LinearLayout.LayoutParams(-1, -2));

        GradientDrawable logBg = new GradientDrawable();
        logBg.setColor(isDark ? 0xFF0E141F : 0xFFE8EEF7);
        logBg.setCornerRadius(dp(14));
        logBg.setStroke(Math.max(1, dp(1)), isDark ? 0x59FFFFFF : 0x40FFFFFF);
        logDisplay = new TextView(this);
        logDisplay.setTypeface(Typeface.MONOSPACE);
        logDisplay.setTextSize(9.5f);
        logDisplay.setTextColor(isDark ? 0xFFEAF0F8 : 0xFF1F3352);
        logDisplay.setPadding(dp(12), dp(10), dp(12), dp(10));
        logDisplay.setLineSpacing(dp(2), 1f);
        logDisplay.setText("");
        logScroll = new ScrollView(this);
        logScroll.setBackground(logBg);
        logScroll.setFillViewport(false);
        logScroll.setVerticalScrollBarEnabled(true);
        logScroll.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            v.getParent().requestDisallowInterceptTouchEvent(
                    a != MotionEvent.ACTION_UP && a != MotionEvent.ACTION_CANCEL);
            return false;
        });
        logScroll.addView(logDisplay, new ScrollView.LayoutParams(-1, -2));
        logCard.addView(logScroll, new LinearLayout.LayoutParams(-1, -2));

        // 不加入页面，执行时通过 expandLog() 弹出
        this.logCardView = logCard;
    }

    private void expandLog() {
        if (logCardView == null) return;
        if (logDialog == null) {
            logDialog = new android.app.Dialog(this);
            logDialog.requestWindowFeature(0);
            logDialog.setContentView(logCardView, new android.view.ViewGroup.LayoutParams(-1, dp(470)));
            logDialog.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(0x00000000));
            logDialog.getWindow().setElevation(0f);
            logDialog.setCanceledOnTouchOutside(true);
        }
        if (!logDialog.isShowing()) logDialog.show();
        scrollLogToBottom();
    }

    private void scrollLogToBottom() {
        if (logDisplay == null || logScroll == null) return;
        logDisplay.requestLayout();
        logDisplay.post(() -> {
            if (isFinishing() || isDestroyed() || logScroll == null) return;
            logScroll.fullScroll(ScrollView.FOCUS_DOWN);
        });
    }

    private void log(String s) {
        main.post(() -> {
            if (logDisplay == null) return;
            String cur = logDisplay.getText().toString().trim();
            if (cur.isEmpty()) {
                logDisplay.setText(s);
            } else {
                logDisplay.append("\n" + s);
            }
            scrollLogToBottom();
        });
    }

    // ================= 执行逻辑 =================

    /** 自选镜像处理卡：选文件 + 保存到工程区 SELinux 目录 */
    private void buildLocalFileCard(LinearLayout content) {
        LinearLayout card = glassCard();
        TextView localTitle = textView(t("自选镜像模式", "Custom Image Mode"), 13f, pal.title);
        localTitle.setTypeface(null, 1);
        card.addView(localTitle);
        card.addView(textView(
                t("选择 vbmeta.img → magiskboot hexpatch 禁用 AVB → 结果保存到工程区 SELinux 目录",
                        "Pick vbmeta.img → magiskboot hexpatch to disable AVB → save to project SELinux dir"),
                10.5f, pal.subtitle));

        // 文件选择行
        LinearLayout fileRow = new LinearLayout(this);
        fileRow.setOrientation(LinearLayout.HORIZONTAL);
        fileRow.setGravity(Gravity.CENTER_VERTICAL);
        fileRow.setPadding(0, dp(8), 0, 0);
        localFileText = textView(t("未选择文件", "No file selected"), 12f, pal.subtitle);
        localFileText.setSingleLine(true);
        localFileText.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        fileRow.addView(localFileText, new LinearLayout.LayoutParams(0, -2, 1f));
        Button browseBtn = new Button(this);
        browseBtn.setText(t("📂 浏览", "Browse"));
        browseBtn.setAllCaps(false);
        browseBtn.setTextSize(11f);
        browseBtn.setTypeface(null, 1);
        browseBtn.setTextColor(pal.accent);
        browseBtn.setGravity(Gravity.CENTER);
        browseBtn.setPadding(dp(10), dp(5), dp(10), dp(5));
        browseBtn.setBackground(rippleBg(isDark()));
        browseBtn.setOnClickListener(v -> {
            Haptics.perform(v);
            startActivityForResult(
                    com.mcai.ubuntudsu.RootfsFilesActivity.createPickIntent(
                            this, t("选择 vbmeta.img", "Pick vbmeta.img"),
                            new String[]{"vbmeta.img", "vbmeta_a.img", "vbmeta_b.img", ".img"}),
                    PICK_VBMETA);
        });
        LinearLayout.LayoutParams browseLp = new LinearLayout.LayoutParams(-2, -2);
        browseLp.leftMargin = dp(8);
        fileRow.addView(browseBtn, browseLp);
        card.addView(fileRow);

        // 输出提示
        String proj = DnaTools.currentProject(this);
        String outDir = proj != null
                ? DnaTools.WORK_ROOT + "/" + proj + "/SELinux"
                : DnaTools.WORK_ROOT + "/<工程>/SELinux";
        TextView outHint = textView(t("输出 → ", "Output → ") + outDir, 10.5f, pal.success);
        outHint.setPadding(0, dp(6), 0, 0);
        card.addView(outHint);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        content.addView(card, lp);
    }

    private void startRun() {
        // 本地文件处理分支
        if (localFile != null) {
            runLocalFile();
            return;
        }
        if (selectedPart == null) {
            toast(t("请先选择本机分区，或选择自选镜像文件", "Pick a device partition, or a custom image file"));
            return;
        }
        if (!DnaTools.INSTANCE.isReady(this)) {
            log(t("✗ DNA 工具链未就绪，请先在工具箱首页下载工具包", "✗ Toolchain not ready, download tools first"));
            return;
        }
        cancelFlag.set(false);
        runBtn.setEnabled(false);
        statusText.setText(t("执行中 …", "Running …"));
        expandLog();

        final String part = selectedPart;
        io.execute(() -> {
            File scriptPath = new File(DnaTools.scriptsDir(this), "vbmeta_boot.sh");
            if (!scriptPath.isFile()) {
                log(t("✗ 脚本 vbmeta_boot.sh 缺失，请重新下载工具包", "✗ Script missing, re-download tools"));
                finishRun(false, "no script");
                return;
            }
            boolean partExists;
            try {
                // 脚本处理 a+b 两个分区，任一存在即可执行（不存在的会被脚本 continue 跳过）
                partExists = com.mcai.ubuntudsu.core.RootShell.INSTANCE.exec(
                        "[ -e /dev/block/bootdevice/by-name/vbmeta_a ] || "
                        + "[ -e /dev/block/bootdevice/by-name/vbmeta_b ] || "
                        + "[ -e /dev/block/by-name/vbmeta_a ] || "
                        + "[ -e /dev/block/by-name/vbmeta_b ]",
                        10000, null).getSuccess();
            } catch (Exception e) {
                partExists = false;
            }
            if (!partExists) {
                log(t("✗ vbmeta 分区不存在或 ROOT 不可用", "✗ vbmeta partition missing or no ROOT"));
                finishRun(false, "partition missing");
                return;
            }
            String cmd = ". " + DnaTools.quote(scriptPath.getAbsolutePath());
            com.mcai.ubuntudsu.core.DnaTools.Result r = DnaTools.run(this, cmd,
                    line -> {
                        log(line);
                        return kotlin.Unit.INSTANCE;
                    },
                    cancelFlag::get,
                    15 * 60_000L);
            if (r.getSuccess()) {
                log(t("✓ 已禁用 " + part + " AVB / dm-verity，重启系统生效",
                        "✓ " + part + " AVB / dm-verity disabled, reboot to apply"));
                finishRun(true, null);
            } else if (cancelFlag.get()) {
                log("⏹ " + t("已终止", "Stopped"));
                finishRun(false, "cancelled");
            } else {
                log("✗ " + r.getMessage());
                finishRun(false, r.getMessage());
            }
        });
    }

    /** 本地 vbmeta 镜像处理：magiskboot hexpatch → 输出到工程区 SELinux 目录 */
    private void runLocalFile() {
        String proj = DnaTools.currentProject(this);
        if (proj == null) {
            log(t("✗ 未选择工程，无法确定输出目录", "✗ No project selected for output"));
            toast(t("请先在工具箱首页选择工程", "Pick a project first"));
            return;
        }
        String outDir = DnaTools.WORK_ROOT + "/" + proj + "/SELinux";
        String inName = new File(localFile).getName();
        String outName = inName.replace(".img", "-disabled.img");
        cancelFlag.set(false);
        runBtn.setEnabled(false);
        statusText.setText(t("处理本地文件 …", "Processing local file …"));
        expandLog();

        io.execute(() -> {
            File outDirF = new File(outDir);
            if (!outDirF.exists() && !outDirF.mkdirs()) {
                log(t("✗ 无法创建输出目录 " + outDir, "✗ Cannot create output dir " + outDir));
                finishRun(false, "mkdir failed");
                return;
            }
            String cmd = "export VBMETA_LOCAL_IN=" + DnaTools.quote(localFile) + "; "
                    + "export VBMETA_LOCAL_OUT=" + DnaTools.quote(outDir + "/" + outName) + "; "
                    + ". " + DnaTools.quote(new File(DnaTools.scriptsDir(this), "vbmeta_local.sh").getAbsolutePath());
            com.mcai.ubuntudsu.core.DnaTools.Result r = DnaTools.run(this, cmd,
                    line -> {
                        log(line);
                        return kotlin.Unit.INSTANCE;
                    },
                    cancelFlag::get,
                    15 * 60_000L);
            if (r.getSuccess()) {
                log(t("✓ 本地文件处理完成 → " + outDir + "/" + outName,
                        "✓ Done → " + outDir + "/" + outName));
                finishRun(true, null);
            } else if (cancelFlag.get()) {
                log("⏹ " + t("已终止", "Stopped"));
                finishRun(false, "cancelled");
            } else {
                log("✗ " + r.getMessage());
                finishRun(false, r.getMessage());
            }
        });
    }

    private void finishRun(boolean success, String msg) {
        main.post(() -> {
            runBtn.setEnabled(true);
            if (success) {
                statusText.setText(t("✓ 完成，重启系统生效", "✓ Done. Reboot to apply."));
            } else if ("cancelled".equals(msg)) {
                statusText.setText(t("已终止", "Stopped"));
            } else {
                statusText.setText(t("失败", "Failed"));
            }
        });
    }
}
