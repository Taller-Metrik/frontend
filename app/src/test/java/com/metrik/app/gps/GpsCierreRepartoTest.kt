package com.metrik.app.gps

import com.metrik.app.core.geometry.Geometry
import com.metrik.app.core.geometry.Point
import com.metrik.app.testing.EvidenceRule
import com.metrik.app.testing.Evidencia
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Reparto del error de cierre: la brújula se desvía cada vez más con lo caminado, así que el cierre se acumula al
 * final (la vuelta por el puente del video «puente parque», 14/11) y ahí debe corregirse, sin mover el inicio ni el
 * cuerpo de la medición.
 */
class GpsCierreRepartoTest {
    @get:Rule val ev = EvidenceRule()

    /**
     * Como el video: ida por un puente de 35 m hacia el este, un rectángulo de 33 × 25 m y la vuelta por el puente,
     * 3 m al lado de la ida, con dos esquinas marcadas cerca de la partida. Área real 105 + 825 = 930 m².
     */
    private val truth = listOf(
        Point(0.0, 0.0), Point(35.0, 0.0), Point(35.0, -11.0), Point(68.0, -11.0), Point(68.0, 14.0),
        Point(35.0, 14.0), Point(35.0, 3.0), Point(4.0, 3.0), Point(0.0, 3.0),
    )

    /** Pasos de 0,7 m; el rumbo se desvía [endDeg]·(s/total)^[power] (deriva que crece con lo caminado). */
    private fun walk(poly: List<Point>, endDeg: Double, power: Double): Pair<List<Point>, List<Int>> {
        val total = poly.indices.sumOf { Geometry.euclidean(poly[it], poly[(it + 1) % poly.size]) }
        val pts = mutableListOf(Point(0.0, 0.0))
        val idx = mutableListOf<Int>()
        var walked = 0.0
        for (side in poly.indices) {
            val a = poly[side]
            val b = poly[(side + 1) % poly.size]
            val steps = Math.round(Geometry.euclidean(a, b) / 0.7).toInt()
            val stepLen = Geometry.euclidean(a, b) / steps
            repeat(steps) {
                val err = endDeg * Math.pow((walked + stepLen / 2) / total, power)
                val heading = Math.atan2(b.y - a.y, b.x - a.x) + Math.toRadians(err)
                val last = pts.last()
                pts += Point(last.x + stepLen * cos(heading), last.y + stepLen * sin(heading))
                walked += stepLen
            }
            if (side < poly.lastIndex) idx += pts.lastIndex
        }
        return pts to idx
    }

    private fun maxError(vs: List<Point>, poly: List<Point>, which: IntRange) =
        which.maxOf { k -> vs.minOf { Geometry.euclidean(it, poly[k]) } }

    /** Lecturas GPS en movimiento cada 6 pasos (σ [sigmaM]) y anclas en las esquinas [anchored], con ruido de semilla [seed]. */
    private fun gps(
        poly: List<Point>, idx: List<Int>, trackSize: Int, anchored: Set<Int>, sigmaM: Double, seed: Long,
    ): Pair<List<GpsTraverse.Anchor?>, List<GpsTraverse.GpsObs>> {
        val rnd = java.util.Random(seed)
        val anchors = (1 until poly.size).map { k ->
            if (k in anchored) GpsTraverse.Anchor(Point(poly[k].x + rnd.nextGaussian(), poly[k].y + rnd.nextGaussian()), 1.5) else null
        }
        val knots = listOf(0) + idx + listOf(trackSize - 1)
        val obs = mutableListOf<GpsTraverse.GpsObs>()
        for (side in poly.indices) {
            val a = poly[side]
            val b = poly[(side + 1) % poly.size]
            var i = knots[side] + 3
            while (i < knots[side + 1]) {
                val f = (i - knots[side]).toDouble() / (knots[side + 1] - knots[side])
                obs += GpsTraverse.GpsObs(
                    i, Point(a.x + (b.x - a.x) * f + rnd.nextGaussian() * sigmaM, a.y + (b.y - a.y) * f + rnd.nextGaussian() * sigmaM), sigmaM,
                )
                i += 6
            }
        }
        return anchors to obs
    }

