package com.mcai.ubuntudsu.core.dna;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.text.TextWatcher;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.mcai.ubuntudsu.R;
import com.mcai.ubuntudsu.RootfsFilesActivity;
import com.mcai.ubuntudsu.core.DnaTools;
import com.mcai.ubuntudsu.core.RootShell;
import com.mcai.ubuntudsu.ui.Haptics;
import com.mcai.ubuntudsu.ui.Ui;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DNA · 本机分区 提取 / 刷写 独立二级页。
 * 列出 /dev/block/by-name 下全部本机分区，支持：
 *  - 提取：dd if=<分区块设备> of=<目标 img>（默认保存到工程 Partitions 目录或 /sdcard/Download）
 *  - 刷写：dd if=<本地 img> of=<分区块设备>
 * 进度通过 /proc/<pid>/io 实时回传；需 ROOT。直接操作块设备有误刷变砖风险。
 */
public final class DnaPartitionActivity extends DnaBaseActivity {

    public static final String EXTRA_MODE = "extra_mode";
    public static final String MODE_EXTRACT = "extract";
    public static final String MODE_FLASH = "flash";

    private static final int PICK_SOURCE_IMG = 3601;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dna-partition");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);

    private String mode = MODE_EXTRACT;
    private final LinkedHashSet<String> selectedParts = new LinkedHashSet<>();
    private String sourceFile = null;          // flash 模式选中的本地 img

    // 提取模式的目标根目录（工程 Partitions / Download）
    private String destDir = null;

    private Ui.DnaPalette pal;

    // UI
    private TextView statusText;
    private TextView progressText;
    private ProgressBar progressBar;
    private EditText destEdit;                 // extract 目标目录
    private TextView sourceText;              // flash 源文件
    private Button runBtn;
    private final List<String> allParts = new ArrayList<>();
    private final List<String> filteredParts = new ArrayList<>();
    private TextView selSummary;               // 页面已选分区摘要
    private Button pickPartBtn;                // 「选择分区」按钮（打开弹框）
    private android.app.Dialog partDialog;

    private Button extractModeBtn;
    private Button flashModeBtn;

