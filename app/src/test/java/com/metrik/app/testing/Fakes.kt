package com.metrik.app.testing

import com.metrik.app.data.local.SurveyDao
import com.metrik.app.data.local.SurveyEntity
import com.metrik.app.data.remote.ProjectDto
import com.metrik.app.data.remote.SurveyDto
import com.metrik.app.data.repository.AuthRepository
import com.metrik.app.data.repository.ProjectRepository
import com.metrik.app.data.repository.SurveyRepository
import com.metrik.app.data.session.Session
import com.metrik.app.data.session.SessionStore
import com.metrik.app.domain.LocalSurvey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Reemplaza Dispatchers.Main en pruebas de ViewModels. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

class FakeSessionStore(initial: Session = Session(baseUrl = "http://localhost/api/v1/")) : SessionStore {
    private val flow = MutableStateFlow(initial)
    override val session: StateFlow<Session> = flow
    override suspend fun saveLogin(token: String, userName: String) { flow.value = flow.value.copy(token = token, userName = userName) }
    override suspend fun logout() { flow.value = flow.value.copy(token = null, userName = null) }
    override suspend fun setBaseUrl(url: String) { flow.value = flow.value.copy(baseUrl = url) }
    override suspend fun setOdometryMode(mode: String) { flow.value = flow.value.copy(odometryMode = mode) }
    override suspend fun setStepLength(meters: Double) { flow.value = flow.value.copy(stepLengthM = meters) }
}

class FakeAuthRepository(var failWith: Throwable? = null) : AuthRepository {
    val calls = mutableListOf<String>()
    override suspend fun login(email: String, password: String) { calls += "login:$email"; failWith?.let { throw it } }
    override suspend fun register(email: String, fullName: String, password: String) { calls += "register:$email"; failWith?.let { throw it } }
    override suspend fun logout() { calls += "logout" }
}

class FakeProjectRepository : ProjectRepository {
    val items = mutableListOf<ProjectDto>()
    override suspend fun list() = items.toList()
    override suspend fun create(name: String, description: String?) = ProjectDto(items.size + 1, name, description).also { items += it }
    override suspend fun delete(id: Int) { items.removeAll { it.id == id } }
    override suspend fun rename(id: Int, name: String): ProjectDto {
        val i = items.indexOfFirst { it.id == id }
        items[i] = items[i].copy(name = name)
        return items[i]
    }
}

class FakeSurveyRepository : SurveyRepository {
    val saved = MutableStateFlow<Map<Long, LocalSurvey>>(emptyMap())
    var nextRemoteId = 100
    override fun observeLocal(projectId: Int): Flow<List<LocalSurvey>> = saved.map { m -> m.values.filter { it.projectId == projectId } }
    override suspend fun remote(projectId: Int): List<SurveyDto> = emptyList()
    override suspend fun saveLocal(survey: LocalSurvey): Long {
        val id = (saved.value.keys.maxOrNull() ?: 0L) + 1
        saved.value = saved.value + (id to survey.copy(id = id))
        return id
    }
    override suspend fun getLocal(id: Long) = saved.value[id]
    override suspend fun deleteLocal(id: Long) { saved.value = saved.value - id }
    override suspend fun rename(localId: Long, name: String) {
        saved.value = saved.value + (localId to saved.value.getValue(localId).copy(name = name))
    }
    override suspend fun sync(localId: Long): LocalSurvey {
        val s = saved.value.getValue(localId).copy(remoteId = nextRemoteId++)
        saved.value = saved.value + (localId to s)
        return s
    }
    override suspend fun syncPending() = saved.value.values.count { !it.synced }.also { n ->
        saved.value.values.filter { !it.synced }.forEach { sync(it.id) }
        if (n < 0) error("unreachable")
    }
}

/** DAO de Room en memoria (para probar repositorios en la JVM sin Android). */
class InMemorySurveyDao : SurveyDao {
    private val rows = MutableStateFlow<Map<Long, SurveyEntity>>(emptyMap())
    override suspend fun insert(entity: SurveyEntity): Long {
        val id = (rows.value.keys.maxOrNull() ?: 0L) + 1
        rows.value = rows.value + (id to entity.copy(id = id))
        return id
    }
    override suspend fun update(entity: SurveyEntity) { rows.value = rows.value + (entity.id to entity) }
    override suspend fun get(id: Long) = rows.value[id]
    override fun observeByProject(projectId: Int) = rows.map { m -> m.values.filter { it.projectId == projectId } }
    override suspend fun pending() = rows.value.values.filter { it.remoteId == null }
    override suspend fun delete(id: Long) { rows.value = rows.value - id }
}