    @Test
    @Evidencia(
        id = "FU-106", nombre = "El cierre se corrige donde la brújula se desvió (al final)", requisito = "RF-08, RF-19",
        objetivo = "Verificar que, cuando la brújula se desvía cada vez más con lo caminado (vuelta por el puente al final), "
            + "el error de cierre se absorbe en los tramos del final y no mueve el inicio ni el cuerpo de la medición.",
        entrada = "Ida por un puente de 35 m, rectángulo 33 × 25 m y vuelta por el puente a 3 m (930 m², 9 vértices, 186 m); "
            + "rumbo desviado 8°·(s/186)² y 6°·s/186; solo pasos y, aparte, con GPS (σ 3 m, anclas en el rectángulo, 10 semillas)",
        esperado = "Antes (sin estimar la deriva) contra ahora: los vértices del inicio se mueven menos, el cierre se absorbe "
            + "en el final y el área queda más cerca de 930 m²",
    )
    fun cierreSeAbsorbeAlFinal() {
        val report = mutableListOf<String>()
        for ((endDeg, power) in listOf(8.0 to 2.0, 6.0 to 1.0)) {
            val (track, idx) = walk(truth, endDeg, power)
            val none = List(truth.size - 1) { null as GpsTraverse.Anchor? }
            val before = GpsTraverse.adjust(track, idx, none, null, startSigmaM = 1.0, headingDriftSigma = 0.0)
            val now = GpsTraverse.adjust(track, idx, none, null, startSigmaM = 1.0)
            // Cuánto movió el ajuste cada esquina respecto de donde se caminó
            fun moved(r: GpsTraverse.Result, k: Int) = Geometry.euclidean(r.vertices[k], track[idx[k - 1]])
            val startBefore = (1..3).maxOf { moved(before, it) }
            val startNow = (1..3).maxOf { moved(now, it) }
            val endNow = (7..8).maxOf { moved(now, it) }
            val errBefore = maxError(before.vertices, truth, 1..5)
            val errNow = maxError(now.vertices, truth, 1..5)
            val areaBefore = Geometry.analyze(before.vertices).areaM2
            val areaNow = Geometry.analyze(now.vertices).areaM2
            report += "deriva ${endDeg.toInt()}°·(s/L)^${power.toInt()}: cierre ${"%.2f".format(now.closureM)} m; inicio movido " +
                "${"%.2f".format(startBefore)} → ${"%.2f".format(startNow)} m, final movido ${"%.2f".format(endNow)} m; error máx. " +
                "inicio+cuerpo ${"%.2f".format(errBefore)} → ${"%.2f".format(errNow)} m; área ${"%.1f".format(areaBefore)} → ${"%.1f".format(areaNow)} m²"
            assertTrue(Geometry.isSimple(now.vertices) && now.closed)
            assertTrue("inicio $startBefore → $startNow", startNow < 0.7 * startBefore)
            assertTrue("final $endNow, inicio $startNow", endNow > startNow)
            assertTrue("error $errBefore → $errNow", errNow < 0.5 * errBefore)
            assertTrue("área $areaBefore → $areaNow", Math.abs(areaNow - 930.0) < 0.5 * Math.abs(areaBefore - 930.0))
        }
        // Con GPS en movimiento y anclas en el rectángulo: el área y el final no empeoran
        val (track, idx) = walk(truth, 6.0, 2.0)
        var areaBefore = 0.0
        var areaNow = 0.0
        var endBefore = 0.0
        var endNow = 0.0
        for (seed in 1L..10L) {
            val (anchors, obs) = gps(truth, idx, track.size, setOf(2, 3, 4, 5), 3.0, seed)
            val end = GpsTraverse.Anchor(Point(0.3, -0.4), 1.5)
            val b = GpsTraverse.adjust(track, idx, anchors, end, 1.0, gps = obs, headingDriftSigma = 0.0)
            val n = GpsTraverse.adjust(track, idx, anchors, end, 1.0, gps = obs)
            assertTrue(Geometry.isSimple(n.vertices))
            areaBefore += Math.abs(Geometry.analyze(b.vertices).areaM2 - 930.0) / 10
            areaNow += Math.abs(Geometry.analyze(n.vertices).areaM2 - 930.0) / 10
            endBefore += maxError(b.vertices, truth, 6..8) / 10
            endNow += maxError(n.vertices, truth, 6..8) / 10
        }
        report += "con GPS (10 semillas): |error de área| medio ${"%.1f".format(areaBefore)} → ${"%.1f".format(areaNow)} m², " +
            "error del final ${"%.2f".format(endBefore)} → ${"%.2f".format(endNow)} m"
        ev.obtenido = report.joinToString("; ")
        assertTrue(areaNow <= areaBefore && endNow <= endBefore)
    }

