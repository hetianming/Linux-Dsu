package com.mcai.ubuntudsu

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.mcai.ubuntudsu.core.ToolInstaller
import com.mcai.ubuntudsu.ui.Ui
import com.mcai.ubuntudsu.ui.pages.OtgAssistantPage

class OtgAssistantActivity : AppCompatActivity() {

    private lateinit var page: OtgAssistantPage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 确保工具安装完成
        val toolsOk = ToolInstaller.ensureInstalled(this)
        if (!toolsOk) {
            Log.e("OtgAssistant", "工具安装失败")
            AlertDialog.Builder(this)
                .setTitle("工具安装失败")
                .setMessage("无法解压内置 adb/fastboot 工具，请检查存储空间或重新安装应用。")
                .setPositiveButton("退出") { _, _ -> finish() }
                .show()
            return
        }

        page = OtgAssistantPage(this) { finish() }
        val content = page.build()

        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        Ui.animateLiquidBackground(root)
        root.addView(content)
        setContentView(root)
        Ui.enableEdgeToEdge(this, root)

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            androidx.core.view.ViewCompat.requestApplyInsets(content)
            insets
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            val path = data?.getStringExtra(RootfsFilesActivity.RESULT_FILE_PATH)
            if (!path.isNullOrBlank()) page.onFilePicked(path)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        page.onDestroy()
    }
}
