package com.mcai.win11emu

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mcai.win11emu.databinding.ActivityDesktopBinding
import java.util.Timer
import java.util.TimerTask

/**
 * Windows 11 桌面主界面
 * 支持安装并启动APK应用
 */
class DesktopActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityDesktopBinding
    private var isStartMenuOpen = false
    
    // 已安装的应用列表
    private val installedApps = mutableListOf<AppInfo>()
    
    data class AppInfo(
        val packageName: String,
        val appName: String,
        val icon: Drawable?
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityDesktopBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // 设置全屏，隐藏状态栏和导航栏
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
        }
        
        setupTaskbar()
        setupDesktopIcons()
        scanInstalledApps()
        updateClock()
        
        // 每秒更新时钟
        Timer().scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                runOnUiThread { updateClock() }
            }
        }, 0, 1000)
    }
    
    private fun setupTaskbar() {
        // 开始菜单按钮
        binding.btnStartMenu.setOnClickListener {
            toggleStartMenu()
        }
        
        // 搜索按钮
        binding.btnSearch.setOnClickListener {
            showSearchDialog()
        }
        
        // 设置按钮 (移除)
        
        // 点击阴影关闭开始菜单
        binding.taskbarOverlay.setOnClickListener {
            if (isStartMenuOpen) toggleStartMenu()
        }
    }
    
    private fun setupDesktopIcons() {
        // 回收站
        binding.iconRecycleBin.setOnClickListener {
            showToast("回收站 - 空")
        }
        
        // 此电脑
        binding.iconThisPC.setOnClickListener {
            openThisPC()
        }
        
        // 浏览器
        binding.iconBrowser.setOnClickListener {
            openBrowser()
        }
        
        // 文件资源管理器
        binding.iconExplorer.setOnClickListener {
            openFileExplorer()
        }
    }
    
    private fun scanInstalledApps() {
        val pm = packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null)
        intent.addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        
        val apps = pm.queryIntentActivities(intent, 0)
        installedApps.clear()
        
        for (resolveInfo in apps) {
            val appInfo = AppInfo(
                packageName = resolveInfo.activityInfo.packageName,
                appName = resolveInfo.loadLabel(pm).toString(),
                icon = resolveInfo.loadIcon(pm)
            )
            installedApps.add(appInfo)
        }
        
        // 添加模拟应用
        installedApps.addAll(listOf(
            AppInfo("com.microsoft.edge", "Microsoft Edge", null),
            AppInfo("com.windows.explorer", "文件资源管理器", null),
            AppInfo("com.windows.settings", "设置", null)
        ))
        
        populateStartMenu()
    }
    
    private fun populateStartMenu() {
        // 清空现有应用列表
        binding.startMenuAppsLayout.removeAllViews()
        
        // 添加应用图标
        for (app in installedApps.take(12)) {
            val appView = layoutInflater.inflate(R.layout.item_app, null)
            val icon = appView.findViewById<android.widget.ImageView>(R.id.appIcon)
            val name = appView.findViewById<android.widget.TextView>(R.id.appName)
            
            icon.setImageDrawable(app.icon)
            name.text = app.appName
            
            appView.setOnClickListener {
                launchApp(app.packageName)
                toggleStartMenu()
            }
            
            binding.startMenuAppsLayout.addView(appView)
        }
    }
    
    private fun toggleStartMenu() {
        isStartMenuOpen = !isStartMenuOpen
        if (isStartMenuOpen) {
            binding.startMenuPanel.visibility = View.VISIBLE
            binding.taskbarOverlay.visibility = View.VISIBLE
            binding.taskbarOverlay.alpha = 0.3f
        } else {
            binding.startMenuPanel.visibility = View.GONE
            binding.taskbarOverlay.visibility = View.GONE
        }
    }
    
    private fun launchApp(packageName: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                startActivity(intent)
            } else {
                // 模拟应用启动
                showToast("启动: $packageName")
            }
        } catch (e: Exception) {
            showToast("无法启动应用: ${e.message}")
        }
    }
    
    private fun showSearchDialog() {
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("搜索")
        builder.setMessage("搜索功能")
        builder.setPositiveButton("确定", null)
        builder.show()
    }
    
    private fun openSettings() {
        val intent = android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
        startActivity(intent)
    }
    
    private fun openThisPC() {
        showToast("此电脑")
    }
    
    private fun openBrowser() {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("https://www.bing.com"))
        startActivity(intent)
    }
    
    private fun openFileExplorer() {
        showToast("文件资源管理器")
    }
    
    private fun updateClock() {
        val calendar = java.util.Calendar.getInstance()
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
        val minute = calendar.get(java.util.Calendar.MINUTE).toString().padStart(2, '0')
        binding.tvTime.text = "$hour:$minute"
    }
    
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
