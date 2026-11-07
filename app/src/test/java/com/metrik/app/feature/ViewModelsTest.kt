package com.metrik.app.feature

import com.metrik.app.core.geometry.Point
import com.metrik.app.data.repository.PlanRepository
import com.metrik.app.domain.GpsSample
import com.metrik.app.domain.LocalSurvey
import com.metrik.app.domain.SurveyMode
import com.metrik.app.feature.auth.AuthValidator
import com.metrik.app.feature.auth.AuthViewModel
import com.metrik.app.feature.gps.GpsPhase
import com.metrik.app.feature.gps.GpsViewModel
import com.metrik.app.feature.measure.MeasurePhase
import com.metrik.app.feature.measure.MeasureViewModel
import com.metrik.app.feature.measure.resolveOdometryMode
import com.metrik.app.feature.plan.PlanViewModel
import com.metrik.app.feature.validation.ReadingValidator
import com.metrik.app.gps.LocationSource
import com.metrik.app.testing.SimulatedOdometrySource
import com.metrik.app.testing.EvidenceRule
import com.metrik.app.testing.Evidencia
import com.metrik.app.testing.FakeAuthRepository
import com.metrik.app.testing.FakeSurveyRepository
import com.metrik.app.testing.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.cos

/** Pruebas unitarias de ViewModels y validadores de la app. */
class ViewModelsTest {
    @get:Rule val ev = EvidenceRule()
    @get:Rule val main = MainDispatcherRule()

    @Test
    @Evidencia(
        id = "FU-16", nombre = "Validación del formulario de login", requisito = "RF-16",
        objetivo = "Verificar que el ViewModel no llama al servidor si el correo o la contraseña son inválidos.",
        entrada = "Correo 'ana@' y contraseña vacía; luego datos válidos",
        esperado = "Errores en ambos campos y 0 llamadas; luego 1 llamada y success",
    )
    fun loginValidacion() {
        val repo = FakeAuthRepository()
        val vm = AuthViewModel(repo)
        vm.onEmail("ana@"); vm.login()
        val s1 = vm.state.value
        vm.onEmail("ana@metrik.pe"); vm.onPassword("clave-segura-123"); vm.login()
        ev.obtenido = "errores=(${s1.emailError}, ${s1.passwordError}), llamadas=${repo.calls}, success=${vm.state.value.success}"
        assertNotNull(s1.emailError); assertNotNull(s1.passwordError)
        assertEquals(listOf("login:ana@metrik.pe"), repo.calls)
        assertTrue(vm.state.value.success)
    }

    @Test
    @Evidencia(
        id = "FU-17", nombre = "Error del servidor visible en el login", requisito = "RF-16",
        entrada = "El repositorio lanza 'Correo o contraseña incorrectos'", esperado = "error mostrado y success=false",
    )
    fun loginErrorServidor() {
        val vm = AuthViewModel(FakeAuthRepository(failWith = IllegalStateException("Correo o contraseña incorrectos")))
        vm.onEmail("ana@metrik.pe"); vm.onPassword("x"); vm.login()
        ev.obtenido = "error='${vm.state.value.error}', success=${vm.state.value.success}"
        assertEquals("Correo o contraseña incorrectos", vm.state.value.error)
    }

    @Test
    @Evidencia(
        id = "FU-18", nombre = "Recorrido completo en el ViewModel de medición", requisito = "RF-01 a RF-06",
        objetivo = "Verificar iniciar → esquinas → finalizar → guardar con el odómetro de demostración (aula 5×4 m).",
        entrada = "SimulatedOdometrySource (solo pruebas) acelerada, esquina en cada vértice, nombre 'Aula demo'",
        esperado = "Plano de 4 muros, área 20 m² ± 2 %, guardado con capture.odometry=steps (fuente simulada de prueba)",
    )
    fun medicionDemo() = runTest {
        val repo = FakeSurveyRepository()
        val vm = MeasureViewModel(projectId = 1, surveys = repo)
        vm.start(SimulatedOdometrySource(timeScale = 1000.0, noiseM = 0.0))
        // Esquina al llegar a cada vértice del aula 5 × 4 m (a 5, 9, 14 y 18 m recorridos)
        for (d in listOf(4.95, 8.95, 13.95, 17.9)) {
            withTimeout(20_000) { vm.state.first { it.totalM > d } }
            vm.markCorner()
        }
        vm.finish()
        val st = vm.state.value
        vm.save("Aula demo")
        withTimeout(5_000) { vm.state.first { it.savedLocalId != null } }
        val saved = repo.saved.value.values.single()
        ev.obtenido = "fase=${st.phase}, muros=${st.analysis?.wallLengthsM?.size}, área=${"%.3f".format(st.analysis?.areaM2)} m², " +
            "escala=1:${st.scaleDenominator}, guardado='${saved.name}' odómetro=${saved.capture?.odometry}"
        assertEquals(MeasurePhase.FINISHED, st.phase)
        assertEquals(4, st.analysis!!.wallLengthsM.size)
        assertEquals(20.0, st.analysis!!.areaM2, 0.4)
        assertEquals("steps", saved.capture?.odometry)
    }

    @Test
    @Evidencia(
        id = "FU-19", nombre = "Recorrido demasiado corto", requisito = "EX-05",
        entrada = "Finalizar sin haber recorrido", esperado = "Fase FINISHED con mensaje de error y sin plano",
    )
    fun recorridoCorto() {
        val vm = MeasureViewModel(1, FakeSurveyRepository())
        vm.start(SimulatedOdometrySource(polygon = listOf(Point(0.0, 0.0), Point(0.05, 0.0), Point(0.05, 0.05)), timeScale = 1000.0))
        vm.finish()
        ev.obtenido = "error='${vm.state.value.error}', polígono=${vm.state.value.polygon.size}"
        assertNotNull(vm.state.value.error)
        assertTrue(vm.state.value.polygon.isEmpty())
    }

