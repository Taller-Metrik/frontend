package com.metrik.app.feature

import com.metrik.app.core.geometry.Point
import com.metrik.app.data.remote.FinishesInDto
import com.metrik.app.data.remote.FinishesOutDto
import com.metrik.app.data.remote.PriceDto
import com.metrik.app.data.remote.TakeoffItemDto
import com.metrik.app.data.repository.FinishesRepository
import com.metrik.app.domain.LocalSurvey
import com.metrik.app.domain.SurveyMode
import com.metrik.app.feature.finishes.FinishesViewModel
import com.metrik.app.testing.EvidenceRule
import com.metrik.app.testing.Evidencia
import com.metrik.app.testing.FakeSurveyRepository
import com.metrik.app.testing.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Pantalla «Metrado de acabados y precios» (CU-07, RF-22 a RF-24). */
class FinishesViewModelTest {
    @get:Rule val ev = EvidenceRule()
    @get:Rule val main = MainDispatcherRule()

    private class FakeFinishes : FinishesRepository {
        val requests = mutableListOf<Pair<Int, FinishesInDto>>()
        val own = mutableMapOf<String, Double>()
        override suspend fun finishes(remoteId: Int, body: FinishesInDto): FinishesOutDto {
            requests += remoteId to body
            val items = listOf(TakeoffItemDto("plaster_m2", "Tarrajeo", "m²", 43.34, 43.34, 43.34, 43.34))
            return FinishesOutDto(remoteId, 20.0, 18.0, body.heightM, items, listOf("supuesto"), listOf("nota"), null, emptyMap())
        }
        override suspend fun prices(): List<PriceDto> = listOf(
            PriceDto("cement_bag", "Cemento", "bolsa", 42.5, "kg", own["cement_bag"] ?: 30.9,
                if ("cement_bag" in own) "user" else "store", 30.9, "Promart", "Cemento Sol", null, "2026-11-10T11:00:00Z", "ok"),
        )
        override suspend fun setPrice(key: String, price: Double) { own[key] = price }
        override suspend fun resetPrice(key: String) { own.remove(key) }
    }

    private suspend fun aula(repo: FakeSurveyRepository) = repo.saveLocal(
        LocalSurvey(
            projectId = 1, name = "Aula", mode = SurveyMode.INTERIOR,
            vertices = listOf(Point(0.0, 0.0), Point(5.0, 0.0), Point(5.0, 4.0), Point(0.0, 4.0)),
            areaM2 = 20.0, perimeterM = 18.0, scaleDenominator = 50,
        ),
    )

    @Test
    @Evidencia(
        id = "FU-56", nombre = "Calcular el metrado de acabados desde la app", requisito = "RF-22 (CU-07)",
        objetivo = "Verificar que la pantalla sincroniza el ambiente y envía altura y vanos; el presupuesto es solo de "
            + "materiales, así que no se envían precios de mano de obra.",
        entrada = "Altura '2,60'; puerta 0,90×2,10 en el muro 1",
        esperado = "Petición con height_m=2.6, 1 vano tipo door y sin labor_prices; resultado mostrado",
    )
    fun calcularAcabados() = runBlocking {
        val repo = FakeSurveyRepository()
        val fin = FakeFinishes()
        val vm = FinishesViewModel(aula(repo), repo, fin)
        withTimeout(5_000) { vm.state.first { it.survey != null } }
        vm.setHeight("2,60")
        vm.addOpening("door"); vm.updateOpening(0, wall = 0, kind = "door", width = "0,90", height = "2,10")
        vm.calculate()
        val s = withTimeout(5_000) { vm.state.first { it.result != null || it.error != null } }
        val body = fin.requests.single().second
        ev.obtenido = "body=$body, error=${s.error}"
        assertEquals(2.6, body.heightM, 1e-9)
        assertEquals("door", body.openings.single().kind)
        assertEquals(0.9, body.openings.single().widthM, 1e-9)
        val json = com.metrik.app.data.remote.MetrikJson.encodeToString(com.metrik.app.data.remote.FinishesInDto.serializer(), body)
        assertFalse(json.contains("labor_prices"))
        assertNotNull(s.result)
    }

