package com.metrik.app.gps

import com.metrik.app.core.geometry.Geometry
import com.metrik.app.testing.EvidenceRule
import com.metrik.app.testing.Evidencia
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test

/**
 * Final de una medición de ida y vuelta por un puente angosto (video «puente parque», 14/11): el inicio y el
 * rectángulo salen bien, pero el tramo final (la vuelta, con esquinas de más marcadas junto a la partida) sale en
 * zigzag con lados cortos y la vuelta se monta sobre la ida. Escenario en [IdaVueltaScenario].
 */
class GpsIdaVueltaTest {
    @get:Rule val ev = EvidenceRule()

    private val seeds = 1L..10L

    private fun runAll(p: IdaVueltaScenario.Params) = seeds.map { IdaVueltaScenario.measure(IdaVueltaScenario.generate(it, p).adjust()) }

    private fun describe(name: String, ms: List<IdaVueltaScenario.Metrics>): String {
        fun d(x: Double) = "%.1f".format(x)
        return "$name: simples ${ms.count { it.simple }}/10, cerradas ${ms.count { it.closed }}/10, " +
            "err. área ${ms.map { d(it.areaErrorPct) }} % (media ${d(ms.map { it.areaErrorPct }.average())}), " +
            "desplaz. inicio+rectángulo ${ms.map { d(it.bodyMaxErrM) }} m (media ${d(ms.map { it.bodyMaxErrM }.average())}), " +
            "lados<5 m ${ms.map { it.shortReturnSides }}, giros>120° ${ms.map { it.reversals }}, " +
            "largo final ×${ms.map { "%.2f".format(it.returnLengthRatio) }}, sep. de la ida ${ms.map { d(it.returnSeparationM) }} m, " +
            "cruza la ida ${ms.count { it.returnCrossesOutbound }}/10"
    }

    /**
     * Desplazamiento máximo de la partida y de M1..M6 con el código actual (semillas 1–10, escenario del video).
     * Un arreglo del final no debe empeorarlo (tolerancia [BODY_TOLERANCE_M]).
     */
    private val bodyBaseline = doubleArrayOf(4.0, 5.8, 6.0, 4.7, 6.2, 6.5, 5.3, 6.0, 2.9, 6.1)

    @Test
    @Ignore("reproduce falla; habilitar tras arreglo")
    @Evidencia(
        id = "FU-102", nombre = "Ida y vuelta por un puente: el final queda limpio", requisito = "RF-08, RF-19, RF-21, EX-06",
        objetivo = "Verificar que, al volver por el mismo puente angosto y marcar esquinas de más junto a la partida, el "
            + "tramo final no sale en zigzag (lados cortos, giros que vuelven sobre sí mismos) ni se monta sobre la ida, "
            + "sin mover el inicio ni el rectángulo.",
        entrada = "Puente 35 × 3 m + rectángulo 33 × 25 m con escalón de 4,62 m; vuelta con M7..M10 amontonadas cerca de la "
            + "partida (4–7 m entre sí, yendo y viniendo); brújula 4° + 0,04°/m, paso ×0,95, GPS ±1,5–3 m, anclas ±1,8 m "
            + "(vuelta ±3 m) con σ 0,6–1 m, GPS de la vuelta corrido 2 m hacia la ida; 10 semillas",
        esperado = "Simple y cerrada en las 10; tramo final sin giros > 120°, a lo sumo 1 lado < 5 m (el ancho del "
            + "puente) y largo ≤ 1,10 × el real; la vuelta no cruza la ida; inicio y rectángulo no se mueven más que hoy "
            + "(+0,5 m); error de área medio ≤ 11 %",
    )
    fun finalLimpioEnIdaYVuelta() {
        val ms = runAll(IdaVueltaScenario.Params())
        ev.obtenido = describe("video", ms)
        assertTrue("se cruza", ms.all { it.simple })
        assertTrue("no cerró", ms.all { it.closed })
        assertTrue("zigzag (giros > 120°): ${ms.map { it.reversals }}", ms.all { it.reversals == 0 })
        assertTrue("lados cortos en el final: ${ms.map { it.shortReturnSides }}", ms.all { it.shortReturnSides <= 1 })
        assertTrue("final alargado: ${ms.map { it.returnLengthRatio }}", ms.all { it.returnLengthRatio <= 1.10 })
        assertTrue("la vuelta cruza la ida", ms.none { it.returnCrossesOutbound } && ms.all { it.returnSeparationM >= 0.0 })
        assertTrue("empeoró el cuerpo: ${ms.map { it.bodyMaxErrM }}", ms.indices.all { ms[it].bodyMaxErrM <= bodyBaseline[it] + BODY_TOLERANCE_M })
        assertTrue("área: ${ms.map { it.areaErrorPct }}", ms.map { it.areaErrorPct }.average() <= 11.0)
    }

    @Test
    @Evidencia(
        id = "FU-103", nombre = "Ida y vuelta por un puente: valores actuales del final (línea base)", requisito = "RF-08, RF-19, RF-21",
        objetivo = "Documentar con el código actual las métricas del final de una medición de ida y vuelta por un puente "
            + "(figura simple, cierre, error de área, desplazamiento del inicio y del rectángulo, lados cortos, zigzag, "
            + "separación de la vuelta respecto de la ida) para medir los arreglos, y vigilar que el inicio y el "
            + "rectángulo no empeoren.",
        entrada = "Escenario de FU-102 (video) y dos variantes: M7..M10 marcadas caminando derecho (colineales) y solo la "
            + "esquina real del final (ideal); 10 semillas cada una",
        esperado = "Simple y cerrada en las 10; la vuelta no cruza la ida; inicio y rectángulo dentro de la línea base "
            + "(+0,5 m). Registra el zigzag actual del escenario del video",
    )
    fun lineaBaseIdaYVuelta() {
        val video = runAll(IdaVueltaScenario.Params())
        val collinear = runAll(IdaVueltaScenario.Params(returnPath = IdaVueltaScenario.COLLINEAR))
        val ideal = runAll(IdaVueltaScenario.Params(returnPath = IdaVueltaScenario.IDEAL))
        val sides = IdaVueltaScenario.generate(1, IdaVueltaScenario.Params()).adjust().vertices.let { v ->
            v.indices.map { "%.1f".format(Geometry.euclidean(v[it], v[(it + 1) % v.size])) }
        }
        ev.obtenido = describe("video", video) + " | lados semilla 1: $sides | " + describe("colineales", collinear) + " | " +
            describe("ideal", ideal)
        println(ev.obtenido)
        for (ms in listOf(video, collinear, ideal)) {
            assertTrue("se cruza", ms.all { it.simple })
            assertTrue("no cerró", ms.all { it.closed })
            assertTrue("la vuelta cruza la ida", ms.none { it.returnCrossesOutbound } && ms.all { it.returnSeparationM >= 0.0 })
        }
        assertTrue("empeoró el cuerpo: ${video.map { it.bodyMaxErrM }}", video.indices.all { video[it].bodyMaxErrM <= bodyBaseline[it] + BODY_TOLERANCE_M })
    }

    private companion object {
        const val BODY_TOLERANCE_M = 0.5
    }
}
