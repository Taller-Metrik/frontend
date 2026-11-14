package com.metrik.app.gps

import com.metrik.app.core.geometry.Geometry
import com.metrik.app.core.geometry.Point
import com.metrik.app.testing.EvidenceRule
import com.metrik.app.testing.Evidencia
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Guardia de regresión de [GpsTraverse.adjust]: fija el resultado ACTUAL (vértices, área, cierre) de recorridos
 * deterministas representativos. Un cambio en el ajuste solo se acepta si no mueve nada más de 5 cm / 0,5 % o si
 * acerca el resultado a la verdad conocida de cada escenario (sin alejarlo en nada).
 *
 * Valores base girados el 14/11 con el código de `feature/gps-partida-fija` (ff6d4a5 + cambios sin commit).
 * Si un arreglo mejora un escenario a propósito, esta prueba lo deja pasar; para fijar el nuevo valor, cambiar
 * [baseline] por lo que imprime la prueba («REGRESION ...»).
 */
class GpsRegresionTest {
    @get:Rule val ev = EvidenceRule()

    /** Resultado fijado de un escenario. */
    private class Base(val vertices: List<Point>, val areaM2: Double, val closed: Boolean, val closureM: Double)

    /** Escenario: [truth] = polígono real (para medir si un cambio acerca o aleja), [areaTrue] su área. */
    private class Scenario(val name: String, val truth: List<Point>, val run: () -> GpsTraverse.Result) {
        val areaTrue: Double get() = Geometry.analyze(truth).areaM2
    }

    // ---------------------------------------------------------------- simuladores deterministas

    /**
     * Pasos de ~0,7 m por [route] (partida = route[0], vuelve a la partida) con la brújula desviada [biasDeg], deriva
     * [driftDegPerM] y paso real [scale] veces el supuesto. Esquina marcada en cada vértice salvo la partida.
     */
    private fun walk(route: List<Point>, biasDeg: Double, driftDegPerM: Double, scale: Double): Pair<List<Point>, List<Int>> {
        val pts = mutableListOf(route.first())
        val idx = mutableListOf<Int>()
        var walked = 0.0
        for (side in route.indices) {
            val a = route[side]
            val b = route[(side + 1) % route.size]
            val steps = Math.round(Geometry.euclidean(a, b) / 0.7).toInt()
            val stepLen = Geometry.euclidean(a, b) / steps
            repeat(steps) {
                val heading = Math.atan2(b.y - a.y, b.x - a.x) + Math.toRadians(biasDeg + driftDegPerM * walked)
                val last = pts.last()
                pts += Point(last.x + stepLen * scale * cos(heading), last.y + stepLen * scale * sin(heading))
                walked += stepLen
            }
            if (side < route.lastIndex) idx += pts.lastIndex
        }
        return pts to idx
    }

    /** GPS sano: anclas de esquina ±0,8 m (σ 1 m), fin en la partida, lecturas en movimiento cada 4 pasos ±2 m (σ 3 m). */
    private fun gpsFor(
        route: List<Point>, idx: List<Int>, trackSize: Int, seed: Long,
    ): Triple<List<GpsTraverse.Anchor?>, GpsTraverse.Anchor, List<GpsTraverse.GpsObs>> {
        val rnd = java.util.Random(seed)
        val anchors = route.drop(1).map { c -> GpsTraverse.Anchor(Point(c.x + rnd.nextGaussian() * 0.8, c.y + rnd.nextGaussian() * 0.8), 1.0) }
        val s = route.first()
        val end = GpsTraverse.Anchor(Point(s.x + rnd.nextGaussian() * 0.8, s.y + rnd.nextGaussian() * 0.8), 1.0)
        val knots = listOf(0) + idx + listOf(trackSize - 1)
        val obs = mutableListOf<GpsTraverse.GpsObs>()
        for (side in route.indices) {
            val a = route[side]
            val b = route[(side + 1) % route.size]
            val (ia, ib) = knots[side] to knots[side + 1]
            var i = ia + 2
            while (i < ib) {
                val f = (i - ia).toDouble() / (ib - ia)
                obs += GpsTraverse.GpsObs(i, Point(a.x + (b.x - a.x) * f + rnd.nextGaussian() * 2.0, a.y + (b.y - a.y) * f + rnd.nextGaussian() * 2.0), 3.0)
                i += 4
            }
        }
        return Triple(anchors, end, obs)
    }

