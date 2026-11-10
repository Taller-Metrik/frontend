package com.metrik.app.feature

import com.metrik.app.core.geometry.Point
import com.metrik.app.data.repository.PlanRepository
import com.metrik.app.domain.LocalSurvey
import com.metrik.app.domain.SurveyMode
import com.metrik.app.feature.plan.PlanViewModel
import com.metrik.app.feature.plan.mimeTypeFor
import com.metrik.app.testing.EvidenceRule
import com.metrik.app.testing.Evidencia
import com.metrik.app.testing.FakeSurveyRepository
import com.metrik.app.testing.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Botón «Informe técnico» de la pantalla del plano (CU-10, RF-28). */
class ReportExportTest {
    @get:Rule val ev = EvidenceRule()
    @get:Rule val main = MainDispatcherRule()

    @Test
    @Evidencia(
        id = "FU-51", nombre = "Descargar el informe técnico desde el plano", requisito = "RF-28 (CU-10)",
        objetivo = "Verificar que el botón sincroniza el recorrido si hace falta, pide el PDF al servidor y lo guarda.",
        entrada = "Recorrido 'Aula Ñandú 2' solo en el celular; el servidor devuelve un PDF",
        esperado = "Se sincroniza, se pide el informe del id remoto y se guarda metrik_informe_aula_nandu_2.pdf con los bytes recibidos",
    )
    fun descargarInforme() = runBlocking {
        val repo = FakeSurveyRepository()
        val id = repo.saveLocal(
            LocalSurvey(
                projectId = 1, name = "Aula Ñandú 2", mode = SurveyMode.INTERIOR,
                vertices = listOf(Point(0.0, 0.0), Point(5.0, 0.0), Point(5.0, 4.0), Point(0.0, 4.0)),
                areaM2 = 20.0, perimeterM = 18.0, scaleDenominator = 50,
            ),
        )
        val pdf = "%PDF-1.4 informe".toByteArray()
        val requested = mutableListOf<Int>()
        val plans = object : PlanRepository {
            override suspend fun exportDxf(remoteId: Int, scale: Int) = ByteArray(0)
            override suspend fun ml(remoteId: Int) = error("no usado")
            override suspend fun reportPdf(remoteId: Int): ByteArray { requested += remoteId; return pdf }
        }
        val saved = mutableMapOf<String, ByteArray>()
        val vm = PlanViewModel(id, repo, plans) { name, bytes -> saved[name] = bytes; "Descargas/MetriK/$name" }
        withTimeout(5_000) { vm.state.first { it.survey != null } }

        vm.exportReport()
        val s = withTimeout(5_000) { vm.state.first { it.message != null || it.error != null } }

        ev.obtenido = "pedido para id=$requested, archivos=${saved.keys}, mensaje=${s.message}, error=${s.error}"
        assertEquals(listOf(s.survey!!.remoteId), requested)
        assertTrue(s.survey!!.synced)
        assertEquals(setOf("metrik_informe_aula_nandu_2.pdf"), saved.keys)
        assertTrue(saved.values.single().contentEquals(pdf))
        assertTrue(s.message!!.contains("Informe"))
    }

    @Test
    @Evidencia(
        id = "FU-52", nombre = "Tipo de archivo al guardar en Descargas", requisito = "RF-12 / RF-28",
        objetivo = "Verificar que cada archivo se guarda con su tipo MIME según la extensión.",
        entrada = "informe.pdf, plano.dxf, diagnostico.csv",
        esperado = "application/pdf, application/dxf y text/csv",
    )
    fun tipoDeArchivo() {
        val tipos = listOf("informe.pdf", "plano.dxf", "diagnostico.csv").map(::mimeTypeFor)
        ev.obtenido = tipos.toString()
        assertEquals(listOf("application/pdf", "application/dxf", "text/csv"), tipos)
    }
}