    @Test
    @Evidencia(
        id = "FU-107", nombre = "Sin deriva de la brújula el resultado no cambia", requisito = "RF-08, RF-19",
        objetivo = "Verificar que estimar la deriva del rumbo no altera una medición sin deriva (rectángulo o terreno "
            + "comunes): los vértices quedan donde quedaban antes.",
        entrada = "Rectángulo 30 × 20 m por pasos: brújula desviada 5° fija, paso 0,95 del supuesto y ruido de rumbo de 3° "
            + "por paso (sin deriva), solo pasos y con GPS (σ 2 m, anclas en 2 esquinas); 10 semillas",
        esperado = "Diferencia máxima con el ajuste anterior < 5 cm por vértice",
    )
    fun sinDerivaNoCambia() {
        val rect = listOf(Point(0.0, 0.0), Point(30.0, 0.0), Point(30.0, 20.0), Point(0.0, 20.0))
        var maxSteps = 0.0
        var maxGps = 0.0
        for (seed in 1L..10L) {
            val rnd = java.util.Random(100 + seed)
            val pts = mutableListOf(Point(0.0, 0.0))
            val idx = mutableListOf<Int>()
            for (side in rect.indices) {
                val a = rect[side]
                val b = rect[(side + 1) % rect.size]
                val steps = Math.round(Geometry.euclidean(a, b) / 0.7).toInt()
                val stepLen = 0.95 * Geometry.euclidean(a, b) / steps
                repeat(steps) {
                    val heading = Math.atan2(b.y - a.y, b.x - a.x) + Math.toRadians(5.0 + 3.0 * rnd.nextGaussian())
                    val last = pts.last()
                    pts += Point(last.x + stepLen * cos(heading), last.y + stepLen * sin(heading))
                }
                if (side < rect.lastIndex) idx += pts.lastIndex
            }
            val none = List(3) { null as GpsTraverse.Anchor? }
            val b0 = GpsTraverse.adjust(pts, idx, none, null, 1.0, headingDriftSigma = 0.0)
            val n0 = GpsTraverse.adjust(pts, idx, none, null, 1.0)
            maxSteps = maxOf(maxSteps, b0.vertices.indices.maxOf { Geometry.euclidean(b0.vertices[it], n0.vertices[it]) })
            val (anchors, obs) = gps(rect, idx, pts.size, setOf(1, 3), 2.0, seed)
            val end = GpsTraverse.Anchor(Point(0.2, 0.3), 1.2)
            val b1 = GpsTraverse.adjust(pts, idx, anchors, end, 1.0, gps = obs, headingDriftSigma = 0.0)
            val n1 = GpsTraverse.adjust(pts, idx, anchors, end, 1.0, gps = obs)
            maxGps = maxOf(maxGps, b1.vertices.indices.maxOf { Geometry.euclidean(b1.vertices[it], n1.vertices[it]) })
        }
        ev.obtenido = "diferencia máx. por vértice: solo pasos ${"%.3f".format(maxSteps)} m, con GPS ${"%.3f".format(maxGps)} m"
        assertTrue("pasos $maxSteps", maxSteps < 0.05)
        assertTrue("gps $maxGps", maxGps < 0.05)
    }
}
