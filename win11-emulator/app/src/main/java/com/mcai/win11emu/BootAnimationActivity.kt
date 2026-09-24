package com.mcai.win11emu

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.mcai.win11emu.databinding.ActivityBootAnimationBinding

/**
 * Windows 11 开机动画Activity
 * 模拟Win11启动画面
 */
class BootAnimationActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityBootAnimationBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBootAnimationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // 模拟开机动画，2秒后进入桌面
        android.os.Handler(mainLooper).postDelayed({
            startActivity(android.content.Intent(this, DesktopActivity::class.java))
            finish()
        }, 2500)
    }
}
