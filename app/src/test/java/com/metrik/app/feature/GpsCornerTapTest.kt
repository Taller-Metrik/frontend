package com.metrik.app.feature

import com.metrik.app.core.geometry.Geometry
import com.metrik.app.core.geometry.Point
import com.metrik.app.domain.GpsSample
import com.metrik.app.feature.gps.GpsCornerTap
import com.metrik.app.feature.gps.GpsCornerTap.Decision
import com.metrik.app.feature.gps.GpsViewModel
import com.metrik.app.gps.GpsPrecisionTest
import com.metrik.app.gps.LocationSource
import com.metrik.app.testing.EvidenceRule
import com.metrik.app.testing.Evidencia
import com.metrik.app.testing.FakeSurveyRepository
import com.metrik.app.testing.MainDispatcherRule
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Random

/**
 * Tocar «Esquina» al final del recorrido (video del puente, 14/11: esquinas 8, 9 y 10 a pocos metros entre sí y de
 * la partida): ya en la partida se pide Finalizar y un toque junto a la última esquina se ignora.
 */
class GpsCornerTapTest {
    @get:Rule val ev = EvidenceRule()
    @get:Rule val main = MainDispatcherRule()

    private val o = Point(0.0, 0.0)

    @Test
    fun decisionPura() {
        val r = GpsCornerTap.startRadiusM(null)
        assertEquals(5.0, r, 1e-9)
        assertEquals(7.5, GpsCornerTap.startRadiusM(7.5), 1e-9)
        assertEquals(10.0, GpsCornerTap.startRadiusM(30.0), 1e-9)
        // Lejos de todo: se marca
        assertEquals(Decision.MARK, GpsCornerTap.decide(Point(30.0, 20.0), o, Point(30.0, 0.0), 1, r))
        // En la partida con ≥ 2 esquinas: Finalizar
        assertEquals(Decision.AT_START, GpsCornerTap.decide(Point(3.0, 2.0), o, Point(0.0, 25.0), 3, r))
        // Cerca de la partida pero con < 2 esquinas (primera esquina de una franja angosta): se marca
        assertEquals(Decision.MARK, GpsCornerTap.decide(Point(0.0, 4.0), o, null, 0, r))
        assertEquals(Decision.MARK, GpsCornerTap.decide(Point(4.0, 0.0), o, Point(0.0, 30.0), 1, r))
        // Tocar de nuevo en el mismo lugar del aviso confirma que es una esquina real
        assertEquals(Decision.MARK, GpsCornerTap.decide(Point(3.0, 2.0), o, Point(0.0, 25.0), 3, r, warnedAt = Point(3.5, 2.5)))
        assertEquals(Decision.AT_START, GpsCornerTap.decide(Point(1.0, 0.0), o, Point(0.0, 25.0), 3, r, warnedAt = Point(4.0, 3.0)))
        // Doble toque: a < 2 m de la última esquina (manda aunque esté en la partida)
        assertEquals(Decision.DOUBLE_TAP, GpsCornerTap.decide(Point(30.0, 1.5), o, Point(30.0, 0.0), 1, r))
        assertEquals(Decision.DOUBLE_TAP, GpsCornerTap.decide(Point(3.0, 1.0), o, Point(3.5, 0.0), 4, r))
        assertEquals(Decision.MARK, GpsCornerTap.decide(Point(30.0, 2.5), o, Point(30.0, 0.0), 1, r))
    }

    /** Recorrido a pie simulado (pasos + brújula sin ruido, GPS cada 4 pasos con ±1 m). */
    private class Sim {
        val gps = MutableSharedFlow<GpsSample>(extraBufferCapacity = 10_000)
        val compass = MutableSharedFlow<Double>(extraBufferCapacity = 10_000)
        val rnd = Random(5L)
        var nowNs = 0L
        var t = 0.0
        val vm = GpsViewModel(1, object : LocationSource { override val samples = gps }, FakeSurveyRepository(),
            startGate = { com.metrik.app.gps.GpsStartGate.off() }, calibrationTarget = 3, compassSteps = compass, stepLengthM = 0.70,
            nanoClock = { nowNs })

        suspend fun start() {
            vm.calibrate()
            for ((x, y) in listOf(0.5 to 0.0, -0.5 to 0.3, 0.0 to -0.4)) gps.emit(GpsPrecisionTest.at(x, y, t++, 2.5))
            vm.startRecording()
        }

        suspend fun walk(a: Point, b: Point) {
            val steps = (Geometry.euclidean(a, b) / 0.7).toInt()
            val heading = Math.atan2(b.x - a.x, b.y - a.y)
            for (k in 1..steps) {
                nowNs += 500_000_000L; t += 0.5
                compass.emit(heading)
                if (k % 4 == 0) {
                    val f = k.toDouble() / steps
                    gps.emit(GpsPrecisionTest.at(a.x + (b.x - a.x) * f + rnd.nextGaussian(), a.y + (b.y - a.y) * f + rnd.nextGaussian(), t, 3.0).copy(speedMps = 1.3))
                }
            }
        }
    }

    @Test
    @Evidencia(
        id = "FU-111", nombre = "Al volver a la partida, «Esquina» pide Finalizar y un doble toque no suma esquinas",
        requisito = "RF-18, RF-19",
        objetivo = "Prevenir el final en zigzag del video del puente (esquinas 8, 9 y 10 a pocos metros entre sí y de la "
            + "partida): tocar «Esquina» ya en la partida no marca una esquina sino que pide Finalizar, y un toque a menos de "
            + "2 m de la última esquina se ignora con aviso. Tocar de nuevo en el mismo lugar confirma una esquina real.",
        entrada = "Rectángulo 40 × 25 m a pie: esquina 1, toque a 1,4 m de ella, esquinas 2 y 3, vuelta a la partida y "
            + "«Esquina» dos veces allí (la segunda en el mismo lugar)",
        esperado = "Toque a 1,4 m: ignorado («Esquina ignorada»); en la partida: 3 esquinas y aviso «Estás en la partida: "
            + "toca Finalizar»; el segundo toque en el mismo lugar sí marca (4)",
    )
    fun finalEnLaPartida() = runBlocking {
        val rect = listOf(Point(0.0, 0.0), Point(40.0, 0.0), Point(40.0, 25.0), Point(0.0, 25.0))
        val sim = Sim()
        sim.start()
        sim.walk(rect[0], rect[1])
        sim.vm.fixCorner()
        sim.walk(rect[1], Point(40.0, 1.4))
        sim.vm.fixCorner()
        val afterDouble = sim.vm.state.value
        sim.walk(Point(40.0, 1.4), rect[2])
        sim.vm.fixCorner()
        sim.walk(rect[2], rect[3])
        sim.vm.fixCorner()
        sim.walk(rect[3], rect[0])
        sim.vm.fixCorner()
        val atStart = sim.vm.state.value
        sim.vm.fixCorner()
        val confirmed = sim.vm.state.value
        ev.obtenido = "tras doble toque: ${afterDouble.cornersFixed} esquinas («${afterDouble.error}»); en la partida: " +
            "${atStart.cornersFixed} esquinas («${atStart.error}»); confirmado: ${confirmed.cornersFixed}"
        assertEquals(1, afterDouble.cornersFixed)
        assertTrue(afterDouble.error.orEmpty().startsWith("Esquina ignorada"))
        assertEquals(3, atStart.cornersFixed)
        assertTrue(atStart.error.orEmpty().startsWith("Estás en la partida: toca Finalizar"))
        assertEquals(4, confirmed.cornersFixed)
    }
}
