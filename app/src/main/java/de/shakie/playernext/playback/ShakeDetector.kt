package de.shakie.playernext.playback

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs

/**
 * Shake to change songs: a strong jerk along the X or Y axis selects next (towards the
 * negative side) or previous (positive). Optionally pauses when the phone lies face
 * down. Only registered while playing and enabled, at UI sensor rate.
 */
class ShakeDetector(context: Context, private val callback: Callback) : SensorEventListener {
    interface Callback {
        fun onShake(next: Boolean)
        fun onFaceDown()
    }

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gravity = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private var registered = false
    private var lastShake = 0L
    private var faceDownSince = 0L
    var sensitivity = 50
    var flipPause = false

    fun start() {
        if (registered || accelerometer == null) return
        sensorManager?.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        if (flipPause && gravity != null) sensorManager?.registerListener(this, gravity, SensorManager.SENSOR_DELAY_NORMAL)
        registered = true
    }

    fun stop() {
        if (!registered) return
        sensorManager?.unregisterListener(this)
        registered = false
        faceDownSince = 0
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        if (event.sensor.type == Sensor.TYPE_GRAVITY) {
            if (event.values[2] < -9f) {
                if (faceDownSince == 0L) faceDownSince = now
                else if (now - faceDownSince > 1200) {
                    faceDownSince = Long.MAX_VALUE / 2
                    callback.onFaceDown()
                }
            } else {
                faceDownSince = 0
            }
            return
        }
        val linear = event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2] - if (linear) 0f else SensorManager.GRAVITY_EARTH
        // 50 -> 14 m/s², 100 -> 8 m/s², 0 -> 22 m/s²
        val threshold = 22f - sensitivity * 0.14f
        if (now - lastShake < 900) return
        val ax = abs(x)
        val ay = abs(y)
        if (maxOf(ax, ay) < threshold || abs(z) > maxOf(ax, ay)) return
        lastShake = now
        val next = if (ax >= ay) x < 0 else y < 0
        callback.onShake(next)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
