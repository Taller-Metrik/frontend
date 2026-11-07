package com.metrik.app.testing

import com.metrik.app.core.geometry.Point
import com.metrik.app.sensors.OdometrySample
import com.metrik.app.sensors.OdometrySource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.hypot

/** SOLO PARA PRUEBAS: la app en funcionamiento no tiene fuentes simuladas. */
/**
 * Recorrido simulado de un ambiente (solo pruebas unitarias y E2E): recorre el polígono a
 * `speedMps` con un pequeño ruido, como lo haría el odómetro real.
 */
class SimulatedOdometrySource(
    private val polygon: List<Point> = listOf(Point(0.0, 0.0), Point(5.0, 0.0), Point(5.0, 4.0), Point(0.0, 4.0)),
    private val speedMps: Double = 1.0,
    private val hz: Int = 30,
    private val timeScale: Double = 1.0,
    private val noiseM: Double = 0.01,
    seed: Long = 7,
) : OdometrySource {
    override val kind: String = "steps"
    private val rng = java.util.Random(seed)

    /** Rumbo simulado de cada tramo (para el giro entre esquinas en las pruebas). */
    fun headingForSegment(i: Int): Double {
        val a = polygon[i % polygon.size]
        val b = polygon[(i + 1) % polygon.size]
        return Math.toDegrees(kotlin.math.atan2(b.x - a.x, b.y - a.y))
    }

    override val samples: Flow<OdometrySample> = flow {
        var t = 0L
        val stepM = speedMps / hz
        val frameMs = (1000.0 / hz / timeScale).toLong().coerceAtLeast(1)
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            val n = maxOf(1, (hypot(b.x - a.x, b.y - a.y) / stepM).toInt())
            for (k in 0 until n) {
                val f = k.toDouble() / n
                emit(
                    OdometrySample(
                        Point(a.x + (b.x - a.x) * f + rng.nextGaussian() * noiseM, a.y + (b.y - a.y) * f + rng.nextGaussian() * noiseM),
                        t,
                    ),
                )
                t += 1_000_000_000L / hz
                delay(frameMs)
            }
        }
        emit(OdometrySample(polygon.first(), t))
    }
}