    @Test
    @Evidencia(
        id = "FU-57", nombre = "Vano inválido en acabados", requisito = "RF-22 / EX-08",
        objetivo = "Verificar que la pantalla no llama al servidor si el vano o la altura no son números válidos.",
        entrada = "Ventana con alto vacío; luego altura '1'",
        esperado = "Mensaje de error y 0 peticiones",
    )
    fun vanoInvalido() = runBlocking {
        val repo = FakeSurveyRepository()
        val fin = FakeFinishes()
        val vm = FinishesViewModel(aula(repo), repo, fin)
        withTimeout(5_000) { vm.state.first { it.survey != null } }
        vm.addOpening("window"); vm.updateOpening(0, wall = 1, kind = "window", width = "1,2", height = "")
        vm.calculate()
        val e1 = vm.state.value.error
        vm.removeOpening(0); vm.setHeight("1")
        vm.calculate()
        val e2 = vm.state.value.error
        ev.obtenido = "errores=($e1, $e2), peticiones=${fin.requests.size}"
        assertNotNull(e1); assertNotNull(e2)
        assertTrue(fin.requests.isEmpty())
    }

    @Test
    @Evidencia(
        id = "FU-58", nombre = "Precio propio de un material", requisito = "RF-23",
        objetivo = "Verificar que el usuario reemplaza el precio de la tienda por el suyo y puede volver al de la tienda.",
        entrada = "Catálogo con cemento a S/ 30,90 (Promart); precio propio '28,50'; luego 'Usar precio de tienda'",
        esperado = "Cemento 28,50 (propio) y luego 30,90 (tienda)",
    )
    fun precioPropio() = runBlocking {
        val repo = FakeSurveyRepository()
        val fin = FakeFinishes()
        val vm = FinishesViewModel(aula(repo), repo, fin)
        withTimeout(5_000) { vm.state.first { it.prices.isNotEmpty() } }
        vm.setOwnPrice("cement_bag", "28,50")
        val propio = withTimeout(5_000) { vm.state.first { it.prices.single().source == "user" } }.prices.single()
        vm.resetPrice("cement_bag")
        val tienda = withTimeout(5_000) { vm.state.first { it.prices.single().source == "store" } }.prices.single()
        ev.obtenido = "propio=${propio.price} (${propio.source}), tienda=${tienda.price} (${tienda.source})"
        assertEquals(28.5, propio.price!!, 1e-9)
        assertEquals(30.9, tienda.price!!, 1e-9)
    }

    @Test
    @Evidencia(
        id = "FU-60", nombre = "Presupuesto de acabados desactualizado", requisito = "RF-22 / RF-23",
        objetivo = "Verificar que el metrado y el presupuesto anteriores se borran al cambiar la altura, la mano de obra "
            + "o un precio del catálogo, porque ya no corresponden.",
        entrada = "Metrado calculado; luego altura '2,70'; recalculado; luego precio propio del cemento '28'",
        esperado = "El resultado queda vacío después de cada cambio",
    )
    fun presupuestoDesactualizado() = runBlocking {
        val repo = FakeSurveyRepository()
        val fin = FakeFinishes()
        val vm = FinishesViewModel(aula(repo), repo, fin)
        withTimeout(5_000) { vm.state.first { it.survey != null && it.prices.isNotEmpty() && !it.busy } }
        vm.calculate()
        withTimeout(5_000) { vm.state.first { it.result != null } }
        vm.setHeight("2,70")
        val tras1 = vm.state.value.result
        vm.calculate()
        withTimeout(5_000) { vm.state.first { it.result != null } }
        vm.setOwnPrice("cement_bag", "28")
        val tras2 = withTimeout(5_000) { vm.state.first { it.prices.single().source == "user" && !it.busy } }.result
        ev.obtenido = "resultado tras altura=$tras1, tras precio=$tras2"
        assertNull(tras1)
        assertNull(tras2)
    }
}
