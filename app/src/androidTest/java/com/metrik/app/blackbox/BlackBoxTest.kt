package com.metrik.app.blackbox

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrik.app.core.geometry.Point
import com.metrik.app.data.remote.ErrorReportDto
import com.metrik.app.data.remote.MlAnalysisDto
import com.metrik.app.data.remote.SurveyDto
import com.metrik.app.data.remote.SurveyValidationDto
import com.metrik.app.data.repository.AuthRepository
import com.metrik.app.data.repository.PlanRepository
import com.metrik.app.data.repository.SurveyRepository
import com.metrik.app.data.repository.ValidationRepository
import com.metrik.app.data.session.Session
import com.metrik.app.data.session.SessionStore
import com.metrik.app.domain.LocalSurvey
import com.metrik.app.domain.SurveyMode
import com.metrik.app.feature.auth.AuthViewModel
import com.metrik.app.feature.auth.LoginScreen
import com.metrik.app.feature.auth.RegisterScreen
import com.metrik.app.feature.plan.PlanScreen
import com.metrik.app.feature.plan.PlanViewModel
import com.metrik.app.feature.settings.SettingsScreen
import com.metrik.app.feature.validation.ValidationScreen
import com.metrik.app.feature.validation.ValidationViewModel
import com.metrik.app.testing.DeviceEvidenceRule
import com.metrik.app.testing.Evidencia
import com.metrik.app.ui.theme.MetrikTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Pruebas de caja negra: cada pantalla se prueba desde fuera, solo con entradas y salidas
 * visibles (particiones de equivalencia y valores límite), sin conocer la implementación.
 */
@RunWith(AndroidJUnit4::class)
class BlackBoxTest {
    private val compose = createComposeRule()
    private val ev = DeviceEvidenceRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(compose).around(ev)

    private class OkAuth : AuthRepository {
        var calls = 0
        override suspend fun login(email: String, password: String) { calls++ }
        override suspend fun register(email: String, fullName: String, password: String) { calls++ }
        override suspend fun logout() = Unit
    }

    private class MemSession : SessionStore {
        private val f = MutableStateFlow(Session(baseUrl = "http://10.0.2.2:8000/api/v1/"))
        override val session: StateFlow<Session> = f
        override suspend fun saveLogin(token: String, userName: String) = Unit
        override suspend fun logout() = Unit
        override suspend fun setBaseUrl(url: String) { f.value = f.value.copy(baseUrl = url) }
        override suspend fun setOdometryMode(mode: String) { f.value = f.value.copy(odometryMode = mode) }
    }

    private val aula = LocalSurvey(
        id = 1, projectId = 1, name = "Aula 201", mode = SurveyMode.INTERIOR,
        vertices = listOf(Point(0.0, 0.0), Point(5.0, 0.0), Point(5.0, 4.0), Point(0.0, 4.0)),
        areaM2 = 20.0, perimeterM = 18.0, scaleDenominator = 50, remoteId = 9,
    )

    private inner class OneSurvey : SurveyRepository {
        override fun observeLocal(projectId: Int): Flow<List<LocalSurvey>> = flowOf(listOf(aula))
        override suspend fun remote(projectId: Int) = emptyList<SurveyDto>()
        override suspend fun saveLocal(survey: LocalSurvey) = 1L
        override suspend fun getLocal(id: Long) = aula
        override suspend fun deleteLocal(id: Long) = Unit
        override suspend fun rename(localId: Long, name: String) = Unit
        override suspend fun sync(localId: Long) = aula
        override suspend fun syncPending() = 0
    }

