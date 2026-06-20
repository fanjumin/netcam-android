package com.netcam.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "netcam_settings")

class SettingsStore(private val context: Context) {

    companion object {
        val KEY_SERVER_PORT = intPreferencesKey("server_port")
        val KEY_VIDEO_RESOLUTION = stringPreferencesKey("video_resolution")
        val KEY_VIDEO_FPS = intPreferencesKey("video_fps")
        val KEY_JPEG_QUALITY = intPreferencesKey("jpeg_quality")
        val KEY_FLASH_ENABLED = booleanPreferencesKey("flash_enabled")
        val KEY_MIRROR_ENABLED = booleanPreferencesKey("mirror_enabled")
        val KEY_AUTO_START = booleanPreferencesKey("auto_start")
        val KEY_MOTION_DETECTION = booleanPreferencesKey("motion_detection")
        val KEY_MOTION_SENSITIVITY = intPreferencesKey("motion_sensitivity")
        val KEY_ZOOM_LEVEL = floatPreferencesKey("zoom_level")
        val KEY_CAMERA_FACING = intPreferencesKey("camera_facing")

        const val DEFAULT_PORT = 8080
        const val DEFAULT_RESOLUTION = "1280x720"
        const val DEFAULT_FPS = 30
        const val DEFAULT_QUALITY = 80
        const val DEFAULT_MOTION_SENSITIVITY = 30
        const val DEFAULT_ZOOM = 1.0f
    }

    val serverPort: Flow<Int> = context.dataStore.data.map { it[KEY_SERVER_PORT] ?: DEFAULT_PORT }
    val videoResolution: Flow<String> = context.dataStore.data.map { it[KEY_VIDEO_RESOLUTION] ?: DEFAULT_RESOLUTION }
    val videoFps: Flow<Int> = context.dataStore.data.map { it[KEY_VIDEO_FPS] ?: DEFAULT_FPS }
    val jpegQuality: Flow<Int> = context.dataStore.data.map { it[KEY_JPEG_QUALITY] ?: DEFAULT_QUALITY }
    val flashEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_FLASH_ENABLED] ?: false }
    val mirrorEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_MIRROR_ENABLED] ?: false }
    val autoStart: Flow<Boolean> = context.dataStore.data.map { it[KEY_AUTO_START] ?: false }
    val motionDetection: Flow<Boolean> = context.dataStore.data.map { it[KEY_MOTION_DETECTION] ?: false }
    val motionSensitivity: Flow<Int> = context.dataStore.data.map { it[KEY_MOTION_SENSITIVITY] ?: DEFAULT_MOTION_SENSITIVITY }
    val zoomLevel: Flow<Float> = context.dataStore.data.map { it[KEY_ZOOM_LEVEL] ?: DEFAULT_ZOOM }
    val cameraFacing: Flow<Int> = context.dataStore.data.map { it[KEY_CAMERA_FACING] ?: 0 }

    suspend fun setServerPort(port: Int) {
        context.dataStore.edit { it[KEY_SERVER_PORT] = port }
    }

    suspend fun setVideoResolution(resolution: String) {
        context.dataStore.edit { it[KEY_VIDEO_RESOLUTION] = resolution }
    }

    suspend fun setVideoFps(fps: Int) {
        context.dataStore.edit { it[KEY_VIDEO_FPS] = fps }
    }

    suspend fun setJpegQuality(quality: Int) {
        context.dataStore.edit { it[KEY_JPEG_QUALITY] = quality.coerceIn(1, 100) }
    }

    suspend fun setFlashEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_FLASH_ENABLED] = enabled }
    }

    suspend fun setMirrorEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_MIRROR_ENABLED] = enabled }
    }

    suspend fun setAutoStart(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_START] = enabled }
    }

    suspend fun setMotionDetection(enabled: Boolean) {
        context.dataStore.edit { it[KEY_MOTION_DETECTION] = enabled }
    }

    suspend fun setMotionSensitivity(sensitivity: Int) {
        context.dataStore.edit { it[KEY_MOTION_SENSITIVITY] = sensitivity.coerceIn(1, 100) }
    }

    suspend fun setZoomLevel(zoom: Float) {
        context.dataStore.edit { it[KEY_ZOOM_LEVEL] = zoom.coerceIn(1.0f, 8.0f) }
    }

    suspend fun setCameraFacing(facing: Int) {
        context.dataStore.edit { it[KEY_CAMERA_FACING] = facing }
    }

    suspend fun getServerPort(): Int = serverPort.first()
    suspend fun getVideoResolution(): String = videoResolution.first()
    suspend fun getVideoFps(): Int = videoFps.first()
    suspend fun getJpegQuality(): Int = jpegQuality.first()
    suspend fun getFlashEnabled(): Boolean = flashEnabled.first()
    suspend fun getMirrorEnabled(): Boolean = mirrorEnabled.first()
    suspend fun getAutoStart(): Boolean = autoStart.first()
    suspend fun getMotionDetection(): Boolean = motionDetection.first()
    suspend fun getMotionSensitivity(): Int = motionSensitivity.first()
    suspend fun getZoomLevel(): Float = zoomLevel.first()
    suspend fun getCameraFacing(): Int = cameraFacing.first()
}
