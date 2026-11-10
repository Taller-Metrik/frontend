package com.metrik.app.gps

import com.metrik.app.core.geometry.Geometry
import com.metrik.app.core.geometry.GeometryException
import com.metrik.app.core.geometry.Point
import com.metrik.app.domain.GpsSample
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Calidad de la señal GNSS en un instante (RF-20): satélites usados en la solución (usedInFix) y
 * C/N0 medio de los 4 usados más fuertes. Se usa el promedio de los más fuertes porque en un celular
 * (antena pequeña) los satélites bajos arrastran la media de todos por debajo de 30 dB-Hz aunque el
 * cielo esté despejado.
 */
data class GnssQuality(val satellites: Int, val cn0DbHz: Double?) {
    companion object {
        fun fromUsedCn0(usedCn0DbHz: List<Double>): GnssQuality {
            val strongest = usedCn0DbHz.sortedDescending().take(4)
            return GnssQuality(usedCn0DbHz.size, strongest.takeIf { it.isNotEmpty() }?.average())
        }
    }
}

/**
 * Esquinas fijadas (stop-and-go) e incertidumbre del área en el celular (RF-18 a RF-21).
 *
 * Es la versión ligera de `backend/app/services/gps_precision.py` y `uncertainty.py`, con las mismas
 * constantes, para mostrar "Área ± U95" sin conexión (RF-17). Al sincronizar, el backend recalcula y su
 * resultado es el oficial.
 */
object GpsPrecision {
    /** Android informa `accuracy` como el radio del 68 %: R = σ·√(−2·ln 0,32). */
    val ACCURACY_TO_SIGMA = sqrt(-2 * ln(0.32))
    const val MIN_SIGMA_M = 0.3
    /** σ_b: error que no se reduce al promediar (multitrayecto, ionosfera). Piso de un vértice. */
    const val NON_AVERAGING_SIGMA_M = 0.5
    const val MIN_SATELLITES = 6
    const val MIN_CN0_DBHZ = 30.0
    const val OUTLIER_SIGMAS = 3.0
    val RAYLEIGH_MEDIAN = sqrt(2 * ln(2.0))
    val RAYLEIGH_P95 = sqrt(-2 * ln(0.05))
    const val CORRELATION_TIME_S = 30.0
    const val MIN_READINGS_PER_VERTEX = 3
    const val COVERAGE_FACTOR = 2.0
    /** Si la σ sale de una sola muestra (el cierre), se amplía k para mantener el 95 %. */
    const val CLOSURE_COVERAGE_FACTOR = 2.5

    fun sigmaFromAccuracy(accuracyM: Double) = maxOf(MIN_SIGMA_M, accuracyM / ACCURACY_TO_SIGMA)

    /** true si la lectura no trae datos de señal o si su señal es suficiente. */
    fun acceptsSignal(s: GpsSample) =
        (s.satellites == null || s.satellites >= MIN_SATELLITES) && (s.cn0DbHz == null || s.cn0DbHz >= MIN_CN0_DBHZ)

    data class CornerFix(val point: Point, val sigmaM: Double, val used: Int, val rejected: Int)

    /**
     * Fija una esquina: centro robusto (mediana), descarte a más de 3σ, promedio ponderado 1/σ² y
     * σ_v² = máx(dispersión, σ reportada)² / n_ef + σ_b², con n_ef = duración / 30 s.
     */
    fun fixCorner(points: List<Point>, sigmasM: List<Double>, durationS: Double): CornerFix {
        if (points.isEmpty()) throw GeometryException("No hay lecturas para fijar la esquina")
        val center = Point(median(points.map { it.x }), median(points.map { it.y }))
        val radial = points.map { Geometry.euclidean(it, center) }
        val robustSigma = maxOf(MIN_SIGMA_M, median(radial) / RAYLEIGH_MEDIAN)
        var keep = radial.indices.filter { radial[it] <= OUTLIER_SIGMAS * robustSigma }
        if (keep.size < MIN_READINGS_PER_VERTEX) keep = points.indices.toList()
        val sigmas = keep.map { maxOf(MIN_SIGMA_M, sigmasM[it]) }
        val weights = sigmas.map { 1 / (it * it) }
        val wSum = weights.sum()
        val point = Point(
            keep.indices.sumOf { points[keep[it]].x * weights[it] } / wSum,
            keep.indices.sumOf { points[keep[it]].y * weights[it] } / wSum,
        )
        val spread = sqrt(keep.indices.sumOf { weights[it] * Geometry.euclidean(points[keep[it]], point).let { d -> d * d } } / (2 * wSum))
        val nEff = minOf(keep.size.toDouble(), maxOf(1.0, durationS / CORRELATION_TIME_S))
        val base = maxOf(spread, sigmas.average())
        val sigma = sqrt(base * base / nEff + NON_AVERAGING_SIGMA_M * NON_AVERAGING_SIGMA_M)
        return CornerFix(point, sigma, keep.size, points.size - keep.size)
    }

    data class CornerPolygon(
        val vertices: List<Point>,
        val sigmasM: List<Double>,
        val closureM: Double?,
        val closureConsistent: Boolean?,
        val k: Double,
    )

    /** Cantidad de esquinas distintas fijadas en el recorrido. */
    fun fixedCorners(samples: List<GpsSample>) = samples.mapNotNull { it.vertexId }.toSet().size

