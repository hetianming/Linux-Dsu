package com.mcai.ubuntudsu

import android.content.Intent
import android.graphics.Color
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mcai.ubuntudsu.ui.Ui

/**
 * gunyahVM 子页面基类：统一「弥散光背景 + 返回顶栏 + 滚动内容」骨架与卡片控件工厂，
 * 与 APP 主体（Ui.liquidBackground / neuCard / glassButton）完全同步主题。
 */
abstract class GunyahSubActivity : AppCompatActivity() {
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
        pickLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val path = result.data?.getStringExtra(RootfsFilesActivity.RESULT_FILE_PATH)
            val cb = pendingPick
            pendingPick = null
            if (path != null) cb?.invoke(path)
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.background = Ui.liquidBackground(this)
        Ui.animateLiquidBackground(root)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(12, d), Ui.dp(10, d), Ui.dp(12, d), Ui.dp(6, d))
        }
        bar.addView(TextView(this).apply {
            text = "‹"; textSize = 26f
            setTextColor(Ui.primaryText(this@GunyahSubActivity))
            setPadding(0, 0, Ui.dp(10, d), 0)
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = pageTitle(); textSize = 18f; setTypeface(null, Typeface.BOLD)
            setTextColor(Ui.primaryText(this@GunyahSubActivity))
        })
        root.addView(bar)

        scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(12, d), Ui.dp(4, d), Ui.dp(12, d), Ui.dp(16, d))
        }
        // 关键：先填充子页面内容（初始化各 lateinit 字段），再挂滚动容器
        buildContent(content)
        scroll.addView(content)
        root.addView(scroll)

        buildActionBar()?.let { root.addView(it) }
        setContentView(root)

        Ui.enableEdgeToEdge(this, root)
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

    // ------------------------- 控件工厂（与主界面一致） -------------------------

    protected fun card(title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.neuCard(this@GunyahSubActivity, 18f)
        setPadding(Ui.dp(12, d), Ui.dp(10, d), Ui.dp(12, d), Ui.dp(10, d))
        layoutParams = LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = Ui.dp(10, d) }
        addView(TextView(this@GunyahSubActivity).apply {
            text = title
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Ui.primaryText(this@GunyahSubActivity))
            setPadding(0, 0, 0, Ui.dp(6, d))
        })
    }

    protected fun hintText(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(Ui.secondaryText(this@GunyahSubActivity))
        setPadding(0, 0, 0, Ui.dp(4, d))
    }

    protected fun actionButton(text: String, accent: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Ui.buttonText(this@GunyahSubActivity))
            gravity = Gravity.CENTER
            background = Ui.glassButton(this@GunyahSubActivity, accent)
            setPadding(Ui.dp(14, d), Ui.dp(13, d), Ui.dp(14, d), Ui.dp(13, d))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

    protected fun primaryButton(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = Ui.chipSelected(this@GunyahSubActivity)
        setPadding(0, Ui.dp(11, d), 0, Ui.dp(11, d))
        layoutParams = LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        setOnClickListener { onClick() }
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
            ).apply { topMargin = Ui.dp(6, d) }
        }
        row.addView(TextView(this).apply {
            text = labelText; textSize = 11.5f
            setTextColor(Ui.secondaryText(this@GunyahSubActivity))
            layoutParams = LinearLayout.LayoutParams(Ui.dp(84, d), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        val edit = EditText(this).apply {
            setText(value)
            this.inputType = inputType
            textSize = 13f
            isSingleLine = true
            setTextColor(Ui.primaryText(this@GunyahSubActivity))
            setHintTextColor(Ui.secondaryText(this@GunyahSubActivity))
            background = Ui.glassButton(this@GunyahSubActivity)
            backgroundTintList = null
            setPadding(Ui.dp(10, d), Ui.dp(8, d), Ui.dp(10, d), Ui.dp(8, d))
            layoutParams = LinearLayout.LayoutParams(0, Ui.dp(42, d), 1f)
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
            ).apply { topMargin = Ui.dp(8, d) }
        }
        row.addView(TextView(this).apply {
            text = labelText; textSize = 11.5f
            setTextColor(Ui.secondaryText(this@GunyahSubActivity))
            layoutParams = LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        var state = init
        row.addView(TextView(this).apply {
            text = if (state) "ON" else "OFF"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Ui.buttonText(this@GunyahSubActivity))
            background = Ui.glassButton(this@GunyahSubActivity)
            setPadding(Ui.dp(14, d), Ui.dp(5, d), Ui.dp(14, d), Ui.dp(5, d))
            setOnClickListener {
                state = !state
                text = if (state) "ON" else "OFF"
                onChange(state)
            }
        })
        return row
    }

    protected fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

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
