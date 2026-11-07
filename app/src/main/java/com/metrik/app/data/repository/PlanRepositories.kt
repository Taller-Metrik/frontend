package com.metrik.app.data.repository

import com.metrik.app.data.remote.ApiClient
import com.metrik.app.data.remote.CalibrationDto
import com.metrik.app.data.remote.GpsReferenceDto
import com.metrik.app.data.remote.GpsReferenceInDto
import com.metrik.app.data.remote.MlAnalysisDto
import com.metrik.app.data.remote.ReferencesDto
import com.metrik.app.data.remote.SurveyValidationDto
import com.metrik.app.data.remote.WallReferenceDto

/** Funciones del plano que requieren el backend: DXF y análisis ML (RF-04, RF-12, RF-15). */
interface PlanRepository {
    suspend fun exportDxf(remoteId: Int, scale: Int): ByteArray
    suspend fun ml(remoteId: Int): MlAnalysisDto
}

class DefaultPlanRepository(private val api: ApiClient) : PlanRepository {
    override suspend fun exportDxf(remoteId: Int, scale: Int): ByteArray = api.api().exportDxf(remoteId, scale).bytes()
    override suspend fun ml(remoteId: Int): MlAnalysisDto = api.api().ml(remoteId)
}

/** Medidas reales con wincha y margen de error (RF-13, RF-14). */
interface ValidationRepository {
    suspend fun submit(remoteId: Int, readings: Map<Int, List<Double>>, realAreaM2: Double?): SurveyValidationDto

    /** Reporte guardado; null si todavía no se registraron medidas con wincha. */
    suspend fun get(remoteId: Int): SurveyValidationDto? = null

    /** Terrenos GPS: referencia externa (cancha oficial, catastro, satelital, tramo base, RTK). */
    suspend fun submitGpsReference(remoteId: Int, type: String, realAreaM2: Double?, realPerimeterM: Double?): GpsReferenceDto =
        throw UnsupportedOperationException()
}

class DefaultValidationRepository(private val api: ApiClient) : ValidationRepository {
    override suspend fun submitGpsReference(remoteId: Int, type: String, realAreaM2: Double?, realPerimeterM: Double?) =
        api.api().putGpsReference(remoteId, GpsReferenceInDto(type, realAreaM2, realPerimeterM))

    override suspend fun get(remoteId: Int): SurveyValidationDto? = try {
        api.api().validation(remoteId)
    } catch (e: retrofit2.HttpException) {
        if (e.code() == 409) null else throw e
    }

    override suspend fun submit(remoteId: Int, readings: Map<Int, List<Double>>, realAreaM2: Double?) =
        api.api().putReferences(
            remoteId,
            ReferencesDto(
                walls = readings.toSortedMap().map { (i, r) -> WallReferenceDto(i, r) },
                realAreaM2 = realAreaM2,
            ),
        )
}

/** Calibraciones de sensores hechas en la app (se guardan también en el servidor). */
interface CalibrationRepository {
    suspend fun save(kind: String, params: Map<String, Double>)
}

class DefaultCalibrationRepository(private val api: ApiClient) : CalibrationRepository {
    override suspend fun save(kind: String, params: Map<String, Double>) {
        api.api().saveCalibration(CalibrationDto(kind, android.os.Build.MODEL, params))
    }
}
