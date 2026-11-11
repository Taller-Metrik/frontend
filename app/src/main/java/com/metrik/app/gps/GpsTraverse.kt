package com.metrik.app.gps

import com.metrik.app.core.geometry.Geometry
import com.metrik.app.core.geometry.GeometryException
import com.metrik.app.core.geometry.Point
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Compensación de la poligonal del terreno (como una poligonal cerrada en topografía), con tres fuentes:
 *
 * 1. **Tramos del recorrido.** Entre esquina y esquina el recorrido (pasos + brújula, o el trazo GPS) da el vector
 *    del tramo. Si durante el tramo hubo lecturas GPS en movimiento ([GpsObs]), el tramo se gira y escala para que
 *    encaje con ellas: un lado torcido por la brújula (una reja, un auto) se endereza con el GPS de ese mismo lado,
 *    sin arrastrar a los demás.
 * 2. **Esquinas ancladas.** Si la persona se quedó quieta en una esquina, el promedio de esas lecturas ([Anchor])
 *    fija la esquina con 1–2 m de error.
 * 3. **Cierre.** La partida es el origen y el recorrido termina en ella: el último tramo vuelve a (0, 0). El error
 *    de cierre (lo que la cadena de tramos no cierra) se reparte entre las esquinas por mínimos cuadrados
 *    ponderados (generaliza la regla de la brújula: cada tramo y cada ancla pesan según su σ).
 *
 * El recorrido se dibuja llevando cada tramo, con una semejanza, a sus dos esquinas ajustadas.
 */
object GpsTraverse {
    /** Posición GPS promediada en una esquina (lecturas con la persona quieta) y su σ. */
    data class Anchor(val point: Point, val sigmaM: Double)

    /** Lectura GPS en movimiento: posición (metros) y el punto del recorrido en el que se recibió. */
    data class GpsObs(val trackIndex: Int, val point: Point, val sigmaM: Double)

    data class Result(
        /** Esquinas ajustadas, empezando por la partida (salvo que la partida esté a mitad de un lado). */
        val vertices: List<Point>,
        val sigmasM: List<Double>,
        /** Recorrido corregido tramo a tramo: pasa por cada esquina y, si cerró, termina en la partida. */
        val track: List<Point>,
        /** Error de cierre: lo que la cadena de tramos corregidos no cierra, antes de repartirlo. */
        val closureM: Double,
        val closed: Boolean,
    )

    /** σ de un tramo del recorrido de largo d: σ₀ + d·deriva (≈ 3 % de lo caminado para pasos + brújula). */
    const val TRACK_SIGMA0_M = 0.5
    const val TRACK_DRIFT_PER_M = 0.03
    /** Cierre aceptable: hasta 3 σ del cierre o el 10 % de lo caminado; más que eso, no se volvió a la partida. */
    private const val CLOSURE_SIGMAS = 3.0
    private const val CLOSURE_FRACTION = 0.10
    /** La partida se omite como vértice si está sobre el lado que une la última esquina con la primera. */
    private const val COLLINEAR_TOLERANCE_M = 1.0
    /** Un tramo se corrige con sus lecturas GPS si tiene al menos estas y se abren bastante más que su error. */
    private const val MIN_GPS_PER_LEG = 3
    private const val MAX_LEG_ROTATION_RAD = Math.PI / 6
    private const val MAX_LEG_SCALE_ERROR = 0.25
    /** Error residual del tramo corregido con GPS: 1 % del largo (lo que el GPS no puede ver). */
    private const val GPS_LEG_RESIDUAL_PER_M = 0.01

    /**
     * Tramo entre dos nudos: su vector [delta] con σ y, si se encajó con GPS, el centro de esas lecturas ([mid]),
     * que cae en la fracción [midT] del tramo. Vector y centro salen independientes del ajuste (centro y giro/escala
     * no se correlacionan): así una esquina sin lecturas quietas también queda ubicada por el GPS de sus lados.
     */
    private class Leg(val delta: Point, val sigmaM: Double, val mid: Anchor? = null, val midT: Double = 0.5)

