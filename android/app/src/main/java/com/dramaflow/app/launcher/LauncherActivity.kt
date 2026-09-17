package com.dramaflow.app.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.dramaflow.app.presentation.DramaAppActivity

/**
 * 应用启动路由。
 *
 * 当前内容库只启用一条短剧流，因此启动后直接进入统一目录与播放器入口；
 * 内容供应方仅存在于内部集成层，不在用户界面中出现。
 */
class LauncherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) startActivity(DramaAppActivity.intent(this))
        finish()
    }
}
