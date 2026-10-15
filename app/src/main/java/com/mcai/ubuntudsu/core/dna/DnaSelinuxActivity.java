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
 * DNA · 内核 SELinux 宽容/严格 独立二级页。
 * 流程：选分区与模式（宽容/严格）→ 开始执行（dd 提取 → magiskboot 改 header → 刷回）
 *      → 页面内日志面板实时输出；15 分钟超时，可点「终止」中断。
 * 静态分区（boot_a/boot_b）默认隐藏，需打开「显示静态分区」开关。
 */
public final class DnaSelinuxActivity extends DnaBaseActivity {

    private static final String PREFS = "dna_tools";
    private static final String KEY_SHOW_STATIC = "selinux_show_static";
    private static final int PICK_VENDOR_BOOT = 3411;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dna-selinux");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);

    // 选择状态
    private String selectedPart = null;
    private boolean selectedPermissive = false;
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
        String preMode = getIntent().getStringExtra("extra_mode");
        if (prePart != null && !prePart.isEmpty()) {
            selectedPart = prePart;
            selectedPermissive = "permissive".equals(preMode);
        }
        buildUi(prePart != null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_VENDOR_BOOT || resultCode != RESULT_OK || data == null) return;
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
        title.setText(t("DNA · SELinux 功能", "DNA · SELinux"));
        title.setTextSize(17);
        title.setTypeface(null, 1);
        title.setTextColor(pal.title);
        title.setPadding(dp(10), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView badge = new TextView(this);
        badge.setText("🛡");
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
        flow.setText("① " + t("选择本机分区", "Pick") + "  →  ② "
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
        // 检测当前 SELinux 状态
        TextView selinuxStatusText = textView(t("检测 SELinux 状态 …", "Detecting SELinux status …"), 10.5f, pal.subtitle);
        LinearLayout.LayoutParams selLp = new LinearLayout.LayoutParams(-1, -2);
        selLp.topMargin = dp(3);
        infoCard.addView(selinuxStatusText, selLp);
        io.execute(() -> {
            String selMode;
            try {
                selMode = com.mcai.ubuntudsu.core.RootShell.INSTANCE
                        .exec("getenforce 2>/dev/null", 5000, null)
                        .getStdout().trim().toUpperCase();
            } catch (Exception e) {
                selMode = "UNKNOWN";
            }
            String selLabel;
            switch (selMode) {
                case "ENFORCING":
                    selLabel = t("SELinux 状态: 严格 (Enforcing)", "SELinux: Enforcing");
                    break;
                case "PERMISSIVE":
                    selLabel = t("SELinux 状态: 宽容 (Permissive)", "SELinux: Permissive");
                    break;
                default:
                    selLabel = t("SELinux 状态: 未知", "SELinux: Unknown");
                    break;
            }
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                selinuxStatusText.setText(selLabel);
            });
        });
        TextView warn = textView(
                t("dd 提取分区 → magiskboot 改 header → 刷回，重启生效。需 ROOT；误改可能无法开机。",
                        "dd extract → magiskboot header edit → flash back. Root required; wrong ops may break boot."),
                13f, pal.danger);
        warn.setPadding(0, dp(6), 0, 0);
        infoCard.addView(warn);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(-1, -2);
        infoLp.topMargin = dp(6);
        content.addView(infoCard, infoLp);

        // ---- 分区与模式选择卡（标题与执行按钮合并进一张卡，减少碎片） ----
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

