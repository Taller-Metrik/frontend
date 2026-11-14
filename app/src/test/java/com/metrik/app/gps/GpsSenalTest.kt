package com.metrik.app.gps

import com.metrik.app.domain.GpsSample
import com.metrik.app.feature.gps.GpsPhase
import com.metrik.app.feature.gps.GpsViewModel
import com.metrik.app.testing.EvidenceRule
import com.metrik.app.testing.Evidencia
import com.metrik.app.testing.FakeSurveyRepository
import com.metrik.app.testing.MainDispatcherRule
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Medir con GPS solo cuando tiene sentido: señal de exterior, terreno más grande que el error y sin cruces. */
class GpsSenalTest {
    @get:Rule val ev = EvidenceRule()
    @get:Rule val main = MainDispatcherRule()

    private fun at(x: Double, y: Double, t: Double, acc: Double): GpsSample = GpsPrecisionTest.at(x, y, t, acc)

    private fun vm(gps: MutableSharedFlow<GpsSample>) =
        GpsViewModel(1, object : LocationSource { override val samples = gps }, FakeSurveyRepository(), startGate = { com.metrik.app.gps.GpsStartGate.off() }, calibrationTarget = 3)

    @Test
    @Evidencia(
        id = "FU-70", nombre = "La medición de precisión espera señal de exterior", requisito = "RF-09",
        objetivo = "Verificar que, bajo techo (precisión peor que 6 m), las lecturas no cuentan para la medición de "
            + "precisión ni para el punto de partida, y que la pantalla avisa que hay que salir al aire libre.",
        entrada = "5 lecturas con precisión 15 m (interior) y luego 3 con 4 m (exterior)",
        esperado = "Con 15 m: 0 lecturas contadas y aviso de señal débil; con 4 m: READY sin aviso",
    )
    fun esperaSenalDeExterior() = runBlocking {
        val gps = MutableSharedFlow<GpsSample>(extraBufferCapacity = 100)
        val vm = vm(gps)
        vm.calibrate()
        repeat(5) { gps.emit(at(12.0 * (it % 2), 0.0, it.toDouble(), 15.0)) }
        val adentro = vm.state.value
        repeat(3) { gps.emit(at(0.0, 0.0, 10.0 + it, 4.0)) }
        val afuera = vm.state.value
        ev.obtenido = "interior: contadas=${adentro.calibrationSamples}, aviso=${adentro.weakSignalM}; " +
            "exterior: fase=${afuera.phase}, aviso=${afuera.weakSignalM}"
        assertEquals(0, adentro.calibrationSamples)
        assertEquals(GpsPhase.CALIBRATING, adentro.phase)
        assertNotNull(adentro.weakSignalM)
        assertEquals(GpsPhase.READY, afuera.phase)
        assertNull(afuera.weakSignalM)
    }

    @Test
    @Evidencia(
        id = "FU-71", nombre = "Recorrido demasiado pequeño para GPS", requisito = "RF-08 / EX-04",
        objetivo = "Verificar que, si lo recorrido no es claramente mayor que el error del GPS, no se dibuja un polígono "
            + "sin sentido y se recomienda medir como ambiente.",
        entrada = "Lecturas con precisión 8 m en un recorrido de ~9 × 9 m",
        esperado = "Fase READY, sin polígono, mensaje que recomienda «Medir un ambiente»",
    )
    fun recorridoMuyPequeno() = runBlocking {
        val gps = MutableSharedFlow<GpsSample>(extraBufferCapacity = 100)
        val vm = vm(gps)
        vm.calibrate()
        repeat(3) { gps.emit(at(0.0, 0.0, it.toDouble(), 3.0)) }
        vm.startRecording()
        listOf(9.0 to 0.0, 9.0 to 9.0, 0.0 to 9.0, 0.0 to 0.0).forEachIndexed { k, (x, y) -> gps.emit(at(x, y, 10.0 * (k + 1), 8.0)) }
        vm.finish()
        val s = vm.state.value
        ev.obtenido = "fase=${s.phase}, polígono=${s.polygon.size}, error=${s.error}"
        assertEquals(GpsPhase.READY, s.phase)
        assertTrue(s.polygon.isEmpty())
        assertTrue(s.error!!.contains("Medir un ambiente"))
    }

    @Test
    @Evidencia(
        id = "FU-72", nombre = "Terreno GPS que se cruza consigo mismo", requisito = "RF-08 / EX-06",
        objetivo = "Verificar que se avisa cuando el recorrido forma un polígono cruzado (por ejemplo, ida y vuelta en cruz).",
        entrada = "Recorrido de 100 × 60 m en forma de moño: (0;0) → (100;60) → (100;0) → (0;60), precisión 3 m",
        esperado = "Fase FINISHED con aviso de que el terreno se cruza consigo mismo",
    )
    fun terrenoCruzado() = runBlocking {
        val gps = MutableSharedFlow<GpsSample>(extraBufferCapacity = 1_000)
        val vm = vm(gps)
        vm.calibrate()
        repeat(3) { gps.emit(at(0.0, 0.0, it.toDouble(), 3.0)) }
        vm.startRecording()
        val esquinas = listOf(0.0 to 0.0, 100.0 to 60.0, 100.0 to 0.0, 0.0 to 60.0, 0.0 to 0.0)
        var t = 10.0
        for (i in 0 until esquinas.size - 1) {
            val (x1, y1) = esquinas[i]
            val (x2, y2) = esquinas[i + 1]
            for (k in 1..20) {
                val f = k / 20.0
                gps.emit(at(x1 + (x2 - x1) * f, y1 + (y2 - y1) * f, t, 3.0))
                t += 5.0
            }
        }
        vm.finish()
        val s = vm.state.value
        ev.obtenido = "fase=${s.phase}, simple=${s.analysis?.isSimple}, error=${s.error}"
        assertEquals(GpsPhase.FINISHED, s.phase)
        assertTrue(s.error!!.contains("se cruza"))
    }
}
