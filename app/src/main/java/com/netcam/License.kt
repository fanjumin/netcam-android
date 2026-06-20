package com.netcam

/**
 * 版本授权控制
 * IS_PRO = true  → 付费版
 * IS_PRO = false → 免费版（MJPEG 流带水印）
 */
object License {
    const val IS_PRO = false

    // 功能开关
    const val ENABLE_TASKER = IS_PRO
    const val ENABLE_CUSTOM_UI = IS_PRO
    const val ENABLE_ONVIF = IS_PRO
    const val ENABLE_CLOUD = IS_PRO
    const val ENABLE_SHORTCUT = IS_PRO

    // 水印文字
    const val WATERMARK_TEXT = "NetCam Pro"
}
