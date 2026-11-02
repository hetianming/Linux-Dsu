package com.mcai.ubuntudsu.ui.pages

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.LifecycleCoroutineScope
import com.mcai.ubuntudsu.R
import com.mcai.ubuntudsu.TvPlayerActivity
import com.mcai.ubuntudsu.core.*
import com.mcai.ubuntudsu.ui.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 手机电视浏览页：源选择 + 搜索 + 分组卡片频道列表。
 *
 * - 内置「全部源合并」选项会把 TVBox / IPv4 / IPv6 三个源按频道名合并，
 *   同名频道在三个源里的不同线路会叠加成同一条的多条备用线路，
 *   播放器断流时即可自动换线，从根源上缓解「直播源不稳定」。
 * - 解析逻辑见 [IptvSource]（m3u 标准解析 + tvbox.json lives[] 二次解析合并）。
 * - 频道以分组标题 + 卡片呈现：台标加载失败时回退到「首字母彩色圆标」，列表更整洁。
 * - 点击频道调用 [TvPlayerActivity] 播放；RTSP 组播源当前播放器不支持，给出提示并跳过。
 */
class TvPage(
    private val activity: Activity,
    private val onClose: () -> Unit,
    private val scope: LifecycleCoroutineScope,
) {
    /** 颜色托盘：用于「首字母圆标」占位，按频道名取色，保证列表色彩不单调。 */
    private val palette = listOf(
        0xFF2E5FB8.toInt(), 0xFF1E8A60.toInt(), 0xFFB57623.toInt(), 0xFF9B59B6.toInt(),
        0xFFE3564D.toInt(), 0xFF0E9BB5.toInt(), 0xFF6D4C41.toInt(), 0xFF3F7FBF.toInt(),
    )

    private var allChannels: List<Channel> = emptyList()
    private var currentSource: TvSource? = null
    private var keyword = ""
    /** 为 true 表示加载「全部源合并」模式（position 0） */
    private var mergedMode = false

    private lateinit var sourceSpinner: Spinner
    private lateinit var searchEdit: EditText
    private lateinit var listView: ListView
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private val adapter = ChannelAdapter()

    fun build(): View {
        val d = activity.resources.displayMetrics.density
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(14, d), Ui.dp(12, d), Ui.dp(14, d), Ui.dp(12, d))
        }

        // 标题栏
        val titleBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(activity).apply {
            text = "手机电视"
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
        }
        val reload = Button(activity).apply {
            text = "刷新"
            textSize = 13f
            setOnClickListener { loadCurrent() }
        }
        titleBar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleBar.addView(reload)

        // 源选择（第一项固定为「全部源合并」）
        sourceSpinner = Spinner(activity)
        val srcNames = mutableListOf("全部源合并 (TVBox+IPv4+IPv6)")
        srcNames += IptvSources.defaults.map { it.name }
        srcNames.add("➕ 自定义源…")
        sourceSpinner.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, srcNames).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        sourceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                when {
                    pos == 0 -> { mergedMode = true; currentSource = null; loadCurrent() }
                    pos <= IptvSources.defaults.size -> {
                        mergedMode = false
                        currentSource = IptvSources.defaults[pos - 1]
                        loadCurrent()
                    }
                    else -> promptCustomSource(d)
                }
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }

        // 搜索框
        searchEdit = EditText(activity).apply {
            hint = "搜索频道名 / 分组"
            setTextColor(Ui.primaryText(activity))
            setHintTextColor(Color.parseColor("#8A9099"))
            textSize = 14f
            background = Ui.neuInset(activity, 12f)
            setPadding(Ui.dp(12, d), Ui.dp(8, d), Ui.dp(12, d), Ui.dp(8, d))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    keyword = s?.toString() ?: ""
                    refreshList()
                }
            })
        }

        // 状态行
        val statusRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(6, d), 0, Ui.dp(4, d))
        }
        progress = ProgressBar(activity, null, android.R.attr.progressBarStyleSmall)
        statusText = TextView(activity).apply {
            text = "准备中…"
            textSize = 13f
            setTextColor(Color.parseColor("#8A9099"))
            setPadding(Ui.dp(10, d), 0, 0, 0)
        }
        statusRow.addView(progress)
        statusRow.addView(statusText)

        // 频道列表
        listView = ListView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            divider = null
            dividerHeight = 0
            setSelector(android.R.color.transparent)
            clipToPadding = false
            setPadding(0, Ui.dp(4, d), 0, Ui.dp(4, d))
        }
        listView.adapter = adapter

        root.addView(titleBar)
        root.addView(sourceSpinner)
        root.addView(searchEdit)
        root.addView(statusRow)
        root.addView(listView)

        loadCurrent()
        return root
    }

    private fun loadCurrent() {
        progress.visibility = View.VISIBLE
        statusText.text = if (mergedMode) "正在合并加载所有源…" else "正在加载：${currentSource?.name} …"
        scope.launch {
            try {
                val ch = if (mergedMode) {
                    IptvSource.loadAllMerged(IptvSources.defaults)
                } else {
                    IptvSource.loadChannels(currentSource!!)
                }
                withContext(Dispatchers.Main) {
                    allChannels = ch
                    val note = if (mergedMode) "（已合并 ${IptvSources.defaults.size} 路源，多线路已叠加）" else ""
                    statusText.text = if (ch.isEmpty()) {
                        "未解析到频道（源可能失效或格式不支持）"
                    } else {
                        "共 ${ch.size} 个频道 $note"
                    }
                    refreshList()
                    progress.visibility = View.GONE
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "加载失败：${e.message ?: "网络错误"}"
                    progress.visibility = View.GONE
                }
            }
        }
    }

    private fun refreshList() {
        val kw = keyword.trim()
        val filtered = if (kw.isBlank()) {
            allChannels
        } else {
            allChannels.filter {
                it.name.contains(kw, ignoreCase = true) ||
                    (it.group?.contains(kw, ignoreCase = true) ?: false)
            }
        }
        adapter.submit(filtered)
    }

    private fun openPlayer(ch: Channel) {
        if (ch.url.startsWith("rtsp://", ignoreCase = true)) {
            Toast.makeText(activity, "RTSP 组播源当前播放器暂不支持，已跳过", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(activity, TvPlayerActivity::class.java).apply {
            putExtra(TvPlayerActivity.EXTRA_NAME, ch.name)
            putExtra(TvPlayerActivity.EXTRA_URL, ch.url)
            // RTSP 组播 ExoPlayer 播不了，备用线路里先剔掉
            val alts = ch.alts.filterNot { it.startsWith("rtsp://", ignoreCase = true) }
            if (alts.isNotEmpty()) {
                putStringArrayListExtra(TvPlayerActivity.EXTRA_ALTS, ArrayList(alts))
            }
        }
        activity.startActivity(intent)
        activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun promptCustomSource(d: Float) {
        val edit = EditText(activity).apply {
            hint = "粘贴 TVBox 订阅(.json) 或 m3u 源地址"
            setTextColor(Ui.primaryText(activity))
            setHintTextColor(Color.parseColor("#8A9099"))
            background = Ui.neuInset(activity, 12f)
            setPadding(Ui.dp(12, d), Ui.dp(8, d), Ui.dp(12, d), Ui.dp(8, d))
        }
        val pad = Ui.dp(20, d)
        AlertDialog.Builder(activity)
            .setTitle("自定义源")
            .setView(
                LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(pad, Ui.dp(8, d), pad, 0)
                    addView(edit)
                },
            )
            .setPositiveButton("加载") { _, _ ->
                val url = edit.text.toString().trim()
                if (url.isNotBlank()) {
                    mergedMode = false
                    currentSource = IptvSources.fromUrl("自定义源", url)
                    loadCurrent()
                } else {
                    sourceSpinner.setSelection(0)
                }
            }
            .setNegativeButton("取消") { _, _ -> sourceSpinner.setSelection(0) }
            .show()
            .also { Ui.styleDialog(activity, it) }
    }

    // ============================================================ 列表适配器

    private sealed class Row
    private data class HeaderRow(val group: String) : Row()
    private data class ChannelRow(val ch: Channel) : Row()

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
    }

    private inner class ChannelAdapter : BaseAdapter() {
        private var rows: List<Row> = emptyList()
        fun submit(channels: List<Channel>) {
            // 按分组归类，分组内按名称排序，分组标题随列表出现顺序保持
            val byGroup = LinkedHashMap<String, MutableList<Channel>>()
            for (c in channels) byGroup.getOrPut(c.group ?: "其他") { mutableListOf() }.add(c)
            val out = mutableListOf<Row>()
            for ((group, list) in byGroup) {
                out.add(HeaderRow(group))
                list.sortedBy { it.name }.forEach { out.add(ChannelRow(it)) }
            }
            rows = out
            notifyDataSetChanged()
        }

        override fun getCount() = rows.size
        override fun getItem(p: Int): Any = rows[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int): Int =
            if (rows[position] is HeaderRow) TYPE_HEADER else TYPE_ITEM

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val d = activity.resources.displayMetrics.density
            return if (getItemViewType(position) == TYPE_HEADER) {
                buildHeader(convertView, (rows[position] as HeaderRow).group, d)
            } else {
                buildCard(convertView, (rows[position] as ChannelRow).ch, position, d)
            }
        }

        // 分组标题
        private fun buildHeader(view: View?, group: String, d: Float): View {
            val tv = view as? TextView ?: TextView(activity).apply {
                textSize = 12f
                setTypeface(Ui.typeface, Typeface.BOLD)
                setTextColor(Ui.secondaryText(activity))
                gravity = Gravity.CENTER_VERTICAL
            }
            tv.text = group
            tv.setPadding(Ui.dp(4, d), Ui.dp(10, d), 0, Ui.dp(4, d))
            return tv
        }

        // 频道卡片
        private fun buildCard(view: View?, ch: Channel, position: Int, d: Float): View {
            var root = view as? LinearLayout
            val holder: CardHolder
            if (root == null) {
                root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                val card = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(Ui.dp(12, d), Ui.dp(10, d), Ui.dp(12, d), Ui.dp(10, d))
                    // 玻璃卡 + 涟漪：与全局「拟态卡片」语言一致
                    background = RippleDrawable(
                        android.content.res.ColorStateList.valueOf(Color.argb(60, 47, 124, 246)),
                        Ui.neuCard(activity, 16f),
                        null,
                    )
                    isClickable = true
                    isFocusable = true
                }
                val logoBox = FrameLayout(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(Ui.dp(46, d), Ui.dp(46, d)).apply {
                        marginEnd = Ui.dp(12, d)
                    }
                }
                val placeholder = TextView(activity).apply {
                    gravity = Gravity.CENTER
                    textSize = 18f
                    setTypeface(Ui.typeface, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    visibility = View.VISIBLE
                }
                val logo = ImageView(activity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                    clipToOutline = true
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(v: View, o: android.graphics.Outline) {
                            o.setRoundRect(0, 0, v.width, v.height, Ui.dp(10, d).toFloat())
                        }
                    }
                    visibility = View.GONE
                }
                logoBox.addView(placeholder)
                logoBox.addView(logo)

                val texts = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val name = TextView(activity).apply {
                    textSize = 15f
                    setTypeface(Ui.typeface, Typeface.BOLD)
                    setTextColor(Ui.primaryText(activity))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                val sub = TextView(activity).apply {
                    textSize = 12f
                    setTextColor(Color.parseColor("#8A9099"))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, Ui.dp(2, d), 0, 0)
                }
                texts.addView(name)
                texts.addView(sub)

                val badge = TextView(activity).apply {
                    textSize = 11f
                    setTypeface(Ui.typeface, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        cornerRadius = Ui.dp(20, d).toFloat()
                        setColor(0xFF1E8A60.toInt())
                    }
                    setPadding(Ui.dp(8, d), Ui.dp(3, d), Ui.dp(8, d), Ui.dp(3, d))
                    visibility = View.GONE
                }

                card.addView(logoBox)
                card.addView(texts)
                card.addView(badge)
                root.addView(card)
                // 卡片之间的留白，营造「卡片」而非「列表行」的观感
                root.addView(View(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(8, d))
                })
                holder = CardHolder(card, placeholder, logo, name, sub, badge)
                root.tag = holder
                card.setOnClickListener { openPlayer(ch) }
                Ui.pressAnimation(card)
            } else {
                holder = root.tag as CardHolder
            }

            holder.name.text = ch.name
            val lineCount = ch.alts.size + 1
            holder.sub.text = buildString {
                append(ch.group ?: "直播")
                append("  ·  $lineCount 路")
            }
            if (ch.alts.isNotEmpty()) {
                holder.badge.visibility = View.VISIBLE
                holder.badge.text = "${ch.alts.size + 1}"
            } else {
                holder.badge.visibility = View.GONE
            }

            // 台标：优先异步加载；失败或缺失则回退到「首字母彩色圆标」
            val initial = ch.name.firstOrNull()?.toString() ?: "?"
            holder.placeholder.text = initial
            holder.placeholder.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(palette[Math.abs(ch.name.hashCode()) % palette.size])
            }
            holder.logo.visibility = View.GONE
            holder.placeholder.visibility = View.VISIBLE
            val logoUrl = ch.logo
            if (!logoUrl.isNullOrBlank()) {
                scope.launch {
                    val bmp = IptvSource.loadLogo(logoUrl)
                    val stillSame = (adapter.rows.getOrNull(position) as? ChannelRow)?.ch?.logo == logoUrl
                    if (bmp != null && stillSame) {
                        withContext(Dispatchers.Main) {
                            holder.logo.setImageBitmap(bmp)
                            holder.logo.visibility = View.VISIBLE
                            holder.placeholder.visibility = View.GONE
                        }
                    }
                }
            }
            return root!!
        }
    }

    /** 频道卡片 ViewHolder：与全局「拟态卡片」语言一致 */
    private data class CardHolder(
        val card: LinearLayout,
        val placeholder: TextView,
        val logo: ImageView,
        val name: TextView,
        val sub: TextView,
        val badge: TextView,
    )
}
