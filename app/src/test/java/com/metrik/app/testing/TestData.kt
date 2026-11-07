package com.metrik.app.testing

import com.metrik.app.core.geometry.Point
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

val RECTANGULO_5x4 = listOf(Point(0.0, 0.0), Point(5.0, 0.0), Point(5.0, 4.0), Point(0.0, 4.0))
val AMBIENTE_L = listOf(
    Point(0.0, 0.0), Point(6.0, 0.0), Point(6.0, 3.0), Point(3.0, 3.0), Point(3.0, 5.0), Point(0.0, 5.0),
)
val AMBIENTE_U = listOf(
    Point(0.0, 0.0), Point(6.0, 0.0), Point(6.0, 5.0), Point(4.0, 5.0),
    Point(4.0, 2.0), Point(2.0, 2.0), Point(2.0, 5.0), Point(0.0, 5.0),
)

/** Trazo simulado de un recorrido por el perímetro: ruido gaussiano y ambiente rotado. */
fun simulateWalk(
    polygon: List<Point>,
    stepM: Double = 0.05,
    noiseM: Double = 0.03,
    rotationDeg: Double = 20.0,
    seed: Int = 1,
): List<Point> {
    val rng = java.util.Random(seed.toLong())
    val a = Math.toRadians(rotationDeg)
    val pts = mutableListOf<Point>()
    for (i in polygon.indices) {
        val p1 = polygon[i]
        val p2 = polygon[(i + 1) % polygon.size]
        val steps = maxOf(1, (hypot(p2.x - p1.x, p2.y - p1.y) / stepM).toInt())
        for (k in 0 until steps) {
            val t = k.toDouble() / steps
            val x = p1.x + (p2.x - p1.x) * t + rng.nextGaussian() * noiseM
            val y = p1.y + (p2.y - p1.y) * t + rng.nextGaussian() * noiseM
            pts += Point(x * cos(a) - y * sin(a), x * sin(a) + y * cos(a))
        }
    }
    pts += pts.first()
    return pts
}

@Suppress("unused")
private val unusedRandom = Random.Default
