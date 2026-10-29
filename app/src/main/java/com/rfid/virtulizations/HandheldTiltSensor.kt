package com.rfid.virtulizations

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import java.util.Locale
import kotlin.math.*

data class HandheldTilt(val degrees: String, val orientation: String, val source: String, val ageMs: Long = 0)

/** Tilt of the screen normal relative to vertical; never an RFID tag bearing. */
class HandheldTiltSensor(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    @Volatile private var sample: Pair<Long, HandheldTilt>? = null
    private val gravity = FloatArray(3)
    private var initialized = false
    fun start() {
        if (sensor != null) manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }
    fun stop() { manager.unregisterListener(this); sample = null; initialized = false }
    fun snapshot(): HandheldTilt? {
        val latest = sample ?: return null
        val age = (SystemClock.elapsedRealtimeNanos() - latest.first) / 1_000_000
        return latest.second.copy(ageMs = age).takeIf { age in 0..1000 }
    }
    override fun onSensorChanged(event: SensorEvent) {
        for (i in 0..2) gravity[i] = if (event.sensor.type == Sensor.TYPE_ACCELEROMETER && initialized)
            0.8f * gravity[i] + 0.2f * event.values[i] else event.values[i]
        initialized = true
        val tilt = tiltFromGravity(gravity[0].toDouble(), gravity[1].toDouble(), gravity[2].toDouble()) ?: return
        sample = event.timestamp to tilt.copy(source = if (event.sensor.type == Sensor.TYPE_GRAVITY) "gravitySensor" else "filteredAccelerometer")
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

fun tiltFromGravity(x: Double, y: Double, z: Double): HandheldTilt? {
    if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return null
    val magnitude = sqrt(x*x + y*y + z*z)
    if (magnitude < 1) return null
    val degrees = Math.toDegrees(acos((z / magnitude).coerceIn(-1.0, 1.0)))
    val label = when {
        degrees <= 10 -> "FLAT_FACE_UP"
        degrees >= 170 -> "FLAT_FACE_DOWN"
        degrees in 80.0..100.0 -> "UPRIGHT"
        else -> "TILTED"
    }
    return HandheldTilt(String.format(Locale.US, "%.1f", degrees), label, "")
}

fun configuredFrequencyBand(reader: ReaderInfo): String {
    val first = reader["regionStartIndex"].toIntOrNull() ?: return ""
    val last = reader["regionEndIndex"].toIntOrNull() ?: return ""
    // Vendor PDF Appendix 2: region 4, 15 points, 865.1 + N * 0.2 MHz.
    if (reader["regionCode"] != "4" || first !in 0..14 || last !in first..14) return ""
    return String.format(Locale.US, "%.1f–%.1f MHz", 865.1 + first * 0.2, 865.1 + last * 0.2)
}
