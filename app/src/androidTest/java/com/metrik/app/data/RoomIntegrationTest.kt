package com.metrik.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrik.app.core.geometry.Point
import com.metrik.app.data.local.MetrikDatabase
import com.metrik.app.data.remote.ApiClient
import com.metrik.app.data.repository.DefaultSurveyRepository
import com.metrik.app.domain.CaptureInfo
import com.metrik.app.domain.GpsSample
import com.metrik.app.domain.LocalSurvey
import com.metrik.app.domain.SurveyMode
import com.metrik.app.testing.DeviceEvidenceRule
import com.metrik.app.testing.Evidencia
import com.metrik.app.testing.FakeBackend
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pruebas de integración en el dispositivo: Room (SQLite real) + repositorio + API simulada. */
@RunWith(AndroidJUnit4::class)
class RoomIntegrationTest {
    @get:Rule val ev = DeviceEvidenceRule(screenshot = false)
    private lateinit var db: MetrikDatabase

    @Before fun setUp() { db = MetrikDatabase.inMemory(ApplicationProvider.getApplicationContext()) }
    @After fun tearDown() = db.close()

    private fun survey(name: String, project: Int) = LocalSurvey(
        projectId = project, name = name, mode = SurveyMode.INTERIOR,
        points = listOf(Point(0.0, 0.0), Point(5.0, 0.0), Point(5.0, 4.0)),
        gps = listOf(GpsSample(-8.13, -79.04, 3.5, 1.0)),
        capture = CaptureInfo("arcore", 1.25, 21.0),
        vertices = listOf(Point(0.0, 0.0), Point(5.0, 0.0), Point(5.0, 4.0), Point(0.0, 4.0)),
        areaM2 = 20.0, perimeterM = 18.0, scaleDenominator = 50,
    )

    @Test
    @Evidencia(
        id = "FI-08", nombre = "Room guarda y recupera el recorrido sin pérdida", requisito = "RF-17, RNF-04",
        objetivo = "Verificar que trazo, GPS y metadatos de captura se guardan en SQLite y se leen idénticos.",
        entrada = "Recorrido con 3 puntos, 1 lectura GPS y captura ARCore",
        esperado = "Objeto leído igual al guardado; la consulta por proyecto filtra",
    )
    fun guardarYLeer() = runBlocking {
        val repo = DefaultSurveyRepository(ApiClient({ "http://localhost/" }, { null }), db.surveyDao())
        val id = repo.saveLocal(survey("Aula 201", 1))
        repo.saveLocal(survey("Otro proyecto", 2))
        val leido = repo.getLocal(id)!!
        val delProyecto = repo.observeLocal(1).first()
        ev.obtenido = "leído='${leido.name}', puntos=${leido.points.size}, gps=${leido.gps?.size}, " +
            "captura=${leido.capture}, en proyecto 1=${delProyecto.size}"
        assertEquals(survey("Aula 201", 1).copy(id = id, createdAt = leido.createdAt), leido)
        assertEquals(1, delProyecto.size)
    }

    @Test
    @Evidencia(
        id = "FI-09", nombre = "Sincronización de pendientes con Room y la API", requisito = "RF-17, EX-07",
        objetivo = "Verificar que los recorridos medidos sin conexión quedan pendientes y se envían todos al sincronizar.",
        entrada = "2 recorridos locales sin id remoto; backend simulado", esperado = "2 enviados, 0 pendientes, ids remotos asignados",
    )
    fun sincronizarPendientes() = runBlocking {
        val server = MockWebServer().apply { dispatcher = FakeBackend(); start() }
        val repo = DefaultSurveyRepository(ApiClient({ server.url("/api/v1/").toString() }, { "tok" }), db.surveyDao())
        repo.saveLocal(survey("A", 1))
        repo.saveLocal(survey("B", 1))
        val antes = db.surveyDao().pending().size
        val enviados = repo.syncPending()
        val despues = db.surveyDao().pending().size
        val ids = repo.observeLocal(1).first().map { it.remoteId }
        ev.obtenido = "pendientes antes=$antes, enviados=$enviados, pendientes después=$despues, ids remotos=$ids"
        assertEquals(2, enviados)
        assertEquals(0, despues)
        server.close()
    }
}
