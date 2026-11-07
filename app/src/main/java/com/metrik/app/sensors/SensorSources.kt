package com.metrik.app.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.metrik.app.core.geometry.Point
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Rumbo del celular (grados, 0 = dirección inicial) con TYPE_GAME_ROTATION_VECTOR:
 * fusión de giroscopio y acelerómetro, sin magnetómetro (estable en interiores).
 */
class HeadingSensor(context: Context) {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    val available: Boolean get() = sensor != null

    val headingDeg: Flow<Double> = callbackFlow {
        val s = sensor ?: run { close(); return@callbackFlow }
        val rotation = FloatArray(9)
        val orientation = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                SensorManager.getOrientation(rotation, orientation)
                trySend(Math.toDegrees(orientation[0].toDouble()))
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        manager.registerListener(listener, s, SensorManager.SENSOR_DELAY_GAME)
        awaitClose { manager.unregisterListener(listener) }
    }
}

/** Odómetro por pasos: acelerómetro (pasos) + giroscopio (rumbo). Respaldo sin ARCore (EX-03). */
class StepOdometrySource(context: Context, private val stepLengthM: Double = 0.70) : OdometrySource {
    override val kind = "steps"
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val heading = HeadingSensor(context)

    override val samples: Flow<OdometrySample> = callbackFlow {
        val accel = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: run { close(); return@callbackFlow }
        val detector = StepDetector()
        var headingRad = 0.0
        var position = Point(0.0, 0.0)
        val rotation = FloatArray(9)
        val orientation = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        val v = event.values
                        if (detector.onAcceleration(v[0].toDouble(), v[1].toDouble(), v[2].toDouble(), event.timestamp)) {
                            position = Point(position.x + stepLengthM * sin(headingRad), position.y + stepLengthM * cos(headingRad))
                            trySend(OdometrySample(position, event.timestamp))
                        }
                    }
                    else -> {
                        SensorManager.getRotationMatrixFromVector(rotation, event.values)
                        SensorManager.getOrientation(rotation, orientation)
                        headingRad = orientation[0].toDouble()
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        trySend(OdometrySample(position, System.nanoTime()))
        manager.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME)
        manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)?.let {
            manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        awaitClose { manager.unregisterListener(listener) }
    }

    val hasHeading: Boolean get() = heading.available
}

/** Fuente alimentada por la vista de ARCore (el renderizador emite cada frame). */
class ArOdometrySource : OdometrySource {
    override val kind = "arcore"
    private val flow = MutableSharedFlow<OdometrySample>(extraBufferCapacity = 64)
    override val samples: Flow<OdometrySample> = flow
    fun emit(sample: OdometrySample) { flow.tryEmit(sample) }
}

/**
 * Pasos con brújula para el modo GPS a pie: un evento por paso con el rumbo absoluto en radianes
 * (0 = norte, π/2 = este). Usa TYPE_ROTATION_VECTOR (con magnetómetro: norte real, necesario para
 * combinar con el GPS), no el GAME_ROTATION_VECTOR del modo interior, que no conoce el norte.
 */
fun compassSteps(context: Context): Flow<Double> = callbackFlow {
    val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val accel = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    val rotationSensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    if (accel == null || rotationSensor == null) { close(); return@callbackFlow }
    val detector = StepDetector()
    val rotation = FloatArray(9)
    var heading: Double? = null
    val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    val v = event.values
                    if (detector.onAcceleration(v[0].toDouble(), v[1].toDouble(), v[2].toDouble(), event.timestamp)) {
                        heading?.let { trySend(it) }
                    }
                }
                else -> {
                    SensorManager.getRotationMatrixFromVector(rotation, event.values)
                    heading = walkingHeading(rotation)
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    manager.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME)
    manager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
    awaitClose { manager.unregisterListener(listener) }
}

/**
 * Rumbo hacia donde "mira" el celular, en el plano horizontal. La matriz lleva del celular al
 * mundo (x = este, y = norte, z = arriba). Celular plano: manda su eje Y (borde superior);
 * celular vertical: manda el eje −Z (la cámara trasera). Se usa el que esté más horizontal,
 * así el rumbo no se vuelve inestable al cambiar la forma de sostenerlo.
 */
fun walkingHeading(r: FloatArray): Double {
    val yE = r[1].toDouble(); val yN = r[4].toDouble()   // eje Y del celular en el mundo
    val zE = -r[2].toDouble(); val zN = -r[5].toDouble() // eje −Z del celular en el mundo
    return if (yE * yE + yN * yN >= zE * zE + zN * zN) atan2(yE, yN) else atan2(zE, zN)
}

/** Un evento por cada paso detectado con el acelerómetro (calibración del largo de paso). */
fun stepEvents(context: Context): Flow<Unit> = callbackFlow {
    val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val accel = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: run { close(); return@callbackFlow }
    val detector = StepDetector()
    val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val v = event.values
            if (detector.onAcceleration(v[0].toDouble(), v[1].toDouble(), v[2].toDouble(), event.timestamp)) trySend(Unit)
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    manager.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME)
    awaitClose { manager.unregisterListener(listener) }
}
