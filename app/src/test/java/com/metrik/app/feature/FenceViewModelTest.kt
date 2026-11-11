package com.metrik.app.feature

import com.metrik.app.core.geometry.Point
import com.metrik.app.data.remote.BudgetDto
import com.metrik.app.data.remote.FenceInDto
import com.metrik.app.data.remote.FenceOutDto
import com.metrik.app.data.remote.TakeoffItemDto
import com.metrik.app.data.remote.missingLabels
import com.metrik.app.data.repository.FenceRepository
import com.metrik.app.domain.LocalSurvey
import com.metrik.app.domain.SurveyMode
import com.metrik.app.feature.fence.FenceViewModel
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

/** Pantalla «Cerco perimétrico» (CU-09, RF-24, RF-27). */
class FenceViewModelTest {
    @get:Rule val ev = EvidenceRule()
    @get:Rule val main = MainDispatcherRule()

    private class FakeFences : FenceRepository {
        val requests = mutableListOf<Pair<Int, FenceInDto>>()
        override suspend fun calculate(remoteId: Int, body: FenceInDto): FenceOutDto {
            requests += remoteId to body
            val items = listOf(
                TakeoffItemDto("posts_total", "Postes en total", "und", 107.0, 106.0, 112.0, 112.0, true),
                TakeoffItemDto("posts_line", "Postes de línea", "und", 98.0, 97.0, 102.0, 102.0, false),
                TakeoffItemDto("wire_rolls", "Rollos de alambre de 200 m", "rollo", 7.0, 7.0, 7.0, 7.0, true),
            )
            val budget = body.prices?.let {
                BudgetDto(emptyList(), 3640.0, 10.0, 364.0, 5.0, 182.0, 4186.0, 18.0, 753.48, 4939.48, listOf("wire_rolls"), "PEN")
            }
            return FenceOutDto(remoteId, body.kind, 320.0, 9.6, 316.0, items, listOf("supuesto"), listOf("Metrado referencial"), budget)
        }
    }

    private suspend fun terreno(repo: FakeSurveyRepository) = repo.saveLocal(
        LocalSurvey(
            projectId = 1, name = "Fundo", mode = SurveyMode.EXTERIOR,
            vertices = listOf(Point(0.0, 0.0), Point(100.0, 0.0), Point(100.0, 60.0), Point(0.0, 60.0)),
            areaM2 = 6000.0, perimeterM = 320.0, scaleDenominator = 500,
        ),
    )

    @Test
    @Evidencia(
        id = "FU-53", nombre = "Calcular el metrado del cerco desde la app", requisito = "RF-27 (CU-09)",
        objetivo = "Verificar que la pantalla sincroniza el terreno, envía el tipo de cerco, el portón y los parámetros, y muestra el metrado.",
        entrada = "Terreno solo en el celular; cerco de alambre, 5 hilos, postes cada 2,5 m y portón de '4,5' m en el lado 2",
        esperado = "Una petición al id remoto con kind=wire, strands=5, post_spacing_m=2.5 y portón (side=1, 4.5 m); 3 partidas mostradas",
    )
    fun calcularMetrado() = runBlocking {
        val repo = FakeSurveyRepository()
        val fences = FakeFences()
        val vm = FenceViewModel(terreno(repo), repo, fences)
        withTimeout(5_000) { vm.state.first { it.survey != null } }
        vm.setKind("wire"); vm.setStrands("5"); vm.setPostSpacing("2,5")
        vm.addGate(); vm.updateGate(0, side = 1, width = "4,5")
        vm.calculate()
        val s = withTimeout(5_000) { vm.state.first { it.result != null || it.error != null } }
        val (remoteId, body) = fences.requests.single()
        ev.obtenido = "id=$remoteId, body=$body, partidas=${s.result?.items?.size}, error=${s.error}"
        assertEquals(s.survey!!.remoteId, remoteId)
        assertEquals("wire", body.kind)
        assertEquals(5, body.strands)
        assertEquals(2.5, body.postSpacingM, 1e-9)
        assertEquals(1, body.gates.single().side)
        assertEquals(4.5, body.gates.single().widthM, 1e-9)
        assertNull(body.prices)
        assertEquals(3, s.result!!.items.size)
    }

