package com.netcam.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SensorData(
    val light: Float = 0f,
    val temperature: Float = 0f,
    val pressure: Float = 0f,
    val humidity: Float = 0f,
    val accelerometerX: Float = 0f,
    val accelerometerY: Float = 0f,
    val accelerometerZ: Float = 0f,
    val gyroscopeX: Float = 0f,
    val gyroscopeY: Float = 0f,
    val gyroscopeZ: Float = 0f,
    val batteryLevel: Float = 0f,
    val batteryTemperature: Float = 0f,
    val isCharging: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun getOverlayText(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder()
        sb.append(dateFormat.format(Date(timestamp)))
        val batteryPct = (batteryLevel * 100).toInt()
        sb.append("  BAT:$batteryPct%")
        if (isCharging) sb.append("⚡")
        if (light > 0) sb.append("  LX:${light.toInt()}")
        if (temperature > 0) sb.append("  T:${String.format("%.1f", temperature)}°C")
        if (pressure > 0) sb.append("  P:${pressure.toInt()}hPa")
        if (humidity > 0) sb.append("  H:${humidity.toInt()}%")
        return sb.toString()
    }
}

class SensorCollector(private val context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val _sensorData = MutableStateFlow(SensorData())
    val sensorData: StateFlow<SensorData> = _sensorData.asStateFlow()

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            updateSensorValue(event)
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    private val registeredSensors = mutableListOf<Sensor>()

    private fun updateSensorValue(event: SensorEvent) {
        val current = _sensorData.value
        _sensorData.value = when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> current.copy(light = event.values[0])
            Sensor.TYPE_AMBIENT_TEMPERATURE -> current.copy(temperature = event.values[0])
            Sensor.TYPE_PRESSURE -> current.copy(pressure = event.values[0])
            Sensor.TYPE_RELATIVE_HUMIDITY -> current.copy(humidity = event.values[0])
            Sensor.TYPE_ACCELEROMETER -> current.copy(
                accelerometerX = event.values[0],
                accelerometerY = event.values[1],
                accelerometerZ = event.values[2]
            )
            Sensor.TYPE_GYROSCOPE -> current.copy(
                gyroscopeX = event.values[0],
                gyroscopeY = event.values[1],
                gyroscopeZ = event.values[2]
            )
            else -> current
        }.copy(timestamp = System.currentTimeMillis())
    }

    private fun readBattery() {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (intent != null) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0f
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            val batteryLevel = if (scale > 0) level.toFloat() / scale else 0f
            _sensorData.value = _sensorData.value.copy(
                batteryLevel = batteryLevel,
                batteryTemperature = temp,
                isCharging = isCharging
            )
        }
    }

    fun start() {
        registerSensor(Sensor.TYPE_LIGHT)
        registerSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)
        registerSensor(Sensor.TYPE_PRESSURE)
        registerSensor(Sensor.TYPE_RELATIVE_HUMIDITY)
        registerSensor(Sensor.TYPE_ACCELEROMETER)
        registerSensor(Sensor.TYPE_GYROSCOPE)
        readBattery()
    }

    private fun registerSensor(type: Int) {
        val sensor = sensorManager.getDefaultSensor(type)
        if (sensor != null) {
            sensorManager.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            registeredSensors.add(sensor)
        }
    }

    fun stop() {
        for (sensor in registeredSensors) {
            sensorManager.unregisterListener(sensorListener, sensor)
        }
        registeredSensors.clear()
    }

    fun refreshBattery() {
        readBattery()
    }

    val sensorDataFlow: Flow<SensorData> = callbackFlow {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                updateSensorValue(event)
                trySend(_sensorData.value)
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }

        val sensors = listOf(
            Sensor.TYPE_LIGHT,
            Sensor.TYPE_AMBIENT_TEMPERATURE,
            Sensor.TYPE_PRESSURE,
            Sensor.TYPE_RELATIVE_HUMIDITY,
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE
        )

        val registered = mutableListOf<Sensor>()
        for (type in sensors) {
            val sensor = sensorManager.getDefaultSensor(type)
            if (sensor != null) {
                sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                registered.add(sensor)
            }
        }

        readBattery()

        awaitClose {
            for (s in registered) {
                sensorManager.unregisterListener(listener, s)
            }
        }
    }
}
