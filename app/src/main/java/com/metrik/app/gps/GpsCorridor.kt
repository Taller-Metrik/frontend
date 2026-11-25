package com.metrik.app.gps

import com.metrik.app.core.geometry.Geometry
import com.metrik.app.core.geometry.Point
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Ida y vuelta por un lugar angosto (un puente, una vereda): se sale de la partida por un corredor y se vuelve por el
 * mismo, a pocos metros. El GPS de las esquinas marcadas en la vuelta (amontonadas cerca de la partida) deja ese lado
 * en zigzag, con lados cortitos espurios y montado sobre la ida. Aquí se reconoce ese corredor sobre el polígono ya
 * ajustado y la vuelta se deja como una franja limpia: paralela a la ida, a la separación media medida, sin cruzarla,
 * conservando la partida y la esquina donde la vuelta se une con el resto de la figura. Función pura (sin Android).
 */
object GpsCorridor {
    /** Rumbos opuestos: la vuelta (vista desde la partida) va hacia donde fue la ida, con esta tolerancia. */
    private const val HEADING_TOLERANCE_DEG = 20.0
    /** La vuelta va por el mismo corredor si sus esquinas quedan a menos de esto de la ida (a lo ancho). */
    private const val MAX_SEPARATION_M = 8.0
    /** Separación mínima con la que se dibuja la vuelta (que no quede encima de la ida). */
    private const val MIN_SEPARATION_M = 0.5
    /** Largo mínimo de la ida para hablar de un corredor. */
    private const val MIN_CORRIDOR_LENGTH_M = 10.0
    /** Holgura a lo largo: las esquinas de la vuelta pueden pasarse un poco de los extremos de la ida. */
    private const val LONGITUDINAL_TOLERANCE_M = 5.0
    /** Solapamiento: la unión de la vuelta con la figura debe estar al menos a esta fracción del largo de la ida. */
    private const val MIN_OVERLAP_FRACTION = 0.6
    /** Lado más corto que se deja en la vuelta regularizada (la llegada a la partida). */
    private const val MIN_SIDE_M = 5.0
    /** Retroceso a lo largo del corredor que delata el zigzag de la vuelta. */
    private const val BACKTRACK_M = 1.0
    /** Si el punto de llegada se aparta menos que esto de la recta unión→partida, la vuelta queda en un solo lado. */
    private const val MIN_BEND_M = 0.3

    data class Result(val vertices: List<Point>, val sigmasM: List<Double>?)

    /** Solo los vértices (ver [regularize]). */
    fun regularize(vertices: List<Point>): List<Point> = regularize(vertices, null).vertices

    /**
     * Si el polígono [vertices] (en el orden recorrido, empezando por la partida) tiene un tramo de ida y otro de
     * vuelta por el mismo corredor, devuelve la figura con la vuelta regularizada; si no, exactamente los mismos
     * vértices (y [sigmasM]). Las σ de los vértices que quedan se conservan; el de llegada nuevo toma la mayor de las
     * esquinas que reemplaza.
     */
    fun regularize(vertices: List<Point>, sigmasM: List<Double>?): Result {
        val same = Result(vertices, sigmasM)
        if (vertices.size < 5 || (sigmasM != null && sigmasM.size != vertices.size)) return same
        for (apex in vertices.indices) {
            val r = tryApex(vertices, sigmasM, apex)
            if (r != null) return r
        }
        return same
    }