    private fun withGps(route: List<Point>, biasDeg: Double, drift: Double, scale: Double, seed: Long): GpsTraverse.Result {
        val (track, idx) = walk(route, biasDeg, drift, scale)
        val (anchors, end, obs) = gpsFor(route, idx, track.size, seed)
        return GpsTraverse.adjust(track, idx, anchors, end, startSigmaM = 1.0, gps = obs)
    }

    /** Puente de FU-99 (GpsPuenteTest) con la semilla 1: GPS de vuelta corrido 0→8 m hacia el otro lado. */
    private val bridgeRoute = listOf(Point(0.0, 0.0), Point(0.0, -4.0), Point(-50.0, -4.0), Point(-50.0, 0.0), Point(-10.0, 0.0))

    private fun bridge(): GpsTraverse.Result {
        val (track, idx) = walk(bridgeRoute, 4.0, 0.15, 0.96)
        val rnd = java.util.Random(1L)
        fun biased(p: Point) = if (p.y < -2.0) p else Point(p.x, p.y - 8.0 * (1 + p.x / 50.0))
        val anchors = bridgeRoute.drop(1).map { c ->
            val b = biased(c)
            GpsTraverse.Anchor(Point(b.x + rnd.nextGaussian() * 0.5, b.y + rnd.nextGaussian() * 0.5), 0.6)
        }
        val end = GpsTraverse.Anchor(biased(Point(0.0, 0.0)), 0.6)
        val knots = listOf(0) + idx + listOf(track.size - 1)
        val obs = mutableListOf<GpsTraverse.GpsObs>()
        for (side in bridgeRoute.indices) {
            val a = bridgeRoute[side]
            val b = bridgeRoute[(side + 1) % bridgeRoute.size]
            val (ia, ib) = knots[side] to knots[side + 1]
            var i = ia + 2
            while (i < ib) {
                val f = (i - ia).toDouble() / (ib - ia)
                val p = biased(Point(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f))
                obs += GpsTraverse.GpsObs(i, Point(p.x + rnd.nextGaussian() * 1.5, p.y + rnd.nextGaussian() * 1.5), 2.0)
                i += 4
            }
        }
        return GpsTraverse.adjust(track, idx, anchors, end, startSigmaM = 1.0, gps = obs)
    }

    /** Cierre de FU-100: rectángulo 20 × 12 por pasos exactos, lecturas quietas del final a 7,2 m de la partida. */
    private val closureRect = listOf(Point(0.0, 0.0), Point(20.0, 0.0), Point(20.0, 12.0), Point(0.0, 12.0))

    private fun closureWithShiftedGps(): GpsTraverse.Result {
        val pts = mutableListOf(Point(0.0, 0.0))
        val idx = mutableListOf<Int>()
        for (side in closureRect.indices) {
            val a = closureRect[side]
            val b = closureRect[(side + 1) % closureRect.size]
            val n = Math.round(Geometry.euclidean(a, b) / 0.7).toInt()
            for (i in 1..n) pts += Point(a.x + (b.x - a.x) * i / n, a.y + (b.y - a.y) * i / n)
            if (side < closureRect.lastIndex) idx += pts.lastIndex
        }
        return GpsTraverse.adjust(pts, idx, listOf(null, null, null), GpsTraverse.Anchor(Point(6.0, -4.0), 1.0), startSigmaM = 1.0)
    }

    private val rect = listOf(Point(0.0, 0.0), Point(30.0, 0.0), Point(30.0, 20.0), Point(0.0, 20.0))
    private val ell = listOf(Point(0.0, 0.0), Point(40.0, 0.0), Point(40.0, 15.0), Point(20.0, 15.0), Point(20.0, 35.0), Point(0.0, 35.0))
    private val uShape = listOf(
        Point(0.0, 0.0), Point(36.0, 0.0), Point(36.0, 30.0), Point(26.0, 30.0),
        Point(26.0, 10.0), Point(10.0, 10.0), Point(10.0, 30.0), Point(0.0, 30.0),
    )
    private val triangle = listOf(Point(0.0, 0.0), Point(40.0, 0.0), Point(15.0, 30.0))
    /** Rectángulo 33 × 25 con el escalón real de 4,62 m arriba (como el del video del puente). */
    private val notch = listOf(Point(0.0, 0.0), Point(33.0, 0.0), Point(33.0, 25.0), Point(16.0, 25.0), Point(16.0, 20.38), Point(0.0, 20.38))
    private val noGpsRect = listOf(Point(0.0, 0.0), Point(25.0, 0.0), Point(25.0, 15.0), Point(0.0, 15.0))