    private TextView logDisplay;
    private ScrollView logScroll;
    private LinearLayout logCardView;
    private android.app.Dialog logDialog;

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
        Haptics.INSTANCE.onTouch(getWindow().getDecorView(), e);
        return super.dispatchTouchEvent(e);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0x00000000);
        Ui.INSTANCE.enableEdgeToEdge(this, getWindow().getDecorView());
        pal = Ui.INSTANCE.dnaPalette(this);
        String m = getIntent().getStringExtra(EXTRA_MODE);
        if (MODE_FLASH.equals(m)) mode = MODE_FLASH;
        buildUi();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_SOURCE_IMG || resultCode != RESULT_OK || data == null) return;
        String path = data.getStringExtra(RootfsFilesActivity.RESULT_FILE_PATH);
        if (path == null || !path.startsWith("/")) return;
        sourceFile = path;
        sourceText.setText(path);
        sourceText.setTextColor(pal.success);
        refreshRunState();
        statusText.setText(t("已选源镜像 " + new File(path).getName(), "Source " + new File(path).getName()));
    }

    @Override
    protected void onDestroy() {
        if (logDialog != null && logDialog.isShowing()) logDialog.dismiss();
        if (partDialog != null && partDialog.isShowing()) partDialog.dismiss();
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

    private TextView textView(String text, float size, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(size);
        tv.setTextColor(color);
        return tv;
    }

    private void buildUi() {
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
        title.setText(t("DNA · 本机分区", "DNA · Device Partitions"));
        title.setTextSize(17);
        title.setTypeface(null, 1);
        title.setTextColor(pal.title);
        title.setPadding(dp(10), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView badge = new TextView(this);
        badge.setText("🗂");
        badge.setTextSize(13f);
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
        flow.setText("① " + t("选择分区", "Pick") + "  →  ② " + t("设置源/目标", "Set I/O") + "  →  ③ " + t("开始", "Run"));
        flow.setTextSize(11.5f);
        flow.setTextColor(pal.success);
        flow.setTypeface(null, 1);
        LinearLayout.LayoutParams flowLp = new LinearLayout.LayoutParams(-1, -2);
        flowLp.bottomMargin = dp(8);
        content.addView(flow, flowLp);

        // ---- 状态卡 ----
        LinearLayout infoCard = glassCard();
        String cur = DnaTools.currentProject(this);
        infoCard.addView(textView(t("当前工程", "Project") + "：" + (cur != null ? cur : t("未选择", "None")), 12f, pal.subtitle));
        statusText = textView(t("待执行", "Idle"), 11.5f, pal.subtitle);
        statusText.setTypeface(null, 1);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
        statusLp.topMargin = dp(3);
        infoCard.addView(statusText, statusLp);

        String slot = DnaTools.slotSuffix(this);
        String slotLabel = (slot != null && !slot.isEmpty())
                ? slot.toUpperCase()
                : t("未识别（可能 A-only 设备）", "Not detected (A-only)");
        infoCard.addView(textView(t("当前活跃槽位: " + slotLabel, "Active slot: " + slotLabel), 10.5f, pal.subtitle));

        TextView rootText = textView(t("检测 ROOT …", "Detecting ROOT …"), 10.5f, pal.subtitle);
        infoCard.addView(rootText);
        io.execute(() -> {
            boolean rootAvail = RootShell.INSTANCE.available(8000);
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                rootText.setText(t("ROOT：" + (rootAvail ? "可用" : "不可用（需授权）"), "ROOT: " + (rootAvail ? "OK" : "denied")));
                rootText.setTextColor(rootAvail ? pal.success : pal.danger);
            });
        });

        TextView warn = textView(
                t("直接操作块设备有误刷变砖风险，请确认分区与镜像匹配，操作前务必备份。",
                        "Writing raw block devices may brick the device. Verify partition/image and back up first."),
                12f, pal.danger);
        warn.setPadding(0, dp(6), 0, 0);
        infoCard.addView(warn);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(-1, -2);
        infoLp.topMargin = dp(6);
        content.addView(infoCard, infoLp);

        // ---- 模式切换（提取 / 刷写） ----
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setPadding(dp(3), dp(3), dp(3), dp(3));
        modeRow.setBackground(rippleBg(isDark()));
        LinearLayout.LayoutParams modeRowLp = new LinearLayout.LayoutParams(-1, -2);
        modeRowLp.topMargin = dp(8);
        modeRow.setLayoutParams(modeRowLp);
        extractModeBtn = modeButton(t("📤 提取分区", "Extract"), true);
        flashModeBtn = modeButton(t("📥 刷写分区", "Flash"), false);
        extractModeBtn.setOnClickListener(v -> { Haptics.INSTANCE.perform(v); setMode(MODE_EXTRACT); });
        flashModeBtn.setOnClickListener(v -> { Haptics.INSTANCE.perform(v); setMode(MODE_FLASH); });
        modeRow.addView(extractModeBtn, new LinearLayout.LayoutParams(0, dp(40), 1f));
        LinearLayout.LayoutParams flashLp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        flashLp.leftMargin = dp(8);
        modeRow.addView(flashModeBtn, flashLp);
        content.addView(modeRow);

        // ---- 分区选择（弹框，参考分解 bin 的弹窗勾选） ----
        LinearLayout partCard = glassCard();
        partCard.addView(textView(t("本机分区", "Device Partitions"), 13f, pal.title));
        ((TextView) partCard.getChildAt(0)).setTypeface(null, 1);

        pickPartBtn = new Button(this);
        pickPartBtn.setText(t("🗂 选择分区（已选 0）", "🗂 Pick (0)"));
        pickPartBtn.setAllCaps(false);
        pickPartBtn.setTextSize(13f);
        pickPartBtn.setTypeface(null, 1);
        pickPartBtn.setTextColor(0xFFFFFFFF);
        pickPartBtn.setGravity(Gravity.CENTER);
        pickPartBtn.setPadding(dp(12), 0, dp(12), 0);
        GradientDrawable pickBg = new GradientDrawable();
        pickBg.setOrientation(GradientDrawable.Orientation.TL_BR);
        pickBg.setColors(new int[]{0xFF35A8C4, 0xFF2E86AB});
        pickBg.setCornerRadius(dp(14));
        pickBg.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
        pickPartBtn.setBackground(pickBg);
        pickPartBtn.setStateListAnimator(null);
        pickPartBtn.setOnClickListener(v -> { Haptics.INSTANCE.perform(v); showPartitionDialog(); });
        LinearLayout.LayoutParams pickLp = new LinearLayout.LayoutParams(-1, dp(44));
        pickLp.topMargin = dp(8);
        partCard.addView(pickPartBtn, pickLp);

        selSummary = textView(t("尚未选择分区", "No partition selected"), 12f, pal.subtitle);
        selSummary.setPadding(0, dp(6), 0, 0);
        partCard.addView(selSummary);

        LinearLayout.LayoutParams partCardLp = new LinearLayout.LayoutParams(-1, -2);
        partCardLp.topMargin = dp(8);
        content.addView(partCard, partCardLp);

        // ---- 源/目标设置卡（按模式切换可见性） ----
        buildIoCard(content);

        // ---- 进度 ----
        progressText = textView("", 11.5f, pal.subtitle);
        LinearLayout.LayoutParams progTextLp = new LinearLayout.LayoutParams(-1, -2);
        progTextLp.topMargin = dp(8);
        content.addView(progressText, progTextLp);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams progBarLp = new LinearLayout.LayoutParams(-1, -2);
        progBarLp.topMargin = dp(4);
        content.addView(progressBar, progBarLp);

        // ---- 执行按钮 ----
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        runBtn = new Button(this);
        runBtn.setText(t("开始提取", "Start Extract"));
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
        runBtn.setEnabled(false);
        runBtn.setOnClickListener(v -> { Haptics.INSTANCE.perform(v); startRun(); });
        btnRow.addView(runBtn, new LinearLayout.LayoutParams(0, dp(44), 1f));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(-1, -2);
        btnLp.topMargin = dp(8);
        btnLp.bottomMargin = dp(8);
        content.addView(btnRow, btnLp);

        // ---- 日志面板（默认隐藏，执行时弹出） ----
        buildLogCard();

        // 初始化模式与默认目标目录
        applyDestDirDefault();
        setMode(mode);
        loadPartitions();
    }

    private Button modeButton(String label, boolean selected) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(12.5f);
        b.setTypeface(null, 1);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(8), 0, dp(8), 0);
        return b;
    }

    private void setMode(String m) {
        mode = m;
        selectedParts.clear();
        updateSelSummary();
        boolean extract = MODE_EXTRACT.equals(m);
        applyModeBg(extractModeBtn, extract);
        applyModeBg(flashModeBtn, !extract);
        if (extractModeBtn != null && flashModeBtn != null) {
            extractModeBtn.setTextColor(extract ? 0xFFFFFFFF : (isDark() ? 0x99FFFFFF : 0x991F3352));
            flashModeBtn.setTextColor(!extract ? 0xFFFFFFFF : (isDark() ? 0x99FFFFFF : 0x991F3352));
        }
        runBtn.setText(extract ? t("开始提取", "Start Extract") : t("开始刷写", "Start Flash"));
        if (extractIoCard != null) extractIoCard.setVisibility(extract ? View.VISIBLE : View.GONE);
        if (flashIoCard != null) flashIoCard.setVisibility(extract ? View.GONE : View.VISIBLE);
        // 切模式清空源文件选择状态
        if (!extract && sourceFile != null) {
            sourceFile = null;
            if (sourceText != null) {
                sourceText.setText(t("未选择文件", "No file selected"));
                sourceText.setTextColor(pal.subtitle);
            }
        }
        refreshRunState();
    }

    private void applyModeBg(Button b, boolean selected) {
        b.setBackground(selected ? selModeBg(isDark()) : dimModeBg(isDark()));
    }

    private LinearLayout extractIoCard;
    private LinearLayout flashIoCard;

    private void buildIoCard(LinearLayout content) {
        // 提取：目标路径
        extractIoCard = glassCard();
        extractIoCard.addView(textView(t("提取目标（输出 img）", "Extract target (output img)"), 13f, pal.title));
        ((TextView) extractIoCard.getChildAt(0)).setTypeface(null, 1);
        destEdit = new EditText(this);
        destEdit.setTextSize(12f);
        destEdit.setSingleLine(true);
        destEdit.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        destEdit.setPadding(dp(10), dp(8), dp(10), dp(8));
        destEdit.setBackground(rippleBg(isDark()));
        LinearLayout.LayoutParams destLp = new LinearLayout.LayoutParams(-1, -2);
        destLp.topMargin = dp(6);
        extractIoCard.addView(destEdit, destLp);
        TextView destHint = textView(
                t("每个所选分区将单独保存为 <目录>/<分区名>.img", "Each selected partition → <dir>/<name>.img"),
                10.5f, pal.subtitle);
        destHint.setPadding(0, dp(4), 0, 0);
        extractIoCard.addView(destHint);

        LinearLayout dirRow = new LinearLayout(this);
        dirRow.setOrientation(LinearLayout.HORIZONTAL);
        dirRow.setPadding(0, dp(6), 0, 0);
        Button projBtn = new Button(this);
        projBtn.setText(t("工程 Partitions", "Project Partitions"));
        setupChip(projBtn);
        projBtn.setOnClickListener(v -> { Haptics.INSTANCE.perform(v); useProjectDir(); });
        Button dlBtn = new Button(this);
        dlBtn.setText(t("/sdcard/Download", "/sdcard/Download"));
        setupChip(dlBtn);
        dlBtn.setOnClickListener(v -> { Haptics.INSTANCE.perform(v); destDir = "/sdcard/Download"; recomputeDest(); });
        dirRow.addView(projBtn, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams dlLp = new LinearLayout.LayoutParams(0, -2, 1f);
        dlLp.leftMargin = dp(8);
        dirRow.addView(dlBtn, dlLp);
        extractIoCard.addView(dirRow);
        LinearLayout.LayoutParams eLp = new LinearLayout.LayoutParams(-1, -2);
        eLp.topMargin = dp(8);
        content.addView(extractIoCard, eLp);

        // 刷写：源文件
        flashIoCard = glassCard();
        flashIoCard.addView(textView(t("刷写源镜像（本地 img）", "Flash source (local img)"), 13f, pal.title));
        ((TextView) flashIoCard.getChildAt(0)).setTypeface(null, 1);
        LinearLayout fileRow = new LinearLayout(this);
        fileRow.setOrientation(LinearLayout.HORIZONTAL);
        fileRow.setGravity(Gravity.CENTER_VERTICAL);
        fileRow.setPadding(0, dp(8), 0, 0);
        sourceText = textView(t("未选择文件", "No file selected"), 12f, pal.subtitle);
        sourceText.setSingleLine(true);
        sourceText.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        fileRow.addView(sourceText, new LinearLayout.LayoutParams(0, -2, 1f));
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
            Haptics.INSTANCE.perform(v);
            startActivityForResult(
                    RootfsFilesActivity.createPickIntent(
                            this, t("选择要刷写的 img", "Pick img to flash"),
                            new String[]{".img"}),
                    PICK_SOURCE_IMG);
        });
        LinearLayout.LayoutParams browseLp = new LinearLayout.LayoutParams(-2, -2);
        browseLp.leftMargin = dp(8);
        fileRow.addView(browseBtn, browseLp);
        flashIoCard.addView(fileRow);
        TextView flashHint = textView(
                t("刷写会将镜像内容写入所选分区（如 boot / vendor_boot / system），刷错分区可能无法开机。",
                        "Flash writes the image into the selected partition; wrong target may break boot."),
                10.5f, pal.subtitle);
        flashHint.setPadding(0, dp(6), 0, 0);
        flashIoCard.addView(flashHint);
        LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(-1, -2);
        fLp.topMargin = dp(8);
        content.addView(flashIoCard, fLp);
    }

    private void setupChip(Button b) {
        b.setAllCaps(false);
        b.setTextSize(11f);
        b.setTypeface(null, 1);
        b.setTextColor(pal.accent);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        b.setBackground(rippleBg(isDark()));
    }

    private void applyDestDirDefault() {
        String proj = DnaTools.currentProject(this);
        destDir = (proj != null) ? DnaTools.WORK_ROOT + "/" + proj + "/Partitions" : "/sdcard/Download";
        recomputeDest();
    }

    private void useProjectDir() {
        String proj = DnaTools.currentProject(this);
        if (proj == null) {
            toast(t("请先在工具箱首页选择工程", "Pick a project first"));
            destDir = "/sdcard/Download";
        } else {
            destDir = DnaTools.WORK_ROOT + "/" + proj + "/Partitions";
        }
        recomputeDest();
    }

    private void recomputeDest() {
        if (destEdit == null) return;
        destEdit.setText(destDir);
    }

    // ================= 分区列表（弹框选择，参考分解 bin） =================

    private void loadPartitions() {
        statusText.setText(t("正在枚举本机分区 …", "Enumerating partitions …"));
        io.execute(() -> {
            List<String> parts = DnaTools.listPartitions(this);
            main.post(() -> fillPartList(parts));
        });
    }

    private void fillPartList(List<String> parts) {
        allParts.clear();
        if (parts == null || parts.isEmpty()) {
            statusText.setText(t("未枚举到分区（需 ROOT 或设备不支持 by-name）", "No partitions found (need ROOT)"));
            updateSelSummary();
            return;
        }
        allParts.addAll(parts);
        statusText.setText(t("共 " + allParts.size() + " 个分区，点「选择分区」挑选", allParts.size() + " partitions, tap Pick"));
        updateSelSummary();
    }

    /** 刷新页面已选分区摘要 + 执行按钮可用性 */
    private void updateSelSummary() {
        if (pickPartBtn != null)
            pickPartBtn.setText(t("🗂 选择分区（已选 " + selectedParts.size() + "）", "🗂 Pick (" + selectedParts.size() + ")"));
        if (selSummary != null) {
            if (selectedParts.isEmpty()) {
                selSummary.setText(t("尚未选择分区", "No partition selected"));
                selSummary.setTextColor(pal.subtitle);
            } else {
                StringBuilder sb = new StringBuilder();
                int i = 0;
                for (String p : selectedParts) {
                    if (i++ > 0) sb.append(", ");
                    sb.append(p);
                    if (i >= 12) { sb.append(" …"); break; }
                }
                selSummary.setText(t("已选：" + sb, "Selected: " + sb));
                selSummary.setTextColor(pal.success);
            }
        }
        refreshRunState();
    }

    private void refreshRunState() {
        boolean ok = !selectedParts.isEmpty() && (MODE_EXTRACT.equals(mode) || sourceFile != null);
        runBtn.setEnabled(ok);
    }

    /** 分区选择弹框：搜索 + 可滚动列表。提取模式提供全选/取消（多选）；刷写模式为单选，不提供全选/取消，避免误刷多分区。 */
    private void showPartitionDialog() {
        if (isFinishing() || isDestroyed()) return;
        if (allParts.isEmpty()) {
            toast(t("尚未枚举到分区，请稍候或检查 ROOT", "No partitions yet, wait or check ROOT"));
            return;
        }
        final boolean flash = MODE_FLASH.equals(mode);
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.setCancelable(true);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(16), dp(18), dp(14));
        GradientDrawable bg = new GradientDrawable();
        boolean dark = isDark();
        bg.setColor(dark ? 0xF22A3546 : 0xF2e9f0f7);
        bg.setCornerRadius(dp(24));
        bg.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
        panel.setBackground(bg);

        TextView title = textView(
                t("选择本机分区", "Select device partition") + (flash ? t("（单选）", " (single)") : t("（可多选）", " (multi)")),
                16f, pal.title);
        title.setTypeface(null, 1);
        title.setPadding(dp(2), 0, 0, dp(10));
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));

        final EditText search = new EditText(this);
        search.setHint(t("搜索分区，如 boot / system / vendor", "Search, e.g. boot / system / vendor"));
        search.setTextSize(12f);
        search.setSingleLine(true);
        search.setPadding(dp(10), dp(8), dp(10), dp(8));
        search.setBackground(rippleBg(dark));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(-1, -2);
        searchLp.bottomMargin = dp(8);
        panel.addView(search, searchLp);

        final TextView count = textView("", 12f, pal.success);
        count.setTypeface(null, 1);
        count.setPadding(0, 0, 0, dp(6));
        panel.addView(count, new LinearLayout.LayoutParams(-1, -2));

        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            filteredParts.clear();
            String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            for (String p : allParts)
                if (q.isEmpty() || p.toLowerCase(Locale.ROOT).contains(q)) filteredParts.add(p);
            list.removeAllViews();
            for (final String part : filteredParts) {
                final boolean on = selectedParts.contains(part);
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(11), dp(10), dp(10), dp(10));
                GradientDrawable rb = new GradientDrawable();
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
                View dot = new View(this);
                GradientDrawable db = new GradientDrawable();
                db.setShape(GradientDrawable.OVAL);
                if (on) { db.setColor(pal.accent); db.setStroke(Math.max(1, dp(1)), 0xB3FFFFFF); }
                else { db.setColor(0x00000000); db.setStroke(Math.max(1, dp(1)), dark ? 0x665a728f : 0x668fa1b8); }
                dot.setBackground(db);
                row.addView(dot, new LinearLayout.LayoutParams(dp(18), dp(18)));
                TextView name = textView(part, 13.5f, on ? pal.success : pal.title);
                name.setTypeface(null, on ? 1 : 0);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                name.setPadding(dp(10), 0, dp(6), 0);
                row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
                row.setOnClickListener(v -> {
                    Haptics.INSTANCE.perform(v);
                    if (flash) {
                        if (selectedParts.contains(part)) selectedParts.remove(part);
                        else { selectedParts.clear(); selectedParts.add(part); }
                    } else {
                        if (selectedParts.contains(part)) selectedParts.remove(part);
                        else selectedParts.add(part);
                    }
                    render[0].run();
                    updateSelSummary();
                });
                LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(-1, -2);
                rLp.bottomMargin = dp(6);
                list.addView(row, rLp);
            }
            count.setText(t("已选 " + selectedParts.size() + " / 显示 " + filteredParts.size() + " / 共 " + allParts.size(),
                    "Selected " + selectedParts.size() + " / shown " + filteredParts.size() + " / total " + allParts.size()));
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { render[0].run(); }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
        render[0].run();

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        btnRow.setPadding(0, dp(12), 0, 0);
        if (!flash) {
            Button allBtn = new Button(this);
            allBtn.setText(t("全选", "Select All"));
            setupChip(allBtn);
            allBtn.setOnClickListener(v -> {
                Haptics.INSTANCE.perform(v);
                selectedParts.addAll(filteredParts);
                render[0].run();
                updateSelSummary();
            });
            Button clearBtn = new Button(this);
            clearBtn.setText(t("取消", "Clear"));
            setupChip(clearBtn);
            clearBtn.setOnClickListener(v -> {
                Haptics.INSTANCE.perform(v);
                selectedParts.clear();
                render[0].run();
                updateSelSummary();
            });
            btnRow.addView(allBtn, new LinearLayout.LayoutParams(0, -2, 1f));
            LinearLayout.LayoutParams clLp = new LinearLayout.LayoutParams(0, -2, 1f);
            clLp.leftMargin = dp(8);
            btnRow.addView(clearBtn, clLp);
        }
        Button doneBtn = gradientButton(t("完成", "Done"), new int[]{0xFF2f9c8f, 0xFF1d6b46}, dp(16));
        doneBtn.setOnClickListener(v -> { Haptics.INSTANCE.perform(v); dialog.dismiss(); });
        LinearLayout.LayoutParams doneLp = new LinearLayout.LayoutParams(0, dp(42), flash ? 1f : 1.2f);
        if (!flash) doneLp.leftMargin = dp(8);
        btnRow.addView(doneBtn, doneLp);
        panel.addView(btnRow, new LinearLayout.LayoutParams(-1, -2));

        dialog.setContentView(panel, new LinearLayout.LayoutParams(-1, dp(500)));
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        try {
            dialog.show();
            dialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.94f), dp(500));
        } catch (Exception ignored) {}
        partDialog = dialog;
    }

    private Button gradientButton(String label, int[] colors, int radius) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(13f);
        b.setAllCaps(false);
        b.setTypeface(null, 1);
        b.setTextColor(0xFFFFFFFF);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, 0, 0, 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setOrientation(GradientDrawable.Orientation.TL_BR);
        bg.setColors(colors);
        bg.setCornerRadius(radius);
        bg.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
        b.setBackground(bg);
        b.setStateListAnimator(null);
        return b;
    }

    // ================= 执行逻辑 =================

    private void startRun() {
        if (selectedParts.isEmpty()) {
            toast(t("请先选择至少一个本机分区", "Select at least one partition"));
            return;
        }
        if (!RootShell.INSTANCE.available(8000)) {
            toast(t("未获得 ROOT 授权", "ROOT not granted"));
            return;
        }
        cancelFlag.set(false);
        runBtn.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        progressText.setText(t("准备中 …", "Preparing …"));
        expandLog();

        final List<String> targets = new ArrayList<>(selectedParts);
        if (MODE_EXTRACT.equals(mode)) {
            final String baseDir = destDir;
            if (baseDir == null || baseDir.trim().isEmpty()) {
                toast(t("请设置提取目标目录", "Set output directory"));
                runBtn.setEnabled(true);
                return;
            }
            log(t("批量提取 " + targets.size() + " 个分区 → " + baseDir, "Batch extract " + targets.size() + " → " + baseDir));
            io.execute(() -> runBatchExtract(targets, baseDir));
        } else {
            if (sourceFile == null) {
                toast(t("请先选择要刷写的 img", "Pick an img to flash"));
                runBtn.setEnabled(true);
                return;
            }
            final String src = sourceFile;
            log(t("批量刷写 " + src + " → " + targets.size() + " 个分区", "Batch flash " + src + " → " + targets.size()));
            io.execute(() -> runBatchFlash(targets, src));
        }
    }

    private void runBatchExtract(List<String> parts, String baseDir) {
        int total = parts.size();
        int[] cur = {0};
        for (String part : parts) {
            if (cancelFlag.get()) { finishRun(false, "已取消", "", ""); return; }
            cur[0]++;
            final int idx = cur[0];
            final String dev = DnaTools.partitionPath(this, part);
            final String dst = baseDir + "/" + part + ".img";
            final int baseline = (idx - 1) * 100;
            log(t("[" + idx + "/" + total + "] 提取 " + part + " → " + dst, "[" + idx + "/" + total + "] extract " + part + " → " + dst));
            com.mcai.ubuntudsu.core.DnaTools.Result r = DnaTools.ddPartition(dev, dst,
                    (pct, written) -> { onProgress((baseline + pct) / total, written); return kotlin.Unit.INSTANCE; },
                    line -> { log(line); return kotlin.Unit.INSTANCE; },
                    cancelFlag::get,
                    30 * 60_000L);
            if (!r.getSuccess()) { finishRun(false, r.getMessage(), dev, dst); return; }
        }
        finishRun(true, "", "", "");
    }

    private void runBatchFlash(List<String> parts, String src) {
        int total = parts.size();
        int[] cur = {0};
        for (String part : parts) {
            if (cancelFlag.get()) { finishRun(false, "已取消", "", ""); return; }
            cur[0]++;
            final int idx = cur[0];
            final String dev = DnaTools.partitionPath(this, part);
            final int baseline = (idx - 1) * 100;
            log(t("[" + idx + "/" + total + "] 刷写 " + src + " → " + part, "[" + idx + "/" + total + "] flash " + src + " → " + part));
            com.mcai.ubuntudsu.core.DnaTools.Result r = DnaTools.ddPartition(src, dev,
                    (pct, written) -> { onProgress((baseline + pct) / total, written); return kotlin.Unit.INSTANCE; },
                    line -> { log(line); return kotlin.Unit.INSTANCE; },
                    cancelFlag::get,
                    30 * 60_000L);
            if (!r.getSuccess()) { finishRun(false, r.getMessage(), src, dev); return; }
        }
        finishRun(true, "", "", "");
    }

    private void onProgress(int pct, long written) {
        main.post(() -> {
            if (progressBar != null) progressBar.setProgress(pct);
            if (progressText != null) {
                progressText.setText(t("进度 " + pct + "%（" + fmtMB(written) + "）",
                        "Progress " + pct + "% (" + fmtMB(written) + ")"));
            }
        });
    }

    private String fmtMB(long bytes) {
        if (bytes >= 1073741824L) return String.format("%.2fG", bytes / 1073741824.0);
        return String.format("%.1fM", bytes / 1048576.0);
    }

    private void finishRun(boolean success, String msg, String src, String dst) {
        main.post(() -> {
            runBtn.setEnabled(true);
            progressBar.setVisibility(View.GONE);
            if (success) {
                statusText.setText(t("✓ 批量操作完成", "✓ Batch done"));
                log(t("✓ 操作完成", "✓ Done"));
            } else if ("已取消".equals(msg)) {
                statusText.setText(t("已终止", "Stopped"));
                log("⏹ " + t("已终止", "Stopped"));
            } else {
                statusText.setText(t("失败：" + (msg != null ? msg : "未知错误"), "Failed: " + (msg != null ? msg : "unknown")));
                log("✗ " + (msg != null ? msg : "unknown"));
            }
        });
    }

    // ================= 日志面板 =================

    private void buildLogCard() {
        boolean dark = isDark();
        LinearLayout logCard = new LinearLayout(this);
        logCard.setOrientation(LinearLayout.VERTICAL);
        logCard.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable logCardBg = new GradientDrawable();
        logCardBg.setColor(dark ? 0xFF111927 : 0xFFF7FAFF);
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
        copyBtn.setBackground(logPillBg(dark));
        copyBtn.setOnClickListener(v -> {
            Haptics.INSTANCE.perform(v);
            if (logDisplay == null) return;
            CharSequence text = logDisplay.getText();
            if (text.length() == 0) {
                toast(t("暂无日志", "Nothing to copy"));
                return;
            }
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Partition log", text));
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
        closeBtn.setBackground(logPillBg(dark));
        closeBtn.setOnClickListener(v -> {
            Haptics.INSTANCE.perform(v);
            if (logDialog != null && logDialog.isShowing()) logDialog.dismiss();
        });
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(dp(40), dp(30));
        closeLp.leftMargin = dp(6);
        logHead.addView(closeBtn, closeLp);
        logCard.addView(logHead, new LinearLayout.LayoutParams(-1, -2));

        GradientDrawable logBg = new GradientDrawable();
        logBg.setColor(dark ? 0xFF0E141F : 0xFFE8EEF7);
        logBg.setCornerRadius(dp(14));
        logBg.setStroke(Math.max(1, dp(1)), dark ? 0x59FFFFFF : 0x40FFFFFF);
        logDisplay = new TextView(this);
        logDisplay.setTypeface(Typeface.MONOSPACE);
        logDisplay.setTextSize(9.5f);
        logDisplay.setTextColor(dark ? 0xFFEAF0F8 : 0xFF1F3352);
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
            if (cur.isEmpty()) logDisplay.setText(s);
            else logDisplay.append("\n" + s);
            scrollLogToBottom();
        });
    }

    // ================= 背景样式 =================

    private GradientDrawable selModeBg(boolean isDark) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(isDark ? 0xF00277BD : 0xF00288D1);
        g.setCornerRadius(dp(14));
        g.setStroke(Math.max(1, dp(1)), isDark ? 0xE64FC3F7 : 0xE64FC3F7);
        return g;
    }

    private GradientDrawable dimModeBg(boolean isDark) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x331A2535);
        g.setCornerRadius(dp(14));
        g.setStroke(Math.max(1, dp(1)), 0x2A4A5568);
        return g;
    }

    private GradientDrawable selPartBg(boolean isDark) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(isDark ? 0xF02A7C5C : 0xF0238C6E);
        g.setCornerRadius(dp(12));
        g.setStroke(Math.max(1, dp(1)), isDark ? 0xE65AD4A0 : 0xE61D7A4F);
        return g;
    }

    private GradientDrawable logPillBg(boolean isDark) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(isDark ? 0x593A4355 : 0x59FFFFFF);
        g.setCornerRadius(dp(15));
        g.setStroke(Math.max(1, dp(1)), isDark ? 0x80FFFFFF : 0x66FFFFFF);
        return g;
    }

    private GradientDrawable rippleBg(boolean isDark) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(isDark ? 0x332A3546 : 0x1A1E2A3A);
        g.setCornerRadius(dp(14));
        g.setStroke(Math.max(1, dp(1)), isDark ? 0x59FFFFFF : 0x2A1E2A3A);
        return g;
    }
}
