package com.metrik.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Contrato de la API /api/v1 (ver backend/app/schemas). Coordenadas como [x, y].

@Serializable
data class RegisterRequest(val email: String, @SerialName("full_name") val fullName: String, val password: String)

@Serializable
data class UserDto(val id: Int, val email: String, @SerialName("full_name") val fullName: String)

@Serializable
data class TokenDto(@SerialName("access_token") val accessToken: String, @SerialName("token_type") val tokenType: String)

@Serializable
data class ProjectCreateDto(val name: String, val description: String? = null)

@Serializable
data class ProjectDto(val id: Int, val name: String, val description: String? = null)

@Serializable
data class GpsPointDto(
    val lat: Double,
    val lon: Double,
    @SerialName("accuracy_m") val accuracyM: Double,
    @SerialName("timestamp_s") val timestampS: Double? = null,
    val satellites: Int? = null,
    @SerialName("cn0_dbhz") val cn0DbHz: Double? = null,
    @SerialName("vertex_id") val vertexId: Int? = null,
)

@Serializable
data class CaptureDto(
    val odometry: String,
    @SerialName("tracking_loss_s") val trackingLossS: Double,
    @SerialName("duration_s") val durationS: Double?,
)

@Serializable
data class SurveyCreateDto(
    val name: String,
    val mode: String,
    @SerialName("device_model") val deviceModel: String?,
    val points: List<List<Double>> = emptyList(),
    val corners: List<List<Double>>? = null,
    @SerialName("gps_points") val gpsPoints: List<GpsPointDto>? = null,
    @SerialName("scale_denominator") val scaleDenominator: Int? = null,
    val capture: CaptureDto? = null,
    val transport: String? = null,
)

@Serializable
data class SurveyDto(
    val id: Int,
    @SerialName("project_id") val projectId: Int,
    val name: String,
    val mode: String,
    @SerialName("area_m2") val areaM2: Double,
    @SerialName("perimeter_m") val perimeterM: Double,
    val closed: Boolean = true,
    @SerialName("scale_denominator") val scaleDenominator: Int,
    val vertices: List<List<Double>> = emptyList(),
    @SerialName("wall_lengths_m") val wallLengthsM: List<Double> = emptyList(),
    @SerialName("interior_angles_deg") val interiorAnglesDeg: List<Double> = emptyList(),
    @SerialName("closure_error_m") val closureErrorM: Double = 0.0,
    @SerialName("concave_count") val concaveCount: Int = 0,
)

@Serializable
data class WallReferenceDto(@SerialName("wall_index") val wallIndex: Int, @SerialName("readings_m") val readingsM: List<Double>)

@Serializable
data class ReferencesDto(val walls: List<WallReferenceDto>, @SerialName("real_area_m2") val realAreaM2: Double? = null)

@Serializable
data class WallComparisonDto(
    @SerialName("wall_index") val wallIndex: Int,
    @SerialName("measured_m") val measuredM: Double,
    @SerialName("real_m") val realM: Double,
    @SerialName("error_m") val errorM: Double,
    @SerialName("rel_error_pct") val relErrorPct: Double,
    @SerialName("needs_extra_reading") val needsExtraReading: Boolean,
)

@Serializable
data class ErrorReportDto(
    val n: Int,
    @SerialName("mae_m") val maeM: Double,
    @SerialName("rmse_m") val rmseM: Double,
    @SerialName("bias_m") val biasM: Double,
    @SerialName("std_m") val stdM: Double,
    @SerialName("mean_rel_error_pct") val meanRelErrorPct: Double,
    @SerialName("area_error_pct") val areaErrorPct: Double? = null,
    @SerialName("meets_target") val meetsTarget: Boolean,
    @SerialName("target_pct") val targetPct: Double,
    val walls: List<WallComparisonDto>,
)

@Serializable
data class SurveyValidationDto(
    @SerialName("survey_id") val surveyId: Int,
    val report: ErrorReportDto,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class WallCorrectionDto(
    @SerialName("wall_index") val wallIndex: Int,
    @SerialName("measured_m") val measuredM: Double,
    @SerialName("corrected_m") val correctedM: Double,
    @SerialName("correction_m") val correctionM: Double,
)

@Serializable
data class MlAnalysisDto(
    @SerialName("reliability_probability") val reliabilityProbability: Double,
    val reliable: Boolean,
    val recommendation: String,
    val walls: List<WallCorrectionDto>,
    @SerialName("corrected_perimeter_m") val correctedPerimeterM: Double,
    @SerialName("correction_applied") val correctionApplied: Boolean = true,
    @SerialName("correction_note") val correctionNote: String = "",
    @SerialName("corrector_model") val correctorModel: String = "",
    @SerialName("classifier_model") val classifierModel: String = "",
)

@Serializable
data class ApiErrorDto(val detail: JsonElement? = null)

@Serializable
data class NameDto(val name: String)

@Serializable
data class GpsReferenceInDto(
    @SerialName("reference_type") val referenceType: String,
    @SerialName("real_area_m2") val realAreaM2: Double? = null,
    @SerialName("real_perimeter_m") val realPerimeterM: Double? = null,
)

@Serializable
data class GpsReferenceDto(
    @SerialName("reference_label") val referenceLabel: String,
    @SerialName("measured_area_m2") val measuredAreaM2: Double,
    @SerialName("real_area_m2") val realAreaM2: Double? = null,
    @SerialName("area_error_pct") val areaErrorPct: Double? = null,
    @SerialName("measured_perimeter_m") val measuredPerimeterM: Double,
    @SerialName("real_perimeter_m") val realPerimeterM: Double? = null,
    @SerialName("perimeter_error_pct") val perimeterErrorPct: Double? = null,
    @SerialName("vertex_mae_m") val vertexMaeM: Double? = null,
    @SerialName("meets_target") val meetsTarget: Boolean? = null,
    @SerialName("target_pct") val targetPct: Double = 5.0,
)

@Serializable
data class CalibrationDto(val kind: String, @SerialName("device_id") val deviceId: String?, val params: Map<String, Double>)