    @Test
    @Evidencia(
        id = "FC-01", nombre = "Login: particiones del correo y contraseña vacía", requisito = "RF-16",
        objetivo = "Verificar los mensajes de la pantalla de login para cada clase de entrada.",
        entrada = "Correo vacío | 'ana@' | 'ana@metrik' | 'ana@metrik.pe' con contraseña vacía",
        esperado = "'Ingresa tu correo' | 'Correo no válido' | 'Correo no válido' | 'Ingresa tu contraseña'; 0 llamadas al servidor",
    )
    fun loginParticiones() {
        val repo = OkAuth()
        compose.setContent { MetrikTheme { LoginScreen(AuthViewModel(repo), {}, {}) } }
        val email = compose.onNodeWithTag("auth_email")
        val submit = compose.onNodeWithTag("login_submit")
        val vistos = mutableListOf<String>()
        submit.performClick(); compose.onNodeWithText("Ingresa tu correo").assertIsDisplayed(); vistos += "vacío"
        email.performTextInput("ana@"); submit.performClick(); compose.onNodeWithText("Correo no válido").assertIsDisplayed(); vistos += "ana@"
        email.performTextClearance(); email.performTextInput("ana@metrik"); submit.performClick()
        compose.onNodeWithText("Correo no válido").assertIsDisplayed(); vistos += "ana@metrik"
        email.performTextClearance(); email.performTextInput("ana@metrik.pe"); submit.performClick()
        compose.onNodeWithText("Ingresa tu contraseña").assertIsDisplayed(); vistos += "válido sin contraseña"
        ev.obtenido = "mensajes correctos para: $vistos; llamadas al servidor=${repo.calls}"
        assertTrue(repo.calls == 0)
        ev.capture()
    }

    @Test
    @Evidencia(
        id = "FC-02", nombre = "Registro: valores límite de la contraseña", requisito = "RF-16, RNF-07",
        objetivo = "Verificar el límite inferior de longitud de la contraseña (8 caracteres).",
        entrada = "Contraseña de 7 caracteres y luego de 8 caracteres",
        esperado = "7 → 'Mínimo 8 caracteres' sin registrar; 8 → registro aceptado",
    )
    fun registroLimites() {
        val repo = OkAuth()
        var registrado = false
        compose.setContent { MetrikTheme { RegisterScreen(AuthViewModel(repo), { registrado = true }, {}) } }
        compose.onNodeWithTag("register_name").performTextInput("Ana Medidora")
        compose.onNodeWithTag("auth_email").performTextInput("ana@metrik.pe")
        compose.onNodeWithTag("auth_password").performTextInput("1234567")
        compose.onNodeWithTag("register_submit").performClick()
        compose.onNodeWithText("Mínimo 8 caracteres").assertIsDisplayed()
        val con7 = repo.calls
        compose.onNodeWithTag("auth_password").performTextInput("8")
        compose.onNodeWithTag("register_submit").performClick()
        compose.waitUntil(5_000) { registrado }
        ev.obtenido = "7 caracteres → error, llamadas=$con7; 8 caracteres → registrado=$registrado"
        assertTrue(con7 == 0 && registrado)
        ev.capture()
    }