    /**
     * @param track recorrido en metros con la partida en el primer punto (origen).
     * @param cornerIdx índice del punto del recorrido en el que se tocó cada esquina, en orden.
     * @param anchors por esquina, el promedio GPS de las lecturas quietas o null si no hubo.
     * @param endAnchor promedio GPS de las lecturas quietas al terminar (en la partida), si hubo.
     * @param gps lecturas GPS en movimiento para corregir cada tramo (vacío si el recorrido ya es GPS).
     * @param sigma0M error base de un tramo; [driftPerM] error que crece con el largo del tramo.
     */
    fun adjust(
        track: List<Point>,
        cornerIdx: List<Int>,
        anchors: List<Anchor?>,
        endAnchor: Anchor?,
        startSigmaM: Double,
        gps: List<GpsObs> = emptyList(),
        sigma0M: Double = TRACK_SIGMA0_M,
        driftPerM: Double = TRACK_DRIFT_PER_M,
    ): Result {
        require(cornerIdx.size == anchors.size) { "Cada esquina necesita su ancla (o null)" }
        if (cornerIdx.size < 2) throw GeometryException("Marca al menos 2 esquinas además de la partida (EX-05)")
        if (track.size < 2) throw GeometryException("Recorrido demasiado corto (EX-05)")
        require(cornerIdx.zipWithNext().all { (a, b) -> a < b } && cornerIdx.first() > 0 && cornerIdx.last() < track.size) {
            "Las esquinas deben estar en orden dentro del recorrido"
        }
        val n = cornerIdx.size
        val start = track.first()
        val cum = DoubleArray(track.size)
        for (i in 1 until track.size) cum[i] = cum[i - 1] + Geometry.euclidean(track[i - 1], track[i])
        val total = cum.last()
        if (total <= 0.0) throw GeometryException("El recorrido no avanza (EX-05)")

        // Tramos: partida → esquina 1 → … → esquina n → final (el final del recorrido)
        val knotIdx = listOf(0) + cornerIdx + listOf(track.lastIndex)
        val legs = knotIdx.zipWithNext().map { (a, b) -> leg(track, a, b, cum, gps, sigma0M, driftPerM) }

        // Cierre: ¿se terminó en la partida? Lo dicen las lecturas quietas del final o, si no las hay, la cadena misma
        val chainEnd = legs.fold(Point(0.0, 0.0)) { acc, l -> Point(acc.x + l.delta.x, acc.y + l.delta.y) }
        val chainSigma = sqrt(legs.sumOf { it.sigmaM * it.sigmaM })
        val closureM = hypot(chainEnd.x - 0.0, chainEnd.y - 0.0)
        val closed = if (endAnchor != null) {
            hypot(endAnchor.point.x - start.x, endAnchor.point.y - start.y) <= maxOf(CLOSURE_SIGMAS * endAnchor.sigmaM, CLOSURE_FRACTION * total)
        } else {
            closureM <= maxOf(CLOSURE_SIGMAS * chainSigma, CLOSURE_FRACTION * total)
        }

        // Mínimos cuadrados: incógnitas c_1..c_n (x e y por separado, misma matriz normal)
        val normal = Array(n) { DoubleArray(n) }
        val rx = DoubleArray(n)
        val ry = DoubleArray(n)
        // Observación lineal Σ aᵢ·cᵢ = m (coordenadas relativas a la partida, que es fija y vale 0)
        fun observe(coef: Map<Int, Double>, m: Point, sigmaM: Double) {
            val w = 1 / (sigmaM * sigmaM)
            val free = coef.filterKeys { it in 0 until n }
            for ((i, ai) in free) {
                rx[i] += w * ai * m.x; ry[i] += w * ai * m.y
                for ((j, aj) in free) normal[i][j] += w * ai * aj
            }
        }
        // Tramo k: del nudo k al k + 1. Nudo 0 = partida, nudo q (1..n) = esquina q − 1, nudo n + 1 = partida al cerrar
        for (k in 0..n) {
            if (k == n && !closed) break
            val l = legs[k]
            val from = k - 1
            val to = if (k == n) -1 else k
            observe(mapOf(to to 1.0, from to -1.0).filterKeys { it >= 0 }, l.delta, l.sigmaM)
            l.mid?.let { m ->
                observe(mapOf(from to 1 - l.midT, to to l.midT).filterKeys { it >= 0 }, Point(m.point.x - start.x, m.point.y - start.y), m.sigmaM)
            }
        }
        anchors.forEachIndexed { k, a -> if (a != null) observe(mapOf(k to 1.0), Point(a.point.x - start.x, a.point.y - start.y), a.sigmaM) }
        val inv = invert(normal) ?: throw GeometryException("No se pudo ajustar la poligonal (EX-05)")
        val cx = DoubleArray(n) { i -> (0 until n).sumOf { j -> inv[i][j] * rx[j] } }
        val cy = DoubleArray(n) { i -> (0 until n).sumOf { j -> inv[i][j] * ry[j] } }
        val adjusted = (0 until n).map { Point(start.x + cx[it], start.y + cy[it]) }
        val sigmas = (0 until n).map { sqrt(maxOf(inv[it][it], 0.0)) }
        val finalPoint = if (closed) start else Point(adjusted.last().x + legs[n].delta.x, adjusted.last().y + legs[n].delta.y)

        // Recorrido: cada tramo entre nudos consecutivos se lleva con una semejanza a sus nudos ajustados
        val knots = listOf(0 to start) + cornerIdx.indices.map { cornerIdx[it] to adjusted[it] } + listOf(track.lastIndex to finalPoint)
        val out = mutableListOf<Point>()
        for ((k0, k1) in knots.zipWithNext()) {
            val (ia, pa) = k0
            val (ib, pb) = k1
            if (ib <= ia) continue
            val segment = warp(track.subList(ia, ib + 1), pa, pb)
            out += if (out.isEmpty()) segment else segment.drop(1)
        }

        var vertices = listOf(start) + adjusted
        var vertexSigmas = listOf(startSigmaM) + sigmas
        if (closed && vertices.size > 3 && segmentDistance(start, adjusted.last(), adjusted.first()) < COLLINEAR_TOLERANCE_M) {
            vertices = adjusted
            vertexSigmas = sigmas
        }
        // Un cierre grande respecto de las σ es información: piso de σ como en GpsPrecision (d/√π)
        val floor = if (closed) closureM / sqrt(Math.PI) / sqrt(n.toDouble()) else 0.0
        return Result(vertices, vertexSigmas.map { maxOf(it, floor) }, out, closureM, closed)
    }

