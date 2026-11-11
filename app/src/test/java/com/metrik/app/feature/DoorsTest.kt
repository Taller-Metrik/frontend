package com.metrik.app.feature

import com.metrik.app.core.geometry.Point
import com.metrik.app.data.remote.FloorPlanInDto
import com.metrik.app.data.remote.FloorPlanOutDto
import com.metrik.app.data.repository.FloorPlanRepository
import com.metrik.app.domain.CaptureInfo
import com.metrik.app.domain.DoorMark
import com.metrik.app.domain.LocalSurvey
import com.metrik.app.domain.SurveyMode
import com.metrik.app.feature.house.HouseViewModel
import com.metrik.app.feature.measure.MeasureViewModel
import com.metrik.app.testing.EvidenceRule
import com.metrik.app.testing.Evidencia
import com.metrik.app.testing.FakeSurveyRepository
import com.metrik.app.testing.MainDispatcherRule
import com.metrik.app.testing.SimulatedOdometrySource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Puertas: el pasillo como referencia para unir los ambientes automáticamente (CU-08). */
class DoorsTest {
    @get:Rule val ev = EvidenceRule()
    @get:Rule val main = MainDispatcherRule()

    private fun rect(w: Double, h: Double) = listOf(Point(0.0, 0.0), Point(w, 0.0), Point(w, h), Point(0.0, h))

    @Test
    @Evidencia(
        id = "FU-68", nombre = "Marcar puertas al medir y empezar un ambiente en una puerta", requisito = "RF-25 (CU-08)",
        objetivo = "Verificar que «Puerta aquí» guarda la posición actual con su nombre, que se ofrecen las puertas ya "
            + "marcadas en el proyecto y que el ambiente guarda en qué puerta empezó.",
        entrada = "Pasillo guardado con la puerta «Sala»; aula 5×4 m simulada: puerta «Cocina» a los 2,5 m del primer muro; "
            + "la medición empieza en la puerta «Sala»",
        esperado = "Puertas ofrecidas = [Sala]; puerta Cocina en ≈ (2,5; 0); capture.startDoor = Sala",
    )
    fun marcarPuertas() = runTest {
        val repo = FakeSurveyRepository()
        repo.saveLocal(LocalSurvey(projectId = 1, name = "Pasillo", mode = SurveyMode.INTERIOR, vertices = rect(10.0, 1.2),
            areaM2 = 12.0, perimeterM = 22.4, scaleDenominator = 50,
            capture = CaptureInfo("steps", 0.0, 60.0, doors = listOf(DoorMark("Sala", 2.0, 1.2)))))
        val vm = MeasureViewModel(projectId = 1, surveys = repo)
        val ofrecidas = withTimeout(5_000) { vm.state.first { it.availableDoors.isNotEmpty() } }.availableDoors
        vm.setStartDoor("Sala")
        vm.start(SimulatedOdometrySource(timeScale = 1000.0, noiseM = 0.0))
        withTimeout(20_000) { vm.state.first { it.totalM > 2.5 } }
        vm.markDoor("Cocina")
        for (d in listOf(4.95, 8.95, 13.95, 17.9)) {
            withTimeout(20_000) { vm.state.first { it.totalM > d } }
            vm.markCorner()
        }
        vm.finish()
        vm.save("Aula")
        withTimeout(5_000) { vm.state.first { it.savedLocalId != null } }
        val capture = repo.saved.value.values.single { it.name == "Aula" }.capture!!
        val puerta = capture.doors.single()
        ev.obtenido = "ofrecidas=$ofrecidas, puerta=${puerta.name} (${"%.2f".format(puerta.x)}; ${"%.2f".format(puerta.y)}), " +
            "empieza en=${capture.startDoor}"
        assertEquals(listOf("Sala"), ofrecidas)
        assertEquals("Cocina", puerta.name)
        assertEquals(2.5, puerta.x, 0.3)
        assertEquals(0.0, puerta.y, 0.1)
        assertEquals("Sala", capture.startDoor)
    }

