package com.mcai.ubuntudsu

import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import com.mcai.ubuntudsu.ui.Haptics

/**
 * 带点击触感的基础 Activity。
 *
 * 为什么重写 dispatchTouchEvent 而不是给每个按钮挂 OnClickListener：
 *  页面里大量控件（卡片、开关、药丸、整行）都是运行期 new 出来的 TextView，
 *  逐个包一层既容易漏、又会和已有的 setOnClickListener 打架。
 *
 * 手势判定全部交给 [Haptics.onTouch]：滑动/拖动不震，只有「点一下」才震。
 */
open class HapticAppCompatActivity : AppCompatActivity() {

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        Haptics.onTouch(window.decorView, ev)
        return super.dispatchTouchEvent(ev)
    }
}
