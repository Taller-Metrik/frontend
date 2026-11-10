package com.metrik.app.data

import com.metrik.app.core.geometry.Point
import com.metrik.app.data.remote.ApiClient
import com.metrik.app.data.remote.userMessage
import com.metrik.app.data.repository.DefaultAuthRepository
import com.metrik.app.data.repository.DefaultPlanRepository
import com.metrik.app.data.repository.DefaultProjectRepository
import com.metrik.app.data.repository.DefaultSurveyRepository
import com.metrik.app.data.repository.DefaultValidationRepository
import com.metrik.app.data.session.Session
import com.metrik.app.domain.CaptureInfo
import com.metrik.app.domain.LocalSurvey
import com.metrik.app.domain.SurveyMode
import com.metrik.app.testing.EvidenceRule
import com.metrik.app.testing.Evidencia
import com.metrik.app.testing.FakeSessionStore
import com.metrik.app.testing.InMemorySurveyDao
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Pruebas de integración: Retrofit + serialización + interceptor de token + repositorios,
 * contra un servidor HTTP simulado que responde como la API de MetriK.
 */
class ApiIntegrationTest {
    @get:Rule val ev = EvidenceRule()
    private lateinit var server: MockWebServer
    private lateinit var session: FakeSessionStore
    private lateinit var api: ApiClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        session = FakeSessionStore(Session(baseUrl = server.url("/api/v1/").toString()))
        api = ApiClient({ session.session.value.baseUrl }, { session.session.value.token })
    }

    @After fun tearDown() = server.close()

    private fun json(code: Int, body: String) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    @Test
    @Evidencia(
        id = "FI-01", nombre = "Login: formulario, token guardado y cabecera Authorization", requisito = "RF-16",
        objetivo = "Verificar que el login envía form-urlencoded, guarda el token y lo usa en la siguiente petición.",
        entrada = "Respuestas simuladas de /auth/login y /auth/me",
        esperado = "Body username=ana%40metrik.pe…, token guardado, /me con 'Bearer tok123'",
    )
    fun loginGuardaTokenYLoUsa() = runBlocking {
        server.enqueue(json(200, """{"access_token":"tok123","token_type":"bearer"}"""))
        server.enqueue(json(200, """{"id":1,"email":"ana@metrik.pe","full_name":"Ana","is_active":true,"created_at":"2026-01-01T00:00:00Z"}"""))
        DefaultAuthRepository(api, session).login("Ana@MetriK.pe", "clave-segura-123")
        val r1 = server.takeRequest()
        val r2 = server.takeRequest()
        val body = r1.body!!.utf8()
        ev.obtenido = "login=${r1.method} ${r1.url.encodedPath} body='$body', /me Authorization='${r2.headers["Authorization"]}', " +
            "usuario='${session.session.value.userName}'"
        assertEquals("/api/v1/auth/login", r1.url.encodedPath)
        assertTrue(body.startsWith("username=ana%40metrik.pe"))
        assertEquals("Bearer tok123", r2.headers["Authorization"])
        assertEquals("Ana", session.session.value.userName)
    }

    @Test
    @Evidencia(
        id = "FI-02", nombre = "Mensaje de error del backend", requisito = "RF-16",
        objetivo = "Verificar que los errores HTTP de FastAPI se muestran en español al usuario.",
        entrada = "401 con detail 'Correo o contraseña incorrectos' y 422 con lista de errores", esperado = "Mensajes legibles",
    )
    fun erroresLegibles() = runBlocking {
        server.enqueue(json(401, """{"detail":"Correo o contraseña incorrectos"}"""))
        server.enqueue(json(422, """{"detail":[{"msg":"El modo interior requiere el trazo"}]}"""))
        val e1 = runCatching { api.api().login("a@b.co", "x") }.exceptionOrNull()!!.userMessage()
        val e2 = runCatching { api.api().projects() }.exceptionOrNull()!!.userMessage()
        ev.obtenido = "401 → '$e1' | 422 → '$e2'"
        assertEquals("Correo o contraseña incorrectos", e1)
        assertEquals("El modo interior requiere el trazo", e2)
    }

    @Test
    @Evidencia(
        id = "FI-03", nombre = "Crear y listar proyectos", requisito = "RF-16",
        entrada = "POST /projects (descripción en blanco) y GET /projects simulados",
        esperado = "JSON {\"name\":\"Pabellón A\"} (nombre recortado, descripción vacía omitida) y lista deserializada",
    )
    fun proyectos() = runBlocking {
        server.enqueue(json(201, """{"id":7,"name":"Pabellón A","description":null,"created_at":"2026-01-01T00:00:00Z"}"""))
        server.enqueue(json(200, """[{"id":7,"name":"Pabellón A","description":null,"created_at":"2026-01-01T00:00:00Z"}]"""))
        val repo = DefaultProjectRepository(api)
        val created = repo.create(" Pabellón A ", "  ")
        val list = repo.list()
        val body = server.takeRequest().body!!.utf8()
        ev.obtenido = "body=$body, creado=#${created.id}, lista=${list.map { it.name }}"
        assertEquals("""{"name":"Pabellón A"}""", body)
        assertEquals(listOf("Pabellón A"), list.map { it.name })
    }

    @Test
    @Evidencia(
        id = "FI-04", nombre = "Sincronizar recorrido medido sin conexión", requisito = "RF-17",
        objetivo = "Verificar que el recorrido guardado localmente se envía con su trazo y metadatos de captura, y se marca sincronizado.",
        entrada = "Recorrido local (3 puntos, ARCore 20 s) y respuesta 201 con id 55",
        esperado = "POST con points/capture, remoteId=55 y 0 pendientes en la BD local",
    )
    fun sincronizarRecorrido() = runBlocking {
        server.enqueue(json(201, """{"id":55,"project_id":3,"name":"Aula","mode":"interior","area_m2":20.0,"perimeter_m":18.0,"scale_denominator":50,"created_at":"2026-01-01T00:00:00Z"}"""))
        val dao = InMemorySurveyDao()
        val repo = DefaultSurveyRepository(api, dao)
        val id = repo.saveLocal(
            LocalSurvey(
                projectId = 3, name = "Aula", mode = SurveyMode.INTERIOR,
                points = listOf(Point(0.0, 0.0), Point(5.0, 0.0), Point(5.0, 4.0)),
                capture = CaptureInfo("arcore", 0.5, 20.0),
                vertices = listOf(Point(0.0, 0.0), Point(5.0, 0.0), Point(5.0, 4.0), Point(0.0, 4.0)),
                areaM2 = 20.0, perimeterM = 18.0, scaleDenominator = 50,
            ),
        )
        val synced = repo.sync(id)
        val req = server.takeRequest()
        val body = req.body!!.utf8()
        ev.obtenido = "${req.method} ${req.url.encodedPath}, remoteId=${synced.remoteId}, pendientes=${dao.pending().size}, " +
            "body con points y capture=${"\"points\":[[0.0,0.0]" in body && "\"tracking_loss_s\":0.5" in body}"
        assertEquals("/api/v1/projects/3/surveys", req.url.encodedPath)
        assertTrue("\"capture\":{\"odometry\":\"arcore\"" in body)
        assertEquals(55, synced.remoteId)
        assertEquals(0, dao.pending().size)
    }

    @Test
    @Evidencia(
        id = "FI-05", nombre = "Descarga del DXF y análisis ML", requisito = "RF-12, RF-15",
        entrada = "GET /surveys/55/export/dxf?scale=100 y GET /surveys/55/ml simulados",
        esperado = "Bytes del DXF recibidos completos; probabilidad y muros corregidos deserializados",
    )
    fun dxfYMl() = runBlocking {
        val dxf = "0\nSECTION\n2\nHEADER\n0\nENDSEC\n0\nEOF\n"
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/dxf").body(dxf).build())
        server.enqueue(json(200, """{"survey_id":55,"reliability_probability":0.93,"reliable":true,"recommendation":"ok","walls":[{"wall_index":0,"measured_m":4.94,"corrected_m":5.0,"correction_m":0.06}],"corrected_perimeter_m":5.0,"corrector_model":"x","classifier_model":"y","version":"v"}"""))
        val repo = DefaultPlanRepository(api)
        val bytes = repo.exportDxf(55, 100)
        val ml = repo.ml(55)
        val req = server.takeRequest()
        ev.obtenido = "${req.url.encodedPath}?${req.url.query} → ${bytes.size} bytes; P=${ml.reliabilityProbability}, " +
            "muro 1 ${ml.walls[0].measuredM}→${ml.walls[0].correctedM}"
        assertEquals("scale=100", req.url.query)
        assertEquals(dxf, bytes.decodeToString())
        assertEquals(5.0, ml.walls[0].correctedM, 1e-9)
    }

    @Test
    @Evidencia(
        id = "FI-11", nombre = "Descarga del informe técnico en PDF", requisito = "RF-28 (CU-10)",
        entrada = "GET /surveys/55/report.pdf simulado",
        esperado = "Ruta /api/v1/surveys/55/report.pdf y bytes del PDF recibidos completos",
    )
    fun informeTecnicoPdf() = runBlocking {
        val pdf = "%PDF-1.4\n% MetriK\n%%EOF\n"
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/pdf").body(pdf).build())
        val bytes = DefaultPlanRepository(api).reportPdf(55)
        val req = server.takeRequest()
        ev.obtenido = "${req.url.encodedPath} → ${bytes.size} bytes"
        assertTrue(req.url.encodedPath.endsWith("/surveys/55/report.pdf"))
        assertEquals(pdf, bytes.decodeToString())
    }

    @Test
    @Evidencia(
        id = "FI-06", nombre = "Enviar lecturas de wincha", requisito = "RF-13, RF-14",
        entrada = "Muros {1:[4.0,4.001], 0:[5.0,5.002]} y área real 20",
        esperado = "PUT con muros ordenados por índice; reporte deserializado",
    )
    fun enviarWincha() = runBlocking {
        server.enqueue(json(200, """{"survey_id":55,"survey_name":"Aula","report":{"n":2,"mae_m":0.02,"rmse_m":0.03,"bias_m":-0.01,"std_m":0.01,"mean_rel_error_pct":0.45,"max_abs_error_m":0.03,"max_rel_error_pct":0.6,"area_error_pct":1.0,"meets_target":true,"target_pct":3.0,"walls":[]},"warnings":[]}"""))
        val r = DefaultValidationRepository(api).submit(55, mapOf(1 to listOf(4.0, 4.001), 0 to listOf(5.0, 5.002)), 20.0)
        val body = server.takeRequest().body!!.utf8()
        ev.obtenido = "body=$body → MAE=${r.report.maeM}, cumple=${r.report.meetsTarget}"
        assertEquals("""{"walls":[{"wall_index":0,"readings_m":[5.0,5.002]},{"wall_index":1,"readings_m":[4.0,4.001]}],"real_area_m2":20.0}""", body)
        assertTrue(r.report.meetsTarget)
    }

    @Test
    @Evidencia(
        id = "FI-07", nombre = "Cambio de URL del servidor en caliente", requisito = "RNF-09",
        objetivo = "Verificar que al cambiar la URL en Ajustes las siguientes llamadas van al nuevo servidor sin reiniciar.",
        entrada = "Dos servidores simulados; cambiar la URL entre llamadas", esperado = "Cada llamada llega al servidor configurado en ese momento",
    )
    fun cambioDeUrl() = runBlocking {
        val second = MockWebServer().apply { start() }
        server.enqueue(json(200, "[]"))
        second.enqueue(json(200, "[]"))
        api.api().projects()
        session.setBaseUrl(second.url("/api/v1/").toString())
        api.api().projects()
        ev.obtenido = "servidor 1: ${server.requestCount} petición(es), servidor 2: ${second.requestCount} petición(es)"
        assertEquals(1, server.requestCount)
        assertEquals(1, second.requestCount)
        second.close()
    }

    @Test
    @Evidencia(
        id = "FI-10", nombre = "Abrir una medición del servidor con sus vértices", requisito = "RF-17",
        objetivo = "Verificar que al abrir una medición guardada en el servidor se descarga el detalle completo (la lista es un resumen sin vértices) y que una copia vacía se repara.",
        entrada = "GET /surveys/18 con 5 vértices; copia local previa sin vértices",
        esperado = "La copia local queda con 5 vértices y el mismo id local",
    )
    fun descargaDetalleDeMedicion() = runBlocking {
        val detalle = """{"id":18,"project_id":2,"name":"corral","mode":"interior","area_m2":23.1,"perimeter_m":19.4,"closed":true,
            "scale_denominator":25,"vertices":[[0,0],[5,0],[5,4],[2,5],[0,4]],"wall_lengths_m":[5,4,3.2,2.2,4]}"""
        server.enqueue(json(200, detalle))
        val dao = InMemorySurveyDao()
        val repo = DefaultSurveyRepository(api, dao)
        val vacia = repo.saveLocal(LocalSurvey(projectId = 2, name = "corral", mode = SurveyMode.INTERIOR, vertices = emptyList(), areaM2 = 23.1, perimeterM = 19.4, scaleDenominator = 25, remoteId = 18))
        val id = repo.downloadRemote(2, 18, vacia)
        val r = server.takeRequest()
        val s = repo.getLocal(id)!!
        ev.obtenido = "petición=${r.method} ${r.url.encodedPath}, id local=$id (antes $vacia), vértices=${s.vertices.size}, área=${s.areaM2}"
        assertEquals("/api/v1/surveys/18", r.url.encodedPath)
        assertEquals(vacia, id)
        assertEquals(5, s.vertices.size)
    }
}
