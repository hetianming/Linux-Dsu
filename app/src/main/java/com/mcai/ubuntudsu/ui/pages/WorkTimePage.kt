package com.mcai.ubuntudsu.ui.pages

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.mcai.ubuntudsu.R
import com.mcai.ubuntudsu.core.WorkTimeStore
import com.mcai.ubuntudsu.ui.Ui
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 日历工时记页面：上下班打卡 + 日历月视图 + 每日工时 + 月度统计（工时/工资）
 *
 * 数据源 [WorkTimeStore]（SharedPreferences）；界面沿用主应用拟态玻璃架构（Ui.glassSurface / neuCard）。
 */
class WorkTimePage(private val activity: Activity) {

    private val store = WorkTimeStore(activity)
    private val d = activity.resources.displayMetrics.density

    private var cal: Calendar = Calendar.getInstance()
    private val monthFmt = SimpleDateFormat("yyyy-MM", Locale.getDefault())
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val timeFmtSec = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // 持久引用：刷新时直接 update
    private var monthTitle: TextView? = null
    private var statTotal: TextView? = null
    private var statSalary: TextView? = null
    private var statDays: TextView? = null
    private var daysContainer: LinearLayout? = null
    private var dayDetailPanel: LinearLayout? = null
    private var clockInBtn: TextView? = null
    private var clockOutBtn: TextView? = null
    private var liveTimer: TextView? = null
    private var gearBtn: TextView? = null

    private var timerRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    /** 设置面板各输入框的「校验并保存」动作；点「完成」时统一执行，任一校验失败则阻止关闭 */
    private val settingsSaves = ArrayList<() -> Boolean>()

    // 工资参数随工资模式动态切换（时薪模式只留时薪；月薪模式只留月薪 + 标准工时）
    private var salaryTitle: TextView? = null
    private var salaryContainer: LinearLayout? = null
    private var salaryHourlyInput: EditText? = null
    private var salaryMonthlyInput: EditText? = null
    private var salaryStdHoursInput: EditText? = null

    /** 工时记主题色（青绿，与蓝灰玻璃卡片协调） */
    private val ACCENT = Color.parseColor("#00A98F")
    /** 当日高亮用的半透明主题色 */
    private val ACCENT_SOFT = Color.argb(56, 0, 169, 143)
    /** 主题色文字：日间压深、夜间提亮，保证在玻璃卡片上清晰可读 */
    private val accentText: Int
        get() = if (Ui.isDark(activity)) Color.parseColor("#3DD9BE") else Color.parseColor("#00614F")

    fun build(): View {
        val scroll = ScrollView(activity).apply {
            isFillViewport = false
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, Ui.dp(16, d))
        }