    /**
     * Polígono a partir de las esquinas fijadas, en el orden de visita (coordenadas locales respecto de la
     * primera lectura). Si la última visita vuelve a la primera esquina, se mide el cierre: d / √π es el
     * piso de σ y, si d supera el percentil 95 esperado, el cierre no es coherente.
     */
    fun cornerPolygon(samples: List<GpsSample>, origin: GpsSample? = null): CornerPolygon {
        val valid = samples.filter(::acceptsSignal)
        if (valid.isEmpty()) throw GeometryException("No hay lecturas con señal suficiente (EX-04)")
        val origin = origin ?: valid.first()
        val visits = mutableListOf<Pair<Int, MutableList<GpsSample>>>()
        var previous: Int? = null
        for (s in valid) {
            val id = s.vertexId
            if (id == null) { previous = null; continue }
            if (id == previous) visits.last().second += s else visits += id to mutableListOf(s)
            previous = id
        }
        val closureVisit = if (visits.size > 2 && visits.last().first == visits.first().first) visits.removeAt(visits.lastIndex).second else null
        val groups = LinkedHashMap<Int, MutableList<List<GpsSample>>>()
        for ((id, visit) in visits) groups.getOrPut(id) { mutableListOf() } += visit
        if (groups.size < 3) throw GeometryException("Solo ${groups.size} esquinas fijadas; se necesitan al menos 3 (EX-05)")

        fun fix(group: List<List<GpsSample>>): CornerFix {
            val readings = group.flatten()
            return fixCorner(
                GpsMath.toEnu(readings, origin.lat, origin.lon),
                readings.map { sigmaFromAccuracy(it.accuracyM) },
                group.sumOf { visit -> if (visit.size > 1) visit.last().timestampS - visit.first().timestampS + 1 else visit.size.toDouble() },
            )
        }

        val fixes = groups.values.map(::fix).toMutableList()
        var closureM: Double? = null
        var consistent: Boolean? = null
        var floor = 0.0
        if (closureVisit != null) {
            val first = fixes[0]
            val last = fix(listOf(closureVisit))
            closureM = Geometry.euclidean(first.point, last.point)
            floor = closureM / sqrt(Math.PI)
            consistent = closureM <= RAYLEIGH_P95 * hypot(first.sigmaM, last.sigmaM)
            val w1 = 1 / (first.sigmaM * first.sigmaM)
            val w2 = 1 / (last.sigmaM * last.sigmaM)
            val merged = Point((first.point.x * w1 + last.point.x * w2) / (w1 + w2), (first.point.y * w1 + last.point.y * w2) / (w1 + w2))
            fixes[0] = CornerFix(merged, 1 / sqrt(w1 + w2), first.used + last.used, first.rejected + last.rejected)
        }
        val dominated = fixes.any { floor > it.sigmaM }
        return CornerPolygon(
            vertices = fixes.map { it.point },
            sigmasM = fixes.map { maxOf(it.sigmaM, floor) },
            closureM = closureM,
            closureConsistent = consistent,
            k = if (dominated) CLOSURE_COVERAGE_FACTOR else COVERAGE_FACTOR,
        )
    }

    data class AreaUncertainty(
        val areaU95M2: Double,
        val areaU95Pct: Double,
        val perimeterU95M: Double,
        val perimeterU95Pct: Double,
        val k: Double,
    )

    /** σ_A² = ¼ Σ σᵢ² dᵢ² y σ_P² = Σ σᵢ² |u_in − u_out|²; se informa U = k·σ. */
    fun uncertainty(vertices: List<Point>, sigmasM: List<Double>, k: Double = COVERAGE_FACTOR): AreaUncertainty {
        val n = vertices.size
        if (n < 3 || sigmasM.size != n) throw GeometryException("Se necesitan al menos 3 vértices con su σ")
        var varArea = 0.0
        var varPerimeter = 0.0
        for (i in 0 until n) {
            val prev = vertices[(i - 1 + n) % n]
            val cur = vertices[i]
            val next = vertices[(i + 1) % n]
            val s2 = sigmasM[i] * sigmasM[i]
            val dx = next.x - prev.x
            val dy = next.y - prev.y
            varArea += s2 * (dx * dx + dy * dy) / 4
            val (ix, iy) = unit(prev, cur)
            val (ox, oy) = unit(cur, next)
            varPerimeter += s2 * ((ix - ox) * (ix - ox) + (iy - oy) * (iy - oy))
        }
        val area = abs((0 until n).sumOf { vertices[it].x * vertices[(it + 1) % n].y - vertices[(it + 1) % n].x * vertices[it].y }) / 2
        val perimeter = (0 until n).sumOf { Geometry.euclidean(vertices[it], vertices[(it + 1) % n]) }
        if (area <= 0 || perimeter <= 0) throw GeometryException("El polígono no tiene área (EX-05)")
        val ua = k * sqrt(varArea)
        val up = k * sqrt(varPerimeter)
        return AreaUncertainty(ua, 100 * ua / area, up, 100 * up / perimeter, k)
    }

    private fun unit(a: Point, b: Point): Pair<Double, Double> {
        val d = Geometry.euclidean(a, b)
        return if (d == 0.0) 0.0 to 0.0 else (b.x - a.x) / d to (b.y - a.y) / d
    }

    private fun median(values: List<Double>): Double {
        val s = values.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }
}
