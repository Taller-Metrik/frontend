package com.metrik.app.gps

import com.metrik.app.core.geometry.Geometry
import com.metrik.app.core.geometry.Point
import com.metrik.app.testing.EvidenceRule
import com.metrik.app.testing.Evidencia
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/** Inicio del recorrido a pie (pasos + brújula + GPS): el trazo no debe saltar cuando el GPS empieza a corregirlo. */
class GpsInicioTest {
    @get:Rule val ev = EvidenceRule()

    private class Run(val maxJumpM: Double, val maxEarlyShiftM: Double, val endErrorM: Double, val areaErrPct: Double, val startMoveM: Double)

    /**
     * Rectángulo 40 × 30 m caminado con pasos reales de 0,77 m (la app supone 0,70 m) y la brújula desviada
     * [biasDeg] (los primeros [transientSteps] pasos con [transientDeg] más: magnetómetro recién encendido).
     * GPS cada 2 pasos con ruido σ = 3 m y precisión 4,5 m; partida con precisión 2 m.
     */
    private fun walk(seed: Long, biasDeg: Double, transientSteps: Int = 0, transientDeg: Double = 0.0): Run {
        val rnd = Random(seed)
        val corners = listOf(Point(0.0, 0.0), Point(0.0, 40.0), Point(30.0, 40.0), Point(30.0, 0.0), Point(0.0, 0.0))
        val f = PdrGpsFusion(0.70, 2.0)
        var truth = Point(0.0, 0.0)
        var steps = 0
        var maxJump = 0.0
        var maxEarly = 0.0
        val trueTrack = mutableListOf(truth)
        for ((a, b) in corners.zipWithNext()) {
            val heading = Math.atan2(b.x - a.x, b.y - a.y)
            val n = (Geometry.euclidean(a, b) / 0.77).toInt()
            repeat(n) {
                val extra = if (steps < transientSteps) transientDeg else 0.0
                val earlyBefore = f.track().getOrNull(6)
                f.onStep(heading + Math.toRadians(biasDeg + extra))
                truth = Point(truth.x + 0.77 * sin(heading), truth.y + 0.77 * cos(heading))
                trueTrack += truth
                steps++
                if (steps % 2 == 0) {
                    val before = f.position
                    val early = f.track().getOrNull(6)
                    f.onGps(Point(truth.x + rnd.nextGaussian() * 3, truth.y + rnd.nextGaussian() * 3), 4.5)
                    maxJump = maxOf(maxJump, Geometry.euclidean(before, f.position))
                    if (early != null) maxEarly = maxOf(maxEarly, Geometry.euclidean(early, f.track()[6]))
                }
                val earlyAfter = f.track().getOrNull(6)
                if (earlyBefore != null && earlyAfter != null) maxEarly = maxOf(maxEarly, Geometry.euclidean(earlyBefore, earlyAfter))
            }
        }
        f.settle()
        val track = f.track()
        val polygon = listOf(track[0]) + corners.drop(1).dropLast(1).map { c -> track.minBy { Geometry.euclidean(it, c) } }
        val area = Geometry.analyze(polygon).areaM2
        return Run(
            maxJump, maxEarly, Geometry.euclidean(track.last(), trueTrack.last()), 100 * kotlin.math.abs(area - 1200.0) / 1200.0,
            Geometry.euclidean(track.first(), Point(0.0, 0.0)),
        )
    }

    @Test
    @Evidencia(
        id = "FU-81", nombre = "El trazo no salta al empezar a corregir con GPS", requisito = "RF-07 / RF-08",
        objetivo = "Verificar que, cuando el GPS empieza a corregir el giro y la escala de los pasos, el trazo en "
            + "pantalla se corrige de a poco: la posición no salta y el comienzo del recorrido no se mueve de golpe.",
        entrada = "Rectángulo 40 × 30 m; brújula desviada 15°; paso real 0,77 m (supuesto 0,70 m); GPS σ 3 m cada 2 pasos; 10 semillas",
        esperado = "Salto de la posición por lectura GPS < 1,5 m; el punto 6 del trazo se mueve < 1 m por actualización; "
            + "al final el trazo queda ajustado (error del final < 4 m, área ± 10 %)",
    )
    fun inicioSinSaltos() {
        val runs = (1L..10L).map { walk(it, biasDeg = 15.0) }
        ev.obtenido = "salto máx=${"%.2f".format(runs.maxOf { it.maxJumpM })} m, punto 6 máx=${"%.2f".format(runs.maxOf { it.maxEarlyShiftM })} m, " +
            "error final máx=${"%.2f".format(runs.maxOf { it.endErrorM })} m, error de área máx=${"%.1f".format(runs.maxOf { it.areaErrPct })} %"
        assertTrue(runs.all { it.maxJumpM < 1.5 })
        assertTrue(runs.all { it.maxEarlyShiftM < 1.0 })
        assertTrue(runs.all { it.endErrorM < 4.0 })
        assertTrue(runs.all { it.areaErrPct < 10.0 })
    }

