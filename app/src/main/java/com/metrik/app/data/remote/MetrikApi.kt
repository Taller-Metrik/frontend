package com.metrik.app.data.remote

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/** Endpoints de la API MetriK usados por la app. */
interface MetrikApi {
    @POST("auth/register")
    suspend fun register(@Body body: RegisterRequest): UserDto

    @FormUrlEncoded
    @POST("auth/login")
    suspend fun login(@Field("username") email: String, @Field("password") password: String): TokenDto

    @GET("auth/me")
    suspend fun me(): UserDto

    @GET("projects")
    suspend fun projects(): List<ProjectDto>

    @POST("projects")
    suspend fun createProject(@Body body: ProjectCreateDto): ProjectDto

    @DELETE("projects/{id}")
    suspend fun deleteProject(@Path("id") id: Int): Response<Unit>

    @GET("projects/{id}/surveys")
    suspend fun surveys(@Path("id") projectId: Int): List<SurveyDto>

    @POST("projects/{id}/surveys")
    suspend fun createSurvey(@Path("id") projectId: Int, @Body body: SurveyCreateDto): SurveyDto

    @GET("surveys/{id}")
    suspend fun survey(@Path("id") id: Int): SurveyDto

    @Streaming
    @GET("surveys/{id}/export/dxf")
    suspend fun exportDxf(@Path("id") id: Int, @Query("scale") scale: Int?): ResponseBody

    /** Informe técnico de la medición en PDF (CU-10, RF-28). */
    @Streaming
    @GET("surveys/{id}/report.pdf")
    suspend fun reportPdf(@Path("id") id: Int): ResponseBody

    @GET("surveys/{id}/ml")
    suspend fun ml(@Path("id") id: Int): MlAnalysisDto

    @GET("surveys/{id}/validation")
    suspend fun validation(@Path("id") id: Int): SurveyValidationDto

    @PATCH("projects/{id}")
    suspend fun renameProject(@Path("id") id: Int, @Body body: NameDto): ProjectDto

    @PATCH("surveys/{id}")
    suspend fun renameSurvey(@Path("id") id: Int, @Body body: NameDto): SurveyDto

    @PUT("surveys/{id}/gps-reference")
    suspend fun putGpsReference(@Path("id") id: Int, @Body body: GpsReferenceInDto): GpsReferenceDto

    @GET("surveys/{id}/gps-reference")
    suspend fun gpsReference(@Path("id") id: Int): GpsReferenceDto

    @POST("calibrations")
    suspend fun saveCalibration(@Body body: CalibrationDto): kotlinx.serialization.json.JsonObject

    @PUT("surveys/{id}/references")
    suspend fun putReferences(@Path("id") id: Int, @Body body: ReferencesDto): SurveyValidationDto
}
