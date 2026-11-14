package com.metrik.app.gps

import com.metrik.app.core.geometry.Geometry
import com.metrik.app.core.geometry.Point
import com.metrik.app.testing.EvidenceRule
import com.metrik.app.testing.Evidencia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ida y vuelta por un puente angosto (video «puente parque», 14/11): la vuelta salía en zigzag con lados cortitos
 * espurios y montada sobre la ida. Tras regularizar debe quedar una franja limpia paralela a la ida, sin tocar el resto.
 */
class GpsCorridorTest {
    @get:Rule val ev = EvidenceRule()

    /** Rectángulo ~33 × 25 m con un escalón real de 4,62 m, unido a la partida por un puente de 35 m y 3 m de ancho. */
    private val rectangle = listOf(
        Point(35.0, 0.0), Point(35.0, 10.0), Point(68.0, 10.0), Point(68.0, -15.0),
        Point(50.0, -15.0), Point(50.0, -10.38), Point(35.0, -10.38),
    )
    private val start = Point(0.0, 0.0)

    /** Verdad: la vuelta va por y = −3 desde la unión (35, −3) y llega a la partida en diagonal (5 m). */
    private val truth = listOf(start) + rectangle + listOf(Point(35.0, -3.0), Point(4.0, -3.0))

    /**
     * Lo que salió en el video: misma ida y rectángulo, la unión (esquina del rectángulo) bien y en la vuelta las esquinas
     * 7..10 amontonadas cerca de la partida, en zigzag (lados de 3,9 a 14 m) y una de ellas montada sobre la ida.
     */
    private val measured = listOf(start) + rectangle + listOf(
        Point(35.0, -3.0), Point(3.0, -4.6), Point(14.5, -0.4), Point(7.0, -3.6), Point(2.6, 1.4),
    )

    private fun returnSides(poly: List<Point>, firstReturnIdx: Int): List<Double> =
        (firstReturnIdx until poly.size).map { Geometry.euclidean(poly[it], poly[(it + 1) % poly.size]) }

    @Test
    @Evidencia(
        id = "FU-108", nombre = "La vuelta por el mismo puente queda paralela a la ida, sin zigzag", requisito = "RF-19, RF-21, EX-06",
        objetivo = "Verificar que, si se va y se vuelve por el mismo lugar angosto, la vuelta queda como una franja limpia " +
            "paralela a la ida (sin lados cortitos espurios ni cruzarla) y que el resto de la figura no cambia.",
        entrada = "Puente 35 × 3 m de ida y vuelta + rectángulo 33 × 25 m con escalón de 4,62 m; vuelta medida en zigzag " +
            "(4 esquinas amontonadas cerca de la partida, una sobre la ida)",
        esperado = "Vuelta sin lados < 5 m, figura simple, área más cercana a la verdad; partida, unión y rectángulo intactos (< 5 cm)",
    )
    fun vueltaPorElPuenteSinZigzag() {
        val out = GpsCorridor.regularize(measured)
        val truthArea = Geometry.analyze(truth).areaM2
        val beforeArea = Geometry.analyze(measured).areaM2
        val afterArea = Geometry.analyze(out).areaM2
        val joinIdx = 1 + rectangle.size
        val sides = returnSides(out, joinIdx)
        ev.obtenido = "vértices ${measured.size} → ${out.size}; lados de la vuelta antes " +
            "${returnSides(measured, joinIdx).map { "%.2f".format(it) }} → después ${sides.map { "%.2f".format(it) }}; " +
            "simple ${Geometry.isSimple(measured)} → ${Geometry.isSimple(out)}; área verdad ${"%.1f".format(truthArea)}, " +
            "antes ${"%.1f".format(beforeArea)}, después ${"%.1f".format(afterArea)} m²"
        assertTrue("sigue con zigzag: $sides", sides.all { it >= 4.99 })
        assertTrue(Geometry.isSimple(out))
        assertTrue("área $afterArea no mejora a $beforeArea (verdad $truthArea)", abs(afterArea - truthArea) < abs(beforeArea - truthArea))
        // Partida, rectángulo y unión quedan donde estaban
        for (i in 0..joinIdx) assertTrue("vértice $i se movió", Geometry.euclidean(out[i], measured[i]) < 0.05)
        // La vuelta (unión → llegada) es casi paralela a la ida y no la cruza (queda del lado de la unión)
        val back = out[joinIdx + 1] - out[joinIdx]
        val angle = Math.toDegrees(abs(Math.atan2(back.y, back.x)))
        assertTrue("vuelta no paralela: $angle°", abs(180.0 - angle) < 10.0)
        assertTrue(out.drop(joinIdx).all { it.y < 0.0 })
    }

