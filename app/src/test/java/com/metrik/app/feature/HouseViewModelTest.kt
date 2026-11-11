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
        objetivo = "Verificar que la pantalla no llama al servidor sin ambientes o con un espesor inválido, que quitar "
            + "un ambiente borra sus uniones y que cambiar la selección borra la planta anterior.",
        entrada = "Sin ambientes; luego espesor 'x'; luego se quita la Cocina (que estaba unida)",
        esperado = "Dos mensajes de error y 0 peticiones; la unión con la Cocina se borra; la planta se borra al quitar un ambiente",
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
        val sinPeticiones = plans.requests.isEmpty()
        vm.updateJoin(0, JoinInput(a = sala, aWall = 0, b = cocina, bWall = 0, thickness = "0.15"))
        // Quitar un ambiente borra sus uniones (no quedan uniones con ambientes que no se eligieron)
        vm.toggleRoom(cocina)
        val e3 = if (vm.state.value.joins.isEmpty()) "uniones borradas" else null
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
    @Test
    @Evidencia(
        id = "FU-80", nombre = "Contorno de la casa comparado con la suma de los ambientes", requisito = "RF-25 (CU-08)",
        objetivo = "Verificar que el contorno de la vivienda (medido caminando su borde) se reconoce por su nombre, no se "
            + "ofrece como ambiente para unir y se compara su área y perímetro con la suma de los ambientes.",
        entrada = "Sala 12 m² y Cocina 9 m² (interiores) y «Contorno de la casa» 24 m², perímetro 20 m; un terreno GPS",
        esperado = "Ambientes = Sala y Cocina; contorno elegido solo; suma 21 m², diferencia 3 m² (12,5 %); al elegir "
            + "solo la Sala, la suma es 12 m²",
    )
    fun contornoDeLaCasa() = runBlocking {
        val repo = FakeSurveyRepository()
        val (sala, _, _) = casa(repo)
        repo.saveLocal(LocalSurvey(projectId = 7, name = "Contorno de la casa", mode = SurveyMode.INTERIOR, vertices = rect(6.0, 4.0),
            areaM2 = 24.0, perimeterM = 20.0, scaleDenominator = 50))
        val vm = HouseViewModel(7, repo, FakeFloorPlans()) { name, _ -> name }
        val s = withTimeout(5_000) { vm.state.first { it.contour != null } }
        val c = vm.comparison()!!
        vm.toggleRoom(sala)
        val soloSala = vm.comparison()!!
        ev.obtenido = "ambientes=${s.rooms.map { it.name }}, contorno=${s.contour?.name}, " +
            "suma=${c.roomsAreaM2}, diferencia=${c.differenceM2} (${"%.1f".format(c.differencePct)} %), solo sala=${soloSala.roomsAreaM2}"
        assertEquals(listOf("Sala", "Cocina"), s.rooms.map { it.name })
        assertEquals(24.0, c.contourAreaM2, 1e-9)
        assertEquals(20.0, c.contourPerimeterM, 1e-9)
        assertEquals(21.0, c.roomsAreaM2, 1e-9)
        assertEquals(3.0, c.differenceM2, 1e-9)
        assertEquals(12.5, c.differencePct, 1e-9)
        assertEquals(12.0, soloSala.roomsAreaM2, 1e-9)
    }
    @Test
    @Evidencia(
        id = "FU-89", nombre = "Panel interactivo: arrastrar la cocina junto a la sala y armar la planta", requisito = "RF-25 (CU-08)",
        objetivo = "Verificar que al elegir ambientes aparecen separados en el panel, que al arrastrar uno y soltarlo junto "
            + "a otro se pega con imán y queda la unión, y que la planta se pide al servidor con los puntos de esa unión.",
        entrada = "Sala 4×3 y Cocina 3×3 elegidas; la cocina se arrastra hasta 0,3 m del muro derecho de la sala y se suelta",
        esperado = "2 posiciones iniciales; 1 unión Sala↔Cocina con puntos en ambos muros; petición sin números de muro "
            + "(los decide el punto) y con a_point/b_point",
    )
    fun panelInteractivo() = runBlocking {
        val repo = FakeSurveyRepository()
        val plans = FakeFloorPlans()
        val (sala, cocina, _) = casa(repo)
        val vm = HouseViewModel(7, repo, plans) { name, _ -> name }
        withTimeout(5_000) { vm.state.first { it.rooms.size == 2 } }
        vm.toggleRoom(sala); vm.toggleRoom(cocina)
        val poses = vm.state.value.poses
        // La cocina empieza a 1 m a la derecha de la sala: se la acerca 0,7 m (queda a 0,3 m) y se suelta
        vm.moveRoom(cocina, -0.7, 0.2)
        vm.dropRoom(cocina)
        val s = vm.state.value
        vm.calculate()
        withTimeout(5_000) { vm.state.first { it.result != null || it.error != null } }
        val join = plans.requests.single().second.joins.single()
        ev.obtenido = "posiciones=${poses.size}, uniones=${s.joins.map { "${s.room(it.a)?.name}↔${s.room(it.b)?.name}" }}, " +
            "mensaje=${s.message}, petición=$join"
        assertEquals(2, poses.size)
        assertEquals(1, s.joins.size)
        assertEquals(setOf(sala, cocina), setOf(s.joins.single().a, s.joins.single().b))
        assertNull(join.aWall)
        assertNull(join.bWall)
        assertNotNull(join.aPoint)
        assertNotNull(join.bPoint)
    }
}