    /**
     * Corredor que sale del vértice [apex]: la cadena anterior en el tiempo es la ida (se deja tal cual) y la otra la
     * vuelta. En la partida (índice 0) la ida es la que sigue hacia adelante y la vuelta la que llega al final.
     */
    private fun tryApex(v: List<Point>, sigmas: List<Double>?, apex: Int): Result? {
        val n = v.size
        val refStep = if (apex == 0) 1 else -1
        val retStep = -refStep
        fun idx(step: Int, i: Int) = ((apex + step * i) % n + n) % n
        val p = v[apex]

        // Ida: segmentos seguidos con el rumbo del primero (±20°)
        val first = v[idx(refStep, 1)] - p
        if (first.norm <= 0.0) return null
        val firstAngle = atan2(first.y, first.x)
        var refEnd = 1
        while (refEnd + 1 < n - 2) {
            val a = v[idx(refStep, refEnd)]
            val b = v[idx(refStep, refEnd + 1)]
            val d = b - a
            if (d.norm <= 0.0 || angleDiffDeg(atan2(d.y, d.x), firstAngle) > HEADING_TOLERANCE_DEG) break
            refEnd++
        }
        val refEndPoint = v[idx(refStep, refEnd)]
        val axis = refEndPoint - p
        val length = axis.norm
        if (length < MIN_CORRIDOR_LENGTH_M) return null
        val u = Point(axis.x / length, axis.y / length)
        fun along(q: Point) = (q.x - p.x) * u.x + (q.y - p.y) * u.y
        fun across(q: Point) = u.x * (q.y - p.y) - u.y * (q.x - p.x)

        // Vuelta: esquinas (desde la partida hacia atrás en el tiempo) dentro del corredor
        val refIdx = (0..refEnd).map { idx(refStep, it) }.toSet()
        var m = 0
        while (m + 1 < n) {
            val k = idx(retStep, m + 1)
            if (k in refIdx) break
            // Fuera de la partida, la vuelta no pasa por el índice 0 (sería dar la vuelta al orden del recorrido)
            if (apex != 0 && k == 0) break
            val q = v[k]
            val t = along(q)
            if (abs(across(q)) >= MAX_SEPARATION_M || t < -LONGITUDINAL_TOLERANCE_M || t > length + LONGITUDINAL_TOLERANCE_M) break
            m++
        }
        if (m < 2) return null // sin esquinas intermedias en la vuelta: no hay nada que limpiar
        val jIdx = idx(retStep, m)
        // El resto de la figura (entre el final de la ida y la unión) debe tener al menos una esquina
        val restCount = n - (refEnd + 1) - m
        if (restCount < 1) return null
        val j = v[jIdx]
        val tJ = along(j)
        val latJ = across(j)
        if (tJ < MIN_OVERLAP_FRACTION * length) return null
        if (Math.toDegrees(abs(atan2(latJ, tJ))) > HEADING_TOLERANCE_DEG) return null

        // Separación media del lado en que queda la unión (que es fija)
        val lats = (1..m).map { across(v[idx(retStep, it)]) }
        val meanLat = lats.average()
        val side = if (abs(latJ) >= MIN_SEPARATION_M) Math.signum(latJ) else if (meanLat != 0.0) Math.signum(meanLat) else 1.0
        // Las esquinas que caen del otro lado de la ida son error del GPS (la vuelta no la cruza): no cuentan
        val sameSide = lats.map { it * side }.filter { it > 0.0 }
        val sep = maxOf(MIN_SEPARATION_M, if (sameSide.isEmpty()) 0.0 else sameSide.average())
        val normal = Point(-u.y * side, u.x * side)

        // Solo si la vuelta está en zigzag: retrocede a lo largo del corredor, se pasa al otro lado de la ida o la
        // figura se cruza. Una franja ya limpia (p. ej. un rectángulo angosto visto desde una esquina) no se toca.
        val ts = listOf(0.0) + (1..m).map { along(v[idx(retStep, it)]) }
        var maxT = 0.0
        var backtracks = false
        for (t in ts) { if (t < maxT - BACKTRACK_M) backtracks = true; maxT = maxOf(maxT, t) }
        val crossesSide = lats.any { it * side < -MIN_SEPARATION_M }
        val crossed = !runCatching { Geometry.isSimple(v) }.getOrDefault(false)
        if (!backtracks && !crossesSide && !crossed) return null

        // Llegada a la partida: punto de la paralela a [sep] desde el que la partida queda a ≥ 5 m
        val tK = sqrt(maxOf(MIN_SIDE_M * MIN_SIDE_M - sep * sep, 0.0))
        val k = Point(p.x + u.x * tK + normal.x * sep, p.y + u.y * tK + normal.y * sep)
        val withK = tJ - tK >= MIN_SIDE_M && segmentDistance(k, j, p) > MIN_BEND_M && side * across(j) > 0

        val removed = (1 until m).map { idx(retStep, it) }.toSet()
        val removedSigma = sigmas?.let { s -> removed.maxOfOrNull { s[it] } }
        for (insert in listOf(withK, false).distinct()) {
            val outV = mutableListOf<Point>()
            val outS = if (sigmas != null) mutableListOf<Double>() else null
            for (i in 0 until n) {
                if (i in removed) continue
                outV += v[i]; outS?.add(sigmas!![i])
                // Fuera de la partida, la vuelta sale del ápice hacia adelante: K va justo después del ápice
                if (insert && apex != 0 && i == apex) { outV += k; outS?.add(removedSigma ?: sigmas!![i]) }
            }
            // En la partida, K es lo último antes de cerrar en ella (unión → K → partida)
            if (insert && apex == 0) { outV += k; outS?.add(removedSigma ?: sigmas!![0]) }
            val ok = runCatching { Geometry.isSimple(outV) }.getOrDefault(false)
            if (ok && outV.size >= 3) return Result(outV, outS)
        }
        return null
    }

    private fun angleDiffDeg(a: Double, b: Double): Double {
        var d = Math.toDegrees(a - b) % 360.0
        if (d > 180) d -= 360.0
        if (d < -180) d += 360.0
        return abs(d)
    }

    /** Distancia del punto [q] al segmento [a]–[b]. */
    private fun segmentDistance(q: Point, a: Point, b: Point): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 <= 0.0) return hypot(q.x - a.x, q.y - a.y)
        val t = (((q.x - a.x) * dx + (q.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(q.x - (a.x + t * dx), q.y - (a.y + t * dy))
    }
}
