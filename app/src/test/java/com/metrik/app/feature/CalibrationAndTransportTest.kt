package com.metrik.app.feature

import com.metrik.app.data.repository.CalibrationRepository
import com.metrik.app.domain.GpsSample
import com.metrik.app.feature.calibration.CalPhase
import com.metrik.app.feature.calibration.CalibrationViewModel
import com.metrik.app.gps.LocationSource
import com.metrik.app.gps.Transport
import com.metrik.app.testing.EvidenceRule
import com.metrik.app.testing.Evidencia
import com.metrik.app.testing.FakeSessionStore
import com.metrik.app.testing.MainDispatcherRule
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CalibrationAndTransportTest {
    @get:Rule val ev = EvidenceRule()
    @get:Rule val main = MainDispatcherRule()

    @Test
    @Evidencia(
        id = "FU-23", nombre = "Filtro GPS según el transporte", requisito = "RF-08",
        objetivo = "Verificar que cada transporte descarta lecturas imposibles o imprecisas para su velocidad.",
        entrada = "Salto de 50 m en 1 s; lectura con precisión de 14 m",
        esperado = "Caminata rechaza ambos; carro acepta el salto y la precisión de 14 m",
    )
    fun filtroTransporte() {
        val a = GpsSample(-8.13, -79.04, 4.0, 0.0)
        val salto = GpsSample(-8.13 + 50 / 111_195.0, -79.04, 4.0, 1.0)
        val impreciso = GpsSample(-8.13, -79.04, 14.0, 1.0)
        val walk = Transport.of("walk")
        val car = Transport.of("car")
        ev.obtenido = "caminata: salto=${Transport.accept(walk, a, salto)}, 14 m=${Transport.accept(walk, a, impreciso)}; " +
            "carro: salto=${Transport.accept(car, a, salto)}, 14 m=${Transport.accept(car, a, impreciso)}"
        assertFalse(Transport.accept(walk, a, salto))
        assertFalse(Transport.accept(walk, a, impreciso))
        assertTrue(Transport.accept(car, a, salto))
        assertTrue(Transport.accept(car, a, impreciso))
    }

    @Test
    @Evidencia(
        id = "FU-24", nombre = "Calibración del largo de paso", requisito = "Calibración",
        objetivo = "Verificar que el largo de paso se calcula con la distancia real y se guarda en el celular y en el servidor.",
        entrada = "10,2 m caminados con 15 pasos detectados", esperado = "0,68 m guardado en la sesión y enviado como step_length",
    )
    fun largoDePaso() {
        val session = FakeSessionStore()
        val saved = mutableListOf<Pair<String, Map<String, Double>>>()
        val repo = object : CalibrationRepository {
            override suspend fun save(kind: String, params: Map<String, Double>) { saved += kind to params }
        }
        val steps = MutableSharedFlow<Unit>(extraBufferCapacity = 100)
        val loc = object : LocationSource { override val samples = emptyFlow<GpsSample>() }
        val vm = CalibrationViewModel(session, repo, steps, null, loc)
        vm.onDistance("10,2")
        vm.startSteps()
        repeat(15) { steps.tryEmit(Unit) }
        vm.finishSteps()
        val s = vm.state.value
        ev.obtenido = "pasos=${s.steps}, largo=${"%.3f".format(s.stepLengthM)} m, sesión=${session.session.value.stepLengthM}, enviado=${saved.map { it.first }}"
        assertEquals(CalPhase.DONE, s.stepPhase)
        assertEquals(0.68, s.stepLengthM!!, 1e-9)
        assertEquals(listOf("step_length"), saved.map { it.first })
    }
}
