package com.mcai.ubuntudsu.ui.pages

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.mcai.ubuntudsu.core.RootShell
import com.mcai.ubuntudsu.ui.Haptics
import com.mcai.ubuntudsu.ui.Ui
import java.io.File
import com.mcai.ubuntudsu.Lang

/**
 * U盘启动页：把手机当 U 盘 / 安装盘使用。
 *
 * 核心能力（面向电脑安装 Windows 场景）：
 *  1. 在线获取 Windows 11 ISO（官方 ARM64 下载页 + 自定义直链走内置 aria2c 多线程下载）
 *  2. 用 Android USB Gadget（usb_gadget）框架把指定 ISO/IMG 暴露给电脑，
 *     电脑 BIOS 里选 U 盘/光盘启动即可安装系统
 *  3. 制作虚拟 U 盘 IMG 镜像（truncate + 可选 FAT32 格式化）
 *  4. 把已生成的 IMG 挂载到本地 /mnt/TimeVUD 进行读写
 *
 * 无 root 时降级为「下载 ISO → 提示用电脑 Rufus / 制作启动盘」。
 * 实现参考用户提供的 VIRTUAL 脚本（TimeVUD / CreateImg / MountImg / CheckVUD）。
 */
class UsbBootPage(
    private val activity: Activity,
    private val onBack: () -> Unit,
) {
    private val ctx: Context get() = activity
    private val d: Float get() = activity.resources.displayMetrics.density

    // ===== IMG 镜像制作状态 =====
    private val imgMaking = java.util.concurrent.atomic.AtomicBoolean(false)
    private val imgCancel = java.util.concurrent.atomic.AtomicBoolean(false)
    private var imgMakeThread: Thread? = null

    // UI 引用（构建后填充）
    private var imgStatusText: TextView? = null
    private var vudStatusText: TextView? = null
    private var vudTypeText: TextView? = null
    private var selinuxText: TextView? = null
    private var startVudBtn: TextView? = null
    private var stopVudBtn: TextView? = null

    // 当前选中的 VUD 连接模式
    private var selectedVudType = "cdrom"

    // IMG 镜像制作参数
    private var selectedImgGb = 4L
    private var selectedFs = "FAT32"

    // 内置文件选择器：ISO/IMG 源文件（浏览选择后作为 VUD 启动源）
    private var pickedSourceFile: File? = null

    // 临时目录（与脚本保持一致，使用 $TMPDIR 风格）
    private val tmpDir = "/data/local/tmp/TimeVUD"

    // 内置文件选择器 request code
    private val isoPickerRequest = 411

    fun build(): View {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(16, d), Ui.dp(12, d), Ui.dp(16, d), Ui.dp(16, d))
        }

        content.addView(buildTopBar())
        content.addView(buildRootStatusCard())
        content.addView(buildImgCard())
        content.addView(buildVudCard())
        content.addView(buildHelpCard())

        val scroll = android.widget.ScrollView(activity).apply {
            isFillViewport = false
            addView(content)
        }
        refreshAll()
        return scroll
    }

    // ==================== 顶栏 ====================
    private fun buildTopBar(): View {
        val bar = FrameLayout(activity).apply {
            setPadding(0, Ui.dp(4, d), 0, Ui.dp(10, d))
        }
        TextView(activity).apply {
            text = Lang.t("U盘启动")
            textSize = 18f
            setTypeface(Ui.typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        }.let { bar.addView(it) }
        return bar
    }

    // ==================== Root + SELinux 状态卡片 ====================
    private fun buildRootStatusCard(): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(14, d), Ui.dp(10, d), Ui.dp(14, d), Ui.dp(10, d))
            background = Ui.glassSurface(activity, 18f)
            Ui.applyNeuShadow(this, 2f, 18f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = Ui.dp(10, d)
            }
        }
        card.addView(TextView(activity).apply {
            text = Lang.t("环境检测")
            textSize = 13f
            setTypeface(Ui.typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
        })

        selinuxText = TextView(activity).apply {
            text = Lang.t("正在检测...")
            textSize = 11f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, Ui.dp(4, d), 0, 0)
        }
        card.addView(selinuxText)

        return card
    }

    // ==================== 制作 U 盘 IMG 镜像（truncate + 格式化） ====================
    private fun buildImgCard(): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(14, d), Ui.dp(12, d), Ui.dp(14, d), Ui.dp(12, d))
            background = Ui.glassSurface(activity, 18f)
            Ui.applyNeuShadow(this, 2f, 18f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = Ui.dp(10, d)
            }
        }

        card.addView(TextView(activity).apply {
            text = Lang.t("制作 U 盘 IMG 镜像")
            textSize = 13f
            setTypeface(Ui.typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
        })
        card.addView(TextView(activity).apply {
            text = Lang.t("本地生成 U 盘格式 IMG，再经虚拟 U 盘暴露给电脑")
            textSize = 11f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, Ui.dp(2, d), 0, Ui.dp(6, d))
        })

        // 镜像大小
        card.addView(TextView(activity).apply {
            text = Lang.t("镜像大小")
            textSize = 12f
            setTypeface(Ui.typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            setPadding(0, Ui.dp(2, d), 0, Ui.dp(4, d))
        })
        val sizes = listOf("1 GB", "4 GB", "8 GB", "16 GB")
        val sizeRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val sizePills = mutableListOf<TextView>()
        sizes.forEachIndexed { i, s ->
            val gb = s.substringBefore(' ')
            val isSelected = i == 1
            val pill = TextView(activity).apply {
                text = s
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(if (isSelected) selectionPillTextColor() else Ui.secondaryText(activity))
                background = if (isSelected) selectionPillSelectedBackground() else Ui.neuInset(activity, 10f)
                setPadding(Ui.dp(8, d), Ui.dp(6, d), Ui.dp(8, d), Ui.dp(6, d))
                setOnClickListener {
                    selectedImgGb = gb.toLong()
                    sizePills.forEach { p ->
                        val sel = p.text.toString() == s
                        p.setTextColor(if (sel) selectionPillTextColor() else Ui.secondaryText(activity))
                        p.background = if (sel) selectionPillSelectedBackground() else Ui.neuInset(activity, 10f)
                    }
                }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = Ui.dp(4, d)
                }
            }
            sizePills.add(pill)
            sizeRow.addView(pill)
        }
        card.addView(sizeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 文件系统
        card.addView(TextView(activity).apply {
            text = Lang.t("文件系统")
            textSize = 12f
            setTypeface(Ui.typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            setPadding(0, Ui.dp(6, d), 0, Ui.dp(4, d))
        })
        val fsList = listOf("FAT32", "ext4")
        val fsRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val fsPills = mutableListOf<TextView>()
        fsList.forEachIndexed { i, fs ->
            val isSelected = i == 0
            val pill = TextView(activity).apply {
                text = fs
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(if (isSelected) selectionPillTextColor() else Ui.secondaryText(activity))
                background = if (isSelected) selectionPillSelectedBackground() else Ui.neuInset(activity, 10f)
                setPadding(Ui.dp(8, d), Ui.dp(6, d), Ui.dp(8, d), Ui.dp(6, d))
                setOnClickListener {
                    selectedFs = fs
                    fsPills.forEach { p ->
                        val sel = p.text.toString() == fs
                        p.setTextColor(if (sel) selectionPillTextColor() else Ui.secondaryText(activity))
                        p.background = if (sel) selectionPillSelectedBackground() else Ui.neuInset(activity, 10f)
                    }
                }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = Ui.dp(4, d)
                }
            }
            fsPills.add(pill)
            fsRow.addView(pill)
        }
        card.addView(fsRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 生成状态（单行，默认隐藏）
        imgStatusText = TextView(activity).apply {
            text = Lang.t("未生成")
            textSize = 11f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, Ui.dp(8, d), 0, 0)
        }
        card.addView(imgStatusText)

        // 制作 + 浏览（与 VUD 卡片同款高度）
        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        btnRow.addView(makePillButton(Lang.t("制作 IMG"), Ui.buttonPrimary(activity)) { createImg() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btnRow.addView(View(activity).apply { layoutParams = LinearLayout.LayoutParams(Ui.dp(8, d), 0) })
        btnRow.addView(makePillButton(Lang.t("选已有 IMG"), Ui.buttonSecondary(activity)) { pickIsoFile() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(btnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(6, d)
        })

        return card
    }

    // ==================== 虚拟 U 盘（USB Gadget 暴露 ISO） ====================
    private fun buildVudCard(): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(14, d), Ui.dp(12, d), Ui.dp(14, d), Ui.dp(12, d))
            background = Ui.glassSurface(activity, 18f)
            Ui.applyNeuShadow(this, 2f, 18f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = Ui.dp(10, d)
            }
        }

        card.addView(TextView(activity).apply {
            text = Lang.t("虚拟 U 盘启动")
            textSize = 13f
            setTypeface(Ui.typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
        })
        card.addView(TextView(activity).apply {
            text = Lang.t("把 ISO 通过 USB Gadget 暴露给电脑")
            textSize = 11f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, Ui.dp(2, d), 0, 0)
        })

        // 连接模式选择
        val vudTypes = listOf("cdrom", "ro", "rw")
        val typeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val typePills = mutableListOf<TextView>()
        vudTypes.forEachIndexed { i, t ->
            val label = when (t) {
                "rw" -> "U盘读写"
                "ro" -> "U盘只读"
                else -> "CD只读"
            }
            val isSelected = i == 0
            val pill = TextView(activity).apply {
                text = label
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(if (isSelected) selectionPillTextColor() else Ui.secondaryText(activity))
                background = if (isSelected) selectionPillSelectedBackground() else Ui.neuInset(activity, 10f)
                setPadding(Ui.dp(10, d), Ui.dp(6, d), Ui.dp(10, d), Ui.dp(6, d))
                setOnClickListener {
                    selectedVudType = t
                    typePills.forEach { p ->
                        val isSel = p.text.toString() == label
                        p.setTextColor(if (isSel) selectionPillTextColor() else Ui.secondaryText(activity))
                        p.background = if (isSel) selectionPillSelectedBackground() else Ui.neuInset(activity, 10f)
                    }
                }
                // 按文字长度自适应权重，避免短标签被截断、宽标签挤压
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = Ui.dp(4, d)
                }
            }
            typePills.add(pill)
            typeRow.addView(pill)
        }
        card.addView(typeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(6, d)
        })

        // 当前 ISO 选择
        card.addView(TextView(activity).apply {
            text = Lang.t("ISO 源文件")
            textSize = 12f
            setTypeface(Ui.typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            setPadding(0, Ui.dp(6, d), 0, Ui.dp(3, d))
        })

        val isoPickRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        isoPickRow.addView(TextView(activity).apply {
            text = Lang.t("自动选择已下载的 ISO")
            textSize = 11f
            setTextColor(Ui.secondaryText(activity))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        isoPickRow.addView(makePillButton(Lang.t("浏览 ISO/IMG..."), Ui.buttonPrimary(activity)) { pickIsoFile() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(isoPickRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // VUD 状态
        vudStatusText = TextView(activity).apply {
            text = Lang.t("未启动")
            textSize = 11f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, Ui.dp(6, d), 0, 0)
        }
        card.addView(vudStatusText)

        vudTypeText = TextView(activity).apply {
            text = ""
            textSize = 10f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, Ui.dp(2, d), 0, 0)
        }
        card.addView(vudTypeText)

        // 启停按钮
        val vudBtnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        startVudBtn = makePillButton(Lang.t("启动虚拟 U 盘"), Ui.buttonSuccess(activity)) { startVud() }
        stopVudBtn = makePillButton(Lang.t("停止"), Ui.buttonDanger(activity)) { stopVud() }
        vudBtnRow.addView(startVudBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        vudBtnRow.addView(View(activity).apply { layoutParams = LinearLayout.LayoutParams(Ui.dp(8, d), 0) })
        vudBtnRow.addView(stopVudBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(vudBtnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(6, d)
        })

        return card
    }

    // ==================== 使用说明 ====================
    private fun buildHelpCard(): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(14, d), Ui.dp(12, d), Ui.dp(14, d), Ui.dp(12, d))
            background = Ui.glassSurface(activity, 18f)
            Ui.applyNeuShadow(this, 2f, 18f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = Ui.dp(10, d)
            }
        }

        card.addView(TextView(activity).apply {
            text = Lang.t("使用说明")
            textSize = 13f
            setTypeface(Ui.typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
        })
        card.addView(makeHelpLine(Lang.t("① 连接手机到电脑，开启 USB 调试 / MTP 模式")))
        card.addView(makeHelpLine(Lang.t("② 制作 U 盘 IMG 镜像（本地 truncate + 格式化）")))
        card.addView(makeHelpLine(Lang.t("③ 启动虚拟 U 盘（root 下 USB Gadget 框架）")))
        card.addView(makeHelpLine(Lang.t("④ 电脑 BIOS 里选 U 盘 / 光盘启动，按提示安装")))
        card.addView(makeHelpLine(Lang.t("也可浏览选择已有 ISO/IMG 作为启动源")))

        return card
    }

    // ==================== 通用组件 ====================
    private fun softButtonTopColor(accent: Int): Int = if (Ui.isDark(activity)) accent else Color.rgb(247, 239, 200)
    private fun softButtonBottomColor(accent: Int): Int = if (Ui.isDark(activity)) accent else Color.rgb(201, 225, 248)
    private fun softButtonText(): Int = if (Ui.isDark(activity)) Color.WHITE else Color.BLACK

    private fun selectionPillSelectedBackground() =
        Ui.neuSolidButton(
            if (Ui.isDark(activity)) Ui.buttonPrimary(activity) else Color.rgb(22, 179, 100),
            if (Ui.isDark(activity)) Ui.buttonPrimary(activity) else Color.rgb(22, 179, 100),
            10f,
            activity,
        )

    private fun selectionPillTextColor(): Int =
        Color.WHITE

    private fun makePillButton(text: String, accent: Int, onClick: () -> Unit): TextView {
        val minH = 44f * d
        val halfH = ((minH / 2) - 14f * d / 2).toInt()
        val tv = TextView(activity).apply {
            this.text = text
            textSize = 13f
            setTypeface(Ui.typeface, Typeface.BOLD)
            setTextColor(softButtonText())
            gravity = Gravity.CENTER
            background = Ui.neuSolidButton(softButtonTopColor(accent), softButtonBottomColor(accent), 14f, activity)
            setPadding(Ui.dp(12, d), halfH, Ui.dp(12, d), halfH)
            setOnClickListener { Haptics.perform(this); onClick() }
            Ui.pressAnimation(this)
        }
        return tv
    }

    private fun makeHelpLine(text: String): TextView = TextView(activity).apply {
        this.text = text
        textSize = 11f
        setTextColor(Ui.secondaryText(activity))
        setPadding(0, Ui.dp(6, d), 0, 0)
        // 行高放宽到 1.7 倍字号，避免相邻说明行在低密度屏上重叠
        val fm = paint.fontMetrics
        lineHeight = ((fm.bottom - fm.top + fm.descent).toInt() + (6f * d).toInt()).coerceAtLeast((16f * d).toInt())
    }

    // ==================== 环境检测（SELinux + USB Gadget 支持） ====================
    private fun refreshAll() {
        Thread {
            val se = detectSelinux()
            val gadgetSupport = detectGadgetSupport()
            val vudRunning = detectVudRunning()
            val img = findLatestImg()
            activity.runOnUiThread {
                selinuxText?.text = se
                vudStatusText?.text = if (vudRunning) Lang.t("正在运行") else Lang.t("未启动")
                vudTypeText?.text = img?.let { "IMG 源文件：${it.name}" } ?: ""
                imgStatusText?.text = img?.let { "已生成：${it.name}" } ?: "未生成"
                startVudBtn?.isEnabled = gadgetSupport
                stopVudBtn?.isEnabled = gadgetSupport
            }
        }.start()
    }

    private fun detectSelinux(): String {
        val r = RootShell.exec("getenforce", 5000)
        val s = r.stdout.trim()
        return when {
            s.equals("Enforcing", true) -> "SELinux 强制模式 · Root 可用"
            s.equals("Permissive", true) -> "SELinux 宽容模式 · Root 可用"
            s.equals("Disabled", true) -> "SELinux 已关闭 · Root 可用"
            r.code != 0 -> "未检测到 Root（虚拟 U 盘不可用，可继续下载 ISO）"
            else -> "SELinux 状态未知"
        }
    }

    private fun detectGadgetSupport(): Boolean {
        val r = RootShell.exec(
            "ls /config/usb_gadget/g1/functions 2>/dev/null | grep -c mass_storage",
            8000,
        )
        return r.code == 0 && r.stdout.trim().toIntOrNull()?.let { it > 0 } == true
    }

    private fun detectVudRunning(): Boolean {
        val r = RootShell.exec(
            "ls /config/usb_gadget/g1/configs/b.1 2>/dev/null | grep -c mass_storage",
            8000,
        )
        return r.code == 0 && r.stdout.trim().toIntOrNull()?.let { it > 0 } == true
    }

    // ==================== 虚拟 U 盘 启停（参考 TimeVUD.sh） ====================
    private fun startVud() {
        val source = pickIsoForVud()
        if (source == null) {
            AlertDialog.Builder(activity)
                .setTitle(Lang.t("未找到可用的 ISO/IMG"))
                .setMessage(Lang.t("请先制作 U 盘 IMG 镜像，或手动浏览选择 ISO/IMG 文件。"))
                .setPositiveButton(Lang.t("关闭"), null)
                .show()
            return
        }
        Thread {
            val iso = source
            val roFile = when (selectedVudType) { "ro" -> "1"; "cdrom" -> "1"; else -> "0" }
            val cdromFile = when (selectedVudType) { "cdrom" -> "1"; else -> "0" }
            val script = buildString {
                append("mkdir -p '$tmpDir' 2>/dev/null; \n")
                append("usbcfg=\$(ls /config/usb_gadget/g1/functions 2>/dev/null | grep mass_storage | head -n 1);\n")
                append("mass_storagpath=\"/config/usb_gadget/g1/functions/\$usbcfg\";\n")
                append("if [ -z \"\$usbcfg\" ]; then echo 'unsupported' > '$tmpDir/vuderr'; exit 1; fi\n")
                append("oemname=\$(cat /config/usb_gadget/g1/strings/0x409/manufacturer 2>/dev/null);\n")
                append("find /config/usb_gadget/g1/configs/b.1/ -maxdepth 1 -type l -print0 | xargs -0 rm -f -- 2>/dev/null;\n")
                append("(ln -s \"\$mass_storagpath\" /config/usb_gadget/g1/configs/b.1/\$usbcfg >/dev/null 2>&1) || { echo 'vud fail' > '$tmpDir/vuderr'; exit 1; }\n")
                append("echo '' > \$mass_storagpath/lun.0/file\n")
                append("echo '$roFile' > \$mass_storagpath/lun.0/ro\n")
                append("echo '$cdromFile' > \$mass_storagpath/lun.0/cdrom\n")
                append("echo 1 > \$mass_storagpath/lun.0/removable\n")
                append("echo 1 > \$mass_storagpath/lun.0/nofua\n")
                append("echo \"\${oemname}_TimeVUD\" > \$mass_storagpath/lun.0/inquiry_string\n")
                append("echo '$iso' > \$mass_storagpath/lun.0/file\n")
                append("echo '$selectedVudType' > '$tmpDir/VUDtype'\n")
                append("echo '$iso' > '$tmpDir/imgfile'\n")
                append("setprop sys.usb.config cdrom,mtp\n")
                append("setprop sys.usb.state cdrom,mtp\n")
                append("echo '' > /config/usb_gadget/g1/UDC\n")
                append("sleep 0.2\n")
                append("getprop sys.usb.controller > /config/usb_gadget/g1/UDC\n")
            }
            val r = RootShell.exec(script, 20000)
            val errFile = File("$tmpDir/vuderr")
            val err = if (errFile.exists()) runCatching { errFile.readText() }.getOrNull()?.trim().orEmpty() else ""
            activity.runOnUiThread {
                if (r.code == 0 && !err.equals("vud fail", true) && !err.equals("unsupported", true)) {
                    vudStatusText?.text = "正在运行（$selectedVudType 模式）"
                    vudTypeText?.text = "ISO：$iso"
                } else {
                    vudStatusText?.text = "启动失败：${err.ifBlank { "检查 USB Gadget 是否支持 mass_storage（需 root）" }}"
                }
            }
        }.start()
    }

    private fun stopVud() {
        Thread {
            val script = buildString {
                append("usbcfg=\$(ls /config/usb_gadget/g1/functions 2>/dev/null | grep mass_storage | head -n 1);\n")
                append("mass_storagpath=\"/config/usb_gadget/g1/functions/\$usbcfg\";\n")
                append("find /config/usb_gadget/g1/configs/b.1/ -maxdepth 1 -type l -print0 | xargs -0 rm -f -- 2>/dev/null;\n")
                append("echo '' > \$mass_storagpath/lun.0/file\n")
                append("echo 0 > \$mass_storagpath/lun.0/removable\n")
                append("echo 0 > \$mass_storagpath/lun.0/ro\n")
                append("echo 0 > \$mass_storagpath/lun.0/cdrom\n")
                append("echo '' > \$mass_storagpath/lun.0/inquiry_string\n")
                append("setprop sys.usb.config mtp\n")
                append("setprop sys.usb.state mtp\n")
                append("echo '' > '$tmpDir/VUDtype'\n")
            }
            RootShell.exec(script, 15000)
            activity.runOnUiThread {
                vudStatusText?.text = Lang.t("已停止")
                vudTypeText?.text = ""
            }
        }.start()
    }

    // ==================== ISO/IMG 文件选择（内置文件选择器） ====================
    private fun pickIsoForVud(): String? {
        val f = pickedSourceFile ?: findLatestImg()
        return f?.absolutePath
    }

    /** 浏览选择 ISO/IMG：调用 APP 内置文件选择器（RootfsFilesActivity） */
    private fun pickIsoFile() {
        activity.startActivityForResult(
            android.content.Intent(activity, com.mcai.ubuntudsu.RootfsFilesActivity::class.java).apply {
                putExtra(com.mcai.ubuntudsu.RootfsFilesActivity.EXTRA_PICK, true)
                putExtra(com.mcai.ubuntudsu.RootfsFilesActivity.EXTRA_TITLE, "选择 ISO / IMG 启动源")
                putExtra(com.mcai.ubuntudsu.RootfsFilesActivity.EXTRA_EXT, ".iso,.img")
            },
            isoPickerRequest,
        )
    }

    /** 宿主 Activity.onActivityResult 派发到本类 */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        if (requestCode != isoPickerRequest || resultCode != Activity.RESULT_OK) return
        val path = data?.getStringExtra(com.mcai.ubuntudsu.RootfsFilesActivity.RESULT_FILE_PATH) ?: return
        val file = java.io.File(path)
        pickedSourceFile = file
        vudTypeText?.text = "IMG 源文件：${file.name}"
    }

    /** 最近生成的 IMG 镜像：优先 /sdcard/UsbImg，其次 TimeVUD 目录 */
    private fun findLatestImg(): File? {
        val usbImgDir = File("/sdcard/UsbImg")
        usbImgDir.listFiles { f -> f.name.endsWith(".img", true) }?.lastOrNull()?.let { return it }
        val vudDir = File(tmpDir)
        return vudDir.listFiles { f -> f.name.endsWith(".img", true) }?.lastOrNull()
    }

    // ==================== 制作 U 盘 IMG 镜像（truncate + 格式化） ====================
    private fun createImg() {
        if (imgMaking.getAndSet(true)) return
        imgCancel.set(false)
        // 保存目录：/sdcard/UsbImg（root 直接落盘）
        val saveDir = File("/sdcard/UsbImg")
        if (!saveDir.exists()) saveDir.mkdirs()
        val imgName = "usb_${selectedImgGb}GB_${selectedFs.lowercase()}_${System.currentTimeMillis() / 1000}.img"
        val target = File(saveDir, imgName)

        imgStatusText?.text = "正在制作 ${selectedImgGb}GB ${selectedFs} IMG..."

        imgMakeThread = Thread {
            var lastError = Lang.t("未开始")
            if (imgCancel.get()) {
                lastError = Lang.t("已取消")
            } else {
                val bytes = selectedImgGb * 1024L * 1024L * 1024L
                val script = buildString {
                    append("mkdir -p '$saveDir.absolutePath' 2>/dev/null;\n")
                    // truncate 创建固定大小的稀疏镜像（占满物理空间用 dd 实写可选）
                    append("truncate -s ${bytes} '${target.absolutePath}'\n")
                    if (selectedFs.equals("ext4", true)) {
                        append("mkfs.ext4 -F '${target.absolutePath}'\n")
                    } else {
                        // FAT32：用 mkfs.fat（部分 ROM 自带）或 mkdosfs
                        append("command -v mkfs.fat >/dev/null 2>&1 && mkfs.fat -F 32 '${target.absolutePath}'\n")
                        append("command -v mkdosfs >/dev/null 2>&1 && mkdosfs -F 32 -n USBFLASH '${target.absolutePath}'\n")
                    }
                    append("echo '__IMG_DONE__'\n")
                }
                val result = RootShell.exec(script, 120000)
                val done = result.stdout.contains("__IMG_DONE__")
                lastError = if (done) "成功" else result.stdout.takeLast(120).ifBlank { "制作失败（退出码 ${result.code}）" }
            }
            imgMaking.set(false)
            activity.runOnUiThread {
                if (imgCancel.get() && lastError == "已取消") {
                    imgStatusText?.text = Lang.t("已取消")
                } else if (lastError == "成功") {
                    imgStatusText?.text = "已生成：${target.name}（$saveDir）"
                } else {
                    imgStatusText?.text = "制作失败：${lastError.take(80)}"
                }
            }
        }.also { it.start() }
    }

    fun cleanup() {
        imgCancel.set(true)
        imgMakeThread?.interrupt()
    }
}
