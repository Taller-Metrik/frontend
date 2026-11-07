package com.metrik.app.feature.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrik.app.core.geometry.Point
import com.metrik.app.data.remote.ProjectDto
import com.metrik.app.data.remote.SurveyDto
import com.metrik.app.data.remote.userMessage
import com.metrik.app.data.repository.AuthRepository
import com.metrik.app.data.repository.ProjectRepository
import com.metrik.app.data.repository.SurveyRepository
import com.metrik.app.domain.LocalSurvey
import com.metrik.app.domain.SurveyMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProjectsUiState(
    val projects: List<ProjectDto> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

class ProjectsViewModel(
    private val projects: ProjectRepository,
    private val surveys: SurveyRepository,
    private val auth: AuthRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ProjectsUiState())
    val state: StateFlow<ProjectsUiState> = _state

    init { refresh() }

    fun refresh() = launchLoading { _state.update { it.copy(projects = projects.list()) } }

    fun create(name: String, description: String?) {
        if (name.isBlank()) { _state.update { it.copy(error = "El nombre del proyecto es obligatorio") }; return }
        launchLoading {
            projects.create(name, description)
            _state.update { it.copy(projects = projects.list(), message = "Proyecto creado") }
        }
    }

    fun rename(id: Int, name: String) = launchLoading {
        projects.rename(id, name)
        _state.update { it.copy(projects = projects.list(), message = "Nombre actualizado") }
    }

    fun delete(id: Int) = launchLoading {
        projects.delete(id)
        _state.update { it.copy(projects = projects.list()) }
    }

    /** Envía al servidor los recorridos medidos sin conexión (RF-17, EX-07). */
    fun syncPending() = launchLoading {
        val n = surveys.syncPending()
        _state.update { it.copy(message = if (n == 0) "No hay recorridos pendientes" else "$n recorrido(s) sincronizado(s)") }
    }

    fun logout(onDone: () -> Unit) = viewModelScope.launch { auth.logout(); onDone() }

    fun consumeMessage() = _state.update { it.copy(message = null, error = null) }

    private fun launchLoading(block: suspend () -> Unit) {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { block() }.onFailure { t -> _state.update { it.copy(error = t.userMessage()) } }
            _state.update { it.copy(loading = false) }
        }
    }
}

data class ProjectDetailUiState(
    val remote: List<SurveyDto> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

class ProjectDetailViewModel(
    val projectId: Int,
    private val surveys: SurveyRepository,
) : ViewModel() {
    val local: StateFlow<List<LocalSurvey>> =
        surveys.observeLocal(projectId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow(ProjectDetailUiState())
    val state: StateFlow<ProjectDetailUiState> = _state

    init { refresh() }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { surveys.remote(projectId) }
                .onSuccess { r -> _state.update { it.copy(remote = r, loading = false) } }
                .onFailure { t -> _state.update { it.copy(loading = false, error = t.userMessage()) } }
        }
    }

    fun sync(localId: Long) = viewModelScope.launch {
        runCatching { surveys.sync(localId) }
            .onSuccess { refresh() }
            .onFailure { t -> _state.update { it.copy(error = t.userMessage()) } }
    }

    fun delete(localId: Long) = viewModelScope.launch { surveys.deleteLocal(localId) }

    fun rename(localId: Long, name: String) = viewModelScope.launch {
        runCatching { surveys.rename(localId, name) }.onFailure { t -> _state.update { it.copy(error = t.userMessage()) } }
    }

    /** Trae al celular un recorrido del servidor (medido en otro dispositivo) para verlo y exportarlo. */
    fun importRemote(dto: SurveyDto, onReady: (Long) -> Unit) = viewModelScope.launch {
        val existing = local.value.firstOrNull { it.remoteId == dto.id }
        if (existing != null && existing.vertices.size >= 3) { onReady(existing.id); return@launch }
        // La lista del servidor es un resumen sin vértices: se descarga el detalle completo.
        runCatching { surveys.downloadRemote(projectId, dto.id, existing?.id) }
            .onSuccess(onReady)
            .onFailure { t -> _state.update { it.copy(error = "No se pudo descargar el plano: ${t.userMessage()}") } }
    }
}