    @Test
    @Evidencia(
        id = "FU-82", nombre = "Brújula recién encendida al empezar", requisito = "RF-07",
        objetivo = "Verificar que un desvío fuerte de la brújula en los primeros pasos (magnetómetro sin asentar) no "
            + "hace saltar el trazo en pantalla ni mueve la partida. (El terreno final de este caso lo verifica FU-86: "
            + "al finalizar cada tramo se corrige con su GPS.)",
        entrada = "Mismo rectángulo; brújula desviada 5° y 40° más en los primeros 12 pasos; 10 semillas",
        esperado = "Salto por lectura GPS < 1,5 m; la partida no se mueve",
    )
    fun brujulaRecienEncendida() {
        val runs = (1L..10L).map { walk(it, biasDeg = 5.0, transientSteps = 12, transientDeg = 40.0) }
        ev.obtenido = "salto máx=${"%.2f".format(runs.maxOf { it.maxJumpM })} m, partida movida máx=${"%.3f".format(runs.maxOf { it.startMoveM })} m"
        assertTrue(runs.all { it.maxJumpM < 1.5 })
        assertTrue(runs.all { it.startMoveM < 1e-6 })
    }
    @Test
    @Evidencia(
        id = "FU-85", nombre = "La partida queda fija mientras se recorre", requisito = "RF-08 / RF-19",
        objetivo = "Verificar que el punto de partida (medido quieto al inicio) no se mueve cuando el GPS corrige el "
            + "giro y la escala de los pasos: así, al volver a la partida, la figura cierra en ella.",
        entrada = "Rectángulo 40 × 30 m con brújula desviada 15° y paso real 0,77 m; GPS con un sesgo de 4 m al este; 5 semillas",
        esperado = "El primer punto del trazo queda en (0; 0) en todo momento y al volver a la partida el trazo termina a < 5 m de ella",
    )
    fun partidaFija() {
        var maxStartMove = 0.0
        var maxEnd = 0.0
        for (seed in 1L..5L) {
            val rnd = Random(seed)
            val f = PdrGpsFusion(0.70, 2.0)
            val corners = listOf(Point(0.0, 0.0), Point(0.0, 40.0), Point(30.0, 40.0), Point(30.0, 0.0), Point(0.0, 0.0))
            var truth = Point(0.0, 0.0)
            var steps = 0
            for ((a, b) in corners.zipWithNext()) {
                val heading = Math.atan2(b.x - a.x, b.y - a.y)
                repeat((Geometry.euclidean(a, b) / 0.77).toInt()) {
                    f.onStep(heading + Math.toRadians(15.0))
                    truth = Point(truth.x + 0.77 * sin(heading), truth.y + 0.77 * cos(heading))
                    if (++steps % 2 == 0) f.onGps(Point(truth.x + 4.0 + rnd.nextGaussian() * 3, truth.y + rnd.nextGaussian() * 3), 4.5)
                    maxStartMove = maxOf(maxStartMove, Geometry.euclidean(f.track().first(), Point(0.0, 0.0)))
                }
            }
            f.settle()
            maxStartMove = maxOf(maxStartMove, Geometry.euclidean(f.track().first(), Point(0.0, 0.0)))
            maxEnd = maxOf(maxEnd, Geometry.euclidean(f.track().last(), Point(0.0, 0.0)))
        }
        ev.obtenido = "movimiento máx. de la partida=${"%.3f".format(maxStartMove)} m, final a ${"%.2f".format(maxEnd)} m de la partida"
        assertTrue(maxStartMove < 1e-6)
        assertTrue(maxEnd < 5.0)
    }
}
