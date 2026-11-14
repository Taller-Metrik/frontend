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
 * Medición de un lugar angosto (puente o calle de 4 m): la figura final no debe cruzarse aunque el GPS esté corrido
 * más que el ancho, y debe cerrar en la partida. Caso del video del 13/11 («calle husares»: cierre de 8 m y cruce).
 */
class GpsPuenteTest {
    @get:Rule val ev = EvidenceRule()

    /** Puente 50 × 4 m recorrido desde la esquina noreste (la partida); la última esquina se marca en la vuelta, a 10 m de la partida (como en el video). */
    private val truth = listOf(Point(0.0, 0.0), Point(0.0, -4.0), Point(-50.0, -4.0), Point(-50.0, 0.0), Point(-10.0, 0.0))

    /**
     * Pasos de ~0,7 m con la brújula desviada [biasDeg] y una deriva que crece con lo caminado ([driftDegPerM]); el
     * largo de paso real es [scale] veces el supuesto. Devuelve el recorrido y el índice de cada esquina marcada.
     */
    private fun walk(biasDeg: Double, driftDegPerM: Double, scale: Double): Pair<List<Point>, List<Int>> {
        val pts = mutableListOf(Point(0.0, 0.0))
        val idx = mutableListOf<Int>()
        var walked = 0.0
        for (side in truth.indices) {
            val a = truth[side]
            val b = truth[(side + 1) % truth.size]
            val steps = Math.round(Geometry.euclidean(a, b) / 0.7).toInt()
            val stepLen = Geometry.euclidean(a, b) / steps
            repeat(steps) {
                val heading = Math.atan2(b.y - a.y, b.x - a.x) + Math.toRadians(biasDeg + driftDegPerM * walked)
                val last = pts.last()
                pts += Point(last.x + stepLen * scale * cos(heading), last.y + stepLen * scale * sin(heading))
                walked += stepLen
            }
            if (side < truth.lastIndex) idx += pts.lastIndex
        }
        return pts to idx
    }

    /**
     * GPS de un lugar angosto entre barandas o paredes: el lado de ida sale bien, pero en el de vuelta los rebotes lo
     * corren hacia el otro lado cada vez más (0 m en el extremo lejano, [biasM] al llegar a la partida), más ruido; el
     * promedio quieto de las esquinas sale con ±0,6 m (lo que da fixCorner: optimista). Es lo que se ve en el video: el último lado queda inclinado.
     */
    private fun narrowGps(idx: List<Int>, trackSize: Int, biasM: Double, seed: Long): Triple<List<GpsTraverse.Anchor?>, GpsTraverse.Anchor, List<GpsTraverse.GpsObs>> {
        val rnd = java.util.Random(seed)
        fun biased(p: Point) = if (p.y < -2.0) p else Point(p.x, p.y - biasM * (1 + p.x / 50.0))
        val anchors = truth.drop(1).map { c ->
            val b = biased(c)
            GpsTraverse.Anchor(Point(b.x + rnd.nextGaussian() * 0.5, b.y + rnd.nextGaussian() * 0.5), 0.6)
        }
        val end = GpsTraverse.Anchor(biased(Point(0.0, 0.0)), 0.6)
        val knots = listOf(0) + idx + listOf(trackSize - 1)
        val obs = mutableListOf<GpsTraverse.GpsObs>()
        for (side in truth.indices) {
            val a = truth[side]
            val b = truth[(side + 1) % truth.size]
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

    @Test
    @Evidencia(
        id = "FU-99", nombre = "Un puente angosto no se cruza al cerrar", requisito = "RF-08, RF-19, EX-06",
        objetivo = "Verificar que, en un lugar más angosto que el error del GPS, el ajuste final no cruza un lado sobre el "
            + "otro: si el GPS lo cruzaría se le cree menos y manda la forma de los pasos.",
        entrada = "Puente 50 × 4 m (200 m²); brújula desviada 4° con deriva 0,15°/m; paso real 0,96 del supuesto; GPS de "
            + "vuelta corrido de 0 a 8 m hacia el otro lado (anclas ±0,6 m); 10 semillas",
        esperado = "Antes: se cruzaba en las 10 (área 50–90 m²). Ahora: simple y cerrada en las 10, área entre 150 y 250 m²",
    )
    fun puenteNoSeCruza() {
        val (track, idx) = walk(biasDeg = 4.0, driftDegPerM = 0.15, scale = 0.96)
        val runs = (1L..10L).map { seed ->
            val (anchors, end, obs) = narrowGps(idx, track.size, biasM = 8.0, seed = seed)
            GpsTraverse.adjust(track, idx, anchors, end, startSigmaM = 1.0, gps = obs)
        }
        val simple = runs.count { Geometry.isSimple(it.vertices) }
        val closed = runs.count { it.closed }
        val areas = runs.map { Geometry.analyze(it.vertices).areaM2 }
        ev.obtenido = "simples $simple/10, cerradas $closed/10, cierre ${"%.1f".format(runs.first().closureM)} m, " +
            "áreas ${areas.map { "%.0f".format(it) }} m²"
        assertTrue("se cruza: $simple/10", simple == 10)
        assertTrue("no cerró: $closed/10", closed == 10)
        assertTrue("áreas $areas", areas.all { it in 150.0..250.0 })
    }

    @Test
    @Evidencia(
        id = "FU-100", nombre = "Llegar a la partida cierra aunque el GPS del final esté corrido", requisito = "RF-19",
        objetivo = "Verificar que al volver a la partida y tocar Finalizar no sale «No terminaste en el punto de partida» "
            + "solo porque el GPS de ese momento está unos metros corrido del de la partida.",
        entrada = "Rectángulo 20 × 12 m por pasos sin error; lecturas quietas del final a 7,2 m de la partida (±1 m informado)",
        esperado = "closed = true (antes: false, porque 7,2 m > 3 × 1 m y > 10 % de 64 m)",
    )
    fun cierreConGpsCorrido() {
        val rect = listOf(Point(0.0, 0.0), Point(20.0, 0.0), Point(20.0, 12.0), Point(0.0, 12.0))
        val pts = mutableListOf(Point(0.0, 0.0))
        val idx = mutableListOf<Int>()
        for (side in rect.indices) {
            val a = rect[side]
            val b = rect[(side + 1) % rect.size]
            val n = Math.round(Geometry.euclidean(a, b) / 0.7).toInt()
            for (i in 1..n) pts += Point(a.x + (b.x - a.x) * i / n, a.y + (b.y - a.y) * i / n)
            if (side < rect.lastIndex) idx += pts.lastIndex
        }
        val r = GpsTraverse.adjust(pts, idx, listOf(null, null, null), GpsTraverse.Anchor(Point(6.0, -4.0), 1.0), startSigmaM = 1.0)
        ev.obtenido = "cerrado=${r.closed}, cierre de los pasos=${"%.2f".format(r.closureM)} m, área=${"%.0f".format(Geometry.analyze(r.vertices).areaM2)} m²"
        assertTrue(r.closed)
    }
}
