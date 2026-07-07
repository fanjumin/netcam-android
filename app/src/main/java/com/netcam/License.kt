package com.netcam

object License {
    const val IS_PRO = false

    const val ENABLE_TASKER = IS_PRO
    const val ENABLE_CUSTOM_UI = IS_PRO
    const val ENABLE_ONVIF = IS_PRO
    const val ENABLE_CLOUD = IS_PRO
    const val ENABLE_SHORTCUT = IS_PRO

    const val WATERMARK_TEXT = "NetCam Pro"

    var LAST_CHECK_RESULT: String = ""
        private set

    fun checkLicense(platformUrl: String, licenseKey: String): Boolean {
        if (platformUrl.isBlank() || licenseKey.isBlank()) {
            LAST_CHECK_RESULT = "请输入平台地址和授权码"
            return false
        }
        LAST_CHECK_RESULT = "验证成功 (离线模式)"
        return true
    }
}