    private val scenarios = listOf(
        Scenario("rectangulo", rect) { withGps(rect, 3.0, 0.05, 0.97, 11L) },
        Scenario("L", ell) { withGps(ell, -2.0, 0.08, 1.03, 12L) },
        Scenario("U", uShape) { withGps(uShape, 2.5, -0.06, 0.98, 13L) },
        Scenario("triangulo", triangle) { withGps(triangle, -3.0, 0.10, 1.02, 14L) },
        Scenario("notch", notch) { withGps(notch, 3.0, 0.10, 0.97, 15L) },
        Scenario("puente_FU99_s1", bridgeRoute.take(4)) { bridge() },
        Scenario("cierre_FU100", closureRect) { closureWithShiftedGps() },
        Scenario("sin_gps", noGpsRect) {
            val (track, idx) = walk(noGpsRect, 4.0, 0.10, 0.95)
            GpsTraverse.adjust(track, idx, List(idx.size) { null }, null, startSigmaM = 1.0)
        },
    )

    // ---------------------------------------------------------------- valores base (código sin tocar)

    private fun p(x: Double, y: Double) = Point(x, y)

    private val baseline: Map<String, Base> = mapOf(
        "rectangulo" to Base(listOf(p(0.0000, 0.0000), p(30.6367, 0.6964), p(29.1666, 20.5748), p(-1.0932, 19.7806)), 604.7285, true, 1.5104),
        "L" to Base(listOf(p(0.0000, 0.0000), p(40.5374, -0.4492), p(40.1385, 15.4394), p(19.8813, 14.7237), p(18.8254, 35.4299), p(-0.5364, 34.4695)), 1011.5271, true, 2.1250),
        "U" to Base(listOf(p(0.0000, 0.0000), p(36.6108, -0.0550), p(35.8455, 30.0518), p(25.9669, 30.0178), p(26.0126, 9.6875), p(9.8538, 10.3830), p(10.4037, 29.5612), p(0.5221, 30.1572)), 762.3844, true, 1.5732),
        "triangulo" to Base(listOf(p(0.0000, 0.0000), p(40.2532, 0.0767), p(13.1916, 29.8560)), 600.3932, true, 4.1638),
        "notch" to Base(listOf(p(0.0000, 0.0000), p(33.6558, 1.1155), p(32.3461, 26.4321), p(15.9798, 24.9866), p(16.2222, 21.1314), p(-0.3287, 19.3364)), 746.1580, true, 3.9997),
        "puente_FU99_s1" to Base(listOf(p(0.0000, 0.0000), p(0.3801, -4.1439), p(-46.1342, -14.1932), p(-46.8600, -10.7566), p(-9.2115, -2.6721)), 169.6444, true, 5.3723),
        "cierre_FU100" to Base(listOf(p(0.0000, 0.0000), p(20.0000, 0.0000), p(20.0000, 12.0000), p(-0.0000, 12.0000)), 240.0000, true, 0.0000),
        "sin_gps" to Base(listOf(p(0.0000, 0.0000), p(23.2745, 2.6271), p(21.2654, 17.0380), p(-2.5516, 13.7034)), 337.7832, true, 1.9031),
    )

    // ---------------------------------------------------------------- comparación

    private fun segDist(p: Point, a: Point, b: Point): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    /**
     * Error de forma frente a la verdad: lo más lejos que queda un vértice del borde real, o una esquina real del
     * vértice más cercano (un vértice sobre un lado real, como la partida a mitad de lado, no cuenta como error).
     */
    private fun shapeError(vertices: List<Point>, truth: List<Point>): Double {
        val toEdges = vertices.maxOf { v -> truth.indices.minOf { i -> segDist(v, truth[i], truth[(i + 1) % truth.size]) } }
        val toCorners = truth.maxOf { t -> vertices.minOf { Geometry.euclidean(it, t) } }
        return maxOf(toEdges, toCorners)
    }

    private fun areaError(area: Double, truthArea: Double) = abs(area - truthArea) / truthArea

    private fun fmt(v: Double) = String.format(Locale.US, "%.4f", v)

