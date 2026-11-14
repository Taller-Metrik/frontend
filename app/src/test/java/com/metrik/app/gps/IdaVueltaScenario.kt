package com.metrik.app.gps

import com.metrik.app.core.geometry.Geometry
import com.metrik.app.core.geometry.Point
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Recorrido del video «puente parque» (14/11), reproducible por semilla: desde la partida se camina ~35 m al este por
 * un puente angosto (3 m de ancho), se da la vuelta a un rectángulo de ~33 × 25 m con un escalón real de 4,62 m y se
 * vuelve por el mismo puente hasta la partida. En la vuelta se marcan 4 esquinas (M7..M10) que no son esquinas del
 * terreno: M7 al llegar al final del puente y M8..M10 amontonadas cerca de la partida, a 3–8 m entre sí.
 *
 * Dos variantes del final ([Params.returnPath]):
 * - [COLLINEAR]: M7..M10 sobre la línea de la vuelta (la persona marcó de más, pero caminando derecho);
 * - [WANDER] (por defecto, la del video): cerca de la partida la persona da vueltas buscando el punto de inicio y
 *   marca M8..M10 a 4–7 m entre sí, yendo y viniendo (lados del video: 4,45 / 14,25 / 7,93 / 3,89 m).
 *
 * La figura que el usuario espera ([intended]) es la franja limpia: partida → M1..M6 → final del puente → partida.
 *
 * Errores modelados (como en el celular):
 * - pasos con la brújula desviada [Params.compassBiasDeg] y deriva [Params.compassDriftDegPerM] por metro caminado,
 *   ruido de rumbo por paso y largo de paso real [Params.stepScale] veces el supuesto;
 * - GPS en movimiento con ruido real 1,5–3 m (σ informado 2 m), una lectura cada [Params.gpsEverySteps] pasos;
 * - en la vuelta por el puente el GPS se corre hacia el lado de ida (rebotes entre barandas), de 0 m en el extremo
 *   del puente a [Params.returnBiasM] al llegar a la partida;
 * - anclas de esquina optimistas: error real ~[Params.anchorErrorM] (en la vuelta [Params.returnAnchorErrorM]: pocas
 *   lecturas quietas, «Quieta en la esquina 8: 2 lecturas»), σ informado 0,6–1 m.
 *
 * Coordenadas en metros: x = este, y = norte, partida en (0, 0). La ida va por y = 0; la vuelta por y = −3.
 */
internal object IdaVueltaScenario {
    /** Partida y esquinas del cuerpo: M1 unión puente→rectángulo, M2..M6 rectángulo con escalón real de 4,62 m. */
    val body: List<Point> = listOf(
        Point(0.0, 0.0), // partida (Inicio)
        Point(35.0, 0.0), // M1: fin del puente de ida
        Point(35.0, 25.0), // M2
        Point(68.0, 25.0), // M3
        Point(68.0, 1.62), // M4
        Point(40.0, 1.62), // M5
        Point(40.0, -3.0), // M6: escalón de 4,62 m; empieza la vuelta por el puente
    )

    /** Final del puente de vuelta, junto a la partida (la única esquina real del tramo final). */
    val returnEnd = Point(0.0, -3.0)

    /** Figura que el usuario espera: franja angosta limpia unida al rectángulo. */
    val intended: List<Point> = body + returnEnd

    /** M7..M10 marcadas caminando derecho por la vuelta. */
    val COLLINEAR = listOf(Point(15.0, -3.0), Point(9.0, -3.0), Point(4.0, -3.0), Point(0.0, -3.0))

    /** M7..M10 como en el video: M7 al bajar del puente y M8..M10 yendo y viniendo cerca de la partida. */
    val WANDER = listOf(Point(7.0, -3.0), Point(3.0, -5.0), Point(10.0, -2.5), Point(3.0, -1.5))

    val intendedArea: Double get() = Geometry.analyze(intended).areaM2

    /** Marcando solo la esquina real del final (lo que la figura esperada necesita): referencia de «bien hecho». */
    val IDEAL = listOf(returnEnd)

    /** Índice (en la lista de esquinas, partida = 0) de la primera esquina marcada en la vuelta (M7). */
    const val FIRST_RETURN_CORNER = 7

    data class Params(
        val returnPath: List<Point> = WANDER,
        val compassBiasDeg: Double = 4.0,
        val compassDriftDegPerM: Double = 0.04,
        val headingNoiseDeg: Double = 3.0,
        val stepScale: Double = 0.95,
        val stepM: Double = 0.7,
        val gpsEverySteps: Int = 3,
        val gpsNoiseMinM: Double = 1.5,
        val gpsNoiseMaxM: Double = 3.0,
        val gpsSigmaM: Double = 2.0,
        val returnBiasM: Double = 2.0,
        val anchorErrorM: Double = 1.8,
        val returnAnchorErrorM: Double = 3.0,
        val anchorSigmaMinM: Double = 0.6,
        val anchorSigmaMaxM: Double = 1.0,
        val endBiasM: Double = 3.0,
        val startSigmaM: Double = 1.0,
    )

