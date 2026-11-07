package com.metrik.app.core.geometry

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Suavizado del trazo para obtener un plano rectangular.
 * Port de backend/app/services/smoothing.py (docs/03-algoritmos.md §3).
 */
object Smoothing {

    data class Result(
        val vertices: List<Point>,
        val inputPoints: Int,
        val closed: Boolean,
        val closureErrorM: Double,
        val dominantAngleDeg: Double,
        val snappedEdges: Int,
        val totalEdges: Int,
    )

    private data class Edge(val v: Point, val dir: Int?)

    /** Ajusta un giro del giroscopio al múltiplo de 90° más cercano si está dentro de la tolerancia. */
    fun snapTurnAngle(angleDeg: Double, toleranceDeg: Double = 15.0): Double {
        val nearest = (angleDeg / 90.0).roundToInt() * 90.0
        return if (abs(angleDeg - nearest) <= toleranceDeg) nearest else angleDeg
    }

    private fun perpendicularDistance(p: Point, a: Point, b: Point): Double {
        val len = Geometry.euclidean(a, b)
        if (len == 0.0) return Geometry.euclidean(p, a)
        return abs((b.x - a.x) * (a.y - p.y) - (a.x - p.x) * (b.y - a.y)) / len
    }

    /** Ramer-Douglas-Peucker iterativo. */
    fun rdp(points: List<Point>, epsilon: Double): List<Point> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size).also { it[0] = true; it[points.lastIndex] = true }
        val stack = ArrayDeque<Pair<Int, Int>>().apply { add(0 to points.lastIndex) }
        while (stack.isNotEmpty()) {
            val (start, end) = stack.removeLast()
            var maxDist = 0.0
            var index = -1
            for (i in start + 1 until end) {
                val d = perpendicularDistance(points[i], points[start], points[end])
                if (d > maxDist) { maxDist = d; index = i }
            }
            if (index != -1 && maxDist > epsilon) {
                keep[index] = true
                stack.add(start to index)
                stack.add(index to end)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    fun movingAverage(points: List<Point>, window: Int): List<Point> {
        if (window <= 1 || points.size <= window) return points
        val half = window / 2
        val out = mutableListOf(points.first())
        for (i in 1 until points.lastIndex) {
            val lo = max(0, i - half)
            val hi = min(points.size, i + half + 1)
            val chunk = points.subList(lo, hi)
            out += Point(chunk.sumOf { it.x } / chunk.size, chunk.sumOf { it.y } / chunk.size)
        }
        out += points.last()
        return out
    }

    /** Ángulo dominante (radianes) módulo 90°: media circular de 4θ ponderada por longitud. */
    fun dominantOrientation(edges: List<Point>): Double {
        var sx = 0.0
        var sy = 0.0
        for (e in edges) {
            val theta = atan2(e.y, e.x)
            sx += e.norm * cos(4 * theta)
            sy += e.norm * sin(4 * theta)
        }
        return if (abs(sx) < 1e-12 && abs(sy) < 1e-12) 0.0 else atan2(sy, sx) / 4.0
    }

    private fun rotate(p: Point, a: Double) = Point(p.x * cos(a) - p.y * sin(a), p.x * sin(a) + p.y * cos(a))

    /**
     * Fusiona lados de igual dirección y quita los menores a una pared mínima. Si el recorrido
     * empezó a mitad de una pared, el último tramo se une al primero y la primera esquina se
     * corre al comienzo de ese tramo: se devuelve ese desplazamiento del origen.
     */
    private fun mergeAndPrune(edges: List<Edge>, minWallM: Double): Pair<List<Edge>, Point> {
        var current = edges
        var shift = Point(0.0, 0.0)
        while (true) {
            val merged = mutableListOf<Edge>()
            for (e in current) {
                val last = merged.lastOrNull()
                if (last != null && e.dir != null && last.dir == e.dir) merged[merged.lastIndex] = Edge(last.v + e.v, e.dir)
                else merged += e
            }
            if (merged.size > 1 && merged.first().dir != null && merged.first().dir == merged.last().dir) {
                val last = merged.removeAt(merged.lastIndex)
                merged[0] = Edge(last.v + merged[0].v, last.dir)
                shift = shift - last.v
            }
            fun weight(i: Int) = merged[i].v.norm / (if (merged[i].dir != null) 1.0 else 2.0)
            val shortest = merged.indices.minBy(::weight)
            if (merged.size > 4 && weight(shortest) < minWallM) merged.removeAt(shortest)
            if (merged == current) return merged to shift
            current = merged
        }
    }

    private fun pointSegmentDistance(p: Point, a: Point, b: Point): Double {
        val ab = b - a
        val den = ab.x * ab.x + ab.y * ab.y
        val t = if (den == 0.0) 0.0 else (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / den).coerceIn(0.0, 1.0)
        return hypot(p.x - (a.x + t * ab.x), p.y - (a.y + t * ab.y))
    }

    /** Reubica cada pared ortogonal en la media de los puntos crudos más cercanos. */
    private fun leastSquaresWalls(vertices: List<Point>, dirs: List<Int>, raw: List<Point>): List<Point> {
        val n = vertices.size
        val offsets = DoubleArray(n) { if (dirs[it] % 2 == 0) vertices[it].y else vertices[it].x }
        val sums = DoubleArray(n)
        val counts = IntArray(n)
        for (p in raw) {
            val k = (0 until n).minBy { pointSegmentDistance(p, vertices[it], vertices[(it + 1) % n]) }
            sums[k] += if (dirs[k] % 2 == 0) p.y else p.x
            counts[k]++
        }
        for (i in 0 until n) if (counts[i] > 0) offsets[i] = sums[i] / counts[i]
        return (0 until n).map { i ->
            val prev = (i - 1 + n) % n
            if (dirs[i] % 2 == 0) Point(offsets[prev], offsets[i]) else Point(offsets[i], offsets[prev])
        }
    }

    fun pathLength(points: List<Point>): Double = (0 until points.lastIndex).sumOf { Geometry.euclidean(points[it], points[it + 1]) }

    /** Tolerancia de cierre: 5 % de lo recorrido, mínimo [baseM], máximo 3 m (igual que el backend). */
    fun closureTolerance(pathLengthM: Double, baseM: Double = 1.0): Double =
        minOf(maxOf(baseM, 0.05 * pathLengthM), maxOf(baseM, 3.0))

    /** Si se pasó del inicio, corta el trazo en el punto (del último 40 %) más cercano al inicio. */
    fun trimLoop(walk: List<Point>, toleranceM: Double): List<Point> {
        if (walk.size < 6) return walk
        val start = walk.first()
        val k = (walk.size * 6 / 10 until walk.size).minBy { Geometry.euclidean(start, walk[it]) }
        return if (Geometry.euclidean(start, walk[k]) <= toleranceM) walk.subList(0, k + 1) else walk
    }

    /**
     * Convierte el trazo crudo (o las esquinas marcadas) en un polígono ortogonal cerrado.
     * Si el recorrido termina lejos del inicio, el hueco se agrega como un lado (closed = false, EX-01).
     */
    fun smoothTrack(
        points: List<Point>,
        corners: List<Point>? = null,
        epsilon: Double = 0.10,
        snapToleranceDeg: Double = 15.0,
        closureToleranceM: Double = 1.0,
        minEdgeM: Double = 0.05,
        minWallM: Double = 0.30,
        smoothingWindow: Int = 5,
    ): Result {
        val source = if (!corners.isNullOrEmpty()) corners else points
        val walk = mutableListOf<Point>()
        for (p in source) if (walk.isEmpty() || Geometry.euclidean(walk.last(), p) > 1e-9) walk += p
        if (walk.size < 3) throw GeometryException("El recorrido necesita al menos 3 puntos distintos")

        val useCorners = !corners.isNullOrEmpty()
        // Con esquinas marcadas no se agranda: la última esquina es real y el hueco se agrega como lado (EX-01)
        val tolerance = if (useCorners) closureToleranceM else closureTolerance(pathLength(walk), closureToleranceM)
        val path = if (useCorners) walk else trimLoop(walk, tolerance)
        var simplified = if (useCorners) path else rdp(movingAverage(path, smoothingWindow), epsilon)
        val closed = Geometry.euclidean(simplified.first(), simplified.last()) <= tolerance
        if (!closed) simplified = simplified + simplified.first()

        val edges = (0 until simplified.lastIndex)
            .map { simplified[it + 1] - simplified[it] }
            .filter { it.norm >= minEdgeM }
        if (edges.size < 3) throw GeometryException("El recorrido es demasiado corto para formar un polígono")
        val closureError = hypot(edges.sumOf { it.x }, edges.sumOf { it.y })

        val alpha = dominantOrientation(edges)
        val tol = Math.toRadians(snapToleranceDeg)
        var snapped = 0
        val local = edges.map { e ->
            val r = rotate(e, -alpha)
            val phi = atan2(r.y, r.x)
            val k = (phi / (PI / 2)).roundToInt()
            if (abs(phi - k * PI / 2) <= tol) {
                snapped++
                Edge(Point(r.norm * cos(k * PI / 2), r.norm * sin(k * PI / 2)), Math.floorMod(k, 4))
            } else Edge(r, null)
        }

        val (merged, shift) = mergeAndPrune(local, minWallM)
        if (merged.size < 3) throw GeometryException("El recorrido suavizado tiene menos de 3 lados")

        // Regla del tránsito: reparte el error de cierre por eje
        val vecs = merged.map { it.v }
        val ex = vecs.sumOf { it.x }
        val ey = vecs.sumOf { it.y }
        val sx = vecs.sumOf { abs(it.x) }
        val sy = vecs.sumOf { abs(it.y) }
        val corrected = vecs.map {
            Point(it.x - if (sx > 0) ex * abs(it.x) / sx else 0.0, it.y - if (sy > 0) ey * abs(it.y) / sy else 0.0)
        }

        var current = shift // primera esquina (antes del inicio si se empezó a mitad de una pared)
        var localVertices = mutableListOf(current)
        for (v in corrected.dropLast(1)) { current += v; localVertices += current }

        val origin = simplified.first()
        val dirs = merged.map { it.dir }
        val alternating = dirs.all { it != null } &&
            dirs.indices.all { dirs[it]!! % 2 != dirs[(it + 1) % dirs.size]!! % 2 }
        if (corners.isNullOrEmpty() && alternating) {
            val raw = walk.map { rotate(it - origin, -alpha) }
            localVertices = leastSquaresWalls(localVertices, dirs.map { it!! }, raw).toMutableList()
        }

        val vertices = localVertices.map { rotate(it, alpha) + origin }
        if (vertices.size < 3) throw GeometryException("El recorrido suavizado tiene menos de 3 vértices")
        return Result(
            vertices = vertices,
            inputPoints = source.size,
            closed = closed,
            closureErrorM = closureError,
            dominantAngleDeg = Math.toDegrees(alpha),
            snappedEdges = snapped,
            totalEdges = edges.size,
        )
    }
}