        root.addView(headerBar())
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(16, d), Ui.dp(14, d), Ui.dp(16, d), 0)
        }
        body.addView(monthStatCard())
        body.addView(calendarCard())
        body.addView(dayDetailCard())
        body.addView(clockCard())
        root.addView(body)

        scroll.addView(root)
        refresh()
        return scroll
    }

    // ==================== 顶部青绿渐变圆角薄条（返回 + 标题 + 齿轮） ====================

    private fun headerBar(): View {
        val bar = FrameLayout(activity)
        bar.background = Ui.gradientGlassCard(
            activity,
            intArrayOf(
                Color.parseColor("#2BC6AE"),
                Color.parseColor("#12B39A"),
                Color.parseColor("#009E86"),
            ),
            accent = ACCENT,
            radiusDp = 18f,
        )
        bar.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = Ui.dp(16, d)
            rightMargin = Ui.dp(16, d)
            topMargin = Ui.dp(4, d)
        }
        val titleContainer = FrameLayout(activity)
        titleContainer.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = 0
        }
        titleContainer.addView(TextView(activity).apply {
            text = "工时记"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(Ui.dp(16, d), Ui.dp(14, d), Ui.dp(16, d), Ui.dp(14, d))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        })
        gearBtn = TextView(activity).apply {
            text = "⚙"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(Ui.dp(4, d), 0, 0, 0)
            isClickable = true
            setOnClickListener { showSettingsPanel() }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL,
            ).apply {
                marginEnd = Ui.dp(16, d)
            }
        }
        titleContainer.addView(gearBtn!!)
        bar.addView(titleContainer)
        return bar
    }

    // ==================== 月统计卡 ====================

    private fun monthStatCard(): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.glassSurface(activity, 18f)
            setPadding(Ui.dp(18, d), Ui.dp(16, d), Ui.dp(18, d), Ui.dp(16, d))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(10, d) }
        }
        card.addView(sectionTitle("本月统计"))
        val statsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statTotal = statsRow.addStatCell("总工时", "0")
        statSalary = statsRow.addStatCell("工资", "0")
        statDays = statsRow.addStatCell("打卡天数", "0")
        card.addView(statsRow)
        card.addView(TextView(activity).apply {
            text = "复制当月记录"
            textSize = 11f
            setTextColor(accentText)
            background = Ui.neuInset(activity, 10f)
            setPadding(Ui.dp(10, d), Ui.dp(4, d), Ui.dp(10, d), Ui.dp(4, d))
            isClickable = true
            setOnClickListener { copyMonthRecords() }
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = Ui.dp(10, d)
        })
        return card
    }

    private fun sectionTitle(text: String): TextView = TextView(activity).apply {
        this.text = text
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Ui.primaryText(activity))
        setPadding(0, 0, 0, Ui.dp(8, d))
    }

    /** 统计单元：大数字（数值）+ 上方大标签 + 下方单位，标签可指定橙色（工资） */
    private fun LinearLayout.addStatCell(title: String, value: String): TextView {
        val cell = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(Ui.dp(4, d), 0, Ui.dp(4, d), 0)
        }
        cell.addView(TextView(activity).apply {
            text = title
            textSize = 12f
            setTextColor(if (title == "工资") accentText else Ui.secondaryText(activity))
            setPadding(0, 0, 0, Ui.dp(6, d))
        })
        val valueText = TextView(activity).apply {
            text = value
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (title == "工资") accentText else Ui.primaryText(activity))
            maxLines = 1
            isSingleLine = true
            gravity = Gravity.CENTER
        }
        cell.addView(valueText)
        cell.addView(TextView(activity).apply {
            text = if (title == "总工时") "小时" else if (title == "工资") "元" else "天"
            textSize = 10f
            setTextColor(if (title == "工资") accentText else Ui.secondaryText(activity))
            setPadding(0, Ui.dp(2, d), 0, 0)
        })
        addView(cell)
        return valueText
    }

    // ==================== 日历卡 ====================

    private fun calendarCard(): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.glassSurface(activity, 18f)
            setPadding(Ui.dp(16, d), Ui.dp(14, d), Ui.dp(16, d), Ui.dp(14, d))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(10, d) }
        }
        val navRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        navRow.addView(narrowBtn("‹") {
            cal.add(Calendar.MONTH, -1); refresh()
        })
        monthTitle = TextView(activity).apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        navRow.addView(monthTitle!!)
        navRow.addView(narrowBtn("›") {
            cal.add(Calendar.MONTH, 1); refresh()
        })
        card.addView(navRow)
        // 周标题行
        val headRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(6, d), 0, Ui.dp(2, d))
        }
        for (w in listOf("一", "二", "三", "四", "五", "六", "日")) {
            headRow.addView(TextView(activity).apply {
                text = w
                textSize = 10f
                setTextColor(Ui.secondaryText(activity))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        card.addView(headRow)
        // 日期行容器（refresh 时动态重建）
        daysContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        card.addView(daysContainer!!)
        return card
    }

    private fun narrowBtn(symbol: String, onClick: () -> Unit): TextView {
        return TextView(activity).apply {
            text = symbol
            textSize = 18f
            setTextColor(Ui.primaryText(activity))
            gravity = Gravity.CENTER
            background = Ui.neuInset(activity, 10f)
            setPadding(Ui.dp(12, d), Ui.dp(6, d), Ui.dp(12, d), Ui.dp(6, d))
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    // ==================== 当日详情卡 ====================

    private fun dayDetailCard(): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.glassSurface(activity, 18f)
            setPadding(Ui.dp(16, d), Ui.dp(14, d), Ui.dp(16, d), Ui.dp(14, d))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(10, d) }
        }
        card.addView(sectionTitle("当日详情"))
        dayDetailPanel = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        card.addView(dayDetailPanel!!)
        return card
    }

    // ==================== 打卡操作卡 ====================

    private fun clockCard(): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.glassSurface(activity, 18f)
            setPadding(Ui.dp(16, d), Ui.dp(14, d), Ui.dp(16, d), Ui.dp(14, d))
        }
        card.addView(sectionTitle("今日打卡"))
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }

        clockInBtn = TextView(activity).apply {
            text = "上班"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundBg(ACCENT)
            setPadding(Ui.dp(14, d), Ui.dp(8, d), Ui.dp(14, d), Ui.dp(8, d))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0f
            ).apply { gravity = Gravity.CENTER_VERTICAL }
            isClickable = true
            setOnClickListener { doClockIn() }
        }
        row.addView(clockInBtn!!)

        liveTimer = TextView(activity).apply {
            text = "00:00:00"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accentText)
            gravity = Gravity.CENTER
            letterSpacing = 0.04f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(liveTimer!!)

        clockOutBtn = TextView(activity).apply {
            text = "下班"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundBg(ACCENT)
            setPadding(Ui.dp(14, d), Ui.dp(8, d), Ui.dp(14, d), Ui.dp(8, d))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0f
            ).apply { gravity = Gravity.CENTER_VERTICAL }
            isClickable = true
            setOnClickListener { doClockOut() }
        }
        row.addView(clockOutBtn!!)

        card.addView(row)
        return card
    }

    // ==================== 日历日期行生成 ====================

    private fun buildDayRows(): LinearLayout {
        val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(System.currentTimeMillis())
        val marked = linkedSetOf<String>()
        for (r in store.monthRecords(monthFmt.format(cal.time))) marked.add(r.date)

        val firstOfMonth = (cal.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
        val lastDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val firstWeek = firstOfMonth.get(Calendar.DAY_OF_WEEK) // 1=Sun,2=Mon,...7=Sat
        val leading = if (firstWeek == 1) 6 else firstWeek - 2 // 周一基

        var cell = 0
        var row = newDayRow()
        while (cell < leading) {
            row.addView(blankCell())
            cell++
            if (cell % 7 == 0) { container.addView(row); row = newDayRow() }
        }
        for (day in 1..lastDay) {
            val dc = (cal.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, day) }
            val ds = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(dc.time)
            val isSel = day == cal.get(Calendar.DAY_OF_MONTH) && isCurrentMonthSelected()
            val isToday = ds == todayStr
            row.addView(buildDayCell(ds, day, isSel, isToday, marked.contains(ds)))
            cell++
            if (cell % 7 == 0) {
                container.addView(row); row = newDayRow()
                if (day == lastDay) break
            }
        }
        // 补齐尾部空格
        if (row.childCount > 0 || cell % 7 != 0) {
            for (i in 0 until ((7 - cell % 7) % 7)) row.addView(blankCell())
            if (row.childCount > 0) container.addView(row)
        }
        return container
    }

    private fun newDayRow(): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(2, d), 0, Ui.dp(2, d))
        }

    private fun blankCell(): View = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(0, Ui.dp(32, d), 1f)
    }

    private fun isCurrentMonthSelected(): Boolean = cal.get(Calendar.MONTH) ==
        Calendar.getInstance().get(Calendar.MONTH) && cal.get(Calendar.YEAR) ==
        Calendar.getInstance().get(Calendar.YEAR)

    private fun buildDayCell(dateStr: String, day: Int, isSel: Boolean, isToday: Boolean, isMarked: Boolean): LinearLayout {
        val restDay = !store.isWorkday(dateStr)
        val cell = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, Ui.dp(32, d), 1f)
            isClickable = true
            setOnClickListener {
                val c2 = Calendar.getInstance()
                c2.time = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dateStr)
                    ?: return@setOnClickListener
                cal = c2
                refresh()
                expandDayDetail(dateStr)
            }
            background = when {
                isSel -> roundBg(ACCENT)
                isToday -> roundBg(ACCENT_SOFT)
                else -> null
            }
            setPadding(0, Ui.dp(4, d), 0, Ui.dp(4, d))
        }
        cell.addView(TextView(activity).apply {
            text = day.toString()
            textSize = if (isSel || isToday) 14f else 12f
            setTypeface(typeface, if (isSel || isToday) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(when {
                isSel -> Color.WHITE
                isToday -> accentText
                restDay -> Ui.secondaryText(activity)
                else -> Ui.primaryText(activity)
            })
            gravity = Gravity.CENTER
        })
        // 标记点：休息日显示空心圈，工作日显示实心点
        if (isMarked || restDay) {
            cell.addView(View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(Ui.dp(4, d), Ui.dp(4, d)).apply {
                    topMargin = Ui.dp(1, d)
                }
                background = when {
                    isSel -> ColorDrawable(Color.WHITE)
                    isMarked -> ColorDrawable(ACCENT)
                    else -> GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.TRANSPARENT)
                        setStroke(Ui.dp(1, d).toInt(), Color.argb(120, 120, 120, 120))
                    }
                }
            })
        }
        return cell
    }

    private fun roundBg(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = Ui.dp(10, d).toFloat()
        }
    }

    // ==================== 刷新 ====================

    private fun refresh() {
        val ym = monthFmt.format(cal.time)
        monthTitle?.text = String.format(Locale.getDefault(), "%d年%d月",
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)

        // 重建日历
        val old = daysContainer
        if (old != null) {
            old.removeAllViews()
            old.addView(buildDayRows())
        }

        // 统计
        val totalH = store.monthTotalHours(ym)
        statTotal?.text = String.format(Locale.getDefault(), "%.1f", totalH)
        statSalary?.text = String.format(Locale.getDefault(), "%.2f", store.monthSalary(ym))
        statDays?.text = store.monthRecords(ym).size.toString()

        // 打卡区
        refreshClockArea()

        val selected = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
        expandDayDetail(selected)
    }

    // ==================== 当日详情 ====================

    private fun expandDayDetail(dateStr: String) {
        val panel = dayDetailPanel ?: return
        panel.removeAllViews()
        val record = store.getRecordFor(dateStr)
        if (record == null) {
            panel.addView(TextView(activity).apply {
                text = "当日无记录"
                textSize = 12f
                setTextColor(Ui.secondaryText(activity))
            })
            panel.addView(actionChip("补录 / 修改工时") { showEditHoursDialog(dateStr) })
            return
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(4, d), 0, 0)
        }
        val inValue = if (record.clockIn > 0) timeFmt.format(record.clockIn * 1000L) else "—"
        row.addView(buildDetailCell("上班", inValue, Ui.primaryText(activity)))
        val outValue = if (record.inProgress) "—"
        else timeFmt.format(record.clockOut * 1000L)
        row.addView(buildDetailCell("下班", outValue, Ui.primaryText(activity)))
        val workValue = if (record.inProgress) "进行中"
        else String.format(Locale.getDefault(), "%.2f", store.netHours(record))
        row.addView(buildDetailCell("工时", workValue,
            if (record.inProgress) accentText else Ui.primaryText(activity)))
        val del = TextView(activity).apply {
            text = "删除"
            textSize = 11f
            setTextColor(Ui.secondaryText(activity))
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener {
                store.deleteRecord(dateStr)
                refresh()
            }
        }
        row.addView(wrapCell(del))
        panel.addView(row)
        panel.addView(actionChip("修改工时") { showEditHoursDialog(dateStr) })

        if (!record.inProgress) {
            val ev = store.evalLateEarly(dateStr, record)
            if (ev.late || ev.early) {
                val tags = ArrayList<String>()
                if (ev.late) tags.add("迟到")
                if (ev.early) tags.add("早退")
                panel.addView(TextView(activity).apply {
                    text = tags.joinToString(" · ")
                    textSize = 11f
                    setTextColor(Ui.buttonWarning(activity))
                    setPadding(0, Ui.dp(4, d), 0, 0)
                })
            }
        }
    }

    private fun actionChip(label: String, onClick: () -> Unit): TextView = TextView(activity).apply {
        text = label
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(accentText)
        gravity = Gravity.CENTER
        background = Ui.neuInset(activity, 10f)
        setPadding(Ui.dp(10, d), Ui.dp(6, d), Ui.dp(10, d), Ui.dp(6, d))
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Ui.dp(10, d) }
    }

    private fun showEditHoursDialog(dateStr: String) {
        val record = store.getRecordFor(dateStr)
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(20, d), Ui.dp(8, d), Ui.dp(20, d), Ui.dp(8, d))
        }
        val inDefault = when {
            record != null && record.clockIn > 0 -> timeFmt.format(record.clockIn * 1000L)
            else -> store.getBaseClockIn()
        }
        val outDefault = when {
            record != null && !record.inProgress -> timeFmt.format(record.clockOut * 1000L)
            else -> store.getBaseClockOut()
        }
        val hoursDefault = when {
            record != null && !record.inProgress ->
                String.format(Locale.getDefault(), "%.2f", store.netHours(record))
            else -> ""
        }
        val inInput = dialogField("上班时间（HH:mm）", inDefault)
        val outInput = dialogField("下班时间（HH:mm）", outDefault)
        val hoursInput = dialogField("净工时（小时，可直接改）", hoursDefault)
        form.addView(inInput.first)
        form.addView(outInput.first)
        form.addView(hoursInput.first)
        form.addView(TextView(activity).apply {
            text = "改净工时会按上班时间自动推算下班时间；改上下班会自动重算工时。"
            textSize = 11f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, Ui.dp(8, d), 0, 0)
        })
        val dialog = AlertDialog.Builder(activity)
            .setTitle("修改 $dateStr 工时")
            .setView(form)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val inEpoch = parseHmToEpoch(dateStr, inInput.second.text.toString().trim())
            if (inEpoch == null) {
                inInput.second.error = "格式 HH:mm"
                return@setOnClickListener
            }
            val hoursText = hoursInput.second.text.toString().trim()
            val hoursEdited = hoursText.isNotEmpty() && hoursText != hoursDefault
            val clockOut: Long = if (hoursEdited) {
                val hours = hoursText.toDoubleOrNull()
                if (hours == null || hours < 0.0) {
                    hoursInput.second.error = "工时需 ≥ 0"
                    return@setOnClickListener
                }
                val deductSec = store.getRestDeductMinutes() * 60L
                (inEpoch + (hours * 3600.0).toLong() + deductSec).coerceAtLeast(inEpoch + 1L)
            } else {
                val outEpoch = parseHmToEpoch(dateStr, outInput.second.text.toString().trim())
                if (outEpoch == null) {
                    outInput.second.error = "格式 HH:mm"
                    return@setOnClickListener
                }
                outEpoch
            }
            if (!store.upsertRecord(dateStr, inEpoch, clockOut)) {
                toast("下班须晚于上班")
                return@setOnClickListener
            }
            dialog.dismiss()
            refresh()
            toast("已保存当日工时")
        }
    }

    private fun dialogField(label: String, value: String): Pair<LinearLayout, EditText> {
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, Ui.dp(6, d), 0, Ui.dp(4, d))
        }
        col.addView(TextView(activity).apply {
            text = label
            textSize = 12f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, 0, 0, Ui.dp(4, d))
        })
        val input = EditText(activity).apply {
            setText(value)
            setPadding(Ui.dp(8, d), Ui.dp(8, d), Ui.dp(8, d), Ui.dp(8, d))
            background = Ui.neuInset(activity, 10f)
            maxLines = 1
        }
        col.addView(input)
        return col to input
    }

    private fun parseHmToEpoch(dateStr: String, hm: String): Long? {
        val parts = hm.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val m = parts.getOrNull(1)?.toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59 || parts.size != 2) return null
        val c = Calendar.getInstance()
        c.time = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dateStr) ?: return null
        c.set(Calendar.HOUR_OF_DAY, h)
        c.set(Calendar.MINUTE, m)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis / 1000L
    }

    /** 明细单元：数值（大字）+ 标签（小字） */
    private fun buildDetailCell(label: String, value: String, valueColor: Int): View {
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(activity).apply {
            text = value
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(valueColor)
            gravity = Gravity.CENTER
        })
        col.addView(TextView(activity).apply {
            text = label
            textSize = 11f
            setTextColor(Ui.secondaryText(activity))
            gravity = Gravity.CENTER
        })
        return col
    }

    /** 把内容 TextView 包成等宽单元格 */
    private fun wrapCell(content: TextView): View {
        val wrap = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        wrap.addView(content)
        return wrap
    }

    // ==================== 打卡 ====================

    private fun refreshClockArea() {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(System.currentTimeMillis())
        val record = store.getRecordFor(todayStr)
        stopTimer()
        when {
            record == null -> {
                clockInBtn?.isEnabled = true; clockInBtn?.alpha = 1f
                clockOutBtn?.isEnabled = false; clockOutBtn?.alpha = 0.4f
                liveTimer?.text = "尚未打卡"
                liveTimer?.textSize = 13f
                liveTimer?.setTextColor(Ui.secondaryText(activity))
            }
            record.inProgress -> {
                clockInBtn?.isEnabled = false; clockInBtn?.alpha = 0.4f
                clockOutBtn?.isEnabled = true; clockOutBtn?.alpha = 1f
                liveTimer?.textSize = 24f
                liveTimer?.setTextColor(accentText)
                startTimer(record)
            }
            else -> {
                clockInBtn?.isEnabled = false; clockInBtn?.alpha = 0.4f
                clockOutBtn?.isEnabled = false; clockOutBtn?.alpha = 0.4f
                liveTimer?.text = String.format(Locale.getDefault(), "%.2f h", store.netHours(record))
                liveTimer?.textSize = 20f
                liveTimer?.setTextColor(Ui.buttonSuccess(activity))
            }
        }
    }

    private fun doClockIn() {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(System.currentTimeMillis())
        if (store.clockIn(todayStr, System.currentTimeMillis() / 1000L)) refresh()
        else toast("今日已打卡")
    }

    private fun doClockOut() {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(System.currentTimeMillis())
        val rec = store.getRecordFor(todayStr)
        if (rec == null) { toast("请先上班打卡"); return }
        if (rec.inProgress && store.clockOut(todayStr, System.currentTimeMillis() / 1000L)) refresh()
        else toast("下班时间需晚于上班时间，可删除重打")
    }

    private fun startTimer(record: WorkTimeStore.Record) {
        val run = object : Runnable {
            override fun run() {
                val elapsedSec = System.currentTimeMillis() / 1000L - record.clockIn
                liveTimer?.text = timeFmtSec.format(elapsedSec * 1000L)
                handler.postDelayed(this, 1000L)
            }
        }
        timerRunnable = run
        handler.postDelayed(run, 1000L)
    }

    private fun stopTimer() {
        timerRunnable?.let { handler.removeCallbacks(it) }
        timerRunnable = null
    }

    // ==================== 完整设置面板（工时记全部配置） ====================

    private fun showSettingsPanel() {
        settingsSaves.clear()
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(20, d), Ui.dp(8, d), Ui.dp(20, d), Ui.dp(8, d))
        }
        val scroll = ScrollView(activity)
        scroll.addView(root)

        root.addView(setSubTitle("工作日设置"))
        root.addView(buildWorkdayRow())
        root.addView(setSubTitle("上下班基准（迟到/早退判定）"))
        root.addView(buildBaseTimeRow())
        root.addView(setSubTitle("加班倍率"))
        root.addView(buildOtRateSection())
        root.addView(setSubTitle("吃饭 / 休息扣除（不计入工时）"))
        root.addView(buildRestDeductRow())
        root.addView(setSubTitle("工资模式"))
        root.addView(buildPayModeRow())
        salaryTitle = setSubTitle("工资参数")
        root.addView(salaryTitle)
        root.addView(buildSalarySection())

        val dialog = AlertDialog.Builder(activity)
            .setTitle("工时记设置")
            .setView(scroll)
            .setPositiveButton("完成", null)
            .create()
        dialog.show()
        // 覆盖默认的点击关闭：先校验并保存全部输入，全部通过才关闭
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val ok = settingsSaves.all { it() } and validateSalary()
            if (ok) {
                refresh()
                dialog.dismiss()
            } else {
                toast("请检查输入格式（时间 HH:mm，数值需 > 0）")
            }
        }
    }

    private fun setSubTitle(text: String): TextView = TextView(activity).apply {
        this.text = text
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Ui.primaryText(activity))
        setPadding(0, Ui.dp(14, d), 0, Ui.dp(6, d))
    }

    /** 周一~周日 7 个开关 */
    private fun buildWorkdayRow(): View {
        val bits = store.getWorkdays()
        val labels = arrayOf("一", "二", "三", "四", "五", "六", "日")
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0 until 7) {
            val on = bits[i]
            val chip = TextView(activity).apply {
                text = labels[i]
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(if (on) Color.WHITE else Ui.secondaryText(activity))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = Ui.dp(8, d).toFloat()
                    setColor(if (on) ACCENT else 0x22FFFFFF.toInt())
                    setStroke(Ui.dp(1, d).toInt(),
                        if (on) accentText else Ui.secondaryText(activity))
                }
                setPadding(Ui.dp(8, d), Ui.dp(6, d), Ui.dp(8, d), Ui.dp(6, d))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { if (i > 0) marginStart = Ui.dp(4, d) }
                isClickable = true
                setOnClickListener {
                    bits[i] = !bits[i]
                    store.setWorkdays(bits)
                    setTextColor(if (bits[i]) Color.WHITE else Ui.secondaryText(activity))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = Ui.dp(8, d).toFloat()
                        setColor(if (bits[i]) ACCENT else 0x22FFFFFF.toInt())
                        setStroke(Ui.dp(1, d).toInt(),
                            if (bits[i]) accentText else Ui.secondaryText(activity))
                    }
                }
            }
            row.addView(chip)
        }
        return row
    }

    /** 上班 / 下班 基准时间（两个输入框） */
    private fun buildBaseTimeRow(): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        row.addView(baseTimeInput("上班", store.getBaseClockIn()) { v ->
            store.setBaseClockIn(v)
        })
        row.addView(baseTimeInput("下班", store.getBaseClockOut()) { v ->
            store.setBaseClockOut(v)
        })
        return row
    }

    private fun baseTimeInput(label: String, current: String, onCommit: (String) -> Unit): View {
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = Ui.dp(8, d) }
        }
        val input = EditText(activity).apply {
            hint = label + "时间"
            inputType = android.text.InputType.TYPE_CLASS_DATETIME or
                android.text.InputType.TYPE_DATETIME_VARIATION_TIME
            setText(current)
            setPadding(Ui.dp(6, d), Ui.dp(6, d), Ui.dp(6, d), Ui.dp(6, d))
            background = Ui.neuInset(activity, 10f)
            maxLines = 1
        }
        fun commit(): Boolean {
            val v = input.text.toString().trim()
            if (!v.matches(Regex("\\d{1,2}:\\d{2}"))) { input.error = "格式 HH:mm"; return false }
            val p = v.split(":")
            val h = p[0].toIntOrNull() ?: -1
            val m = p[1].toIntOrNull() ?: -1
            if (h !in 0..23 || m !in 0..59) { input.error = "时间无效"; return false }
            input.error = null
            onCommit(v)
            return true
        }
        input.setOnEditorActionListener { _, _, _ -> commit() }
        settingsSaves.add { commit() }
        col.addView(TextView(activity).apply {
            text = label
            textSize = 11f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, 0, 0, Ui.dp(2, d))
        })
        col.addView(input)
        return col
    }

    /** 加班倍率：开关 + 三个倍率输入（关闭时隐藏参数） */
    private var otRateContainer: LinearLayout? = null

    private fun buildOtRateSection(): View {
        val wrap = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        val toggleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(6, d), 0, Ui.dp(6, d))
        }
        toggleRow.addView(TextView(activity).apply {
            text = "加班倍率"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val sw = Switch(activity).apply {
            textOn = "开"
            textOff = "关"
            setChecked(store.getOtEnabled())
            setOnCheckedChangeListener { _, checked ->
                store.setOtEnabled(checked)
                otRateContainer?.visibility = if (checked) View.VISIBLE else View.GONE
            }
        }
        toggleRow.addView(sw)
        wrap.addView(toggleRow)

        val rateContainer = buildOtRateRow()
        rateContainer.visibility = if (store.getOtEnabled()) View.VISIBLE else View.GONE
        otRateContainer = rateContainer
        wrap.addView(rateContainer)

        return wrap
    }

    /** 三个加班倍率输入框 */
    private fun buildOtRateRow(): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, Ui.dp(6, d))
        }
        row.addView(rateInput("工作日", store.getOtRateWeekday()) { store.setOtRateWeekday(it) })
        row.addView(rateInput("休息日", store.getOtRateRest()) { store.setOtRateRest(it) })
        row.addView(rateInput("节假日", store.getOtRateHoliday()) { store.setOtRateHoliday(it) })
        return row
    }

    private fun rateInput(label: String, current: Double, onCommit: (Double) -> Unit): View {
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = Ui.dp(8, d) }
        }
        val input = EditText(activity).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format(Locale.getDefault(), "%.1f", current))
            setPadding(Ui.dp(6, d), Ui.dp(6, d), Ui.dp(6, d), Ui.dp(6, d))
            background = Ui.neuInset(activity, 10f)
            maxLines = 1
        }
        input.setOnEditorActionListener { _, _, _ ->
            val v = input.text.toString().toDoubleOrNull()
            if (v != null && v > 0.0) { input.error = null; onCommit(v); true }
            else { input.error = "需 > 0"; false }
        }
        settingsSaves.add {
            val v = input.text.toString().toDoubleOrNull()
            if (v != null && v > 0.0) { input.error = null; onCommit(v); true }
            else { input.error = "需 > 0"; false }
        }
        col.addView(TextView(activity).apply {
            text = label + " ×"
            textSize = 11f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, 0, 0, Ui.dp(2, d))
        })
        col.addView(input)
        return col
    }

    /** 工资模式 单选（时薪 / 月薪） */
    private fun buildPayModeRow(): View {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val items = arrayOf("时薪模式" to WorkTimeStore.PayMode.HOURLY, "月薪模式" to WorkTimeStore.PayMode.MONTHLY)
        val chips = arrayOfNulls<TextView>(items.size)
        fun render() {
            val mode = store.getPayMode()
            for (i in items.indices) {
                val sel = items[i].second == mode
                chips[i]?.let { c ->
                    c.setTextColor(if (sel) Color.WHITE else Ui.secondaryText(activity))
                    c.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = Ui.dp(8, d).toFloat()
                        setColor(if (sel) ACCENT else 0x22FFFFFF.toInt())
                        setStroke(Ui.dp(1, d).toInt(),
                            if (sel) accentText else Ui.secondaryText(activity))
                    }
                }
            }
        }
        for (i in items.indices) {
            val label = items[i].first
            val m = items[i].second
            val chip = TextView(activity).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(Ui.dp(10, d), Ui.dp(6, d), Ui.dp(10, d), Ui.dp(6, d))
                isClickable = true
                setOnClickListener {
                    store.setPayMode(m)
                    render()
                    renderSalary()
                    refresh()
                }
            }
            chip.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { if (i == 0) marginEnd = Ui.dp(8, d) }
            chips[i] = chip
            row.addView(chip)
        }
        render()
        return row
    }

    /** 工资参数区（随工资模式动态切换内容） */
    private fun buildSalarySection(): View {
        val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        salaryContainer = container
        renderSalary()
        return container
    }

    /** 按时薪/月薪模式重建工资参数：时薪模式只留时薪；月薪模式只留月薪 + 标准工时 */
    private fun renderSalary() {
        val container = salaryContainer ?: return
        container.removeAllViews()
        salaryHourlyInput = null
        salaryMonthlyInput = null
        salaryStdHoursInput = null
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        if (store.getPayMode() == WorkTimeStore.PayMode.HOURLY) {
            salaryTitle?.text = "时薪设置"
            val (v, et) = amountInput("时薪", store.getHourlyRate())
            salaryHourlyInput = et
            row.addView(v)
        } else {
            salaryTitle?.text = "月薪 / 月标准工时"
            val (v1, et1) = amountInput("月薪", store.getMonthlySalary())
            salaryMonthlyInput = et1
            row.addView(v1)
            val (v2, et2) = amountInput("标准工时", store.getStdMonthHours())
            salaryStdHoursInput = et2
            row.addView(v2)
        }
        container.addView(row)
    }

    /** 校验并保存当前显示的工资参数（只保存与模式相关的字段） */
    private fun validateSalary(): Boolean {
        var ok = true
        fun check(et: EditText?, save: (Double) -> Unit) {
            val input = et ?: return
            val v = input.text.toString().toDoubleOrNull()
            if (v != null && v > 0.0) { input.error = null; save(v) }
            else { input.error = "需 > 0"; ok = false }
        }
        check(salaryHourlyInput) { store.setHourlyRate(it) }
        check(salaryMonthlyInput) { store.setMonthlySalary(it) }
        check(salaryStdHoursInput) { store.setStdMonthHours(it) }
        return ok
    }

    private fun amountInput(label: String, current: Double): Pair<View, EditText> {
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = Ui.dp(8, d) }
        }
        val input = EditText(activity).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format(Locale.getDefault(), "%.2f", current))
            setPadding(Ui.dp(6, d), Ui.dp(6, d), Ui.dp(6, d), Ui.dp(6, d))
            background = Ui.neuInset(activity, 10f)
            maxLines = 1
        }
        col.addView(TextView(activity).apply {
            text = label
            textSize = 11f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, 0, 0, Ui.dp(2, d))
        })
        col.addView(input)
        return Pair(col, input)
    }

    /** 休息扣除：整数（分钟/天），允许 0 */
    private fun buildRestDeductRow(): View {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(intInput("扣除（分钟/天）", store.getRestDeductMinutes()) {
            store.setRestDeductMinutes(it)
        })
        return row
    }

    private fun intInput(label: String, current: Int, onCommit: (Int) -> Unit): View {
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = Ui.dp(8, d) }
        }
        val input = EditText(activity).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(current.toString())
            setPadding(Ui.dp(6, d), Ui.dp(6, d), Ui.dp(6, d), Ui.dp(6, d))
            background = Ui.neuInset(activity, 10f)
            maxLines = 1
        }
        fun commit(): Boolean {
            val v = input.text.toString().trim().toIntOrNull()
            return if (v != null && v >= 0) { input.error = null; onCommit(v); true }
            else { input.error = "需 ≥ 0"; false }
        }
        input.setOnEditorActionListener { _, _, _ -> commit() }
        settingsSaves.add { commit() }
        col.addView(TextView(activity).apply {
            text = label
            textSize = 11f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, 0, 0, Ui.dp(2, d))
        })
        col.addView(input)
        return col
    }

    // ==================== 复制当月记录到剪贴板 ====================

    private fun copyMonthRecords() {
        val ym = monthFmt.format(cal.time)
        val label = String.format(Locale.getDefault(), "%d年%d月",
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
        val csv = store.exportMonthCsv(ym, label)
        val cb = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cb.setPrimaryClip(android.content.ClipData.newPlainText("工时记录", csv))
        toast("已复制当月记录到剪贴板")
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    fun destroy() {
        stopTimer()
    }
}
