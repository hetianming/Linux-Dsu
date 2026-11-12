package com.mcai.ubuntudsu

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mcai.ubuntudsu.ui.GunTheme

/**
 * GunyaniOS 子页面基类：统一「APP 全局主题 + 返回顶栏 + 滚动内容」骨架与卡片控件工厂。
 * v2.0.30 起 GunTheme 只是全局 Ui 的转发层，与首页 / 多功能 / DSU 同一套配色。
 */
abstract class GunyahSubActivity : HapticAppCompatActivity() {
    protected val d: Float get() = resources.displayMetrics.density
    private lateinit var scroll: ScrollView

    /** 子页面标题 */
    protected abstract fun pageTitle(): String

    /** 子页面内容（往 parent 里加卡片） */
    protected abstract fun buildContent(parent: LinearLayout)

    private var pendingPick: ((String) -> Unit)? = null
    private lateinit var pickLauncher: androidx.activity.result.ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GunTheme.applyTheme(this)
        pickLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val path = result.data?.getStringExtra(RootfsFilesActivity.RESULT_FILE_PATH)
            val cb = pendingPick
            pendingPick = null
            if (path != null) cb?.invoke(path)
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.background = GunTheme.background()

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(GunTheme.dp(12, d), GunTheme.dp(2, d), GunTheme.dp(12, d), GunTheme.dp(4, d))
        }
        bar.addView(TextView(this).apply {
            text = "‹"; textSize = 24f
            includeFontPadding = false
            setTextColor(GunTheme.TEXT)
            setPadding(0, 0, GunTheme.dp(10, d), 0)
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = pageTitle(); textSize = 18f; setTypeface(null, Typeface.BOLD)
            includeFontPadding = false
            setTextColor(GunTheme.TEXT)
        })
        root.addView(bar)

        scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(GunTheme.dp(12, d), GunTheme.dp(2, d), GunTheme.dp(12, d), GunTheme.dp(16, d))
        }
        // 关键：先填充子页面内容（初始化各 lateinit 字段），再挂滚动容器
        buildContent(content)
        scroll.addView(content)
        root.addView(scroll)

        buildActionBar()?.let { root.addView(it) }
        setContentView(root)

        com.mcai.ubuntudsu.ui.Ui.enableEdgeToEdge(this, root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        onCreateContent()
    }

    /** insets 设置完成、视图就绪后的回调（初始刷新等） */
    protected open fun onCreateContent() {}

    /** 子页面可覆盖：底部固定操作栏 */
    protected open fun buildActionBar(): LinearLayout? = null

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.zoom_in, R.anim.zoom_out)
    }

    // ------------------------- 控件工厂（APP 全局主题） -------------------------

    protected fun card(title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GunTheme.card(16f, d)
        setPadding(GunTheme.dp(12, d), GunTheme.dp(10, d), GunTheme.dp(12, d), GunTheme.dp(10, d))
        layoutParams = LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = GunTheme.dp(10, d) }
        addView(TextView(this@GunyahSubActivity).apply {
            text = title
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(GunTheme.TEXT)
            setPadding(0, 0, 0, GunTheme.dp(6, d))
        })
    }

    protected fun hintText(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 10.5f
        setTextColor(GunTheme.MUTED)
        setPadding(0, 0, 0, GunTheme.dp(4, d))
    }

    protected fun actionButton(text: String, accent: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (accent == GunTheme.ACCENT || accent == GunTheme.WARN) GunTheme.ON_ACCENT else GunTheme.TEXT)
            gravity = Gravity.CENTER
            background = GunTheme.chip(d, accent)
            setPadding(GunTheme.dp(12, d), GunTheme.dp(12, d), GunTheme.dp(12, d), GunTheme.dp(12, d))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            GunTheme.pressAnimation(this)
        }

    protected fun primaryButton(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTypeface(null, Typeface.BOLD)
        setTextColor(GunTheme.ON_ACCENT)
        gravity = Gravity.CENTER
        background = GunTheme.primaryButton(d)
        setPadding(0, GunTheme.dp(12, d), 0, GunTheme.dp(12, d))
        layoutParams = LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        setOnClickListener { onClick() }
        GunTheme.pressAnimation(this)
    }

    protected fun editRow(
        labelText: String,
        value: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        parent: LinearLayout,
    ): EditText {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = GunTheme.dp(6, d) }
        }
        row.addView(TextView(this).apply {
            text = labelText; textSize = 11f
            setTextColor(GunTheme.TEXT2)
            layoutParams = LinearLayout.LayoutParams(GunTheme.dp(88, d), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        val edit = EditText(this).apply {
            setText(value)
            this.inputType = inputType
            textSize = 12.5f
            isSingleLine = true
            setTextColor(GunTheme.TEXT)
            setHintTextColor(GunTheme.MUTED)
            background = GunTheme.cardAlt(10f, d)
            backgroundTintList = null
            setPadding(GunTheme.dp(10, d), GunTheme.dp(8, d), GunTheme.dp(10, d), GunTheme.dp(8, d))
            layoutParams = LinearLayout.LayoutParams(0, GunTheme.dp(40, d), 1f)
        }
        row.addView(edit)
        parent.addView(row)
        return edit
    }

    protected fun toggleRow(labelText: String, init: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = GunTheme.dp(8, d) }
        }
        row.addView(TextView(this).apply {
            text = labelText; textSize = 11f
            setTextColor(GunTheme.TEXT2)
            layoutParams = LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        var state = init
        row.addView(TextView(this).apply {
            text = if (state) "ON" else "OFF"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (state) GunTheme.ON_ACCENT else GunTheme.TEXT2)
            background = GunTheme.pill(d, if (state) GunTheme.ACCENT else GunTheme.CHIP)
            setPadding(GunTheme.dp(14, d), GunTheme.dp(5, d), GunTheme.dp(14, d), GunTheme.dp(5, d))
            setOnClickListener {
                state = !state
                text = if (state) "ON" else "OFF"
                setTextColor(if (state) GunTheme.ON_ACCENT else GunTheme.TEXT2)
                background = GunTheme.pill(d, if (state) GunTheme.ACCENT else GunTheme.CHIP)
                onChange(state)
            }
        })
        return row
    }

    protected fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    /**
     * 配置类页面保存后的统一收尾：提示一句，然后直接回虚拟机主页。
     *
     * 以前保存只弹 toast、人还留在配置页，用户得自己按返回 ——
     * 而且返回是逐层退，中间夹着的页面还得一层层点。这里一次性清到主页。
     */
    protected fun saveAndGoHome(msg: String) {
        toast(msg)
        val home = Intent(this, GunyahVMActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { startActivity(home) }
        finish()
    }

    /**
     * 下拉选择行：点击弹出单选对话框。
     * @param options 显示文本 -> 存储值
     */
    protected fun selectorRow(
        labelText: String,
        options: List<Pair<String, String>>,
        current: String,
        onSelect: (String) -> Unit,
        parent: LinearLayout,
    ): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = GunTheme.dp(6, d) }
        }
        row.addView(TextView(this).apply {
            text = labelText; textSize = 11f
            setTextColor(GunTheme.TEXT2)
            layoutParams = LinearLayout.LayoutParams(GunTheme.dp(88, d), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        val value = TextView(this).apply {
            text = options.firstOrNull { it.second == current }?.first ?: current
            textSize = 12.5f
            setTextColor(GunTheme.TEXT)
            gravity = Gravity.CENTER_VERTICAL
            isSingleLine = true
            background = GunTheme.cardAlt(10f, d)
            setPadding(GunTheme.dp(10, d), GunTheme.dp(10, d), GunTheme.dp(10, d), GunTheme.dp(10, d))
            layoutParams = LinearLayout.LayoutParams(0, GunTheme.dp(40, d), 1f)
        }
        value.setOnClickListener {
            val labels = options.map { it.first }.toTypedArray()
            val idx = options.indexOfFirst { it.first == value.text }.coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle(labelText)
                .setSingleChoiceItems(labels, idx) { dlg, which ->
                    onSelect(options[which].second)
                    value.text = options[which].first
                    dlg.dismiss()
                }
                .setNegativeButton(Lang.t("取消"), null)
                .show()
        }
        row.addView(value)
        parent.addView(row)
        return value
    }

    /** 路径行：左侧可手填路径，右侧「选择」唤起 APP 内设文件管理。返回 EditText。 */
    protected fun fileRow(
        labelText: String,
        value: String,
        parent: LinearLayout,
        pickerTitle: String = labelText,
        onPicked: (String) -> Unit = {},
    ): EditText {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = GunTheme.dp(6, d) }
        }
        row.addView(TextView(this).apply {
            text = labelText; textSize = 11f
            setTextColor(GunTheme.TEXT2)
            layoutParams = LinearLayout.LayoutParams(GunTheme.dp(88, d), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        val edit = EditText(this).apply {
            setText(value)
            textSize = 11.5f
            isSingleLine = true
            setTextColor(GunTheme.TEXT)
            setHintTextColor(GunTheme.MUTED)
            background = GunTheme.cardAlt(10f, d)
            backgroundTintList = null
            setPadding(GunTheme.dp(10, d), GunTheme.dp(8, d), GunTheme.dp(10, d), GunTheme.dp(8, d))
            layoutParams = LinearLayout.LayoutParams(0, GunTheme.dp(40, d), 1f)
        }
        row.addView(edit)
        row.addView(TextView(this).apply {
            text = Lang.t("选择"); textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(GunTheme.ON_ACCENT)
            gravity = Gravity.CENTER
            background = GunTheme.chip(d, GunTheme.ACCENT)
            setPadding(GunTheme.dp(12, d), GunTheme.dp(9, d), GunTheme.dp(12, d), GunTheme.dp(9, d))
            layoutParams = LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, GunTheme.dp(40, d),
            ).apply { marginStart = GunTheme.dp(6, d) }
            setOnClickListener {
                openFilePicker(pickerTitle) { p ->
                    edit.setText(p)
                    onPicked(p)
                }
            }
        })
        parent.addView(row)
        return edit
    }

    protected fun openFilePicker(title: String, onPicked: (String) -> Unit) {
        // 复用 APP 内设文件管理（RootfsFilesActivity）选择任意文件
        pendingPick = onPicked
        pickLauncher.launch(Intent(this, RootfsFilesActivity::class.java).apply {
            putExtra(RootfsFilesActivity.EXTRA_PICK, true)
            putExtra(RootfsFilesActivity.EXTRA_TITLE, title)
            putExtra(RootfsFilesActivity.EXTRA_EXT_ALL, true)
        })
    }
}
