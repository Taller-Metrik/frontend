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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Pruebas del flujo "Fijar esquina" en la pantalla GPS (RF-18, RF-19, RF-21). */
class GpsCornersViewModelTest {
    @get:Rule val ev = EvidenceRule()
    @get:Rule val main = MainDispatcherRule()

    @Test
    @Evidencia(
        id = "FU-48", nombre = "Medir un terreno fijando esquinas en la app", requisito = "RF-18 / RF-19 / RF-21",
        objetivo = "Verificar el flujo completo: recorrer, fijar 4 esquinas, volver a fijar el inicio, finalizar y guardar.",
        entrada = "Terreno 100×60 m simulado: 60 lecturas quietas por esquina (σ=1,5 m) y caminata entre esquinas",
        esperado = "4 esquinas y cierre fijados; área 6000 ± 3 % con su U95; se guardan las 300 lecturas de esquina con vertex_id 0..3",
    )
    fun fijarEsquinasYGuardar() = runBlocking {
        val gps = MutableSharedFlow<GpsSample>(extraBufferCapacity = 10_000)
        val source = object : LocationSource { override val samples = gps }
        val repo = FakeSurveyRepository()
        val vm = GpsViewModel(1, source, repo, calibrationTarget = 3, cornerDwellSamples = 60)

        vm.calibrate()
        repeat(3) { gps.emit(GpsPrecisionTest.at(0.0, 0.0, it.toDouble(), 2.27)) }
        assertEquals(GpsPhase.READY, vm.state.value.phase)
        vm.startRecording()

        // El usuario toca "Fijar esquina" al llegar a cada esquina; la app etiqueta las lecturas
        var previous: Int? = null
        var visits = 0
        for (s in GpsPrecisionTest.simulateStopAndGo(seed = 4, dwell = 60)) {
            if (s.vertexId != null && s.vertexId != previous) {
                if (visits < 4) vm.fixCorner() else vm.fixStartAgain()
                visits++
            }
            previous = s.vertexId
            gps.emit(s.copy(vertexId = null))
        }
        val recording = vm.state.value
        vm.finish()
        val done = vm.state.value
        vm.save("Terreno esquinas")
        withTimeout(5_000) { vm.state.first { it.savedLocalId != null } }
        val saved = repo.saved.value.values.single()
        val cornerSamples = saved.gps!!.filter { it.vertexId != null }

        ev.obtenido = "esquinas=${recording.cornersFixed}, cierre=${recording.closureFixed}, " +
            "área=${"%.1f".format(done.analysis?.areaM2)} ± ${"%.1f".format(done.uncertainty?.areaU95M2)} m², " +
            "lecturas de esquina guardadas=${cornerSamples.size}"
        assertEquals(4, recording.cornersFixed)
        assertTrue(recording.closureFixed)
        assertNull(recording.fixingVertex)
        assertEquals(6000.0, done.analysis!!.areaM2, 180.0)
        assertNotNull(done.uncertainty)
        assertEquals(300, cornerSamples.size)
        assertEquals(setOf(0, 1, 2, 3), cornerSamples.map { it.vertexId }.toSet())
        assertEquals(saved.gps.sortedBy { it.timestampS }, saved.gps)
    }

    @Test
    @Evidencia(
        id = "FU-49", nombre = "Volver a fijar el inicio exige 3 esquinas", requisito = "RF-19",
        objetivo = "Verificar que no se puede cerrar el recorrido antes de fijar al menos 3 esquinas.",
        entrada = "Recorrido con solo 1 esquina fijada y luego 'Volver a fijar el inicio'",
        esperado = "No inicia la fijación y muestra un mensaje de error",
    )
    fun cierreRequiereTresEsquinas() = runBlocking {
        val gps = MutableSharedFlow<GpsSample>(extraBufferCapacity = 1_000)
        val vm = GpsViewModel(1, object : LocationSource { override val samples = gps }, FakeSurveyRepository(),
            calibrationTarget = 3, cornerDwellSamples = 5)
        vm.calibrate()
        repeat(3) { gps.emit(GpsPrecisionTest.at(0.0, 0.0, it.toDouble(), 2.27)) }
        vm.startRecording()
        vm.fixCorner()
        repeat(5) { gps.emit(GpsPrecisionTest.at(0.0, 0.0, 10.0 + it, 2.27)) }
        vm.fixStartAgain()
        val s = vm.state.value
        ev.obtenido = "esquinas=${s.cornersFixed}, fijando=${s.fixingVertex}, error=${s.error}"
        assertEquals(1, s.cornersFixed)
        assertNull(s.fixingVertex)
        assertNotNull(s.error)
    }

    @Test
    @Evidencia(
        id = "FU-50", nombre = "Finalizar a mitad de una esquina no la cuenta", requisito = "RF-18 / RF-19",
        objetivo = "Verificar que las lecturas de una esquina incompleta se descartan al finalizar o al cancelar.",
        entrada = "4 esquinas fijadas; se empieza a fijar el inicio otra vez y se finaliza con 10 de 60 lecturas. " +
            "Además, otra esquina cancelada a la mitad",
        esperado = "Sin cierre (la fijación incompleta se descarta), área calculada con las 4 esquinas y 240 lecturas de esquina guardadas",
    )
    fun esquinaIncompletaSeDescarta() = runBlocking {
        val gps = MutableSharedFlow<GpsSample>(extraBufferCapacity = 10_000)
        val repo = FakeSurveyRepository()
        val vm = GpsViewModel(1, object : LocationSource { override val samples = gps }, repo,
            calibrationTarget = 3, cornerDwellSamples = 60)
        vm.calibrate()
        repeat(3) { gps.emit(GpsPrecisionTest.at(0.0, 0.0, it.toDouble(), 2.27)) }
        vm.startRecording()
        val sim = GpsPrecisionTest.simulateStopAndGo(seed = 4, dwell = 60, close = false)
        var previous: Int? = null
        for (s in sim) {
            if (s.vertexId != null && s.vertexId != previous) {
                if (s.vertexId == 2) {
                    // Primer intento en la esquina 3: se cancela a la mitad y se vuelve a fijar
                    vm.fixCorner()
                    repeat(30) { gps.emit(s.copy(vertexId = null)) }
                    vm.cancelCorner()
                }
                vm.fixCorner()
            }
            previous = s.vertexId
            gps.emit(s.copy(vertexId = null))
        }
        vm.fixStartAgain()
        repeat(10) { gps.emit(GpsPrecisionTest.at(0.0, 0.0, 10_000.0 + it, 2.27)) }
        vm.finish()
        val done = vm.state.value
        vm.save("Terreno")
        withTimeout(5_000) { vm.state.first { it.savedLocalId != null } }
        val corner = repo.saved.value.values.single().gps!!.filter { it.vertexId != null }
        ev.obtenido = "cierre=${done.closureM}, área=${"%.1f".format(done.analysis?.areaM2)} m², lecturas de esquina=${corner.size}"
        assertNull(done.closureM)
        assertEquals(6000.0, done.analysis!!.areaM2, 180.0)
        assertEquals(240, corner.size)
    }
}
