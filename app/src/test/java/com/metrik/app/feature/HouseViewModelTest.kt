package com.metrik.app.feature

import com.metrik.app.core.geometry.Point
import com.metrik.app.data.remote.FloorPlanInDto
import com.metrik.app.data.remote.FloorPlanOutDto
import com.metrik.app.data.remote.PlacedRoomDto
import com.metrik.app.data.repository.FloorPlanRepository
import com.metrik.app.domain.LocalSurvey
import com.metrik.app.domain.SurveyMode
import com.metrik.app.feature.house.HouseViewModel
import com.metrik.app.feature.house.JoinInput
import com.metrik.app.testing.EvidenceRule
import com.metrik.app.testing.Evidencia
import com.metrik.app.testing.FakeSurveyRepository
import com.metrik.app.testing.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Pantalla «Plano de vivienda» con varios ambientes (CU-08, RF-25, RF-26). */
class HouseViewModelTest {
    @get:Rule val ev = EvidenceRule()
    @get:Rule val main = MainDispatcherRule()

    private class FakeFloorPlans : FloorPlanRepository {
        val requests = mutableListOf<Pair<Int, FloorPlanInDto>>()
        val dxfRequests = mutableListOf<Pair<Int, FloorPlanInDto>>()
        override suspend fun plan(projectId: Int, body: FloorPlanInDto): FloorPlanOutDto {
            requests += projectId to body
            val rooms = body.rooms.map { id ->
                PlacedRoomDto(id, "Ambiente $id", listOf(listOf(0.0, 0.0), listOf(4.0, 0.0), listOf(4.0, 3.0)), 6.0)
            }
            return FloorPlanOutDto(projectId, rooms, 6.0 * rooms.size, listOf("Muros unidos de largo distinto"))
        }
        override suspend fun dxf(projectId: Int, body: FloorPlanInDto): ByteArray {
            dxfRequests += projectId to body
            return "0\nSECTION".toByteArray()
        }
    }

    private fun rect(w: Double, h: Double) = listOf(Point(0.0, 0.0), Point(w, 0.0), Point(w, h), Point(0.0, h))

    private suspend fun casa(repo: FakeSurveyRepository): Triple<Long, Long, Long> {
        val sala = repo.saveLocal(LocalSurvey(projectId = 7, name = "Sala", mode = SurveyMode.INTERIOR, vertices = rect(4.0, 3.0),
            areaM2 = 12.0, perimeterM = 14.0, scaleDenominator = 50, remoteId = 41))
        val cocina = repo.saveLocal(LocalSurvey(projectId = 7, name = "Cocina", mode = SurveyMode.INTERIOR, vertices = rect(3.0, 3.0),
            areaM2 = 9.0, perimeterM = 12.0, scaleDenominator = 50))
        val terreno = repo.saveLocal(LocalSurvey(projectId = 7, name = "Terreno", mode = SurveyMode.EXTERIOR, vertices = rect(20.0, 10.0),
            areaM2 = 200.0, perimeterM = 60.0, scaleDenominator = 200))
        return Triple(sala, cocina, terreno)
    }

    @Test
    @Evidencia(
        id = "FU-62", nombre = "Unir ambientes del proyecto desde la app", requisito = "RF-25 (CU-08)",
        objetivo = "Verificar que la pantalla lista solo los ambientes interiores, sincroniza los que faltan y envía "
            + "los ids remotos con las uniones; luego muestra la planta.",
        entrada = "Sala (sincronizada, id 41), Cocina (solo en el celular) y un terreno GPS; unión Sala muro 2 ↔ Cocina muro 4, espesor '0,15'",
        esperado = "Lista con 2 ambientes; petición rooms=[41, 100], join (41, 1, 100, 3, 0.15); planta y advertencia mostradas",
    )
    fun unirAmbientes() = runBlocking {
        val repo = FakeSurveyRepository()
        val plans = FakeFloorPlans()
        val (sala, cocina, _) = casa(repo)
        val vm = HouseViewModel(7, repo, plans) { name, _ -> "Descargas/MetriK/$name" }
        val listados = withTimeout(5_000) { vm.state.first { it.rooms.size == 2 } }.rooms.map { it.name }
        vm.toggleRoom(sala); vm.toggleRoom(cocina)
        vm.addJoin()
        vm.updateJoin(0, JoinInput(a = sala, aWall = 1, b = cocina, bWall = 3, thickness = "0,15"))
        vm.calculate()
        val s = withTimeout(5_000) { vm.state.first { it.result != null || it.error != null } }
        val (projectId, body) = plans.requests.single()
        ev.obtenido = "listados=$listados, body=$body, advertencias=${s.result?.warnings}, error=${s.error}"
        assertEquals(listOf("Sala", "Cocina"), listados)
        assertEquals(7, projectId)
        assertEquals(listOf(41, 100), body.rooms)
        val j = body.joins.single()
        assertEquals(listOf(41, 1, 100, 3), listOf(j.aSurvey, j.aWall, j.bSurvey, j.bWall))
        assertEquals(0.15, j.thicknessM, 1e-9)
        assertEquals(2, s.result!!.rooms.size)
        assertTrue(s.result!!.warnings.isNotEmpty())
    }

