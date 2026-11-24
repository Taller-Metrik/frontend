package com.metrik.app.data

import com.metrik.app.core.geometry.Point
import com.metrik.app.data.local.toDomain
import com.metrik.app.data.local.toEntity
import com.metrik.app.data.repository.toCreateDto
import com.metrik.app.domain.GpsSample
import com.metrik.app.domain.LocalSurvey
import com.metrik.app.domain.SurveyMode
import com.metrik.app.testing.EvidenceRule
import com.metrik.app.testing.Evidencia
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SurveyMappingTest {
    @get:Rule val ev = EvidenceRule()

    @Test
    @Evidencia(
        id = "FU-117", nombre = "El trazo y las etapas sobreviven a la base local y se envían al servidor", requisito = "RF-08",
        objetivo = "Verificar que el trazo de pantalla y las etapas del procesamiento se guardan en Room y viajan al backend "
            + "en `points` y `stages` (antes el recorrido exterior enviaba `points` vacío).",
        entrada = "Recorrido exterior con 3 puntos de trazo y 2 etapas",
        esperado = "Ida y vuelta por la entidad sin pérdidas; el DTO lleva points (3) y stages con las 2 etapas",
    )
    fun trazoYEtapasViajan() {
        val survey = LocalSurvey(
            projectId = 1, name = "Parque", mode = SurveyMode.EXTERIOR,
            points = listOf(Point(0.0, 0.0), Point(10.0, 0.2), Point(10.1, 5.0)),
            stages = mapOf("esquinas_tocadas" to listOf(Point(0.0, 0.0), Point(10.0, 0.0)), "poligonal" to listOf(Point(1.0, 2.0))),
            gps = listOf(GpsSample(-8.1, -79.0, 3.0, 0.0)),
            vertices = listOf(Point(0.0, 0.0), Point(10.0, 0.0), Point(10.0, 5.0)), areaM2 = 25.0, perimeterM = 26.2, scaleDenominator = 500,
        )
        val back = survey.toEntity().toDomain()
        val dto = survey.toCreateDto()
        ev.obtenido = "points=${dto.points.size}, stages=${dto.stages?.mapValues { it.value.size }}"
        assertEquals(survey.points, back.points)
        assertEquals(survey.stages, back.stages)
        assertEquals(listOf(listOf(0.0, 0.0), listOf(10.0, 0.2), listOf(10.1, 5.0)), dto.points)
        assertEquals(mapOf("esquinas_tocadas" to listOf(listOf(0.0, 0.0), listOf(10.0, 0.0)), "poligonal" to listOf(listOf(1.0, 2.0))), dto.stages)
    }
}
