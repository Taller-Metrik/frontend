package com.metrik.app.core.geometry

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Punto en el plano del piso, en metros. */
data class Point(val x: Double, val y: Double) {
    operator fun minus(o: Point) = Point(x - o.x, y - o.y)
    operator fun plus(o: Point) = Point(x + o.x, y + o.y)
    operator fun times(k: Double) = Point(x * k, y * k)
    val norm: Double get() = hypot(x, y)
}

class GeometryException(message: String) : IllegalArgumentException(message)

/** Mismo cálculo que backend/app/services/geometry.py (docs/03-algoritmos.md §4). */
object Geometry {
    private const val EPS = 1e-9

    fun euclidean(a: Point, b: Point): Double = hypot(b.x - a.x, b.y - a.y)

    fun manhattan(a: Point, b: Point): Double = abs(b.x - a.x) + abs(b.y - a.y)

    /** Quita vértices repetidos consecutivos y el punto de cierre duplicado. */
    fun normalize(vertices: List<Point>): List<Point> {
        val cleaned = mutableListOf<Point>()
        for (p in vertices) if (cleaned.isEmpty() || euclidean(cleaned.last(), p) > EPS) cleaned += p
        if (cleaned.size > 1 && euclidean(cleaned.first(), cleaned.last()) <= EPS) cleaned.removeAt(cleaned.lastIndex)
        if (cleaned.size < 3) throw GeometryException("Se necesitan al menos 3 vértices distintos para formar un polígono")
        return cleaned
    }

    /** Área con signo (Gauss / Shoelace). Positiva = antihorario. */
    fun signedArea(v: List<Point>): Double {
        var total = 0.0
        for (i in v.indices) {
            val a = v[i]
            val b = v[(i + 1) % v.size]
            total += a.x * b.y - b.x * a.y
        }
        return total / 2.0
    }

    fun area(vertices: List<Point>): Double = abs(signedArea(normalize(vertices)))

    fun wallLengths(v: List<Point>): List<Double> = v.indices.map { euclidean(v[it], v[(it + 1) % v.size]) }

    fun perimeter(vertices: List<Point>): Double = wallLengths(normalize(vertices)).sum()

    /** Ángulo interior de cada vértice en grados; > 180° = vértice cóncavo. */
    fun interiorAngles(vertices: List<Point>): List<Double> {
        val p = normalize(vertices)
        val ccw = signedArea(p) > 0
        return p.indices.map { i ->
            val v = p[i]
            val a = p[(i - 1 + p.size) % p.size] - v
            val b = p[(i + 1) % p.size] - v
            val cross = if (ccw) b.x * a.y - b.y * a.x else a.x * b.y - a.y * b.x
            val dot = a.x * b.x + a.y * b.y
            (Math.toDegrees(atan2(cross, dot)) + 360.0) % 360.0
        }
    }

    private fun orient(a: Point, b: Point, c: Point) = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

    private fun segmentsIntersect(p1: Point, p2: Point, p3: Point, p4: Point): Boolean {
        val d1 = orient(p3, p4, p1)
        val d2 = orient(p3, p4, p2)
        val d3 = orient(p1, p2, p3)
        val d4 = orient(p1, p2, p4)
        return ((d1 > EPS && d2 < -EPS) || (d1 < -EPS && d2 > EPS)) &&
            ((d3 > EPS && d4 < -EPS) || (d3 < -EPS && d4 > EPS))
    }

    /** True si ningún par de lados no adyacentes se cruza. */
    fun isSimple(vertices: List<Point>): Boolean {
        val p = normalize(vertices)
        val n = p.size
        for (i in 0 until n) for (j in i + 2 until n) {
            if (i == 0 && j == n - 1) continue
            if (segmentsIntersect(p[i], p[(i + 1) % n], p[j], p[(j + 1) % n])) return false
        }
        return true
    }

    fun analyze(vertices: List<Point>): PolygonAnalysis {
        val p = normalize(vertices)
        val angles = interiorAngles(p)
        val walls = wallLengths(p)
        val signed = signedArea(p)
        return PolygonAnalysis(
            vertices = p,
            areaM2 = abs(signed),
            perimeterM = walls.sum(),
            wallLengthsM = walls,
            interiorAnglesDeg = angles,
            concaveVertices = angles.indices.filter { angles[it] > 180.0 + 1e-6 },
            isSimple = isSimple(p),
            counterClockwise = signed > 0,
        )
    }
}

data class PolygonAnalysis(
    val vertices: List<Point>,
    val areaM2: Double,
    val perimeterM: Double,
    val wallLengthsM: List<Double>,
    val interiorAnglesDeg: List<Double>,
    val concaveVertices: List<Int>,
    val isSimple: Boolean,
    val counterClockwise: Boolean,
) {
    val isConvex: Boolean get() = concaveVertices.isEmpty()
    val angleSumDeg: Double get() = interiorAnglesDeg.sum()
    val expectedAngleSumDeg: Double get() = (vertices.size - 2) * 180.0
}
