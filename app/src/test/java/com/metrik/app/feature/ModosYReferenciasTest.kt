package com.metrik.app.feature

import com.metrik.app.core.geometry.Point
import com.metrik.app.domain.LocalSurvey
import com.metrik.app.domain.SurveyMode
import com.metrik.app.feature.dashboard.algorithmsFor
import com.metrik.app.feature.validation.GPS_REFERENCES
import com.metrik.app.testing.EvidenceRule
import com.metrik.app.testing.Evidencia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Tipos de referencia GPS y planos importados: la app debe hablar igual que el backend. */
class ModosYReferenciasTest {
    @get:Rule val ev = EvidenceRule()

    @Test
    @Evidencia(
        id = "FU-118", nombre = "Todas las referencias de terreno del backend", requisito = "RF-13, RF-14 (CU-06)",
        objetivo = "Verificar que la validación de terrenos ofrece los cinco tipos de referencia que acepta el backend.",
        entrada = "Lista GPS_REFERENCES de la pantalla «Validar terreno»",
        esperado = "cancha, catastro, satelital, tramo_base y rtk, sin repetidos",
    )
    fun referenciasDeTerreno() {
        val keys = GPS_REFERENCES.map { it.first }
        ev.obtenido = "claves=$keys"
        assertEquals(setOf("cancha", "catastro", "satelital", "tramo_base", "rtk"), keys.toSet())
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    @Evidencia(
        id = "FU-119", nombre = "Plano importado con su propio modo", requisito = "RF-11 (CU-05)",
        objetivo = "Verificar que la app conoce el modo «imported» del backend y no lo describe como interior ni como GPS.",
        entrada = "Recorrido con modo de API «imported»",
        esperado = "Modo IMPORTED y algoritmos de plano importado (sin odómetro ni GPS)",
    )
    fun planoImportado() {
        val mode = SurveyMode.entries.first { it.api == "imported" }
        val survey = LocalSurvey(
            projectId = 1, name = "Plano escaneado", mode = mode,
            vertices = listOf(Point(0.0, 0.0), Point(4.0, 0.0), Point(4.0, 3.0), Point(0.0, 3.0)),
            areaM2 = 12.0, perimeterM = 14.0, scaleDenominator = 50, remoteId = 9,
        )
        val algorithms = algorithmsFor(survey, null).map { it.first }
        ev.obtenido = "modo=${mode.api}, algoritmos=$algorithms"
        assertTrue("Plano importado" in algorithms)
        assertTrue("Odómetro" !in algorithms && "Posición" !in algorithms)
    }
}
