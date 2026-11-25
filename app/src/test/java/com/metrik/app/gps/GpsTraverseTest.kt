package com.metrik.app.gps

import com.metrik.app.core.geometry.Geometry
import com.metrik.app.core.geometry.Point
import com.metrik.app.sensors.circularMeanRad
import com.metrik.app.testing.EvidenceRule
import com.metrik.app.testing.Evidencia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Ajuste de la poligonal GPS: esquinas ancladas, cierre en la partida y recorrido corregido tramo a tramo. */
class GpsTraverseTest {
    @get:Rule val ev = EvidenceRule()

    private val truth = listOf(Point(0.0, 0.0), Point(100.0, 0.0), Point(100.0, 60.0), Point(0.0, 60.0))

    /**
     * Recorrido por pasos (0,7 m) del rectángulo, deformado como lo deforma la brújula: toda la figura girada [rotDeg]
     * y escalada [scale], y el lado [bentSide] además girado [bentDeg] (una reja o un auto desvían la brújula).
     * Devuelve el recorrido y el índice del punto de cada esquina marcada.
     */
    private fun pdr(rotDeg: Double, scale: Double, bentSide: Int, bentDeg: Double): Pair<List<Point>, List<Int>> {
        val pts = mutableListOf(Point(0.0, 0.0))
        val idx = mutableListOf<Int>()
        for (side in 0 until 4) {
            val a = truth[side]
            val b = truth[(side + 1) % 4]
            val len = Geometry.euclidean(a, b)
            val heading = Math.atan2(b.y - a.y, b.x - a.x) + Math.toRadians(rotDeg + if (side == bentSide) bentDeg else 0.0)
            val steps = (len / 0.7).toInt()
            repeat(steps) {
                val last = pts.last()
                pts += Point(last.x + 0.7 * scale * cos(heading), last.y + 0.7 * scale * sin(heading))
            }
            if (side < 3) idx += pts.lastIndex
        }
        return pts to idx
    }

    /** Lecturas GPS en movimiento: cada ~8 pasos, la posición real de ese paso con ruido σ = 2 m. */
    private fun gpsAlongSides(idx: List<Int>, trackSize: Int, seed: Long): List<GpsTraverse.GpsObs> {
        val rnd = java.util.Random(seed)
        val knots = listOf(0) + idx + listOf(trackSize - 1)
        val obs = mutableListOf<GpsTraverse.GpsObs>()
        for (side in 0 until 4) {
            val a = truth[side]
            val b = truth[(side + 1) % 4]
            val (ia, ib) = knots[side] to knots[side + 1]
            var i = ia + 4
            while (i < ib) {
                val f = (i - ia).toDouble() / (ib - ia)
                obs += GpsTraverse.GpsObs(i, Point(a.x + (b.x - a.x) * f + rnd.nextGaussian() * 2, a.y + (b.y - a.y) * f + rnd.nextGaussian() * 2), 2.0)
                i += 8
            }
        }
        return obs
    }