    data class Run(
        /** Esquinas reales que se caminaron: partida, M1..M10. */
        val walkedCorners: List<Point>,
        val track: List<Point>,
        val cornerIdx: List<Int>,
        val anchors: List<GpsTraverse.Anchor?>,
        val end: GpsTraverse.Anchor,
        val gps: List<GpsTraverse.GpsObs>,
        val startSigmaM: Double,
    ) {
        fun adjust(): GpsTraverse.Result = GpsTraverse.adjust(track, cornerIdx, anchors, end, startSigmaM, gps = gps)
    }

    /** Corrimiento del GPS en la vuelta por el puente: hacia la ida (+y), creciendo hacia la partida. */
    fun returnBias(p: Point, biasM: Double): Point =
        if (p.y < -0.75 && p.x <= 40.0) Point(p.x, p.y + biasM * (1 - p.x / 40.0)) else p

    fun generate(seed: Long, p: Params = Params()): Run {
        require(p.returnPath.isNotEmpty()) { "Al menos una esquina en la vuelta" }
        val corners = body + p.returnPath
        val rnd = Random(seed)
        val pts = mutableListOf(Point(0.0, 0.0))
        val truthAt = mutableListOf(Point(0.0, 0.0))
        val idx = mutableListOf<Int>()
        var walked = 0.0
        for (side in corners.indices) {
            val a = corners[side]
            val b = corners[(side + 1) % corners.size]
            val steps = maxOf(1, Math.round(Geometry.euclidean(a, b) / p.stepM).toInt())
            val stepLen = Geometry.euclidean(a, b) / steps
            val dir = atan2(b.y - a.y, b.x - a.x)
            for (s in 1..steps) {
                val err = p.compassBiasDeg + p.compassDriftDegPerM * walked + rnd.nextGaussian() * p.headingNoiseDeg
                val h = dir + Math.toRadians(err)
                val last = pts.last()
                pts += Point(last.x + stepLen * p.stepScale * cos(h), last.y + stepLen * p.stepScale * sin(h))
                truthAt += Point(a.x + (b.x - a.x) * s / steps, a.y + (b.y - a.y) * s / steps)
                walked += stepLen
            }
            if (side < corners.lastIndex) idx += pts.lastIndex
        }
        val gps = mutableListOf<GpsTraverse.GpsObs>()
        var i = 2
        while (i < pts.size - 1) {
            if (i !in idx) {
                val noise = p.gpsNoiseMinM + rnd.nextDouble() * (p.gpsNoiseMaxM - p.gpsNoiseMinM)
                val q = returnBias(truthAt[i], p.returnBiasM)
                gps += GpsTraverse.GpsObs(i, Point(q.x + rnd.nextGaussian() * noise, q.y + rnd.nextGaussian() * noise), p.gpsSigmaM)
            }
            i += p.gpsEverySteps
        }
        val anchors = corners.indices.drop(1).map { k ->
            val q = returnBias(corners[k], p.returnBiasM)
            val e = if (k >= FIRST_RETURN_CORNER) p.returnAnchorErrorM else p.anchorErrorM
            val sigma = p.anchorSigmaMinM + rnd.nextDouble() * (p.anchorSigmaMaxM - p.anchorSigmaMinM)
            GpsTraverse.Anchor(Point(q.x + rnd.nextGaussian() * e, q.y + rnd.nextGaussian() * e), sigma)
        }
        // Lecturas quietas del final (en la partida): el GPS de la vuelta sigue corrido unos metros
        val end = GpsTraverse.Anchor(
            Point(rnd.nextGaussian() * p.anchorErrorM, p.endBiasM + rnd.nextGaussian() * p.anchorErrorM), 0.8,
        )
        return Run(corners, pts, idx, anchors, end, gps, p.startSigmaM)
    }

    // ---------------------------------------------------------------- métricas