    @Test
    @Evidencia(
        id = "FU-69", nombre = "Unir automáticamente por las puertas", requisito = "RF-25 (CU-08)",
        objetivo = "Verificar que la app elige el pasillo como base y arma una unión por cada ambiente que empezó en una "
            + "puerta marcada, enviando los puntos de las puertas (sin número de muro).",
        entrada = "Pasillo con puertas «Sala» (2; 1,2) y «Cocina» (8; 1,2); Sala y Cocina empiezan en su puerta (2; 0) y (1,5; 0); "
            + "un Baño sin puerta",
        esperado = "Selección [Pasillo, Sala, Cocina]; 2 uniones con a_point/b_point y sin a_wall/b_wall",
    )
    fun unirAutomaticamente() = runBlocking {
        val repo = FakeSurveyRepository()
        val pasillo = repo.saveLocal(LocalSurvey(projectId = 7, name = "Pasillo", mode = SurveyMode.INTERIOR, vertices = rect(10.0, 1.2),
            areaM2 = 12.0, perimeterM = 22.4, scaleDenominator = 50, remoteId = 1, createdAt = 1,
            capture = CaptureInfo("steps", 0.0, 60.0, doors = listOf(DoorMark("Sala", 2.0, 1.2), DoorMark("Cocina", 8.0, 1.2)))))
        val sala = repo.saveLocal(LocalSurvey(projectId = 7, name = "Sala", mode = SurveyMode.INTERIOR, vertices = rect(4.0, 3.0),
            points = listOf(Point(2.0, 0.0), Point(4.0, 0.0)), areaM2 = 12.0, perimeterM = 14.0, scaleDenominator = 50, remoteId = 2,
            createdAt = 2, capture = CaptureInfo("arcore", 0.0, 60.0, startDoor = "Sala")))
        val cocina = repo.saveLocal(LocalSurvey(projectId = 7, name = "Cocina", mode = SurveyMode.INTERIOR, vertices = rect(3.0, 3.0),
            points = listOf(Point(1.5, 0.0), Point(3.0, 0.0)), areaM2 = 9.0, perimeterM = 12.0, scaleDenominator = 50, remoteId = 3,
            createdAt = 3, capture = CaptureInfo("steps", 0.0, 60.0, startDoor = "Cocina")))
        repo.saveLocal(LocalSurvey(projectId = 7, name = "Baño", mode = SurveyMode.INTERIOR, vertices = rect(2.0, 2.0),
            areaM2 = 4.0, perimeterM = 8.0, scaleDenominator = 50, remoteId = 4, createdAt = 4))
        val sent = mutableListOf<FloorPlanInDto>()
        val plans = object : FloorPlanRepository {
            override suspend fun plan(projectId: Int, body: FloorPlanInDto): FloorPlanOutDto {
                sent += body
                return FloorPlanOutDto(projectId, emptyList(), 0.0)
            }
            override suspend fun dxf(projectId: Int, body: FloorPlanInDto) = ByteArray(0)
        }
        val vm = HouseViewModel(7, repo, plans) { n, _ -> n }
        withTimeout(5_000) { vm.state.first { it.rooms.size == 4 } }
        vm.autoJoin()
        val s = vm.state.value
        vm.calculate()
        withTimeout(5_000) { vm.state.first { it.result != null || it.error != null } }
        val body = sent.single()
        ev.obtenido = "selección=${s.selected.map { id -> s.room(id)?.name }}, uniones=${body.joins}"
        assertEquals(listOf(pasillo, sala, cocina), s.selected)
        assertEquals(listOf(1, 2, 3), body.rooms)
        assertEquals(2, body.joins.size)
        val j = body.joins.first { it.bSurvey == 2 }
        assertEquals(1, j.aSurvey)
        assertNull(j.aWall)
        assertNull(j.bWall)
        assertEquals(listOf(2.0, 1.2), j.aPoint)
        assertEquals(listOf(2.0, 0.0), j.bPoint)
        assertTrue(body.joins.any { it.bSurvey == 3 && it.aPoint == listOf(8.0, 1.2) })
    }
}
