package com.metrik.app.feature

import com.metrik.app.domain.GpsSample
import com.metrik.app.feature.gps.GpsPhase
import com.metrik.app.feature.gps.GpsViewModel
import com.metrik.app.gps.GpsPrecisionTest
import com.metrik.app.gps.LocationSource
import com.metrik.app.testing.EvidenceRule
import com.metrik.app.testing.Evidencia
import com.metrik.app.testing.FakeSurveyRepository
import com.metrik.app.testing.MainDispatcherRule
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Arranque de la medición GPS: brújula y GPS ya encendidos al empezar a caminar, sin lecturas viejas. */
class GpsArranqueViewModelTest {
    @get:Rule val ev = EvidenceRule()
    @get:Rule val main = MainDispatcherRule()

    @Test
    @Evidencia(
        id = "FU-83", nombre = "Brújula encendida desde la medición de precisión", requisito = "RF-07",
        objetivo = "Verificar que la brújula se enciende al medir la precisión (se asienta mientras la persona está quieta) "
            + "y que no se apaga y vuelve a encender al empezar a grabar (arranque sin asentar).",
        entrada = "Medición a pie: medir precisión con 3 lecturas y empezar a grabar",
        esperado = "Durante la precisión: 1 suscriptor a la brújula; al grabar: sigue 1 (no se reinicia)",
    )
    fun brujulaEncendidaDesdeLaPrecision() = runBlocking {
        val gps = MutableSharedFlow<GpsSample>(extraBufferCapacity = 100)
        val compass = MutableSharedFlow<Double>(extraBufferCapacity = 100)
        val vm = GpsViewModel(1, object : LocationSource { override val samples = gps }, FakeSurveyRepository(), startGate = { com.metrik.app.gps.GpsStartGate.off() },
            calibrationTarget = 3, compassSteps = compass)
        vm.calibrate()
        val compassWhileCalibrating = withTimeout(5_000) { compass.subscriptionCount.first { it > 0 } }
        repeat(3) { gps.emit(GpsPrecisionTest.at(0.0, 0.0, it.toDouble(), 2.5)) }
        withTimeout(5_000) { vm.state.first { it.phase == GpsPhase.READY } }
        vm.startRecording()
        withTimeout(5_000) { gps.subscriptionCount.first { it > 0 } }
        ev.obtenido = "brújula al medir precisión=$compassWhileCalibrating, al grabar=${compass.subscriptionCount.value}"
        assertEquals(1, compassWhileCalibrating)
        assertEquals(1, compass.subscriptionCount.value)
    }

    @Test
    @Evidencia(
        id = "FU-84", nombre = "Una lectura GPS vieja al empezar no entra al recorrido", requisito = "RF-08",
        objetivo = "Verificar que una lectura con hora anterior a la partida (ubicación guardada o atrasada) se descarta "
            + "y no mueve el comienzo del recorrido.",
        entrada = "En bicicleta (solo GPS): precisión con lecturas en t = 0..2 s; al grabar llega una lectura de t = 1,5 s "
            + "a 40 m y luego una nueva de t = 3 s a 8 m",
        esperado = "La lectura vieja se descarta (descartadas = 1) y el recorrido queda con la partida y la lectura nueva",
    )
    fun lecturaViejaDescartada() = runBlocking {
        val gps = MutableSharedFlow<GpsSample>(extraBufferCapacity = 100)
        val vm = GpsViewModel(1, object : LocationSource { override val samples = gps }, FakeSurveyRepository(), startGate = { com.metrik.app.gps.GpsStartGate.off() }, calibrationTarget = 3)
        vm.setTransport("bike")
        vm.calibrate()
        repeat(3) { gps.emit(GpsPrecisionTest.at(0.0, 0.0, it.toDouble(), 2.5)) }
        withTimeout(5_000) { vm.state.first { it.phase == GpsPhase.READY } }
        vm.startRecording()
        withTimeout(5_000) { gps.subscriptionCount.first { it > 0 } }
        gps.emit(GpsPrecisionTest.at(40.0, 40.0, 1.5, 2.5).copy(speedMps = 4.0))
        gps.emit(GpsPrecisionTest.at(8.0, 0.0, 3.0, 2.5).copy(speedMps = 4.0))
        val s = withTimeout(5_000) { vm.state.first { it.samples.size + it.discarded >= 3 } }
        ev.obtenido = "lecturas en el recorrido=${s.samples.size}, descartadas=${s.discarded}, " +
            "último punto=${s.track.lastOrNull()}"
        assertEquals(1, s.discarded)
        assertEquals(2, s.samples.size)
    }
}