    @Test
    @Evidencia(
        id = "FU-63", nombre = "Datos inválidos del plano de vivienda", requisito = "RF-25 / EX-08",
        objetivo = "Verificar que la pantalla no llama al servidor sin ambientes, con un espesor inválido o con una unión "
            + "a un ambiente que no se eligió, y que cambiar la selección borra la planta anterior.",
        entrada = "Sin ambientes; luego espesor 'x'; luego unión con la Cocina sin elegirla",
        esperado = "Tres mensajes de error, 0 peticiones; la planta se borra al quitar un ambiente",
    )
    fun datosInvalidos() = runBlocking {
        val repo = FakeSurveyRepository()
        val plans = FakeFloorPlans()
        val (sala, cocina, _) = casa(repo)
        val vm = HouseViewModel(7, repo, plans) { name, _ -> name }
        withTimeout(5_000) { vm.state.first { it.rooms.size == 2 } }
        vm.calculate()
        val e1 = vm.state.value.error
        vm.toggleRoom(sala); vm.toggleRoom(cocina); vm.addJoin()
        vm.updateJoin(0, JoinInput(a = sala, aWall = 0, b = cocina, bWall = 0, thickness = "x"))
        vm.calculate()
        val e2 = vm.state.value.error
        vm.updateJoin(0, JoinInput(a = sala, aWall = 0, b = cocina, bWall = 0, thickness = "0.15"))
        vm.toggleRoom(cocina)
        vm.calculate()
        val e3 = vm.state.value.error
        val sinPeticiones = plans.requests.isEmpty()
        vm.toggleRoom(cocina); vm.calculate()
        withTimeout(5_000) { vm.state.first { it.result != null } }
        vm.toggleRoom(cocina)
        val tras = vm.state.value.result
        ev.obtenido = "errores=($e1, $e2, $e3), sin peticiones=$sinPeticiones, planta tras quitar=$tras"
        assertNotNull(e1); assertNotNull(e2); assertNotNull(e3)
        assertTrue(sinPeticiones)
        assertNull(tras)
    }

    @Test
    @Evidencia(
        id = "FU-64", nombre = "Descargar el DXF de la vivienda", requisito = "RF-26 (CU-08)",
        objetivo = "Verificar que el DXF se pide con los mismos ambientes y uniones y se guarda en Descargas.",
        entrada = "Sala y Cocina unidas; botón «Descargar DXF»",
        esperado = "Una petición DXF al proyecto 7 y archivo metrik_vivienda_7.dxf guardado",
    )
    fun descargarDxf() = runBlocking {
        val repo = FakeSurveyRepository()
        val plans = FakeFloorPlans()
        val (sala, cocina, _) = casa(repo)
        val saved = mutableListOf<Pair<String, ByteArray>>()
        val vm = HouseViewModel(7, repo, plans) { name, bytes -> saved += name to bytes; "Descargas/MetriK/$name" }
        withTimeout(5_000) { vm.state.first { it.rooms.size == 2 } }
        vm.toggleRoom(sala); vm.toggleRoom(cocina); vm.addJoin()
        vm.exportDxf()
        val s = withTimeout(5_000) { vm.state.first { it.message != null || it.error != null } }
        ev.obtenido = "peticiones DXF=${plans.dxfRequests.size}, archivo=${saved.singleOrNull()?.first}, mensaje=${s.message}"
        assertEquals(7, plans.dxfRequests.single().first)
        assertEquals("metrik_vivienda_7.dxf", saved.single().first)
        assertNotNull(s.message)
    }
}