    @Test
    @Evidencia(
        id = "FU-20", nombre = "GPS: calibración y área en el ViewModel", requisito = "RF-08, RF-09",
        objetivo = "Verificar calibrar (lecturas quietas) → recorrer → finalizar → guardar en modo exterior.",
        entrada = "15 lecturas quietas (precisión 3 m) y recorrido de 100×60 m",
        esperado = "READY sin calibrar, área 6000 m² ± 3 %, guardado EXTERIOR 1:500",
    )
    fun gpsViewModel() = runBlocking {
        val lat0 = -8.13
        val lon0 = -79.04
        fun at(x: Double, y: Double, t: Double) =
            GpsSample(lat0 + Math.toDegrees(y / 6_371_000.0), lon0 + Math.toDegrees(x / (6_371_000.0 * cos(Math.toRadians(lat0)))), 3.0, t)
        var recording = false
        val source = object : LocationSource {
            override val samples = flow {
                if (!recording) {
                    repeat(15) { emit(at(0.0, 0.0, it.toDouble())) }
                } else {
                    val poly = listOf(0.0 to 0.0, 100.0 to 0.0, 100.0 to 60.0, 0.0 to 60.0)
                    for (i in 0..3) for (k in 0 until 40) {
                        val (x1, y1) = poly[i]
                        val (x2, y2) = poly[(i + 1) % 4]
                        emit(at(x1 + (x2 - x1) * k / 40, y1 + (y2 - y1) * k / 40, k.toDouble()))
                    }
                }
            }
        }
        val repo = FakeSurveyRepository()
        val vm = GpsViewModel(1, source, repo)
        vm.calibrate()
        val cal = vm.state.value
        recording = true
        vm.startRecording(); vm.finish(); vm.save("Cancha")
        withTimeout(5_000) { vm.state.first { it.savedLocalId != null } }
        val saved = repo.saved.value.values.single()
        ev.obtenido = "fase calibración=${cal.phase}, calibrar=${cal.calibration?.needsCalibration}, " +
            "área=${"%.1f".format(vm.state.value.analysis?.areaM2)} m², guardado=${saved.mode} 1:${saved.scaleDenominator}"
        assertEquals(GpsPhase.READY, cal.phase)
        assertEquals(6000.0, vm.state.value.analysis!!.areaM2, 180.0)
        assertEquals(SurveyMode.EXTERIOR, saved.mode)
        assertEquals(500, saved.scaleDenominator)
    }

    @Test
    @Evidencia(
        id = "FU-21", nombre = "Plano: medidas en papel y nombre del DXF", requisito = "RF-10, RF-12",
        entrada = "Aula 5×4 m guardada; escala 1:50 → 1:100; nombre 'Aula Ñandú 2'",
        esperado = "1:50 → [100, 80, 100, 80] mm; 1:100 → [50, 40, 50, 40] mm; archivo metrik_aula_nandu_2_1-100.dxf",
    )
    fun planEscalas() = runBlocking {
        val repo = FakeSurveyRepository()
        val id = repo.saveLocal(
            LocalSurvey(
                projectId = 1, name = "Aula Ñandú 2", mode = SurveyMode.INTERIOR,
                vertices = listOf(Point(0.0, 0.0), Point(5.0, 0.0), Point(5.0, 4.0), Point(0.0, 4.0)),
                areaM2 = 20.0, perimeterM = 18.0, scaleDenominator = 50,
            ),
        )
        val noPlan = object : PlanRepository {
            override suspend fun exportDxf(remoteId: Int, scale: Int) = ByteArray(0)
            override suspend fun ml(remoteId: Int) = error("no usado")
        }
        val vm = PlanViewModel(id, repo, noPlan) { _, _ -> "" }
        withTimeout(5_000) { vm.state.first { it.analysis != null } }
        val a = vm.paperMm()
        vm.setScale(100)
        val b = vm.paperMm()
        val file = PlanViewModel.dxfFileName("Aula Ñandú 2", 100)
        ev.obtenido = "1:50=$a, 1:100=$b, archivo=$file"
        assertEquals(listOf(100L, 80L, 100L, 80L), a.map { Math.round(it) })
        assertEquals(listOf(50L, 40L, 50L, 40L), b.map { Math.round(it) })
        assertEquals("metrik_aula_nandu_2_1-100.dxf", file)
    }

    @Test
    @Evidencia(
        id = "FU-22", nombre = "Validadores de entrada (correo, contraseña, lecturas, modo)", requisito = "RF-13, RF-16",
        objetivo = "Verificar las reglas puras que usan las pantallas.",
        entrada = "Varios valores válidos e inválidos", esperado = "Cada regla acepta/rechaza según la partición",
    )
    fun validadores() {
        val r = listOf(
            AuthValidator.email("a@b.co") == null, AuthValidator.email("a@b") != null,
            AuthValidator.password("1234567") != null, AuthValidator.password("12345678") == null,
            ReadingValidator.parse("4,98") == ReadingValidator.Result.Ok(4.98), ReadingValidator.parse("0") is ReadingValidator.Result.Invalid,
            ReadingValidator.parse("") == ReadingValidator.Result.Empty, ReadingValidator.parse("200.01") is ReadingValidator.Result.Invalid,
            resolveOdometryMode("auto", false) == "steps", resolveOdometryMode("arcore", true) == "arcore",
        )
        ev.obtenido = "${r.count { it }}/${r.size} reglas correctas"
        assertTrue(r.all { it })
        assertNull(AuthValidator.name("Ana"))
    }
}
