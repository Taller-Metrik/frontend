package com.metrik.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/* JSON del plano de vivienda: POST /projects/{id}/floor-plan y floor-plan.dxf (CU-08, RF-25, RF-26). */

@Serializable
data class FloorJoinDto(
    /** Ambiente ya ubicado (o la base) */
    @SerialName("a_survey") val aSurvey: Int,
    /** Muro de A: 0 = del vértice 1 al 2. Null = el muro más cercano a [aPoint] */
    @SerialName("a_wall") val aWall: Int?,
    @SerialName("b_survey") val bSurvey: Int,
    @SerialName("b_wall") val bWall: Int?,
    /** Espesor del muro compartido: 0,15 tabique; 0,25 portante */
    @SerialName("thickness_m") val thicknessM: Double = 0.15,
    /** Puerta en las coordenadas de A [x, y]: el backend alinea las dos puertas */
    @SerialName("a_point") val aPoint: List<Double>? = null,
    @SerialName("b_point") val bPoint: List<Double>? = null,
)

@Serializable
data class FloorPlanInDto(
    /** Ids remotos de los ambientes; el primero es la base */
    val rooms: List<Int>,
    val joins: List<FloorJoinDto> = emptyList(),
    @SerialName("scale_denominator") val scaleDenominator: Int = 100,
)

@Serializable
data class PlacedRoomDto(
    @SerialName("survey_id") val surveyId: Int,
    val name: String,
    /** Vértices ya ubicados en la planta común: [[x, y], …] en metros */
    val vertices: List<List<Double>>,
    @SerialName("area_m2") val areaM2: Double,
)

@Serializable
data class FloorPlanOutDto(
    @SerialName("project_id") val projectId: Int,
    val rooms: List<PlacedRoomDto>,
    @SerialName("total_area_m2") val totalAreaM2: Double,
    val warnings: List<String> = emptyList(),
)
