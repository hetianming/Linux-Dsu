package com.mcai.ubuntudsu.core.dna;

import com.mcai.ubuntudsu.R;
import com.mcai.ubuntudsu.core.DnaTools;
import com.mcai.ubuntudsu.core.RootShell;

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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DNA · 解压 ROM 独立二级页（v3.30.23）。
 * 对齐原版 home.sh：dna unzip --delete $silence $DNA_DIR/$ZIP $DNA_DIR
 * —— 解压目标固定为工程根目录 /sdcard/Linux-Dsu/PDNA，dna 自动创建 PDNA_<zip名> 新工程。
 * 文件列表点选/再点取消；浏览选择文件，不再提供手动路径输入框。
 */
public final class DnaUnzipActivity extends DnaBaseActivity {

    private static final int PICK_ROM_ZIP = 3412;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_ROM_ZIP || resultCode != RESULT_OK || data == null) return;
        String path = data.getStringExtra(com.mcai.ubuntudsu.RootfsFilesActivity.RESULT_FILE_PATH);
        if (path == null || !path.startsWith("/")) return;
        chosenZip = path;
        chosenSizeText = "";
        renderSelection();
        log("📦 " + path.substring(path.lastIndexOf('/') + 1));
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService io =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "dna-unzip");
                t.setDaemon(true);
                return t;
            });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);

    private String chosenZip;                       // 选中的 zip 绝对路径（null = 未选）
    private String chosenSizeText = "";             // 选中文件大小（列表选择时已知）
    private LinearLayout chosenBanner;
    private TextView chosenName, chosenPath;
    private LinearLayout zipList;
    private TextView zipCount, zipEmpty;
    private CheckBox deleteSource;
    private TextView status;
    private FrameLayout progressTrack;
    private View progressFill;
    private TextView consoleText;
    private ScrollView consoleScroll;
    private LinearLayout consoleCard;
    private android.app.Dialog consoleDialog;
    private Button runBtn;

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
        buildUi();
        refreshZips();
        log(t("提示：解压目标为工程根目录，自动创建 PDNA_ 新工程", "Tip: unzip to work root, auto-creates a PDNA_ project"));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        if (consoleDialog != null && consoleDialog.isShowing()) consoleDialog.dismiss();
        consoleDialog = null;
    }

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
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, 0, 0, 0);
        boolean dark = com.mcai.ubuntudsu.ui.Ui.INSTANCE.isDark(this);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(dark ? 0x593A4355 : 0x59FFFFFF);
        bg.setCornerRadius(h > 0 ? Math.min(Math.abs(w), h) / 2 : dp(16));
        bg.setStroke(Math.max(1, dp(1)), dark ? 0x80FFFFFF : 0x80FFFFFF);
        b.setBackground(bg);
        b.setStateListAnimator(null);
        return b;
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
        content.setPadding(dp(16), dp(6), dp(16), dp(20));
        page.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        // ---- 标题栏 ----
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(t("DNA · 解压 ROM", "DNA · Unzip ROM"));
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

        // ---- 徽章 + 说明 ----
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout icon = new FrameLayout(this);
        android.graphics.drawable.GradientDrawable ib = new android.graphics.drawable.GradientDrawable();
        ib.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        ib.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TL_BR);
        ib.setColors(new int[]{0xFF35A8C4, 0xFF0E7D95});
        ib.setStroke(Math.max(1, dp(1)), 0xB3FFFFFF);
        icon.setBackground(ib);
        TextView ie = new TextView(this);
        ie.setText("📦");
        ie.setTextSize(17);
        ie.setGravity(Gravity.CENTER);
        icon.addView(ie, new FrameLayout.LayoutParams(-1, -1));
        head.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout tb = new LinearLayout(this);
        tb.setOrientation(LinearLayout.VERTICAL);
        tb.setPadding(dp(12), 0, 0, 0);
        TextView h1 = new TextView(this);
        h1.setText(t("解压 ROM 压缩包", "Unzip ROM package"));
        h1.setTextSize(15.5f);
        h1.setTypeface(null, 1);
        h1.setTextColor(pal.title);
        tb.addView(h1, new LinearLayout.LayoutParams(-1, -2));
        TextView h2 = new TextView(this);
        h2.setText(t("解压 zip 自动创建 PDNA_ 新工程于工程根目录", "Unzips zip, auto-creates a PDNA_ project at work root"));
        h2.setTextSize(11.5f);
        h2.setTextColor(pal.subtitle);
        LinearLayout.LayoutParams h2Lp = new LinearLayout.LayoutParams(-1, -2);
        h2Lp.topMargin = dp(2);
        tb.addView(h2, h2Lp);
        head.addView(tb, new LinearLayout.LayoutParams(0, -2, 1f));
        content.addView(head, new LinearLayout.LayoutParams(-1, -2));

        // ---- zip 列表卡 ----
        LinearLayout zipCard = glassCard();
        LinearLayout.LayoutParams zcLp = new LinearLayout.LayoutParams(-1, -2);
        zcLp.topMargin = dp(12);
        content.addView(zipCard, zcLp);
        LinearLayout zh = new LinearLayout(this);
        zh.setOrientation(LinearLayout.HORIZONTAL);
        zh.setGravity(Gravity.CENTER_VERTICAL);
        TextView zt = new TextView(this);
        zt.setText(t("压缩包", "Packages"));
        zt.setTextSize(14f);
        zt.setTypeface(null, 1);
        zt.setTextColor(pal.title);
        zh.addView(zt, new LinearLayout.LayoutParams(0, -2, 1f));
        zipCount = new TextView(this);
        zipCount.setTextSize(11.5f);
        zipCount.setTextColor(pal.success);
        zh.addView(zipCount, new LinearLayout.LayoutParams(-2, -2));
        Button refresh = pillButton("⟳", 14, pal.accent, dp(38), dp(32));
        refresh.setOnClickListener(v -> { Haptics.perform(v); refreshZips(); });
        android.widget.LinearLayout.LayoutParams rfLp = new LinearLayout.LayoutParams(dp(38), dp(32));
        rfLp.leftMargin = dp(8);
        zh.addView(refresh, rfLp);
        Button browse = pillButton("📂", 15, pal.accent, dp(38), dp(32));
        browse.setOnClickListener(v -> {
            Haptics.perform(v);
            startActivityForResult(
                    com.mcai.ubuntudsu.RootfsFilesActivity.createPickIntent(
                            this, t("选择 ROM 压缩包", "Select ROM zip"), new String[]{".zip", ".zip2"}),
                    PICK_ROM_ZIP);
        });
        android.widget.LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(dp(38), dp(32));
        brLp.leftMargin = dp(6);
        zh.addView(browse, brLp);
        zipCard.addView(zh, new LinearLayout.LayoutParams(-1, -2));

        zipEmpty = new TextView(this);
        zipEmpty.setText(t("未发现 zip，点 📂 浏览选择文件", "No zip found, tap 📂 to browse"));
        zipEmpty.setTextSize(12f);
        zipEmpty.setTextColor(pal.subtitle);
        zipEmpty.setPadding(dp(2), dp(8), 0, dp(4));
        zipCard.addView(zipEmpty, new LinearLayout.LayoutParams(-1, -2));

        zipList = new LinearLayout(this);
        zipList.setOrientation(LinearLayout.VERTICAL);
        ScrollView zs = new DnaActivity.BoundedScrollView(this, dp(300));
        zipList.setOrientation(LinearLayout.VERTICAL);
        zs.addView(zipList, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams zlLp = new LinearLayout.LayoutParams(-1, -2);
        zlLp.topMargin = dp(4);
        zipCard.addView(zs, zlLp);
        // v3.40.22：删除旧的按下即独占 OnTouchListener —— 与 BoundedScrollView 内置的
        // dispatchTouchEvent 边界交还逻辑叠加会冲突（无条件独占 → 滚到顶/底后外层接不走）

        // ---- 手动路径输入框已移除（v3.30.23：已有 📂 浏览按钮，无需再显示路径框） ----

        // ---- 已选文件横幅（v3.30.25：列表/浏览选择均可见，可一键取消） ----
        chosenBanner = glassCard();
        chosenBanner.setVisibility(View.GONE);
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(-1, -2);
        cbLp.topMargin = dp(8);
        content.addView(chosenBanner, cbLp);
        LinearLayout cbRow = new LinearLayout(this);
        cbRow.setOrientation(LinearLayout.HORIZONTAL);
        cbRow.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout cbIcon = new FrameLayout(this);
        android.graphics.drawable.GradientDrawable cib = new android.graphics.drawable.GradientDrawable();
        cib.setCornerRadius(dp(16));
        cib.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TL_BR);
        cib.setColors(new int[]{0xFF2f9c8f, 0xFF1d6b46});
        cbIcon.setBackground(cib);
        TextView cbEmoji = new TextView(this);
        cbEmoji.setText("📦");
        cbEmoji.setTextSize(15);
        cbEmoji.setGravity(Gravity.CENTER);
        cbIcon.addView(cbEmoji, new FrameLayout.LayoutParams(-1, -1));
        cbRow.addView(cbIcon, new LinearLayout.LayoutParams(dp(32), dp(32)));
        LinearLayout cbText = new LinearLayout(this);
        cbText.setOrientation(LinearLayout.VERTICAL);
        cbText.setPadding(dp(10), 0, dp(8), 0);
        chosenName = new TextView(this);
        chosenName.setTextSize(13.5f);
        chosenName.setTypeface(null, 1);
        chosenName.setTextColor(pal.success);
        chosenName.setSingleLine(true);
        chosenName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        cbText.addView(chosenName, new LinearLayout.LayoutParams(-1, -2));
        chosenPath = new TextView(this);
        chosenPath.setTextSize(11.5f);
        chosenPath.setTextColor(pal.subtitle);
        chosenPath.setSingleLine(true);
        chosenPath.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams cpLp = new LinearLayout.LayoutParams(-1, -2);
        cpLp.topMargin = dp(1);
        cbText.addView(chosenPath, cpLp);
        cbRow.addView(cbText, new LinearLayout.LayoutParams(0, -2, 1f));
        Button clearSel = pillButton("✕", 13, pal.danger, dp(32), dp(32));
        clearSel.setOnClickListener(v -> {
            Haptics.perform(v);
            chosenZip = null;
            chosenSizeText = "";
            renderSelection();
            log("⊘ " + t("已取消选择", "Deselected"));
        });
        cbRow.addView(clearSel, new LinearLayout.LayoutParams(dp(32), dp(32)));
        chosenBanner.addView(cbRow, new LinearLayout.LayoutParams(-1, -2));

        // ---- 目标提示 ----
        TextView targetHint = new TextView(this);
        targetHint.setText("➜ " + t("解压到 ", "Unzip to ") + DnaTools.WORK_ROOT
                + t("（自动创建 PDNA_ 新工程）", " (auto-creates a PDNA_ project)"));
        targetHint.setTextSize(11.5f);
        targetHint.setTextColor(pal.success);
        targetHint.setSingleLine(true);
        targetHint.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        targetHint.setPadding(dp(4), dp(8), dp(4), 0);
        content.addView(targetHint, new LinearLayout.LayoutParams(-1, -2));

        // ---- 选项 ----
        deleteSource = new CheckBox(this);
        deleteSource.setText(t("解压后删除源 zip 文件", "Delete source zip after unzip"));
        deleteSource.setTextSize(12.5f);
        deleteSource.setTextColor(pal.title);
        deleteSource.setPadding(dp(2), 0, 0, 0);
        LinearLayout.LayoutParams dsLp = new LinearLayout.LayoutParams(-1, -2);
        dsLp.topMargin = dp(6);
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

        // ---- 解压按钮 ----
        runBtn = new Button(this, null, 0);
        runBtn.setText("▶  " + t("开始解压", "Unzip"));
        runBtn.setTextSize(15f);
        runBtn.setTypeface(null, 1);
        runBtn.setAllCaps(false);
        runBtn.setTextColor(android.graphics.Color.WHITE);
        runBtn.setMinWidth(0);
        runBtn.setMinHeight(0);
        runBtn.setGravity(Gravity.CENTER);
        runBtn.setPadding(0, 0, 0, 0);
        android.graphics.drawable.GradientDrawable rbBg = new android.graphics.drawable.GradientDrawable();
        rbBg.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TL_BR);
        rbBg.setColors(new int[]{0xFF2f9c8f, 0xFF1d6b46});
        rbBg.setCornerRadius(dp(18));
        rbBg.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
        runBtn.setBackground(rbBg);
        runBtn.setStateListAnimator(null);
        runBtn.setOnClickListener(v -> {
            Haptics.perform(v);
            if (running.get()) { cancelFlag.set(true); log(t("正在取消 ...", "Cancelling...")); return; }
            startUnzip();
        });
        LinearLayout.LayoutParams rbLp = new LinearLayout.LayoutParams(-1, dp(54));
        rbLp.topMargin = dp(10);
        rbLp.bottomMargin = dp(10);
        content.addView(runBtn, rbLp);

        // ---- 控制台（v3.41.14：执行任务时弹出小窗口，不再嵌入页面）----
        buildConsole();
    }

    /** 浅色磨砂控制台卡（v3.30.25：弃用黑色日志框；可滚动 / 复制日志 / 清除日志 / 关闭窗） */
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

    // ================= 数据 =================

    /** zip 条目（名称 + 完整路径 + 大小） */
    private static final class ZipItem {
        final String name, path; final long size;
        ZipItem(String name, String path, long size) { this.name = name; this.path = path; this.size = size; }
    }

    /** 扫描 PDNA 根目录 + 当前工程内的 zip（root 列目录带大小） */
    private void refreshZips() {
        status.setText(t("正在扫描 zip ...", "Scanning zips..."));
        io.execute(() -> {
            List<ZipItem> entries = new ArrayList<>();
            String cur = DnaTools.currentProject(this);
            for (DnaTools.BrowseEntry e : DnaTools.browseDir(DnaTools.WORK_ROOT)) {
                if (!e.isDir() && (e.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".zip")
                        || e.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".zip2")))
                    entries.add(new ZipItem(e.getName(), DnaTools.WORK_ROOT + "/" + e.getName(), e.getSize()));
            }
            if (cur != null) {
                for (DnaTools.BrowseEntry e : DnaTools.browseDir(DnaTools.WORK_ROOT + "/" + cur)) {
                    if (!e.isDir() && (e.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".zip")
                            || e.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".zip2")))
                        entries.add(new ZipItem(e.getName(), DnaTools.WORK_ROOT + "/" + cur + "/" + e.getName(), e.getSize()));
                }
            }
            final List<ZipItem> files = entries;
            main.post(() -> { renderZips(files); status.setText(""); });
        });
    }

    private void renderZips(List<ZipItem> files) {
        zipList.removeAllViews();
        zipEmpty.setVisibility(files.isEmpty() ? View.VISIBLE : View.GONE);
        for (final ZipItem e : files) {
            final String fullPath = e.path;
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
            ib.setColors(new int[]{0xFF8E6FC7, 0xFF2E86AB});
            icon.setBackground(ib);
            TextView ie = new TextView(this);
            ie.setText("📦");
            ie.setTextSize(13);
            ie.setGravity(Gravity.CENTER);
            icon.addView(ie, new FrameLayout.LayoutParams(-1, -1));
            row.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(34)));
            // 名称 + 路径
            LinearLayout textBox = new LinearLayout(this);
            textBox.setOrientation(LinearLayout.VERTICAL);
            textBox.setPadding(dp(10), 0, dp(8), 0);
            TextView name = new TextView(this);
            name.setText(e.name);
            name.setTextSize(13.5f);
            name.setTypeface(null, 1);
            name.setTextColor(pal.title);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            textBox.addView(name, new LinearLayout.LayoutParams(-1, -2));
            TextView path = new TextView(this);
            path.setText(fullPath);
            path.setTextSize(11.5f);
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
            size.setText(fmtSize(e.size));
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
                if (fullPath.equals(chosenZip)) {
                    chosenZip = null;
                    chosenSizeText = "";
                    log("⊘ " + t("已取消选择", "Deselected") + " " + e.name);
                } else {
                    chosenZip = fullPath;
                    chosenSizeText = fmtSize(e.size);
                    log("📦 " + e.name + " · " + fmtSize(e.size));
                }
                renderSelection();
            });
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(-1, -2);
            rLp.topMargin = dp(6);
            zipList.addView(row, rLp);
        }
        renderSelection();
    }

    private void renderSelection() {
        for (int i = 0; i < zipList.getChildCount(); i++) {
            View c = zipList.getChildAt(i);
            if (!(c instanceof LinearLayout) || !(c.getTag() instanceof String)) continue;
            boolean on = c.getTag().equals(chosenZip);
            android.graphics.drawable.GradientDrawable rb = new android.graphics.drawable.GradientDrawable();
            rb.setColor(on ? 0x3335A8C4 : 0x22FFFFFF);
            rb.setCornerRadius(dp(14));
            rb.setStroke(Math.max(1, dp(1)), on ? pal.accent : 0x33FFFFFF);
            c.setBackground(rb);
            // v3.30.23 修复：✓ 标记在右侧容器（childAt(2)）内，此前误把名称文本框(childAt(1))藏掉导致文件名不显示
            LinearLayout right = (LinearLayout) ((LinearLayout) c).getChildAt(2);
            right.getChildAt(1).setVisibility(on ? View.VISIBLE : View.GONE);
        }
        int total = zipList.getChildCount();
        zipCount.setText(total == 0 ? "" : " " + (chosenZip != null ? 1 : 0) + "/" + total);
        // v3.30.25：已选横幅（浏览选择的文件不在列表内，靠横幅显示当前选中）
        if (chosenBanner != null) {
            boolean has = chosenZip != null && !chosenZip.isEmpty();
            chosenBanner.setVisibility(has ? View.VISIBLE : View.GONE);
            if (has) {
                chosenName.setText(chosenZip.substring(chosenZip.lastIndexOf('/') + 1));
                chosenPath.setText(chosenSizeText.isEmpty() ? chosenZip : chosenSizeText + " · " + chosenZip);
            }
        }
    }

    // ================= 执行 =================

    private void startUnzip() {
        if (chosenZip == null || chosenZip.isEmpty()) {
            toast(t("请选择要解压的 zip 文件", "Pick a zip file to unzip"));
            return;
        }
        final String zip = chosenZip;
        expandConsole();
        // v3.30.16：对齐原版 home.sh「dna unzip --delete $silence $DNA_DIR/$ZIP $DNA_DIR」
        // 目标固定为工程根目录，dna 自动创建 PDNA_<zip名> 新工程；--delete 参数只认 0/1
        final String command = "dna unzip --delete " + (deleteSource.isChecked() ? "1" : "0") + " "
                + DnaTools.quote(zip) + " " + DnaTools.quote(DnaTools.WORK_ROOT);
        log("$ " + command);
        running.set(true);
        cancelFlag.set(false);
        runBtn.setText("■  " + t("取消", "Cancel"));
        status.setText(t("正在解压 ...", "Unzipping..."));
        status.setTextColor(pal.subtitle);
        progressTrack.setVisibility(View.VISIBLE);
        io.execute(() -> {
            DnaTools.Result result = DnaTools.run(this, command,
                    line -> { log(line); return kotlin.Unit.INSTANCE; },
                    () -> cancelFlag.get());
            main.post(() -> {
                running.set(false);
                runBtn.setText("▶  " + t("开始解压", "Unzip"));
                progressTrack.setVisibility(View.GONE);
                if (result.getSuccess()) {
                    log("✓ " + result.getMessage());
                    status.setText("✓ " + t("解压完成（新工程已创建，可在 DNA 页切换）", "Done (new project created)"));
                    status.setTextColor(pal.success);
                    toast(t("解压完成", "Done"));
                } else {
                    log("✗ " + result.getMessage());
                    status.setText("✗ " + t("解压失败", "Failed") + ": " + result.getMessage());
                    status.setTextColor(pal.danger);
                }
                refreshZips();
            });
        });
    }

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
            consoleScroll.fullScroll(View.FOCUS_DOWN);
        });
    }

    /** 大小格式化：整数值省小数（392 KB / 8 MB / 1.5 GB） */
    private static String fmtSize(long b) {
        if (b < 1024) return b + " B";
        double kb = b / 1024.0;
        if (kb < 1024) return kb < 10 && kb != Math.floor(kb)
                ? String.format(java.util.Locale.US, "%.1f KB", kb) : String.format(java.util.Locale.US, "%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return mb < 10 && mb != Math.floor(mb)
                ? String.format(java.util.Locale.US, "%.1f MB", mb) : String.format(java.util.Locale.US, "%.0f MB", mb);
        return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0);
    }
}
