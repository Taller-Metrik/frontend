package com.metrik.app.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrik.app.data.remote.userMessage
import com.metrik.app.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Reglas de los formularios (particiones de equivalencia de las pruebas de caja negra). */
object AuthValidator {
    private val EMAIL = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    const val MIN_PASSWORD = 8
    const val MAX_PASSWORD = 72

    fun email(value: String): String? = when {
        value.isBlank() -> "Ingresa tu correo"
        !EMAIL.matches(value.trim()) -> "Correo no válido"
        else -> null
    }

    fun password(value: String): String? = when {
        value.isEmpty() -> "Ingresa tu contraseña"
        value.length < MIN_PASSWORD -> "Mínimo $MIN_PASSWORD caracteres"
        value.length > MAX_PASSWORD -> "Máximo $MAX_PASSWORD caracteres"
        else -> null
    }

    fun name(value: String): String? = when {
        value.trim().length < 2 -> "Ingresa tu nombre (mínimo 2 letras)"
        value.length > 120 -> "Nombre demasiado largo"
        else -> null
    }
}

data class AuthUiState(
    val email: String = "",
    val password: String = "",
    val fullName: String = "",
    val emailError: String? = null,
    val passwordError: String? = null,
    val nameError: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val success: Boolean = false,
)

class AuthViewModel(private val repo: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state

    fun onEmail(v: String) = _state.update { it.copy(email = v, emailError = null, error = null) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, passwordError = null, error = null) }
    fun onName(v: String) = _state.update { it.copy(fullName = v, nameError = null, error = null) }

    fun login() {
        val s = _state.value
        val e = AuthValidator.email(s.email)
        val p = if (s.password.isEmpty()) "Ingresa tu contraseña" else null
        if (e != null || p != null) {
            _state.update { it.copy(emailError = e, passwordError = p) }
            return
        }
        submit { repo.login(s.email, s.password) }
    }

    fun register() {
        val s = _state.value
        val n = AuthValidator.name(s.fullName)
        val e = AuthValidator.email(s.email)
        val p = AuthValidator.password(s.password)
        if (n != null || e != null || p != null) {
            _state.update { it.copy(nameError = n, emailError = e, passwordError = p) }
            return
        }
        submit { repo.register(s.email, s.fullName, s.password) }
    }

    private fun submit(action: suspend () -> Unit) {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { action() }
                .onSuccess { _state.update { it.copy(loading = false, success = true) } }
                .onFailure { t -> _state.update { it.copy(loading = false, error = t.userMessage()) } }
        }
    }
}