    @Test
    @Evidencia(
        id = "FU-54", nombre = "Presupuesto del cerco con precios ingresados", requisito = "RF-24 (CU-09)",
        objetivo = "Verificar que solo se envían precios válidos de partidas presupuestables y que se muestra el presupuesto.",
        entrada = "Precios: postes '25', línea '20' (informativa), rollos 'abc'; luego 'Presupuestar'",
        esperado = "Se envía solo {posts_total=25.0}; el presupuesto muestra total S/ 4939,48",
    )
    fun presupuestoConPrecios() = runBlocking {
        val repo = FakeSurveyRepository()
        val fences = FakeFences()
        val vm = FenceViewModel(terreno(repo), repo, fences)
        withTimeout(5_000) { vm.state.first { it.survey != null } }
        vm.calculate()
        withTimeout(5_000) { vm.state.first { it.result != null } }
        vm.setPrice("posts_total", "25"); vm.setPrice("posts_line", "20"); vm.setPrice("wire_rolls", "abc")
        vm.calculateBudget()
        val s = withTimeout(5_000) { vm.state.first { it.result?.budget != null || it.error != null } }
        val prices = fences.requests.last().second.prices
        ev.obtenido = "precios enviados=$prices, total=${s.result?.budget?.total}"
        assertEquals(mapOf("posts_total" to 25.0), prices)
        assertNotNull(s.result!!.budget)
        assertEquals(4939.48, s.result!!.budget!!.total, 1e-9)
    }

    @Test
    @Evidencia(
        id = "FU-55", nombre = "Datos inválidos del cerco", requisito = "RF-27",
        objetivo = "Verificar que la pantalla no llama al servidor si el ancho del portón o la altura no son números válidos.",
        entrada = "Portón con ancho 'x'; luego altura del muro vacía",
        esperado = "Mensaje de error y 0 peticiones al servidor",
    )
    fun datosInvalidos() = runBlocking {
        val repo = FakeSurveyRepository()
        val fences = FakeFences()
        val vm = FenceViewModel(terreno(repo), repo, fences)
        withTimeout(5_000) { vm.state.first { it.survey != null } }
        vm.addGate(); vm.updateGate(0, side = 0, width = "x")
        vm.calculate()
        val e1 = vm.state.value.error
        vm.removeGate(0); vm.setKind("brick"); vm.setWallHeight("")
        vm.calculate()
        val e2 = vm.state.value.error
        ev.obtenido = "errores=($e1, $e2), peticiones=${fences.requests.size}"
        assertNotNull(e1); assertNotNull(e2)
        assertTrue(fences.requests.isEmpty())
    }

    @Test
    @Evidencia(
        id = "FU-59", nombre = "Metrado del cerco desactualizado", requisito = "RF-27",
        objetivo = "Verificar que al cambiar un parámetro del cerco se borra el metrado anterior, que ya no corresponde.",
        entrada = "Metrado calculado; luego hilos '6' y, tras recalcular, ancho de portón '3'",
        esperado = "El resultado queda vacío después de cada cambio; los precios escritos se conservan",
    )
    fun metradoDesactualizado() = runBlocking {
        val repo = FakeSurveyRepository()
        val fences = FakeFences()
        val vm = FenceViewModel(terreno(repo), repo, fences)
        withTimeout(5_000) { vm.state.first { it.survey != null } }
        vm.calculate()
        withTimeout(5_000) { vm.state.first { it.result != null } }
        vm.setPrice("posts_total", "25")
        vm.setStrands("6")
        val tras1 = vm.state.value
        vm.addGate()
        vm.calculate()
        withTimeout(5_000) { vm.state.first { it.result != null } }
        vm.updateGate(0, side = 0, width = "3")
        val tras2 = vm.state.value
        ev.obtenido = "resultado tras hilos=${tras1.result}, tras portón=${tras2.result}, precios=${tras2.prices}"
        assertNull(tras1.result)
        assertNull(tras2.result)
        assertEquals("25", tras2.prices["posts_total"])
    }

    @Test
    @Evidencia(
        id = "FU-61", nombre = "Partidas sin precio con su nombre", requisito = "RF-24",
        objetivo = "Verificar que las partidas que quedaron fuera del presupuesto se muestran por su descripción.",
        entrada = "Presupuesto con missing_prices=[wire_rolls, clave_desconocida]",
        esperado = "[Rollos de alambre de 200 m, clave_desconocida]",
    )
    fun partidasSinPrecio() {
        val items = listOf(TakeoffItemDto("wire_rolls", "Rollos de alambre de 200 m", "rollo", 7.0, 7.0, 7.0, 7.0, true))
        val budget = BudgetDto(emptyList(), 0.0, 10.0, 0.0, 5.0, 0.0, 0.0, 18.0, 0.0, 0.0, listOf("wire_rolls", "clave_desconocida"))
        val labels = budget.missingLabels(items)
        ev.obtenido = labels.toString()
        assertEquals(listOf("Rollos de alambre de 200 m", "clave_desconocida"), labels)
    }
}