    @Test
    @Evidencia(
        id = "FU-109", nombre = "Sin ida y vuelta por el mismo lugar, la figura no cambia", requisito = "RF-19",
        objetivo = "Verificar que la regularización del corredor solo actúa cuando hay ida y vuelta por el mismo lugar: " +
            "en figuras comunes y en el puente de FU-99 devuelve exactamente los mismos vértices.",
        entrada = "Rectángulo 40 × 25 m, L, figura cóncava (U), puente 50 × 4 m de FU-99 (verdad y 10 ajustes con GPS corrido)",
        esperado = "Mismos vértices, sin cambios",
    )
    fun sinCorredorNoCambia() {
        val shapes = mutableListOf(
            listOf(Point(0.0, 0.0), Point(40.0, 0.0), Point(40.0, 25.0), Point(0.0, 25.0)),
            listOf(Point(0.0, 0.0), Point(30.0, 0.0), Point(30.0, 10.0), Point(10.0, 10.0), Point(10.0, 30.0), Point(0.0, 30.0)),
            listOf(Point(0.0, 0.0), Point(30.0, 0.0), Point(30.0, 30.0), Point(20.0, 30.0), Point(20.0, 10.0),
                Point(10.0, 10.0), Point(10.0, 30.0), Point(0.0, 30.0)),
            bridgeTruth,
        )
        val (track, idx) = walkBridge(biasDeg = 4.0, driftDegPerM = 0.15, scale = 0.96)
        for (seed in 1L..10L) {
            val (anchors, end, obs) = narrowGps(idx, track.size, biasM = 8.0, seed = seed)
            shapes += GpsTraverse.adjust(track, idx, anchors, end, startSigmaM = 1.0, gps = obs).vertices
        }
        val changed = shapes.count { GpsCorridor.regularize(it) != it }
        // También con σ: deben volver las mismas
        shapes.forEach { s ->
            val sig = List(s.size) { 1.0 + it }
            val r = GpsCorridor.regularize(s, sig)
            assertEquals(s, r.vertices)
            assertEquals(sig, r.sigmasM)
        }
        ev.obtenido = "figuras cambiadas: $changed de ${shapes.size}"
        assertEquals(0, changed)
    }

    // --- Puente de FU-99 (misma simulación que GpsPuenteTest) ---
    private val bridgeTruth = listOf(Point(0.0, 0.0), Point(0.0, -4.0), Point(-50.0, -4.0), Point(-50.0, 0.0), Point(-10.0, 0.0))

    private fun walkBridge(biasDeg: Double, driftDegPerM: Double, scale: Double): Pair<List<Point>, List<Int>> {
        val pts = mutableListOf(Point(0.0, 0.0))
        val idx = mutableListOf<Int>()
        var walked = 0.0
        for (side in bridgeTruth.indices) {
            val a = bridgeTruth[side]
            val b = bridgeTruth[(side + 1) % bridgeTruth.size]
            val steps = Math.round(Geometry.euclidean(a, b) / 0.7).toInt()
            val stepLen = Geometry.euclidean(a, b) / steps
            repeat(steps) {
                val heading = Math.atan2(b.y - a.y, b.x - a.x) + Math.toRadians(biasDeg + driftDegPerM * walked)
                val last = pts.last()
                pts += Point(last.x + stepLen * scale * cos(heading), last.y + stepLen * scale * sin(heading))
                walked += stepLen
            }
            if (side < bridgeTruth.lastIndex) idx += pts.lastIndex
        }
        return pts to idx
    }

    private fun narrowGps(idx: List<Int>, trackSize: Int, biasM: Double, seed: Long): Triple<List<GpsTraverse.Anchor?>, GpsTraverse.Anchor, List<GpsTraverse.GpsObs>> {
        val rnd = java.util.Random(seed)
        fun biased(p: Point) = if (p.y < -2.0) p else Point(p.x, p.y - biasM * (1 + p.x / 50.0))
        val anchors = bridgeTruth.drop(1).map { c ->
            val b = biased(c)
            GpsTraverse.Anchor(Point(b.x + rnd.nextGaussian() * 0.5, b.y + rnd.nextGaussian() * 0.5), 0.6)
        }
        val end = GpsTraverse.Anchor(biased(Point(0.0, 0.0)), 0.6)
        val knots = listOf(0) + idx + listOf(trackSize - 1)
        val obs = mutableListOf<GpsTraverse.GpsObs>()
        for (side in bridgeTruth.indices) {
            val a = bridgeTruth[side]
            val b = bridgeTruth[(side + 1) % bridgeTruth.size]
            val (ia, ib) = knots[side] to knots[side + 1]
            var i = ia + 2
            while (i < ib) {
                val f = (i - ia).toDouble() / (ib - ia)
                val p = biased(Point(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f))
                obs += GpsTraverse.GpsObs(i, Point(p.x + rnd.nextGaussian() * 1.5, p.y + rnd.nextGaussian() * 1.5), 2.0)
                i += 4
            }
        }
        return Triple(anchors, end, obs)
    }
}
