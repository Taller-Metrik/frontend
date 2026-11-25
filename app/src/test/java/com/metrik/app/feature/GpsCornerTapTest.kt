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
import kotlin.math.abs
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
        assertEquals(Decision.CONFIRMED, GpsCornerTap.decide(Point(3.0, 2.0), o, Point(0.0, 25.0), 3, r, warnedAt = Point(3.5, 2.5)))
        assertEquals(Decision.AT_START, GpsCornerTap.decide(Point(1.0, 0.0), o, Point(0.0, 25.0), 3, r, warnedAt = Point(4.0, 3.0)))
        // Lado corto: a < 2 m de la última esquina no se descarta, se pide confirmar (manda aunque esté en la partida)
        assertEquals(Decision.SHORT_SIDE, GpsCornerTap.decide(Point(30.0, 1.5), o, Point(30.0, 0.0), 1, r))
        assertEquals(Decision.SHORT_SIDE, GpsCornerTap.decide(Point(3.0, 1.0), o, Point(3.5, 0.0), 4, r))
        assertEquals(Decision.CONFIRMED, GpsCornerTap.decide(Point(30.0, 1.5), o, Point(30.0, 0.0), 1, r, warnedAt = Point(30.0, 1.4)))
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

        /** Se detiene [seconds] en [p] (sin pasos): el GPS manda lecturas quietas ahí. */
        suspend fun pause(p: Point, seconds: Double = 4.0) {
            nowNs += 3_500_000_000L; t += 3.5
            repeat(((seconds - 3.5) / 0.5).toInt().coerceAtLeast(1)) {
                nowNs += 500_000_000L; t += 0.5
                gps.emit(GpsPrecisionTest.at(p.x + rnd.nextGaussian() * 0.3, p.y + rnd.nextGaussian() * 0.3, t, 3.0).copy(speedMps = 0.0))
            }
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
            + "2 m de la última esquina no suma sin confirmar (aviso «Lado corto»). Tocar de nuevo en el mismo lugar confirma una esquina real.",
        entrada = "Rectángulo 40 × 25 m a pie: esquina 1, toque a 1,4 m de ella, esquinas 2 y 3, vuelta a la partida y "
            + "«Esquina» dos veces allí (la segunda en el mismo lugar)",
        esperado = "Toque a 1,4 m: sin sumar, aviso «Lado corto»; en la partida: 3 esquinas y aviso «Estás en la partida: "
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
        assertTrue(afterDouble.error.orEmpty().startsWith("Lado corto"))
        assertEquals(3, atStart.cornersFixed)
        assertTrue(atStart.error.orEmpty().startsWith("Estás en la partida: toca Finalizar"))
        assertEquals(4, confirmed.cornersFixed)
    }

    @Test
    @Evidencia(
        id = "FU-113", nombre = "Puente angosto: los lados de menos de 2 m se marcan y llegan al plano",
        requisito = "RF-18, RF-19",
        objetivo = "El puente del profesor tiene lados de menos de 2 m: antes el toque se descartaba («Esquina ignorada: "
            + "estás a menos de 2 m») o se perdía sin aviso porque los 2 primeros pasos tras detenerse en la esquina "
            + "quedaban retenidos. Ahora tocar «Esquina» aplica esos pasos, pide confirmar el lado corto y la esquina "
            + "confirmada no la borra la limpieza del final.",
        entrada = "Franja de 20 × 1,4 m a pie, deteniéndose 4 s en cada esquina: esquina 1, 2 pasos (1,4 m) y dos toques, "
            + "20 m y dos toques junto a la partida (a 1,4 m), vuelta a la partida y Finalizar",
        esperado = "Primer toque del lado corto: aviso «Lado corto», sin sumar; el segundo suma. 3 esquinas y plano de 4 "
            + "vértices con dos lados cortos de 1,4 m ± 0,5 m",
    )
    fun ladosCortosDelPuente() = runBlocking {
        val sim = Sim()
        sim.start()
        sim.walk(Point(0.0, 0.0), Point(20.0, 0.0))
        sim.pause(Point(20.0, 0.0))
        sim.vm.fixCorner()
        sim.walk(Point(20.0, 0.0), Point(20.0, 1.4))
        sim.vm.fixCorner()
        val firstTap = sim.vm.state.value
        sim.vm.fixCorner()
        val short = sim.vm.state.value
        sim.walk(Point(20.0, 1.4), Point(0.0, 1.4))
        sim.pause(Point(0.0, 1.4))
        sim.vm.fixCorner()
        sim.vm.fixCorner()
        val third = sim.vm.state.value
        sim.walk(Point(0.0, 1.4), Point(0.0, 0.0))
        sim.pause(Point(0.0, 0.0), seconds = 8.0)
        sim.vm.finish()
        val end = sim.vm.state.value
        val sides = end.polygon.indices.map { Geometry.euclidean(end.polygon[it], end.polygon[(it + 1) % end.polygon.size]) }
        ev.obtenido = "primer toque: ${firstTap.cornersFixed} («${firstTap.error}»); confirmado: ${short.cornersFixed}; " +
            "esquinas: ${third.cornersFixed}; plano: ${end.polygon.size} vértices, lados ${sides.map { "%.2f".format(java.util.Locale.US, it) }}"
        assertEquals(1, firstTap.cornersFixed)
        assertTrue(firstTap.error.orEmpty().startsWith("Lado corto"))
        assertEquals(2, short.cornersFixed)
        assertEquals(3, third.cornersFixed)
        assertEquals(4, end.polygon.size)
        assertEquals(2, sides.count { abs(it - 1.4) < 0.5 })
    }

    @Test
    @Evidencia(
        id = "FU-114", nombre = "Deshacer la última esquina",
        requisito = "RF-18",
        objetivo = "Una esquina marcada por error se quita con «Deshacer» sin empezar de nuevo (antes no había forma).",
        entrada = "Esquina 1 en (20, 0), esquina 2 por error en (20, 10), Deshacer, y «Esquina» otra vez",
        esperado = "Tras deshacer: 1 esquina y 1 punto dibujado; el toque siguiente vuelve a marcar la esquina 2",
    )
    fun deshacerUltimaEsquina() = runBlocking {
        val sim = Sim()
        sim.start()
        sim.walk(Point(0.0, 0.0), Point(20.0, 0.0))
        sim.vm.fixCorner()
        sim.walk(Point(20.0, 0.0), Point(20.0, 10.0))
        sim.vm.fixCorner()
        val before = sim.vm.state.value
        sim.vm.undoCorner()
        val undone = sim.vm.state.value
        sim.vm.fixCorner()
        val again = sim.vm.state.value
        ev.obtenido = "antes ${before.cornersFixed}; tras deshacer ${undone.cornersFixed} (${undone.cornerPoints.size} puntos); de nuevo ${again.cornersFixed}"
        assertEquals(2, before.cornersFixed)
        assertEquals(1, undone.cornersFixed)
        assertEquals(1, undone.cornerPoints.size)
        assertEquals(2, again.cornersFixed)
    }
}
