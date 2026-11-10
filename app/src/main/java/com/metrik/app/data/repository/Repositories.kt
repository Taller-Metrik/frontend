package com.metrik.app.data.repository

import android.os.Build
import com.metrik.app.data.local.SurveyDao
import com.metrik.app.data.local.toDomain
import com.metrik.app.data.local.toEntity
import com.metrik.app.data.remote.ApiClient
import com.metrik.app.data.remote.CaptureDto
import com.metrik.app.data.remote.GpsPointDto
import com.metrik.app.data.remote.ProjectCreateDto
import com.metrik.app.data.remote.ProjectDto
import com.metrik.app.data.remote.RegisterRequest
import com.metrik.app.data.remote.SurveyCreateDto
import com.metrik.app.data.remote.SurveyDto
import com.metrik.app.data.session.SessionStore
import com.metrik.app.domain.GpsSample
import com.metrik.app.domain.LocalSurvey
import com.metrik.app.domain.SurveyMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// ---------------------------------------------------------------- autenticación

interface AuthRepository {
    suspend fun login(email: String, password: String)
    suspend fun register(email: String, fullName: String, password: String)
    suspend fun logout()
}

class DefaultAuthRepository(private val api: ApiClient, private val session: SessionStore) : AuthRepository {
    override suspend fun login(email: String, password: String) {
        val token = api.api().login(email.trim().lowercase(), password)
        session.saveLogin(token.accessToken, "")
        val me = api.api().me()
        session.saveLogin(token.accessToken, me.fullName)
    }

    override suspend fun register(email: String, fullName: String, password: String) {
        api.api().register(RegisterRequest(email.trim().lowercase(), fullName.trim(), password))
        login(email, password)
    }

    override suspend fun logout() = session.logout()
}

// ---------------------------------------------------------------- proyectos

interface ProjectRepository {
    suspend fun list(): List<ProjectDto>
    suspend fun create(name: String, description: String?): ProjectDto
    suspend fun delete(id: Int)
    suspend fun rename(id: Int, name: String): ProjectDto
}

class DefaultProjectRepository(private val api: ApiClient) : ProjectRepository {
    override suspend fun list() = api.api().projects()

    override suspend fun create(name: String, description: String?) =
        api.api().createProject(ProjectCreateDto(name.trim(), description?.trim()?.ifBlank { null }))

    override suspend fun delete(id: Int) {
        api.api().deleteProject(id)
    }

    override suspend fun rename(id: Int, name: String) = api.api().renameProject(id, com.metrik.app.data.remote.NameDto(name.trim()))
}

// ---------------------------------------------------------------- recorridos (offline primero)

interface SurveyRepository {
    fun observeLocal(projectId: Int): Flow<List<LocalSurvey>>
    suspend fun remote(projectId: Int): List<SurveyDto>
    suspend fun saveLocal(survey: LocalSurvey): Long
    suspend fun getLocal(id: Long): LocalSurvey?
    suspend fun deleteLocal(id: Long)

    /** Cambia el nombre del plano en el celular y, si ya está sincronizado, en el servidor. */
    suspend fun rename(localId: Long, name: String)

    /** Envía el recorrido al backend y guarda el id remoto. */
    suspend fun sync(localId: Long): LocalSurvey

    /** Sincroniza todos los recorridos pendientes; devuelve cuántos se enviaron. */
    suspend fun syncPending(): Int

    /**
     * Descarga el detalle completo (con vértices) de un recorrido del servidor y lo guarda en el
     * celular; si [existingLocalId] no es null, actualiza esa copia. Devuelve el id local.
     */
    suspend fun downloadRemote(projectId: Int, remoteId: Int, existingLocalId: Long? = null): Long =
        throw UnsupportedOperationException("downloadRemote")
}

class DefaultSurveyRepository(private val api: ApiClient, private val dao: SurveyDao) : SurveyRepository {
    override fun observeLocal(projectId: Int) = dao.observeByProject(projectId).map { list -> list.map { it.toDomain() } }
    override suspend fun remote(projectId: Int) = api.api().surveys(projectId)
    override suspend fun saveLocal(survey: LocalSurvey) = dao.insert(survey.toEntity())
    override suspend fun getLocal(id: Long) = dao.get(id)?.toDomain()
    override suspend fun deleteLocal(id: Long) = dao.delete(id)

    override suspend fun rename(localId: Long, name: String) {
        val local = requireNotNull(getLocal(localId)) { "Recorrido $localId no existe" }
        local.remoteId?.let { api.api().renameSurvey(it, com.metrik.app.data.remote.NameDto(name.trim())) }
        dao.update(local.copy(name = name.trim()).toEntity())
    }

    override suspend fun sync(localId: Long): LocalSurvey {
        val local = requireNotNull(getLocal(localId)) { "Recorrido $localId no existe" }
        if (local.synced) return local
        val remote = api.api().createSurvey(local.projectId, local.toCreateDto())
        // El servidor vuelve a procesar el recorrido: se guarda su plano para que los muros
        // (numeración y medidas) coincidan con los que usa la validación con wincha.
        val updated = if (remote.vertices.size >= 3) local.copy(
            remoteId = remote.id,
            vertices = remote.vertices.map { com.metrik.app.core.geometry.Point(it[0], it[1]) },
            areaM2 = remote.areaM2,
            perimeterM = remote.perimeterM,
            closed = remote.closed,
        ) else local.copy(remoteId = remote.id)
        dao.update(updated.toEntity())
        return updated
    }

    override suspend fun downloadRemote(projectId: Int, remoteId: Int, existingLocalId: Long?): Long {
        val d = api.api().survey(remoteId)
        val survey = LocalSurvey(
            projectId = projectId,
            name = d.name,
            mode = if (d.mode == "exterior") SurveyMode.EXTERIOR else SurveyMode.INTERIOR,
            vertices = d.vertices.map { com.metrik.app.core.geometry.Point(it[0], it[1]) },
            areaM2 = d.areaM2,
            perimeterM = d.perimeterM,
            scaleDenominator = d.scaleDenominator,
            closed = d.closed,
            remoteId = d.id,
        )
        if (existingLocalId != null) {
            dao.update(survey.copy(id = existingLocalId).toEntity())
            return existingLocalId
        }
        return dao.insert(survey.toEntity())
    }

    override suspend fun syncPending(): Int {
        var sent = 0
        for (entity in dao.pending()) runCatching { sync(entity.id) }.onSuccess { sent++ }
        return sent
    }
}

/** Lectura GPS → JSON del backend. Los campos nulos no se envían (`explicitNulls = false`). */
fun GpsSample.toDto() = GpsPointDto(lat, lon, accuracyM, timestampS, satellites, cn0DbHz, vertexId)

fun LocalSurvey.toCreateDto(): SurveyCreateDto = SurveyCreateDto(
    name = name,
    mode = mode.api,
    deviceModel = listOfNotNull(Build.MANUFACTURER, Build.MODEL).joinToString(" ").trim().ifBlank { null },
    points = if (mode == SurveyMode.INTERIOR) points.map { listOf(it.x, it.y) } else emptyList(),
    corners = corners?.map { listOf(it.x, it.y) },
    gpsPoints = gps?.map { it.toDto() },
    scaleDenominator = scaleDenominator,
    capture = if (mode == SurveyMode.INTERIOR) capture?.let { CaptureDto(it.odometry, it.trackingLossS, it.durationS) } else null,
    transport = if (mode == SurveyMode.EXTERIOR) capture?.transport ?: "walk" else null,
)
