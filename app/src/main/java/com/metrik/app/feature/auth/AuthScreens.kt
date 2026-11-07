package com.metrik.app.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrik.app.ui.components.ErrorBanner
import com.metrik.app.ui.components.LoadingButton

@Composable
fun LoginScreen(vm: AuthViewModel, onLoggedIn: () -> Unit, onRegister: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.success) { if (s.success) onLoggedIn() }
    AuthScaffold(subtitle = "Recorre un ambiente con tu celular y obtén su plano a escala") {
        EmailField(s.email, s.emailError, vm::onEmail)
        PasswordField(s.password, s.passwordError, vm::onPassword)
        ErrorBanner(s.error)
        LoadingButton("Iniciar sesión", s.loading, vm::login, Modifier.fillMaxWidth().testTag("login_submit"))
        TextButton(onClick = onRegister, modifier = Modifier.testTag("go_register")) { Text("¿No tienes cuenta? Regístrate") }
    }
}

@Composable
fun RegisterScreen(vm: AuthViewModel, onRegistered: () -> Unit, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.success) { if (s.success) onRegistered() }
    AuthScaffold(subtitle = "Crea tu cuenta para guardar tus planos") {
        OutlinedTextField(
            value = s.fullName, onValueChange = vm::onName, label = { Text("Nombre completo") },
            isError = s.nameError != null, supportingText = { s.nameError?.let { Text(it) } }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("register_name"),
        )
        EmailField(s.email, s.emailError, vm::onEmail)
        PasswordField(s.password, s.passwordError, vm::onPassword)
        ErrorBanner(s.error)
        LoadingButton("Crear cuenta", s.loading, vm::register, Modifier.fillMaxWidth().testTag("register_submit"))
        TextButton(onClick = onBack) { Text("Ya tengo cuenta") }
    }
}

@Composable
private fun AuthScaffold(subtitle: String, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Straighten, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(72.dp))
            Text("MetriK", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun EmailField(value: String, error: String?, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text("Correo") }, singleLine = true,
        isError = error != null, supportingText = { error?.let { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth().testTag("auth_email"),
    )
}

@Composable
private fun PasswordField(value: String, error: String?, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text("Contraseña") }, singleLine = true,
        isError = error != null, supportingText = { error?.let { Text(it) } },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth().testTag("auth_password"),
    )
}