    /** 分区 + 模式按钮组（每分区一行，宽容/严格两个按钮；静态分区受开关控制） */
    private void buildPartRows(LinearLayout container) {
        boolean isDark = isDark();
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean showStatic = prefs.getBoolean(KEY_SHOW_STATIC, false);

        LinearLayout toggleRow = new LinearLayout(this);
        toggleRow.setOrientation(LinearLayout.HORIZONTAL);
        toggleRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(-1, -2);
        toggleLp.topMargin = dp(4);
        toggleRow.setLayoutParams(toggleLp);
        Switch sw = new Switch(this);
        sw.setText(t("显示静态分区", "Show static partitions"));
        sw.setTextSize(12.5f);
        sw.setTypeface(null, 1);
        sw.setTextColor(isDark ? 0xFFFFFFFF : 0xFF1F3352);
        sw.setChecked(showStatic);
        final LinearLayout staticBox = new LinearLayout(this);
        staticBox.setOrientation(LinearLayout.VERTICAL);
        sw.setOnCheckedChangeListener((v, checked) -> {
            prefs.edit().putBoolean(KEY_SHOW_STATIC, checked).apply();
            staticBox.setVisibility(checked ? View.VISIBLE : View.GONE);
        });
        toggleRow.addView(sw, new LinearLayout.LayoutParams(0, -2, 1f));
        container.addView(toggleRow);

        container.addView(groupLabel(t("动态分区", "Dynamic"), isDark));
        container.addView(partRow("vendor_boot_a"));
        container.addView(partRow("vendor_boot_b"));

        staticBox.addView(groupLabel(t("静态分区", "Static"), isDark));
        staticBox.addView(partRow("boot_a"));
        staticBox.addView(partRow("boot_b"));
        staticBox.setVisibility(showStatic ? View.VISIBLE : View.GONE);
        container.addView(staticBox);
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

        Button permissiveBtn = new Button(this);
        Button enforcingBtn = new Button(this);
        setupModeBtn(permissiveBtn, true, part);
        setupModeBtn(enforcingBtn, false, part);
        row.addView(permissiveBtn);
        LinearLayout.LayoutParams enfLp = new LinearLayout.LayoutParams(-2, -2);
        enfLp.leftMargin = dp(8);
        row.addView(enforcingBtn, enfLp);

        partBtnPairs.add(new Object[]{part, permissiveBtn, enforcingBtn});
        return row;
    }

    private void refreshAllPartRows() {
        for (Object[] p : partBtnPairs) {
            String part = (String) p[0];
            Button permissiveBtn = (Button) p[1];
            Button enforcingBtn = (Button) p[2];
            boolean rowSelected = part.equals(selectedPart);
            applyModeBg(permissiveBtn, rowSelected && selectedPermissive);
            applyModeBg(enforcingBtn, rowSelected && !selectedPermissive);
        }
    }