    /**
     * Vector de un tramo del recorrido. Con lecturas GPS en movimiento suficientes y bien abiertas, el tramo se
     * gira y escala (semejanza de mínimos cuadrados, acotada) para encajar con ellas y su σ baja al error del GPS
     * promediado; si no, vale el recorrido tal cual con σ₀ + deriva·largo.
     */
    private fun leg(track: List<Point>, a: Int, b: Int, cum: DoubleArray, gps: List<GpsObs>, sigma0M: Double, driftPerM: Double): Leg {
        val pa = track[a]
        val pb = track[b]
        val raw = Point(pb.x - pa.x, pb.y - pa.y)
        val length = cum[b] - cum[a]
        val obs = gps.filter { it.trackIndex in a..b }
        if (obs.size >= MIN_GPS_PER_LEG && length > 0.0) {
            // Semejanza (Umeyama 2D) de los puntos del recorrido del tramo sobre sus lecturas GPS, ponderada por 1/σ²
            var w = 0.0; var mpx = 0.0; var mpy = 0.0; var mqx = 0.0; var mqy = 0.0
            val pairs = obs.map { Triple(track[it.trackIndex], it.point, 1 / (it.sigmaM * it.sigmaM)) }
            for ((p, q, wi) in pairs) { w += wi; mpx += wi * p.x; mpy += wi * p.y; mqx += wi * q.x; mqy += wi * q.y }
            mpx /= w; mpy /= w; mqx /= w; mqy /= w
            var sxy = 0.0; var cross = 0.0; var varP = 0.0; var varQ = 0.0
            for ((p, q, wi) in pairs) {
                val px = p.x - mpx; val py = p.y - mpy; val qx = q.x - mqx; val qy = q.y - mqy
                sxy += wi * (px * qx + py * qy); cross += wi * (px * qy - py * qx)
                varP += wi * (px * px + py * py); varQ += wi * (qx * qx + qy * qy)
            }
            val meanSigma2 = obs.sumOf { it.sigmaM * it.sigmaM } / obs.size
            val spreadEnough = varP > 0 && varQ / w > 4 * meanSigma2
            if (spreadEnough) {
                val theta = atan2(cross, sxy).coerceIn(-MAX_LEG_ROTATION_RAD, MAX_LEG_ROTATION_RAD)
                val scale = (hypot(sxy, cross) / varP).coerceIn(1 - MAX_LEG_SCALE_ERROR, 1 + MAX_LEG_SCALE_ERROR)
                val c = cos(theta); val s = sin(theta)
                val delta = Point(scale * (c * raw.x - s * raw.y), scale * (s * raw.x + c * raw.y))
                val residual2 = (GPS_LEG_RESIDUAL_PER_M * length).let { it * it }
                // Error del vector por eje: el giro y la escala tienen varianza 1/Σw·|p−p̄|², a lo largo de |Δ|
                val sigma = sqrt((raw.x * raw.x + raw.y * raw.y) / varP + residual2)
                // Centro de las lecturas: cae donde el centro de sus puntos del recorrido se proyecta sobre el tramo
                val rawLen2 = raw.x * raw.x + raw.y * raw.y
                val t = if (rawLen2 > 0) (((mpx - pa.x) * raw.x + (mpy - pa.y) * raw.y) / rawLen2).coerceIn(0.0, 1.0) else 0.5
                // Si el recorrido se aparta de la cuerda, ese desvío (girado y escalado) se descuenta del centro GPS
                val ox = pa.x + t * raw.x - mpx
                val oy = pa.y + t * raw.y - mpy
                val chordPoint = Point(mqx + scale * (c * ox - s * oy), mqy + scale * (s * ox + c * oy))
                val mid = Anchor(chordPoint, sqrt(1 / w + residual2))
                return Leg(delta, maxOf(sigma, sigma0M), mid, t)
            }
        }
        return Leg(raw, sigma0M + driftPerM * length)
    }

