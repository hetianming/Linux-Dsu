package com.mcai.ubuntudsu

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mcai.ubuntudsu.core.Env
import com.mcai.ubuntudsu.core.RootShell
import com.mcai.ubuntudsu.ui.Ui
import java.io.File

// 内置文件管理器：
// 1. rootfs 模式（默认）：浏览 / 编辑 Ubuntu rootfs 内文件
// 2. 选择模式（EXTRA_PICK）：作为文件选择器使用，根目录 /sdcard，
//    点击文件即返回 FileProvider uri 给调用方（rootfs 本地安装 / DSU 选 GSI zip）
class RootfsFilesActivity : AppCompatActivity() {
    private lateinit var currentDir: File
    private lateinit var pathText: TextView
    private lateinit var listHost: LinearLayout
    private val history = ArrayDeque<File>()
    // 左缘手势：滑动返回上级；根目录时提示，再滑退出
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeTracking = false
    private var rootHintShownAt = 0L
    // 选择模式
    private var pickMode = false
    private var pickTitle = "选择文件"
    private var pickExt = listOf(".tar.gz", ".tar.xz", ".tgz", ".txz")

    companion object {
        const val EXTRA_PICK = "extra_pick"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_EXT = "extra_ext"
        const val RESULT_FILE_PATH = "result_file_path"
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        val density = resources.displayMetrics.density
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                swipeStartX = event.x
                swipeStartY = event.y
                swipeTracking = event.x <= 40 * density
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                // 从左缘向右滑动超阈值立即触发，避免被列表滚动吞掉 UP
                if (swipeTracking) {
                    val dx = event.x - swipeStartX
                    val dy = event.y - swipeStartY
                    if (dx > 56 * density && dx > kotlin.math.abs(dy) * 2) {
                        swipeTracking = false
                        handleEdgeBack()
                    }
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                swipeTracking = false
            }
        }
        return super.dispatchTouchEvent(event)
    }

    // 左缘手势动作：非根目录返回上级；根目录首次提示，2.5 秒内再次触发退出
    private fun handleEdgeBack() {
        val root = pickRoot()
        if (currentDir.absolutePath != root.absolutePath) {
            navigateUp()
            return
        }
        val now = System.currentTimeMillis()
        if (now - rootHintShownAt < 2500L) {
            finish()
        } else {
            rootHintShownAt = now
            Toast.makeText(this, "已到根目录，再次左缘滑动退出", Toast.LENGTH_SHORT).show()
        }
    }

    // 模式对应的浏览根目录：选择模式从 /sdcard 开始，rootfs 模式固定 rootfs 目录
    private fun pickRoot(): File = if (pickMode) File("/sdcard") else Env.rootfs(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickMode = intent.getBooleanExtra(EXTRA_PICK, false)
        if (pickMode) {
            pickTitle = intent.getStringExtra(EXTRA_TITLE) ?: "选择文件"
            pickExt = (intent.getStringExtra(EXTRA_EXT) ?: "").split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            if (pickExt.isEmpty()) pickExt = listOf(".tar.gz", ".tar.xz", ".tgz", ".txz")
        } else if (!Env.ubuntuInstalled(this)) {
            Toast.makeText(this, "Ubuntu rootfs 尚未安装", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        currentDir = pickRoot()
        buildUi()
        refresh()
    }

    private fun buildUi() {
        val d = resources.displayMetrics.density
        // 外层：滚动内容 + 底部固定工具条（FrameLayout 叠放）
        val frame = FrameLayout(this)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(14, d), Ui.dp(10, d), Ui.dp(14, d), Ui.dp(14, d))
        }
        // 底部工具条预留高度 + 路径省略后滚动区不会被遮挡
        page.setPaddingRelative(page.paddingStart, page.paddingTop, page.paddingEnd, Ui.dp(64, d))

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = "‹"
            textSize = 26f
            setTextColor(Ui.primaryText(this@RootfsFilesActivity))
            setPadding(Ui.dp(2, d), 0, Ui.dp(10, d), 0)
            setOnClickListener {
                if (history.isEmpty()) finish() else navigateUp()
            }
            Ui.pressAnimation(this)
        })
        titleRow.addView(TextView(this).apply {
            text = if (pickMode) pickTitle else "rootfs 文件管理"
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Ui.primaryText(this@RootfsFilesActivity))
            // 长标题不挤掉返回按钮
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        page.addView(titleRow)

        pathText = TextView(this).apply {
            textSize = 11f
            setTextColor(Ui.secondaryText(this@RootfsFilesActivity))
            setPadding(0, Ui.dp(4, d), 0, Ui.dp(6, d))
            // 长路径单行省略（MIDDLE 保留首尾可见），不撑高布局
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        page.addView(pathText)

        val scrollCard = card()
        listHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollCard.addView(listHost)
        page.addView(scrollCard)

        val scroll = ScrollView(this).apply {
            addView(page, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        frame.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        // 底部固定工具条：上滑滚动也不消失；高模糊磨砂底、无外层边框、小圆角
        val toolBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(12, d), Ui.dp(8, d), Ui.dp(12, d), Ui.dp(8, d))
            background = Ui.frostedSurface(this@RootfsFilesActivity, radiusDp = 12f, stroke = false)
            elevation = Ui.dp(6, d).toFloat()
        }
        if (pickMode) {
            toolBar.addView(smallAction("上级", Ui.buttonSecondary(this)) { navigateUp() }.apply {
                layoutParams = LinearLayout.LayoutParams(0, Ui.dp(34, d), 1f).apply { marginEnd = Ui.dp(6, d) }
            })
            toolBar.addView(smallAction("根目录", Ui.buttonPrimary(this)) {
                history.clear()
                currentDir = pickRoot()
                refresh()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, Ui.dp(34, d), 1f).apply { marginStart = Ui.dp(6, d) }
            })
        } else {
            toolBar.addView(smallAction("上级", Ui.buttonSecondary(this)) { navigateUp() }.apply {
                layoutParams = LinearLayout.LayoutParams(0, Ui.dp(34, d), 1f).apply { marginEnd = Ui.dp(5, d) }
            })
            toolBar.addView(smallAction("新建文件", Ui.buttonPrimary(this)) { promptCreate(newFile = true) }.apply {
                layoutParams = LinearLayout.LayoutParams(0, Ui.dp(34, d), 1f).apply { marginStart = Ui.dp(5, d); marginEnd = Ui.dp(5, d) }
            })
            toolBar.addView(smallAction("新建文件夹", Ui.buttonSuccess(this)) { promptCreate(newFile = false) }.apply {
                layoutParams = LinearLayout.LayoutParams(0, Ui.dp(34, d), 1f).apply { marginStart = Ui.dp(5, d) }
            })
        }
        // 悬浮胶囊工具条：四边留距，圆角磨砂无描边
        frame.addView(toolBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ).apply {
            marginStart = Ui.dp(12, d)
            marginEnd = Ui.dp(12, d)
            bottomMargin = Ui.dp(10, d)
        })

        Ui.animateLiquidBackground(frame)
        setContentView(frame, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        Ui.enableEdgeToEdge(this, frame)
        ViewCompat.setOnApplyWindowInsetsListener(frame) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top + Ui.dp(2, resources.displayMetrics.density), bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(frame)
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        val d = resources.displayMetrics.density
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.dp(14, d), Ui.dp(12, d), Ui.dp(14, d), Ui.dp(12, d))
        background = Ui.glassSurface(this@RootfsFilesActivity, 18f)
        elevation = Ui.dp(3, d).toFloat()
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun smallAction(text: String, accent: Int, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 11f
        gravity = Gravity.CENTER
        setTextColor(Ui.buttonText(this@RootfsFilesActivity))
        background = Ui.glassButton(this@RootfsFilesActivity, accent)
        val d = resources.displayMetrics.density
        setPadding(Ui.dp(8, d), 0, Ui.dp(8, d), 0)
        minimumWidth = Ui.dp(64, d)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(30, d)).apply {
            marginEnd = Ui.dp(6, d)
        }
        Ui.pressAnimation(this)
        setOnClickListener { onClick() }
    }

    private fun navigateUp() {
        if (history.isEmpty()) return
        currentDir = history.removeLastOrNull() ?: pickRoot()
        refresh()
    }

    private fun enter(dir: File) {
        if (!entryIsDir(dir)) return
        history.addLast(currentDir)
        currentDir = dir
        refresh()
    }

    private fun refresh() {
        val root = pickRoot()
        val displayPath = when {
            pickMode -> currentDir.absolutePath
            else -> "/" + currentDir.absolutePath.removePrefix(root.absolutePath).trimStart('/')
        }
        pathText.text = displayPath
        listHost.removeAllViews()
        val listing = runCatching { currentDir.listFiles() }.getOrNull()
        when {
            // 普通读取成功：直接用
            listing != null -> showFiles(
                listing.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }),
            )
            // 无权限：root 兜底浏览（后台线程执行，避免主线程 su 阻塞 ANR）
            pickMode -> refreshByRootInBackground()
            else -> showNoPermission()
        }
    }

    // root 兜底浏览：后台线程判定 root 可用并 ls 列目录，结果回主线程渲染
    private fun refreshByRootInBackground() {
        Thread {
            val available = RootShell.available()
            val entries: List<File>? = if (available) listEntriesByRoot() else null
            runOnUiThread {
                if (entries != null) {
                    showFiles(entries)
                } else {
                    showNoPermission()
                }
            }
        }.start()
    }

    // root 列目录（须在后台线程调用）：失败返回 null
    private fun listEntriesByRoot(): List<File>? {
        val dir = currentDir.absolutePath
        val result = RootShell.exec("ls -1p \"$dir\" 2>/dev/null", timeoutMs = 15000)
        if (!result.success) return null
        rootDirs.clear()
        val entries = result.stdout.lineSequence()
            .filter { it.isNotEmpty() }
            .map { line ->
                val isDir = line.endsWith("/")
                val name = line.trimEnd('/')
                if (isDir) rootDirs.add("${currentDir.absolutePath}/$name")
                File(currentDir, name)
            }
            .sortedWith(compareBy<File> { !entryIsDir(it) }.thenBy { it.name.lowercase() })
            .toList()
        return entries
    }

    // root 兜底列目录的目录标记：ls -1p 的 / 后缀记入集合，避免逐条 root 查询
    private val rootDirs = mutableSetOf<String>()

    // 目录判定：优先 File.isDirectory 与 rootDirs 集合；均为否时按非目录处理（避免 UI 线程 root 查询阻塞）
    private fun entryIsDir(file: File): Boolean =
        file.isDirectory || rootDirs.contains(file.absolutePath)

    private fun showFiles(files: List<File>) {
        if (files.isEmpty()) {
            listHost.addView(TextView(this).apply {
                text = "空目录"
                textSize = 12f
                setTextColor(Ui.secondaryText(this@RootfsFilesActivity))
                setPadding(0, Ui.dp(10, resources.displayMetrics.density), 0, Ui.dp(10, resources.displayMetrics.density))
                gravity = Gravity.CENTER
            })
            return
        }
        files.forEach { file -> listHost.addView(fileRow(file)) }
    }

    private fun showNoPermission() {
        listHost.addView(TextView(this).apply {
            text = "无权限读取该目录"
            textSize = 12f
            setTextColor(Ui.secondaryText(this@RootfsFilesActivity))
            setPadding(0, Ui.dp(10, resources.displayMetrics.density), 0, Ui.dp(6, resources.displayMetrics.density))
            gravity = Gravity.CENTER
        })
        listHost.addView(smallAction("授予存储权限", Ui.buttonPrimary(this)) { requestStorage() }
            .apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = Gravity.CENTER_HORIZONTAL }
            })
    }

    // 运行时申请存储权限（Android 13+ 请求 READ_MEDIA 权限组）
    private fun requestStorage() {
        val permissions = if (android.os.Build.VERSION.SDK_INT >= 33) {
            arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            )
        }
        ActivityCompat.requestPermissions(this, permissions, 1002)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1002) {
            if (grantResults.isNotEmpty() && grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
                refresh()
            } else {
                Toast.makeText(this, "未授予存储权限，无法浏览 /sdcard", Toast.LENGTH_LONG).show()
            }
        }
    }

    // 选择模式：点击匹配扩展名的文件立即返回其绝对路径（不拷贝，读取端按需用 root 流）
    private fun pickFile(file: File) {
        if (pickExt.isNotEmpty() && pickExt.none { file.name.lowercase().endsWith(it) }) {
            Toast.makeText(this, "支持的格式: ${pickExt.joinToString(" / ")}", Toast.LENGTH_SHORT).show()
            return
        }
        deliverPick(file)
    }

    private fun deliverPick(file: File) {
        val result = android.content.Intent().apply {
            putExtra(RESULT_FILE_PATH, file.absolutePath)
        }
        setResult(RESULT_OK, result)
        finish()
    }

    private fun fileRow(file: File): View {
        val d = resources.displayMetrics.density
        val isDir = entryIsDir(file)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(5, d), 0, Ui.dp(5, d))
            setOnClickListener {
                if (isDir) enter(file) else if (pickMode) pickFile(file) else openFileMenu(file)
            }
            Ui.pressAnimation(this)
        }
        row.addView(TextView(this).apply {
            text = if (isDir) "📁" else if (isTextFile(file)) "📄" else "▣"
            textSize = 16f
            gravity = Gravity.CENTER
        })
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Ui.dp(10, d)
            }
        }
        textCol.addView(TextView(this).apply {
            text = file.name
            textSize = 13f
            setTextColor(Ui.primaryText(this@RootfsFilesActivity))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        textCol.addView(TextView(this).apply {
            text = if (isDir) "目录" else Env.formatSize(file.length())
            textSize = 10f
            setTextColor(Ui.secondaryText(this@RootfsFilesActivity))
        })
        row.addView(textCol)
        row.addView(TextView(this).apply {
            text = "›"
            textSize = 18f
            setTextColor(Ui.secondaryText(this@RootfsFilesActivity))
        })
        return row
    }

    private fun isTextFile(file: File): Boolean {
        val name = file.name.lowercase()
        val textExt = listOf(
            ".txt", ".conf", ".cfg", ".sh", ".bash", ".md", ".list", ".sources", ".json", ".xml",
            ".yaml", ".yml", ".ini", ".desktop", ".service", ".vnc", ".profile", ".bashrc", "os-release", "version",
        )
        if (textExt.any { name.endsWith(it) }) return true
        if (!name.contains('.')) {
            return runCatching {
                file.inputStream().use { input ->
                    val head = ByteArray(512)
                    val n = input.read(head)
                    (0 until n).all { head[it] != 0.toByte() }
                }
            }.getOrDefault(false)
        }
        return false
    }

    private fun openFileMenu(file: File) {
        val actions = mutableListOf<String>()
        if (isTextFile(file)) actions.add("编辑")
        actions.add("重命名")
        actions.add("删除")
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    "编辑" -> openEditor(file)
                    "重命名" -> promptRename(file)
                    "删除" -> confirmDelete(file)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openEditor(file: File) {
        val d = resources.displayMetrics.density
        val editor = EditText(this).apply {
            setText(runCatching { file.readText() }.getOrElse { "" })
            textSize = 12f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            minLines = 14
            gravity = Gravity.TOP or Gravity.START
        }
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setView(editor)
            .setPositiveButton("保存") { _, _ ->
                runCatching { file.writeText(editor.text.toString()) }
                    .onSuccess { Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show() }
                    .onFailure { Toast.makeText(this, "保存失败: ${it.message}", Toast.LENGTH_LONG).show() }
            }
            .setNeutralButton("取消", null)
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun promptRename(file: File) {
        val input = EditText(this).apply {
            setText(file.name)
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("重命名")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty() || name.contains('/')) {
                    Toast.makeText(this, "名称无效", Toast.LENGTH_SHORT).show()
                } else {
                    runCatching { file.renameTo(File(file.parentFile, name)) }
                        .onSuccess { refresh() }
                        .onFailure { Toast.makeText(this, "重命名失败: ${it.message}", Toast.LENGTH_SHORT).show() }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this)
            .setTitle("删除 ${file.name}")
            .setMessage("将删除 ${if (file.isDirectory) "该目录及全部内容" else "该文件"}，不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                runCatching { if (file.isDirectory) file.deleteRecursively() else file.delete() }
                    .onSuccess { refresh() }
                    .onFailure { Toast.makeText(this, "删除失败: ${it.message}", Toast.LENGTH_SHORT).show() }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun promptCreate(newFile: Boolean) {
        val input = EditText(this).apply {
            hint = if (newFile) "文件名" else "文件夹名"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle(if (newFile) "新建文件" else "新建文件夹")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty() || name.contains('/')) {
                    Toast.makeText(this, "名称无效", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val target = File(currentDir, name)
                val ok = if (newFile) target.createNewFile() else target.mkdirs()
                if (ok) refresh() else Toast.makeText(this, "创建失败（可能已存在）", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
