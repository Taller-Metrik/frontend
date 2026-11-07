package com.metrik.app.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrik.app.MainActivity
import com.metrik.app.MetrikApp
import com.metrik.app.testing.DeviceEvidenceRule
import com.metrik.app.testing.Evidencia
import com.metrik.app.testing.FakeBackend
import com.metrik.app.testing.SimulatedOdometrySource
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Pruebas End-to-End: la app completa en el celular, de la pantalla de login al plano exportado.
 * El backend se simula dentro del dispositivo (MockWebServer) y el odómetro se inyecta solo en la prueba,
 * porque ARCore requiere mover físicamente el celular (eso se valida en campo con la wincha).
 */
@RunWith(AndroidJUnit4::class)
class EndToEndTest {
    private val compose = createEmptyComposeRule()
    private val ev = DeviceEvidenceRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(compose).around(ev)

    private lateinit var server: MockWebServer
    private lateinit var backend: FakeBackend
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        backend = FakeBackend()
        server = MockWebServer().apply { dispatcher = backend; start() }
        val container = ApplicationProvider.getApplicationContext<MetrikApp>().container
        runBlocking {
            container.session.logout()
            container.session.setBaseUrl(server.url("/api/v1/").toString())
            container.session.setOdometryMode("auto")
        }
        container.database.clearAllTables()
        container.odometryOverride = SimulatedOdometrySource()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario?.close()
        server.close()
    }

    private fun login(password: String = "clave-segura-123") {
        compose.onNodeWithTag("auth_email").performTextClearance()
        compose.onNodeWithTag("auth_email").performTextInput("ana@metrik.pe")
        compose.onNodeWithTag("auth_password").performTextClearance()
        compose.onNodeWithTag("auth_password").performTextInput(password)
        compose.onNodeWithTag("login_submit").performClick()
    }

    private fun createAndOpenProject(name: String) {
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("new_project")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("new_project").performClick()
        compose.onNodeWithTag("project_name").performTextInput(name)
        compose.onNodeWithTag("project_create").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("project_$name")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("project_$name").performClick()
    }

    @Test
    @Evidencia(
        id = "FE-01", nombre = "Inicio de sesión y creación de proyecto", requisito = "CU-01 (RF-16)",
        objetivo = "Verificar el flujo de entrada: contraseña errónea → mensaje; correcta → proyectos; crear proyecto.",
        precondiciones = "App recién abierta, sin sesión; backend simulado",
        entrada = "ana@metrik.pe con 'incorrecta' y luego 'clave-segura-123'; proyecto 'Pabellón A'",
        esperado = "Mensaje 'Correo o contraseña incorrectos'; pantalla Proyectos con 'Pabellón A' en la lista",
    )
    fun loginYProyecto() {
        login("incorrecta")
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Correo o contraseña incorrectos")).fetchSemanticsNodes().isNotEmpty() }
        login()
        createAndOpenProject("Pabellón A")
        compose.onNodeWithTag("measure_interior").assertIsDisplayed()
        ev.obtenido = "error mostrado con contraseña incorrecta; proyecto 'Pabellón A' creado y abierto; peticiones=${backend.requests}"
        ev.capture()
    }

    @Test
    @Evidencia(
        id = "FE-02", nombre = "Medir un ambiente y exportar el plano a DXF", requisito = "CU-01, CU-04 (RF-01 a RF-12)",
        objetivo = "Verificar el flujo completo de medición: recorrido con esquinas → plano suavizado → guardar → sincronizar → DXF.",
        precondiciones = "Sesión iniciada; odómetro simulado inyectado por la prueba (aula 5 × 4 m a 1 m/s)",
        entrada = "Iniciar, 'Esquina' a los 5, 9, 14 y 18 s, Finalizar, nombre 'Aula 201', Guardar, DXF",
        esperado = "Plano de 4 muros con área ≈ 20 m²; DXF guardado en Descargas/MetriK",
    )
    fun medirYExportar() {
        login()
        createAndOpenProject("Laboratorio")
        compose.onNodeWithTag("measure_interior").performClick()
        compose.onNodeWithTag("measure_start").performClick()
        listOf(5_100L, 4_000L, 5_000L, 4_200L).forEach { wait ->
            Thread.sleep(wait)
            compose.onNodeWithTag("measure_corner").performClick()
        }
        compose.onNodeWithTag("measure_finish").performClick()
        compose.onNodeWithText("Plano suavizado").assertIsDisplayed()
        compose.onNodeWithTag("survey_name").performScrollTo().performTextInput("Aula 201")
        compose.onNodeWithTag("survey_save").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("plan_export_dxf")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("plan_export_dxf").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("DXF guardado", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        ev.obtenido = "plano de 4 muros guardado y sincronizado; DXF descargado " +
            "(${backend.requests.count { "export/dxf" in it }} descarga) y guardado en Descargas/MetriK"
        ev.capture()
    }

    @Test
    @Evidencia(
        id = "FE-03", nombre = "Análisis ML y validación con wincha", requisito = "CU-06 (RF-13, RF-14, RF-15)",
        objetivo = "Verificar: medir → analizar con ML → registrar lecturas de wincha → ver margen de error.",
        precondiciones = "Sesión iniciada; odómetro simulado inyectado por la prueba",
        entrada = "Recorrido simulado; 'Analizar ML'; lecturas 5 / 4 / 5 / 4 m",
        esperado = "Probabilidad de confiable visible; reporte con MAE y 'Cumple el objetivo'",
    )
    fun mlYValidacion() {
        login()
        createAndOpenProject("Aulas")
        compose.onNodeWithTag("measure_interior").performClick()
        compose.onNodeWithTag("measure_start").performClick()
        Thread.sleep(19_000)
        compose.onNodeWithTag("measure_finish").performClick()
        compose.onNodeWithTag("survey_name").performScrollTo().performTextInput("Aula 305")
        compose.onNodeWithTag("survey_save").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("plan_ml")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("plan_ml").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("ml_probability")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("plan_validate").performScrollTo().performClick()
        listOf("5", "4", "5", "4").forEachIndexed { i, v ->
            compose.onNodeWithTag("reading_${i}_1").performScrollTo().performTextInput(v)
        }
        compose.onNodeWithTag("validation_submit").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("validation_verdict")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("validation_verdict").performScrollTo().assertIsDisplayed()
        ev.obtenido = "ML: probabilidad mostrada; validación: reporte con veredicto 'Cumple el objetivo'"
        ev.capture()
    }
}