    @Test
    @Evidencia(
        id = "FU-75", nombre = "Poligonal ajustada a las esquinas y cerrada en la partida", requisito = "RF-08 / RF-18 / RF-19",
        objetivo = "Verificar que, con las esquinas marcadas, el GPS de cada tramo y la posición GPS promediada en 3 de ellas, "
            + "el polígono queda en las esquinas reales aunque la brújula haya girado la figura y torcido un lado, que el cierre "
            + "se reparte hasta terminar exactamente en la partida y que el recorrido corregido pasa por cada esquina.",
        entrada = "Rectángulo 100×60 m por pasos: figura girada 8° y escalada 0,94; lado 3 torcido 15° más; GPS en movimiento "
            + "cada 8 pasos (σ 2 m); anclas GPS (σ 1,5 m) en las esquinas 1, 2 y 4 (la 3 sin lecturas quietas); lecturas finales en la partida",
        esperado = "Vértices a < 2 m de los reales, área 6000 m² ± 3 %, cierre repartido (termina en la partida) y el "
            + "recorrido pasa por las 4 esquinas",
    )
    fun poligonalAjustada() {
        val (track, idx) = pdr(rotDeg = 8.0, scale = 0.94, bentSide = 2, bentDeg = 15.0)
        val anchors = listOf(
            GpsTraverse.Anchor(Point(100.4, -0.3), 1.5),
            GpsTraverse.Anchor(Point(99.6, 60.5), 1.5),
            null,
        )
        val r = GpsTraverse.adjust(
            track, idx, anchors, endAnchor = GpsTraverse.Anchor(Point(0.3, -0.4), 1.2), startSigmaM = 1.0,
            gps = gpsAlongSides(idx, track.size, seed = 21),
        )
        val errors = truth.map { t -> r.vertices.minOf { Geometry.euclidean(it, t) } }
        val area = Geometry.analyze(r.vertices).areaM2
        val through = r.vertices.all { v -> r.track.any { Geometry.euclidean(it, v) < 1e-6 } }
        ev.obtenido = "errores por vértice=${errors.map { "%.2f".format(it) }}, área=${"%.1f".format(area)}, " +
            "cierre=${"%.2f".format(r.closureM)} m, cerrado=${r.closed}, pasa por las esquinas=$through, " +
            "fin del recorrido=${r.track.last()}"
        assertEquals(4, r.vertices.size)
        assertTrue(errors.all { it < 2.0 })
        assertEquals(6000.0, area, 180.0)
        assertTrue(r.closed)
        assertTrue(through)
        assertEquals(0.0, Geometry.euclidean(r.track.last(), Point(0.0, 0.0)), 1e-6)
    }

    @Test
    @Evidencia(
        id = "FU-76", nombre = "No se fuerza el cierre si no se volvió a la partida", requisito = "RF-19",
        objetivo = "Verificar que, si el recorrido termina lejos del punto de partida, no se reparte un cierre inexistente.",
        entrada = "Mismo rectángulo, pero el recorrido termina en (30; 20) y sin lecturas finales",
        esperado = "closed = false, el último vértice no se mueve a la partida",
    )
    fun sinCierreForzado() {
        val (track, idx) = pdr(rotDeg = 0.0, scale = 1.0, bentSide = -1, bentDeg = 0.0)
        val cut = track.take(idx[2] + 40) // se queda a mitad del último lado
        val r = GpsTraverse.adjust(cut, idx, listOf(null, null, null), endAnchor = null, startSigmaM = 1.0)
        ev.obtenido = "cerrado=${r.closed}, cierre=${"%.1f".format(r.closureM)} m, fin=${r.track.last()}"
        assertFalse(r.closed)
        assertTrue(r.closureM > 20.0)
        assertTrue(Geometry.euclidean(r.track.last(), Point(0.0, 0.0)) > 20.0)
    }

    @Test
    @Evidencia(
        id = "FU-77", nombre = "Rumbo promedio del paso (media circular)", requisito = "RF-07",
        objetivo = "Verificar que el rumbo de cada paso se promedia sin el salto de ±180° (el balanceo del brazo da "
            + "lecturas a ambos lados del norte).",
        entrada = "Rumbos 350°, 10°, 355°, 5°; y 170°, 190°",
        esperado = "≈ 0° (no 180°) y ≈ 180°",
    )
    fun mediaCircular() {
        val norte = Math.toDegrees(circularMeanRad(listOf(350.0, 10.0, 355.0, 5.0).map { Math.toRadians(it) }))
        val sur = Math.toDegrees(circularMeanRad(listOf(170.0, 190.0).map { Math.toRadians(it) }))
        ev.obtenido = "norte=${"%.1f".format(norte)}°, sur=${"%.1f".format(sur)}°"
        assertEquals(0.0, ((norte + 540) % 360) - 180, 0.5)
        assertEquals(180.0, (sur + 360) % 360, 0.5)
    }
}
