package com.metrik.app.gps

import com.metrik.app.domain.GpsSample
import com.metrik.app.feature.gps.GpsPhase
import com.metrik.app.feature.gps.GpsViewModel
import com.metrik.app.sensors.WalkingGate
import com.metrik.app.testing.EvidenceRule
import com.metrik.app.testing.Evidencia
import com.metrik.app.testing.FakeSurveyRepository
import com.metrik.app.testing.MainDispatcherRule
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** GPS con el celular quieto: el recorrido no debe avanzar solo (pasos falsos y "paseo" de la señal). */
class GpsQuietoTest {
    @get:Rule val ev = EvidenceRule()
    @get:Rule val main = MainDispatcherRule()

    private fun at(x: Double, y: Double, t: Double, acc: Double = 3.0, speed: Double? = null): GpsSample =
        GpsPrecisionTest.at(x, y, t, acc).copy(speedMps = speed)

    @Test
    @Evidencia(
        id = "FU-65", nombre = "Pasos solo en racha de caminata", requisito = "RF-07",
        objetivo = "Verificar que un movimiento aislado del celular (mirar la pantalla, girar la muñeca) no cuenta como "
            + "paso y que una caminata real no pierde sus primeros pasos.",
        entrada = "Picos aislados cada 2 s; luego 5 pasos cada 0,5 s",
        esperado = "0 pasos por los picos aislados; los 5 pasos de la caminata (los 3 primeros al confirmarse la racha)",
    )
    fun pasosEnRacha() {
        val gate = WalkingGate()
        val aislados = listOf(0L, 2_000L, 4_000L).sumOf { ms -> gate.onStep(ms * 1_000_000, 0.0).size }
        val caminata = (0 until 5).map { k -> gate.onStep((10_000L + 500L * k) * 1_000_000, 0.0).size }
        ev.obtenido = "aislados=$aislados, caminata por paso=$caminata"
        assertEquals(0, aislados)
        assertEquals(listOf(0, 0, 3, 1, 1), caminata)
    }

    @Test
    @Evidencia(
        id = "FU-66", nombre = "Quieto a pie: ni pasos falsos ni paseo del GPS", requisito = "RF-07 / RF-08",
        objetivo = "Verificar que, quieto con el celular en la mano, el recorrido no avanza aunque haya picos del "
            + "acelerómetro y el GPS salte varios metros; y que caminando sí avanza y el GPS corrige solo en movimiento.",
        entrada = "3 picos aislados con lecturas GPS que saltan 6 m (Doppler 0 m/s); luego 5 pasos al norte; "
            + "lectura GPS con Doppler 0,1 m/s y otra con 1,3 m/s",
        esperado = "Quieto: 1 punto y 0 correcciones; caminando: 6 puntos; solo la lectura con 1,3 m/s corrige",
    )
    fun quietoAPie() = runBlocking {
        val gps = MutableSharedFlow<GpsSample>(extraBufferCapacity = 1_000)
        val brujula = MutableSharedFlow<Double>(extraBufferCapacity = 1_000)
        var now = 0L
        val vm = GpsViewModel(1, object : LocationSource { override val samples = gps }, FakeSurveyRepository(),
            calibrationTarget = 3, compassSteps = brujula, stepLengthM = 0.70, nanoClock = { now })
        vm.calibrate()
        repeat(3) { gps.emit(at(0.0, 0.0, it.toDouble())) }
        assertEquals(GpsPhase.READY, vm.state.value.phase)
        vm.startRecording()

        for (k in 0 until 3) {
            now = k * 2_000_000_000L
            brujula.emit(0.0)
            gps.emit(at(6.0 * (k % 2 * 2 - 1), 6.0, 10.0 + k, speed = 0.0))
        }
        val quieto = vm.state.value
        for (k in 0 until 5) {
            now = 20_000_000_000L + k * 500_000_000L
            brujula.emit(0.0)
        }
        val caminando = vm.state.value
        gps.emit(at(0.5, 3.5, 30.0, speed = 0.1))
        val trasLento = vm.state.value.gpsCorrections
        gps.emit(at(0.5, 3.5, 31.0, speed = 1.3))
        val trasRapido = vm.state.value.gpsCorrections

        ev.obtenido = "quieto: puntos=${quieto.track.size}, pasos=${quieto.steps}, correcciones=${quieto.gpsCorrections}; " +
            "caminando: puntos=${caminando.track.size}; correcciones tras 0,1 m/s=$trasLento, tras 1,3 m/s=$trasRapido"
        assertEquals(1, quieto.track.size)
        assertEquals(0, quieto.gpsCorrections)
        assertEquals(6, caminando.track.size)
        assertEquals(0, trasLento)
        assertEquals(1, trasRapido)
    }

    @Test
    @Evidencia(
        id = "FU-67", nombre = "Quieto con GPS solo: el Doppler frena el paseo", requisito = "RF-08",
        objetivo = "Verificar que, sin pasos (sin brújula, o en bici, moto o carro), las lecturas con velocidad Doppler "
            + "casi nula no agregan puntos aunque salten más que su precisión.",
        entrada = "Partida en el origen; 3 lecturas a 8 m (precisión 3 m) con 0,1 m/s; luego una a 20 m con 1,4 m/s",
        esperado = "Las 3 lecturas quietas no se agregan; la de 1,4 m/s sí (2 puntos en total)",
    )
    fun quietoSoloGps() = runBlocking {
        val gps = MutableSharedFlow<GpsSample>(extraBufferCapacity = 1_000)
        val vm = GpsViewModel(1, object : LocationSource { override val samples = gps }, FakeSurveyRepository(), calibrationTarget = 3)
        vm.calibrate()
        repeat(3) { gps.emit(at(0.0, 0.0, it.toDouble())) }
        vm.startRecording()
        for (k in 0 until 3) gps.emit(at(8.0, 0.0, 10.0 * (k + 1), speed = 0.1))
        val quieto = vm.state.value.samples.size
        gps.emit(at(20.0, 0.0, 50.0, speed = 1.4))
        val movido = vm.state.value.samples.size
        ev.obtenido = "puntos quieto=$quieto, tras moverse=$movido"
        assertEquals(1, quieto)
        assertEquals(2, movido)
    }
}