    /** Semejanza que lleva el primer punto de [seg] a [pa] y el último a [pb]; un tramo sin largo solo se traslada. */
    private fun warp(seg: List<Point>, pa: Point, pb: Point): List<Point> {
        val a = seg.first()
        val b = seg.last()
        val ux = b.x - a.x
        val uy = b.y - a.y
        val vx = pb.x - pa.x
        val vy = pb.y - pa.y
        val lu = hypot(ux, uy)
        if (lu < 1e-6) {
            val n = seg.size - 1
            return seg.mapIndexed { i, _ -> if (n == 0) pa else Point(pa.x + vx * i / n, pa.y + vy * i / n) }
        }
        val scale = hypot(vx, vy) / lu
        val theta = atan2(vy, vx) - atan2(uy, ux)
        val c = cos(theta)
        val s = sin(theta)
        return seg.map { p ->
            val dx = p.x - a.x
            val dy = p.y - a.y
            Point(pa.x + scale * (c * dx - s * dy), pa.y + scale * (s * dx + c * dy))
        }
    }

    /** Inversa por Gauss-Jordan con pivoteo parcial; null si la matriz es singular. */
    private fun invert(m: Array<DoubleArray>): Array<DoubleArray>? {
        val n = m.size
        val a = Array(n) { i -> DoubleArray(2 * n) { j -> if (j < n) m[i][j] else if (j - n == i) 1.0 else 0.0 } }
        for (col in 0 until n) {
            val pivot = (col until n).maxByOrNull { abs(a[it][col]) } ?: return null
            if (abs(a[pivot][col]) < 1e-12) return null
            if (pivot != col) { val t = a[pivot]; a[pivot] = a[col]; a[col] = t }
            val p = a[col][col]
            for (j in 0 until 2 * n) a[col][j] /= p
            for (i in 0 until n) if (i != col) {
                val f = a[i][col]
                if (f != 0.0) for (j in 0 until 2 * n) a[i][j] -= f * a[col][j]
            }
        }
        return Array(n) { i -> DoubleArray(n) { j -> a[i][j + n] } }
    }

    private fun segmentDistance(p: Point, a: Point, b: Point): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return Geometry.euclidean(p, a)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
        return Geometry.euclidean(p, Point(a.x + dx * t, a.y + dy * t))
    }
}
