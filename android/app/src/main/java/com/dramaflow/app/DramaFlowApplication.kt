package com.dramaflow.app

import android.app.Application

/**
 * 应用级入口。
 *
 * Dramaverse PSSDK 会依据 PSSDK.Config 中的 VOD App ID 与 assets 内 License 文件
 * 初始化自身的播放器。这里不再以反射方式重复调用 BytePlus VOD SDK，避免不同
 * 初始化参数或 License 路径与 PSSDK 内置播放器产生冲突。
 */
class DramaFlowApplication : Application()