    @Test
    @Evidencia(
        id = "FC-03", nombre = "Lecturas de wincha: entradas inválidas y límites", requisito = "RF-13",
        objetivo = "Verificar la validación de las lecturas de wincha antes de enviarlas.",
        entrada = "Muro 1 'abc', muro 2 '0', muro 3 '200.01'; luego 5 / 4,00 / 5 / 4 m",
        esperado = "'No es un número', 'Debe ser mayor que 0', 'Máximo 200 m'; luego reporte 'Cumple el objetivo'",
    )
    fun lecturasWincha() {
        val validation = object : ValidationRepository {
            override suspend fun submit(remoteId: Int, readings: Map<Int, List<Double>>, realAreaM2: Double?) =
                SurveyValidationDto(9, ErrorReportDto(4, 0.02, 0.025, -0.01, 0.02, 0.5, null, true, 3.0, emptyList()))
        }
        compose.setContent { MetrikTheme { ValidationScreen(ValidationViewModel(1, OneSurvey(), validation), {}) } }
        compose.onNodeWithTag("reading_0_1").performTextInput("abc")
        compose.onNodeWithTag("reading_1_1").performTextInput("0")
        compose.onNodeWithTag("reading_2_1").performTextInput("200.01")
        compose.onNodeWithTag("validation_submit").performScrollTo().performClick()
        compose.onNodeWithText("No es un número").assertExists()
        compose.onNodeWithText("Debe ser mayor que 0").assertExists()
        compose.onNodeWithText("Máximo 200 m").assertExists()
        listOf("5", "4,00", "5", "4").forEachIndexed { i, v ->
            compose.onNodeWithTag("reading_${i}_1").performScrollTo().performTextClearance()
            compose.onNodeWithTag("reading_${i}_1").performTextInput(v)
        }
        compose.onNodeWithTag("validation_submit").performScrollTo().performClick()
        compose.onNodeWithTag("validation_verdict").performScrollTo().assertIsDisplayed()
        ev.obtenido = "3 errores mostrados; con datos válidos → 'Cumple el objetivo (≤ 3 %)'"
        ev.capture()
    }

    @Test
    @Evidencia(
        id = "FC-04", nombre = "Ajustes: modo de medición", requisito = "RNF-06",
        objetivo = "Verificar que el usuario elige cómo medir y que no se muestra ninguna configuración técnica de conexión.",
        entrada = "Tocar 'Pasos' y luego 'Cámara (ARCore)'",
        esperado = "Se guarda la opción elegida; no existe campo de URL del servidor",
    )
    fun modoAjustes() {
        val session = MemSession()
        compose.setContent { MetrikTheme { SettingsScreen(session, {}, arSupportedOverride = true) } }
        compose.onNodeWithTag("mode_steps").performClick()
        compose.waitUntil(3_000) { session.session.value.odometryMode == "steps" }
        compose.onNodeWithTag("mode_arcore").performClick()
        compose.waitUntil(3_000) { session.session.value.odometryMode == "arcore" }
        val sinUrl = compose.onAllNodes(androidx.compose.ui.test.hasTestTag("settings_url")).fetchSemanticsNodes().isEmpty()
        ev.obtenido = "modo guardado=${session.session.value.odometryMode}; sin campo de URL=$sinUrl"
        assertTrue(sinUrl)
        ev.capture()
    }

    @Test
    @Evidencia(
        id = "FC-05", nombre = "Plano: cambio de escala y distancias", requisito = "RF-07, RF-10",
        objetivo = "Verificar que al elegir otra escala cambian las medidas en papel y que se muestran ambas distancias.",
        entrada = "Aula 5×4 m; escala 1:50 → 1:100; vértices V1 y V3",
        esperado = "Muro 1: 100.0 mm → 50.0 mm; Euclidiana 6.40 m y Manhattan 9.00 m",
    )
    fun planEscala() {
        val noPlan = object : PlanRepository {
            override suspend fun exportDxf(remoteId: Int, scale: Int) = ByteArray(0)
            override suspend fun ml(remoteId: Int): MlAnalysisDto = error("no usado")
        }
        compose.setContent { MetrikTheme { PlanScreen(PlanViewModel(1, OneSurvey(), noPlan) { _, _ -> "" }, {}, {}) } }
        compose.onNodeWithTag("wall_0").performScrollTo()
        compose.onNodeWithText("100.0 mm", substring = true).assertExists()
        compose.onNodeWithTag("scale_100").performScrollTo().performClick()
        compose.onNodeWithText("50.0 mm", substring = true).assertExists()
        compose.onNodeWithTag("dist_euclid").performScrollTo()
        compose.onNodeWithText("6.40 m").assertExists()
        compose.onNodeWithText("9.00 m").assertExists()
        ev.obtenido = "1:50 → 100.0 mm; 1:100 → 50.0 mm; Euclidiana 6.40 m; Manhattan 9.00 m"
        ev.capture()
    }
}
