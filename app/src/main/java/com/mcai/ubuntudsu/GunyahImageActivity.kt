package com.mcai.ubuntudsu

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.mcai.ubuntudsu.core.GunyahManager
import com.mcai.ubuntudsu.ui.Ui

/** ③ 镜像映射 独立页面：7 个镜像，全部走 APP 内设文件管理选择，格式不限。 */
class GunyahImageActivity : GunyahSubActivity() {
    private lateinit var cfg: GunyahManager.Config

    override fun pageTitle() = "镜像映射"

    override fun buildContent(parent: LinearLayout) {
        cfg = GunyahManager.loadConfig(this)
        val c = card("③ 镜像映射")
        c.addView(hintText("点「选择」调用 APP 内设文件管理，格式不限"))
        val def = { base: String -> "${GunyahManager.imagesDir(this)}/$base" }
        addImageRow(c, "init-esp.img", def("init-esp.img"), { cfg.initEsp }, { cfg.initEsp = it })
        addImageRow(c, "os.img", def("os.img"), { cfg.osImg }, { cfg.osImg = it })
        addImageRow(c, "systemos.img", def("systemos.img"), { cfg.systemosImg }, { cfg.systemosImg = it })
        addImageRow(c, "data-store.img", def("data-store.img"), { cfg.dataStore }, { cfg.dataStore = it })
        addImageRow(c, "droidvm-disk.img", def("droidvm-disk.img"), { cfg.droidvmDisk }, { cfg.droidvmDisk = it })
        addImageRow(c, "root.apfs", "/data/user/0/com.mcai.ubuntudsu/files/rootfs/ubuntu/home/hakios/userland/root.apfs", { cfg.rootApfs }, { cfg.rootApfs = it })
        addImageRow(c, "system.apfs", "/data/user/0/com.mcai.ubuntudsu/files/rootfs/ubuntu/home/hakios/userland/system.apfs", { cfg.systemApfs }, { cfg.systemApfs = it })
        parent.addView(c)
    }

    override fun buildActionBar(): LinearLayout? {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Ui.dp(12, d), Ui.dp(8, d), Ui.dp(12, d), Ui.dp(12, d))
        }
        bar.addView(actionButton("💾  保存映射", Ui.buttonPrimary(this)) {
            GunyahManager.saveConfig(this, cfg)
            toast("镜像映射已保存")
        })
        return bar
    }

    private fun addImageRow(
        parent: LinearLayout,
        label: String,
        fallback: String,
        get: () -> String,
        set: (String) -> Unit,
    ) {
        val init = get().ifBlank { fallback }
        set(init)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(6, d) }
        }
        row.addView(TextView(this).apply {
            text = label; textSize = 11f
            setTextColor(Ui.secondaryText(this@GunyahImageActivity))
            layoutParams = LinearLayout.LayoutParams(Ui.dp(96, d), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        val pathView = TextView(this).apply {
            text = init; textSize = 10f
            setTextColor(Ui.primaryText(this@GunyahImageActivity))
            maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
            background = Ui.glassButton(this@GunyahImageActivity)
            setPadding(Ui.dp(8, d), Ui.dp(6, d), Ui.dp(8, d), Ui.dp(6, d))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(pathView)
        row.addView(TextView(this).apply {
            text = "选择"; textSize = 12f
            setTextColor(Ui.buttonText(this@GunyahImageActivity))
            setTypeface(null, Typeface.BOLD)
            background = Ui.glassButton(this@GunyahImageActivity, Color.parseColor("#3a6df0"))
            setPadding(Ui.dp(12, d), Ui.dp(6, d), Ui.dp(12, d), Ui.dp(6, d))
            setOnClickListener {
                openFilePicker("选择 $label") { p ->
                    set(p); pathView.text = p
                }
            }
        })
        parent.addView(row)
    }
}
