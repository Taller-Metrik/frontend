package com.metrik.app.gps

import com.metrik.app.core.geometry.Geometry
import com.metrik.app.domain.GpsSample
import com.metrik.app.testing.EvidenceRule
import com.metrik.app.testing.Evidencia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Random

/** Cierre del recorrido GPS en el punto de partida (ajuste de la poligonal, regla de la brújula). */
class GpsCierreTest {
    @get:Rule val ev = EvidenceRule()

    /** Recorrido de 100 × 60 m: una lectura cada ~2 m, ruido σ, deriva lineal hasta [drift] al final y [tail] extra. */
    private fun rectangulo(
        sigma: Double = 1.0,
        drift: Pair<Double, Double> = 0.0 to 0.0,
        tail: List<Pair<Double, Double>> = emptyList(),
        seed: Long = 7,
    ): List<GpsSample> {
        val rnd = Random(seed)
        val corners = listOf(0.0 to 0.0, 100.0 to 0.0, 100.0 to 60.0, 0.0 to 60.0, 0.0 to 0.0)
        val pts = mutableListOf<Pair<Double, Double>>()
        for (i in 0 until 4) {
            val (x1, y1) = corners[i]
            val (x2, y2) = corners[i + 1]
            val n = (Math.hypot(x2 - x1, y2 - y1) / 2).toInt()
            for (k in 0 until n) pts += (x1 + (x2 - x1) * k / n) to (y1 + (y2 - y1) * k / n)
        }
        pts += 0.0 to 0.0
        val out = pts.mapIndexed { i, (x, y) ->
            val f = i.toDouble() / (pts.size - 1)
            val nx = if (i == 0) 0.0 else rnd.nextGaussian() * sigma
            val ny = if (i == 0) 0.0 else rnd.nextGaussian() * sigma
            GpsPrecisionTest.at(x + drift.first * f + nx, y + drift.second * f + ny, i.toDouble(), 3.0)
        }
        return out + tail.mapIndexed { k, (x, y) -> GpsPrecisionTest.at(x, y, (pts.size + k).toDouble(), 6.0) }
    }

    @Test
    @Evidencia(
        id = "FU-73", nombre = "La cola final no cruza el terreno", requisito = "RF-08 / EX-06",
        objetivo = "Verificar que, al volver al punto de partida, las lecturas posteriores (por ejemplo, con la señal "
            + "degradada al acercarse a un techo) se descartan y no cruzan el polígono.",
        entrada = "Rectángulo 100 × 60 m recorrido (σ = 1 m) que vuelve al inicio y luego 5 lecturas que se meten hacia "
            + "(25; 20), (40; 5), (10; 35)…",
        esperado = "Polígono simple (sin cruces), 4 lados, área 6000 m² ± 2 % y 5 lecturas de cola descartadas",
    )
    fun colaNoCruza() {
        val tail = listOf(8.0 to 6.0, 25.0 to 20.0, 40.0 to 5.0, 10.0 to 35.0, 30.0 to 30.0)
        val r = GpsMath.closedPolygon(rectangulo(tail = tail))
        ev.obtenido = "simple=${r.analysis.isSimple}, lados=${r.vertices.size}, área=${"%.1f".format(r.analysis.areaM2)}, " +
            "cola descartada=${r.droppedTail}, cierre=${"%.2f".format(r.closureM)} m"
        assertTrue(r.analysis.isSimple)
        assertEquals(4, r.vertices.size)
        assertEquals(6000.0, r.analysis.areaM2, 120.0)
        assertTrue(r.droppedTail >= 4)
    }

    @Test
    @Evidencia(
        id = "FU-74", nombre = "Cierre exacto en el punto de partida", requisito = "RF-08 / RF-19",
        objetivo = "Verificar que el recorrido termina exactamente en el punto de partida: la diferencia de cierre se "
            + "reparte a lo largo del recorrido en proporción a lo caminado (regla de la brújula) y se informa.",
        entrada = "Rectángulo 100 × 60 m cuya deriva acumulada deja el final 6 m al este y 4 m al sur del inicio",
        esperado = "Cierre informado ≈ 7,2 m; el polígono empieza en el punto de partida; área 6000 m² ± 2 %",
    )
    fun cierreExacto() {
        val samples = rectangulo(sigma = 0.3, drift = 6.0 to -4.0)
        val r = GpsMath.closedPolygon(samples)
        val start = GpsMath.toEnu(samples).first()
        val nearest = r.vertices.minOf { Geometry.euclidean(it, start) }
        ev.obtenido = "cierre=${"%.2f".format(r.closureM)} m, vértice más cercano al inicio a ${"%.3f".format(nearest)} m, " +
            "área=${"%.1f".format(r.analysis.areaM2)}"
        assertNotNull(r.closureM)
        assertEquals(7.2, r.closureM!!, 0.8)
        assertEquals(0.0, nearest, 1e-6)
        assertEquals(6000.0, r.analysis.areaM2, 120.0)
    }
}