    private void setupModeBtn(Button b, boolean permissive, String part) {
        boolean isDark = isDark();
        b.setText(permissive ? t("宽容", "Permissive") : t("严格", "Enforcing"));
        b.setAllCaps(false);
        b.setTextSize(11f);
        b.setTypeface(null, 1);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), dp(5), dp(12), dp(5));
        b.setBackground(dimModeBg(isDark));
        b.setTextColor(isDark ? 0x99FFFFFF : 0x991F3352);
        b.setOnClickListener(v -> {
            Haptics.perform(v);
            if (part != null) {
                selectedPart = part;
                selectedPermissive = permissive;
                runBtn.setEnabled(true);
                statusText.setText(t("已选择 " + (permissive ? "宽容" : "严格") + " " + part,
                        "Selected " + (permissive ? "permissive" : "enforcing") + " " + part));
                refreshAllPartRows();
            } else {
                selectedPermissive = permissive;
                statusText.setText(t("本地文件模式：" + (permissive ? "宽容" : "严格"),
                        "Local mode: " + (permissive ? "permissive" : "enforcing")));
                // 本地文件模式：刷新同行两个按钮（选中亮，未选灰暗）
                ViewGroup parent = (ViewGroup) v.getParent();
                for (int i = 0; i < parent.getChildCount(); i++) {
                    View child = parent.getChildAt(i);
                    if (child instanceof Button) {
                        Button bb = (Button) child;
                        boolean isPermissive = t("宽容", "Permissive").equals(bb.getText().toString());
                        boolean isEnforcing = t("严格", "Enforcing").equals(bb.getText().toString());
                        boolean isSel = (isPermissive && permissive) || (isEnforcing && !permissive);
                        applyModeBg(bb, isSel);
                    }
                }
            }
        });
        b.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
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

    private void refreshModeRow(Button permissiveBtn, Button enforcingBtn) {
        boolean permissiveSelected = selectedPermissive;
        applyModeBg(permissiveBtn, permissiveSelected);
        applyModeBg(enforcingBtn, !permissiveSelected);
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

    // ================= 日志面板（默认隐藏，执行时弹出，对齐 DnaActivity logCard/expandLog） =================

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
            cm.setPrimaryClip(android.content.ClipData.newPlainText("SELinux log", text));
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

    /** 自选镜像处理卡：选文件 + 宽容/严格 + 保存到工程区 SELinux 目录 */
    private void buildLocalFileCard(LinearLayout content) {
        LinearLayout card = glassCard();
        TextView localTitle = textView(t("自选镜像模式", "Custom Image Mode"), 13f, pal.title);
        localTitle.setTypeface(null, 1);
        card.addView(localTitle);
        card.addView(textView(
                t("选择 boot.img 或 vendor_boot.img → magiskboot 改 header → 宽容/严格结果保存到工程区 SELinux 目录",
                        "Pick boot.img or vendor_boot.img → magiskboot header edit → save to project SELinux dir"),
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
                            this, t("选择 vendor_boot.img", "Pick vendor_boot.img"),
                            new String[]{"vendor_boot.img", ".img"}),
                    PICK_VENDOR_BOOT);
        });
        LinearLayout.LayoutParams browseLp = new LinearLayout.LayoutParams(-2, -2);
        browseLp.leftMargin = dp(8);
        fileRow.addView(browseBtn, browseLp);
        card.addView(fileRow);

        // 模式选择：宽容 / 严格 两按钮（选中亮，未选灰暗）—— 按钮居右
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);
        modeRow.setPadding(0, dp(6), 0, 0);
        TextView modeLabel = textView(t("模式", "Mode"), 11.5f, pal.subtitle);
        modeLabel.setTypeface(null, 1);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0, -2, 1f);
        modeRow.addView(modeLabel, labelLp);
        Button permissiveBtn = new Button(this);
        Button enforcingBtn = new Button(this);
        setupModeBtn(permissiveBtn, true, null);
        setupModeBtn(enforcingBtn, false, null);
        LinearLayout.LayoutParams permissLp = new LinearLayout.LayoutParams(-2, -2);
        permissLp.leftMargin = dp(10);
        modeRow.addView(permissiveBtn, permissLp);
        LinearLayout.LayoutParams enforceLp = new LinearLayout.LayoutParams(-2, -2);
        enforceLp.leftMargin = dp(8);
        modeRow.addView(enforcingBtn, enforceLp);
        if (selectedPermissive) refreshModeRow(permissiveBtn, enforcingBtn);
        card.addView(modeRow);

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
            runLocalFile(selectedPermissive);
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
        final boolean permissive = selectedPermissive;
        io.execute(() -> {
            File scriptPath = new File(DnaTools.scriptsDir(this), "selinux_boot.sh");
            if (!scriptPath.isFile()) {
                log(t("✗ 脚本 selinux_boot.sh 缺失，请重新下载工具包", "✗ Script missing, re-download tools"));
                finishRun(false, "no script");
                return;
            }
            boolean partExists;
            try {
                partExists = com.mcai.ubuntudsu.core.RootShell.INSTANCE.exec(
                        "[ -e /dev/block/by-name/" + part + " ] || [ -e /dev/block/bootdevice/by-name/" + part + " ]",
                        10000, null).getSuccess();
            } catch (Exception e) {
                partExists = false;
            }
            if (!partExists) {
                log(t("✗ 分区 " + part + " 不存在或 ROOT 不可用", "✗ Partition " + part + " missing or no ROOT"));
                finishRun(false, "partition missing");
                return;
            }
            String cmd = "export SELINUX_PART=" + DnaTools.quote(part) + "; "
                    + "export SELINUX_MODE=" + DnaTools.quote(permissive ? "permissive" : "enforcing") + "; "
                    + ". " + DnaTools.quote(scriptPath.getAbsolutePath());
            com.mcai.ubuntudsu.core.DnaTools.Result r = DnaTools.run(this, cmd,
                    line -> {
                        log(line);
                        return kotlin.Unit.INSTANCE;
                    },
                    cancelFlag::get,
                    15 * 60_000L);
            if (r.getSuccess()) {
                log(t("✓ 已刷入" + (permissive ? "宽容" : "严格") + "，重启系统生效",
                        "✓ " + (permissive ? "Permissive" : "Enforcing") + " flashed, reboot to apply"));
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

    /** 自选镜像处理：magiskboot 改 header → 输出到工程区 SELinux 目录 */
    private void runLocalFile(boolean permissive) {
        String proj = DnaTools.currentProject(this);
        if (proj == null) {
            log(t("✗ 未选择工程，无法确定输出目录", "✗ No project selected for output"));
            toast(t("请先在工具箱首页选择工程", "Pick a project first"));
            return;
        }
        String outDir = DnaTools.WORK_ROOT + "/" + proj + "/SELinux";
        String inName = new File(localFile).getName();
        String outName = inName.replace(".img", "-" + (permissive ? "permissive" : "enforcing") + ".img");
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
            String cmd = "export SELINUX_MODE=" + DnaTools.quote(permissive ? "permissive" : "enforcing") + "; "
                    + "export SELINUX_LOCAL_IN=" + DnaTools.quote(localFile) + "; "
                    + "export SELINUX_LOCAL_OUT=" + DnaTools.quote(outDir + "/" + outName) + "; "
                    + ". " + DnaTools.quote(new File(DnaTools.scriptsDir(this), "selinux_local.sh").getAbsolutePath());
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