    data class Metrics(
        /** (a) La figura no se cruza. */
        val simple: Boolean,
        /** (b) Cerró en la partida. */
        val closed: Boolean,
        val closureM: Double,
        val areaM2: Double,
        /** (c) Error de área respecto de la figura esperada ([intended]). */
        val areaErrorPct: Double,
        /** (d) Máximo desplazamiento de la partida y de M1..M6 respecto de la verdad (no debe empeorar). */
        val bodyMaxErrM: Double,
        /** (e) Lados de menos de 5 m en el tramo final (M6 → … → partida); la figura esperada tiene 1 (el ancho, 3 m). */
        val shortReturnSides: Int,
        /** (e) Giros de más de 120° (el lado vuelve sobre sí mismo, lados ≥ 2 m) en el tramo final; esperado 0. */
        val reversals: Int,
        /** (e) Largo del tramo final ajustado / largo esperado (M6 → final del puente → partida = 43 m); 1 = limpio. */
        val returnLengthRatio: Double,
        /** Vértices del tramo final que sobran respecto de la franja limpia (que tiene 1: el final del puente). */
        val extraReturnVertices: Int,
        /** (f) Mínima distancia con signo de los vértices de la vuelta al lado de ida; < 0 = quedó del otro lado. */
        val returnSeparationM: Double,
        /** (f) Algún lado del tramo final cruza el lado de ida. */
        val returnCrossesOutbound: Boolean,
        val vertices: List<Point>,
    ) {
        fun summary() = "simple=$simple cerrada=$closed cierre=${f1(closureM)} área=${f0(areaM2)} (err ${f1(areaErrorPct)} %) " +
            "cuerpo=${f1(bodyMaxErrM)} m cortos=$shortReturnSides giros>120=$reversals largo×${"%.2f".format(returnLengthRatio)} " +
            "sobran=$extraReturnVertices sep=${f1(returnSeparationM)} m cruza=$returnCrossesOutbound"
    }

    /**
     * Esquinas ajustadas, empezando por la partida (que puede venir omitida si quedó sobre un lado). Las 7 primeras
     * son la partida y M1..M6; el resto, la vuelta (un arreglo puede dejar menos vértices en la vuelta).
     */
    private fun corners(r: GpsTraverse.Result): List<Point> =
        if (r.vertices.none { hypot(it.x, it.y) < 1e-6 }) listOf(Point(0.0, 0.0)) + r.vertices else r.vertices

    fun measure(r: GpsTraverse.Result): Metrics {
        val v = corners(r)
        val simple = runCatching { Geometry.isSimple(r.vertices) }.getOrDefault(false)
        val area = runCatching { Geometry.analyze(r.vertices).areaM2 }.getOrDefault(0.0)
        val bodyErr = body.indices.maxOf { Geometry.euclidean(v[it], body[it]) }
        val finalChain = (6..v.lastIndex).map { v[it] } + v[0]
        val intendedChain = listOf(body[6], returnEnd, body[0])
        fun length(c: List<Point>) = c.zipWithNext().sumOf { (a, b) -> Geometry.euclidean(a, b) }
        val shortSides = finalChain.zipWithNext().count { (a, b) -> Geometry.euclidean(a, b) < 5.0 }
        // Giro de más de 120° entre dos lados de al menos 2 m (uno más corto es el ancho del puente o ruido)
        val reversals = (1 until finalChain.lastIndex).count { k ->
            val a = finalChain[k - 1]; val b = finalChain[k]; val c = finalChain[k + 1]
            if (Geometry.euclidean(a, b) < 2.0 || Geometry.euclidean(b, c) < 2.0) return@count false
            val t = atan2(c.y - b.y, c.x - b.x) - atan2(b.y - a.y, b.x - a.x)
            abs(Math.toDegrees(atan2(sin(t), cos(t)))) > 120.0
        }
        val s = v[0]; val m1 = v[1]
        val ux = m1.x - s.x; val uy = m1.y - s.y
        val len = hypot(ux, uy)
        // La vuelta real queda a la derecha de la ida (al sur de una ida que va al este): distancia positiva
        val sep = finalChain.drop(1).dropLast(1).minOfOrNull { q -> -(ux * (q.y - s.y) - uy * (q.x - s.x)) / len } ?: 0.0
        val crosses = finalChain.zipWithNext().dropLast(1).any { (a, b) -> segmentsCross(a, b, s, m1) }
        // La franja limpia tiene en el tramo final M6 → final del puente → partida: 1 vértice intermedio
        val extra = finalChain.size - 3
        return Metrics(
            simple, r.closed, r.closureM, area, abs(area - intendedArea) / intendedArea * 100, bodyErr, shortSides, reversals,
            length(finalChain) / length(intendedChain), extra, sep, crosses, r.vertices,
        )
    }

    private fun segmentsCross(p1: Point, p2: Point, p3: Point, p4: Point): Boolean {
        fun o(a: Point, b: Point, c: Point) = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
        val d1 = o(p3, p4, p1); val d2 = o(p3, p4, p2); val d3 = o(p1, p2, p3); val d4 = o(p1, p2, p4)
        return d1 * d2 < 0 && d3 * d4 < 0
    }

    private fun f0(x: Double) = "%.0f".format(x)
    private fun f1(x: Double) = "%.1f".format(x)
}