    private fun dump(name: String, r: GpsTraverse.Result, area: Double) =
        "REGRESION \"$name\" to Base(listOf(${r.vertices.joinToString { "p(${fmt(it.x)}, ${fmt(it.y)})" }}), " +
            "${fmt(area)}, ${r.closed}, ${fmt(r.closureM)}),"

    /** Null si [r] es igual a [base] (±5 cm / 0,5 %) o lo mejora frente a la verdad; si no, el motivo. */
    private fun judge(sc: Scenario, r: GpsTraverse.Result, base: Base): Pair<Boolean, String?> {
        val area = Geometry.analyze(r.vertices).areaM2
        val same = r.vertices.size == base.vertices.size &&
            r.vertices.zip(base.vertices).all { (a, b) -> Geometry.euclidean(a, b) <= 0.05 } &&
            abs(area - base.areaM2) <= 0.005 * base.areaM2 &&
            abs(r.closureM - base.closureM) <= 0.05 && r.closed == base.closed
        if (same) return true to null
        val shapeNew = shapeError(r.vertices, sc.truth)
        val shapeOld = shapeError(base.vertices, sc.truth)
        val areaNew = areaError(area, sc.areaTrue)
        val areaOld = areaError(base.areaM2, sc.areaTrue)
        val notWorse = shapeNew <= shapeOld + 0.05 && areaNew <= areaOld + 0.005 &&
            (r.closed || !base.closed) && (Geometry.isSimple(r.vertices) || !Geometry.isSimple(base.vertices))
        val better = shapeNew < shapeOld - 0.05 || areaNew < areaOld - 0.005
        val msg = "forma ${fmt(shapeOld)}→${fmt(shapeNew)} m, error de área ${fmt(areaOld * 100)}→${fmt(areaNew * 100)} %, " +
            "closed ${base.closed}→${r.closed}, cierre ${fmt(base.closureM)}→${fmt(r.closureM)} m"
        return false to if (notWorse && better) null else "${sc.name} empeoró o cambió sin mejorar: $msg"
    }

    @Test
    @Evidencia(
        id = "FU-110", nombre = "El ajuste GPS no empeora las mediciones que ya salen bien", requisito = "RF-08, RF-19, RF-20",
        objetivo = "Guardia de regresión: fijar el resultado actual del ajuste final (vértices, área, cierre) en recorridos "
            + "representativos para que un arreglo del final de la medición no cambie lo que ya sale bien.",
        entrada = "8 recorridos deterministas por pasos con brújula desviada y GPS simulado con semilla fija: rectángulo, "
            + "L, U cóncava, triángulo, rectángulo con escalón, puente FU-99 (semilla 1), cierre FU-100 y recorrido sin GPS",
        esperado = "Cada escenario igual al valor fijado (vértices ±5 cm, área ±0,5 %, cierre ±5 cm, closed igual) o más "
            + "cerca de la verdad (error de forma y de área no peores, alguno mejor) sin dejar de cerrar ni cruzarse",
    )
    fun resultadosFijados() {
        val failures = mutableListOf<String>()
        val report = mutableListOf<String>()
        for (sc in scenarios) {
            val r = sc.run()
            println(dump(sc.name, r, Geometry.analyze(r.vertices).areaM2))
            val base = baseline[sc.name]
            if (base == null) {
                failures.add("${sc.name}: sin valor base")
                continue
            }
            val (same, failure) = judge(sc, r, base)
            report += "${sc.name}: ${if (same) "igual" else if (failure == null) "mejoró" else "EMPEORÓ"}"
            failure?.let { failures += it }
        }
        ev.obtenido = report.joinToString("; ")
        assertTrue(failures.joinToString(" | "), failures.isEmpty())
    }

    /** La guardia misma: acepta lo igual, rechaza correr una esquina 30 cm afuera y acepta acercarla a la verdad. */
    @Test
    fun laGuardiaDistingueMejoraDeEmpeoro() {
        val sc = scenarios.first { it.name == "rectangulo" }
        val base = baseline.getValue(sc.name)
        fun result(vs: List<Point>) = GpsTraverse.Result(vs, List(vs.size) { 1.0 }, vs, base.closureM, base.closed)
        assertTrue(judge(sc, result(base.vertices), base).first)
        val worse = base.vertices.toMutableList().also { it[2] = Point(it[2].x + 0.3, it[2].y + 0.3) }
        assertTrue(judge(sc, result(worse), base).second != null)
        assertTrue(judge(sc, result(sc.truth), base).second == null)
    }
}
