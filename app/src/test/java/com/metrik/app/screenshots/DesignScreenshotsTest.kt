package com.metrik.app.screenshots

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.metrik.app.core.geometry.Point
import com.metrik.app.data.remote.ErrorReportDto
import com.metrik.app.data.remote.MlAnalysisDto
import com.metrik.app.data.remote.ProjectDto
import com.metrik.app.data.remote.SurveyValidationDto
import com.metrik.app.data.remote.WallComparisonDto
import com.metrik.app.data.remote.WallCorrectionDto
import com.metrik.app.data.repository.PlanRepository
import com.metrik.app.data.repository.ValidationRepository
import com.metrik.app.domain.GpsSample
import com.metrik.app.domain.LocalSurvey
import com.metrik.app.domain.SurveyMode
import com.metrik.app.feature.auth.AuthViewModel
import com.metrik.app.feature.dashboard.DashboardScreen
import com.metrik.app.feature.dashboard.DashboardViewModel
import com.metrik.app.feature.auth.LoginScreen
import com.metrik.app.feature.auth.RegisterScreen
import com.metrik.app.feature.gps.GpsScreen
import com.metrik.app.feature.gps.GpsViewModel
import com.metrik.app.feature.measure.MeasureScreen
import com.metrik.app.feature.measure.MeasureViewModel
import com.metrik.app.feature.plan.PlanScreen
import com.metrik.app.feature.plan.PlanViewModel
import com.metrik.app.feature.projects.ProjectDetailScreen
import com.metrik.app.feature.projects.ProjectDetailViewModel
import com.metrik.app.feature.projects.ProjectsScreen
import com.metrik.app.feature.projects.ProjectsViewModel
import com.metrik.app.feature.settings.SettingsScreen
import com.metrik.app.feature.validation.ValidationScreen
import com.metrik.app.feature.validation.ValidationViewModel
import com.metrik.app.gps.LocationSource
import com.metrik.app.sensors.HeadingSensor
import com.metrik.app.sensors.OdometrySample
import com.metrik.app.sensors.OdometrySource
import com.metrik.app.testing.FakeAuthRepository
import com.metrik.app.testing.FakeProjectRepository
import com.metrik.app.testing.FakeSessionStore
import com.metrik.app.testing.FakeSurveyRepository
import com.metrik.app.ui.theme.MetrikTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Capturas del diseño de la app (pantallas reales renderizadas con Robolectric + Roborazzi).
 * Los datos son de ejemplo y existen solo en esta prueba. Salida: docs/diseno-app/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class DesignScreenshotsTest {
    @get:Rule val compose = createComposeRule()

    private fun capture(name: String) = compose.onRoot().captureRoboImage("../../docs/diseno-app/$name.png")

    private fun tall() = RuntimeEnvironment.setQualifiers("w411dp-h1500dp-xxhdpi")

    private val aula = listOf(Point(0.0, 0.0), Point(5.0, 0.0), Point(5.0, 4.0), Point(0.0, 4.0))
    private val labL = listOf(Point(0.0, 0.0), Point(6.0, 0.0), Point(6.0, 3.0), Point(3.0, 3.0), Point(3.0, 5.0), Point(0.0, 5.0))

    private fun surveys() = FakeSurveyRepository().also { repo ->
        runBlocking {
            repo.saveLocal(LocalSurvey(projectId = 1, name = "Aula 201", mode = SurveyMode.INTERIOR, vertices = aula,
                areaM2 = 20.0, perimeterM = 18.0, scaleDenominator = 25, remoteId = 12))
            repo.saveLocal(LocalSurvey(projectId = 1, name = "Laboratorio de cómputo", mode = SurveyMode.INTERIOR, vertices = labL,
                areaM2 = 24.0, perimeterM = 22.0, scaleDenominator = 25))
            repo.saveLocal(LocalSurvey(projectId = 1, name = "Cancha deportiva", mode = SurveyMode.EXTERIOR,
                vertices = listOf(Point(0.0, 0.0), Point(100.0, 0.0), Point(100.0, 60.0), Point(0.0, 60.0)),
                areaM2 = 5975.4, perimeterM = 318.6, scaleDenominator = 500, remoteId = 14))
        }
    }

    /** Trazo de una caminata por el perímetro hasta la distancia indicada. */
    private fun walk(poly: List<Point>, untilM: Double, step: Double = 0.05): List<Point> {
        val out = mutableListOf<Point>()
        var d = 0.0
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            val n = (hypot(b.x - a.x, b.y - a.y) / step).toInt()
            for (k in 0 until n) {
                if (d > untilM) return out
                val j = 0.015 * sin(d * 7)
                out += Point(a.x + (b.x - a.x) * k / n + j, a.y + (b.y - a.y) * k / n - j)
                d += step
            }
        }
        out += poly.first()
        return out
    }

    private class PushSource : OdometrySource {
        override val kind = "steps"
        val flow = MutableSharedFlow<OdometrySample>(extraBufferCapacity = 100_000)
        override val samples = flow
        var t = 0L
        fun push(points: List<Point>) = points.forEach { flow.tryEmit(OdometrySample(it, t)); t += 30_000_000 }
    }

    @Test
    fun p01_login() {
        compose.setContent { MetrikTheme { LoginScreen(AuthViewModel(FakeAuthRepository()), {}, {}) } }
        capture("01_login")
    }

    @Test
    fun p02_registro() {
        val vm = AuthViewModel(FakeAuthRepository())
        vm.onName("Ana Medidora"); vm.onEmail("ana@metrik"); vm.onPassword("1234567"); vm.register()
        compose.setContent { MetrikTheme { RegisterScreen(vm, {}, {}) } }
        capture("02_registro_validacion")
    }

    @Test
    fun p03_proyectos() {
        val projects = FakeProjectRepository().apply {
            items += ProjectDto(1, "Pabellón A — Aulas", "Segundo piso, aulas 201 a 205")
            items += ProjectDto(2, "Laboratorios", "Bloque C")
            items += ProjectDto(3, "Terreno UPAO", "Medición exterior con GPS")
        }
        val vm = ProjectsViewModel(projects, FakeSurveyRepository(), FakeAuthRepository())
        compose.setContent { MetrikTheme { ProjectsScreen(vm, "Ana Medidora", { _, _ -> }, {}, {}) } }
        compose.waitForIdle()
        capture("03_proyectos")
    }

    @Test
    fun p04_detalle_proyecto() {
        val vm = ProjectDetailViewModel(1, surveys())
        compose.setContent { MetrikTheme { ProjectDetailScreen(vm, "Pabellón A — Aulas", {}, {}, {}, {}) } }
        compose.waitForIdle()
        capture("04_detalle_proyecto")
    }

    @Test
    fun p05_midiendo() {
        val source = PushSource()
        val vm = MeasureViewModel(1, FakeSurveyRepository())
        val heading = HeadingSensor(ApplicationProvider.getApplicationContext())
        compose.setContent { MetrikTheme { MeasureScreen(vm, "auto", heading, source, 0.70, {}, {}) } }
        vm.start(source)
        compose.waitForIdle()
        val path = walk(aula, 11.5)
        source.push(path.take(100)); compose.waitForIdle(); vm.markCorner()
        source.push(path.drop(100).take(80)); compose.waitForIdle(); vm.markCorner()
        source.push(path.drop(180)); compose.waitForIdle()
        capture("05_midiendo_recorrido")
    }

    @Test
    fun p06_plano_suavizado() {
        val source = PushSource()
        val vm = MeasureViewModel(1, FakeSurveyRepository())
        val heading = HeadingSensor(ApplicationProvider.getApplicationContext())
        compose.setContent { MetrikTheme { MeasureScreen(vm, "auto", heading, source, 0.70, {}, {}) } }
        vm.start(source)
        compose.waitForIdle()
        val path = walk(labL, 30.0)
        var done = 0
        for (cut in listOf(120, 180, 240, 280, 340)) {
            source.push(path.subList(done, cut)); compose.waitForIdle(); vm.markCorner(); done = cut
        }
        source.push(path.drop(done)); compose.waitForIdle(); vm.markCorner()
        vm.finish()
        compose.waitForIdle()
        capture("06_plano_suavizado")
    }

    @Test
    fun p07_gps() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val lat0 = -8.13
        val lon0 = -79.04
        fun at(x: Double, y: Double, t: Double) =
            GpsSample(lat0 + Math.toDegrees(y / 6_371_000.0), lon0 + Math.toDegrees(x / (6_371_000.0 * cos(Math.toRadians(lat0)))), 3.8, t)
        var recording = false
        val loc = object : LocationSource {
            override val samples = flow {
                if (!recording) {
                    repeat(15) { emit(at(sin(it.toDouble()) * 1.5, cos(it * 1.3) * 1.5, it.toDouble())) }
                } else {
                    walk(listOf(Point(0.0, 0.0), Point(100.0, 0.0), Point(100.0, 60.0), Point(0.0, 60.0)), 250.0, 2.5)
                        .forEachIndexed { i, p -> emit(at(p.x + sin(i * 0.7) * 2, p.y + cos(i * 0.9) * 2, i.toDouble())) }
                }
            }
        }
        val vm = GpsViewModel(1, loc, FakeSurveyRepository())
        compose.setContent { MetrikTheme { GpsScreen(vm, {}, {}) } }
        vm.calibrate(); compose.waitForIdle()
        recording = true
        vm.startRecording(); compose.waitForIdle()
        capture("07_gps_recorriendo")
    }

    @Test
    fun p08_plano_y_ml() {
        tall()
        val plans = object : PlanRepository {
            override suspend fun exportDxf(remoteId: Int, scale: Int) = ByteArray(0)
            override suspend fun reportPdf(remoteId: Int) = ByteArray(0)
            override suspend fun ml(remoteId: Int) = MlAnalysisDto(
                0.93, true, "Recorrido confiable: puede usar las medidas",
                listOf(WallCorrectionDto(0, 4.94, 5.0, 0.06), WallCorrectionDto(1, 3.95, 4.0, 0.05),
                    WallCorrectionDto(2, 4.95, 5.01, 0.06), WallCorrectionDto(3, 3.94, 4.0, 0.06)),
                18.01, true, "Corrección aplicada (modelo entrenado para el odómetro ARCore)",
            )
        }
        val vm = PlanViewModel(1, surveys(), plans) { _, _ -> "" }
        compose.setContent { MetrikTheme { PlanScreen(vm, {}, {}) } }
        compose.waitForIdle()
        vm.analyzeMl(); compose.waitForIdle()
        capture("08_plano_escala_y_ml")
    }

    @Test
    fun p09_validacion() {
        tall()
        val validation = object : ValidationRepository {
            override suspend fun submit(remoteId: Int, readings: Map<Int, List<Double>>, realAreaM2: Double?) = SurveyValidationDto(
                12,
                ErrorReportDto(4, 0.021, 0.024, -0.012, 0.02, 0.47, 0.9, true, 3.0, listOf(
                    WallComparisonDto(0, 5.0, 5.001, -0.001, 0.02, false), WallComparisonDto(1, 4.0, 4.03, -0.03, 0.74, false),
                    WallComparisonDto(2, 5.0, 4.98, 0.02, 0.4, false), WallComparisonDto(3, 4.0, 4.032, -0.032, 0.79, true),
                )),
                listOf("Muro 4: las lecturas de wincha difieren más de 1 cm; tome una tercera lectura"),
            )
        }
        val vm = ValidationViewModel(1, surveys(), validation)
        compose.setContent { MetrikTheme { ValidationScreen(vm, {}) } }
        compose.waitForIdle()
        listOf("5.001" to "5.000", "4.03" to "4.03", "4.98" to "4.981", "4.02" to "4.045").forEachIndexed { i, (a, b) ->
            vm.onReading(i, true, a); vm.onReading(i, false, b)
        }
        vm.onRealArea("20")
        vm.submit(); compose.waitForIdle()
        capture("09_validacion_wincha")
    }

    @Test
    fun p10_ajustes() {
        compose.setContent { MetrikTheme { SettingsScreen(FakeSessionStore(), {}, arSupportedOverride = true) } }
        compose.waitForIdle()
        capture("10_ajustes")
    }

    @Test
    fun p03b_proyectos_vacio() {
        val vm = ProjectsViewModel(FakeProjectRepository(), FakeSurveyRepository(), FakeAuthRepository())
        compose.setContent { MetrikTheme { ProjectsScreen(vm, "Ana Medidora", { _, _ -> }, {}, {}) } }
        compose.waitForIdle()
        capture("03b_proyectos_como_funciona")
    }

    @Test
    fun p11_dashboard() {
        tall()
        val plans = object : PlanRepository {
            override suspend fun exportDxf(remoteId: Int, scale: Int) = ByteArray(0)
            override suspend fun reportPdf(remoteId: Int) = ByteArray(0)
            override suspend fun ml(remoteId: Int) = MlAnalysisDto(
                0.93, true, "Recorrido confiable: puede usar las medidas",
                listOf(WallCorrectionDto(0, 4.94, 4.99, 0.05), WallCorrectionDto(1, 3.95, 3.99, 0.04),
                    WallCorrectionDto(2, 4.95, 5.0, 0.05), WallCorrectionDto(3, 3.94, 3.98, 0.04)),
                17.96, true, "Corrección aplicada (modelo entrenado para el odómetro ARCore)",
                correctorModel = "Random Forest", classifierModel = "Random Forest",
            )
        }
        val report = SurveyValidationDto(
            12,
            // Valores coherentes con los muros medidos (4.94, 3.95, 4.94, 3.95) y la wincha (5.00, 4.03, 4.98, 4.03)
            ErrorReportDto(4, 0.065, 0.067, -0.065, 0.019, 1.50, 2.86, true, 3.0, listOf(
                WallComparisonDto(0, 4.94, 5.0, -0.06, 1.2, false), WallComparisonDto(1, 3.95, 4.03, -0.08, 1.99, false),
                WallComparisonDto(2, 4.94, 4.98, -0.04, 0.8, false), WallComparisonDto(3, 3.95, 4.03, -0.08, 1.99, false),
            )),
        )
        val validation = object : ValidationRepository {
            override suspend fun submit(remoteId: Int, readings: Map<Int, List<Double>>, realAreaM2: Double?) = report
            override suspend fun get(remoteId: Int) = report
        }
        val repo = FakeSurveyRepository()
        runBlocking {
            repo.saveLocal(LocalSurvey(projectId = 1, name = "Aula 201", mode = SurveyMode.INTERIOR,
                vertices = listOf(Point(0.0, 0.0), Point(4.94, 0.0), Point(4.94, 3.95), Point(0.0, 3.95)),
                capture = com.metrik.app.domain.CaptureInfo("arcore", 0.4, 21.0),
                areaM2 = 19.51, perimeterM = 17.78, scaleDenominator = 25, remoteId = 12))
        }
        val vm = DashboardViewModel(1, repo, plans, validation)
        compose.setContent { MetrikTheme { DashboardScreen(vm, {}, {}) } }
        compose.waitForIdle()
        capture("11_dashboard_precision")
    }

    @Test
    fun p12_calibracion() {
        val loc = object : LocationSource {
            override val samples = flow { repeat(15) { emit(GpsSample(-8.13 + sin(it.toDouble()) * 2e-5, -79.04, 3.5, it.toDouble())) } }
        }
        val repo = object : com.metrik.app.data.repository.CalibrationRepository {
            override suspend fun save(kind: String, params: Map<String, Double>) = Unit
        }
        val steps = MutableSharedFlow<Unit>(extraBufferCapacity = 100)
        val vm = com.metrik.app.feature.calibration.CalibrationViewModel(FakeSessionStore(), repo, steps, null, loc)
        compose.setContent { MetrikTheme { com.metrik.app.feature.calibration.CalibrationScreen(vm, {}) } }
        vm.onDistance("10.2"); vm.startSteps(); compose.waitForIdle()
        repeat(15) { steps.tryEmit(Unit) }; compose.waitForIdle(); vm.finishSteps()
        vm.startGps(); compose.waitForIdle()
        capture("12_calibracion")
    }

    @Test
    fun p13_gps_transporte() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val vm = GpsViewModel(1, object : LocationSource { override val samples = flow<GpsSample> { } }, FakeSurveyRepository())
        vm.setTransport("bike")
        compose.setContent { MetrikTheme { GpsScreen(vm, {}, {}) } }
        compose.waitForIdle()
        capture("13_gps_elegir_transporte")
    }

    @Test
    fun p14_referencia_gps() {
        val repo = surveys()
        val validation = object : ValidationRepository {
            override suspend fun submit(remoteId: Int, readings: Map<Int, List<Double>>, realAreaM2: Double?) = error("no usado")
            override suspend fun submitGpsReference(remoteId: Int, type: String, realAreaM2: Double?, realPerimeterM: Double?) =
                com.metrik.app.data.remote.GpsReferenceDto("Cancha con medidas oficiales", 6142.0, 6000.0, 2.37, 323.1, 320.0, 0.97, null, true, 5.0)
        }
        val vm = com.metrik.app.feature.validation.GpsReferenceViewModel(3, repo, validation)
        compose.setContent { MetrikTheme { com.metrik.app.feature.validation.GpsReferenceScreen(vm, {}) } }
        compose.waitForIdle()
        vm.onArea("6000"); vm.onPerimeter("320"); vm.submit(); compose.waitForIdle()
        capture("14_validar_terreno_gps")
    }

    @Test
    fun p15_renombrar() {
        val projects = FakeProjectRepository().apply { items += ProjectDto(1, "Pabellón A — Aulas", "Segundo piso") }
        val vm = ProjectsViewModel(projects, FakeSurveyRepository(), FakeAuthRepository())
        compose.setContent { MetrikTheme { ProjectsScreen(vm, "Ana Medidora", { _, _ -> }, {}, {}) } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false  // el cursor que parpadea nunca deja la UI inactiva
        compose.onNodeWithTag("rename_project_1").performClick()
        compose.mainClock.advanceTimeBy(600)
        com.github.takahirom.roborazzi.captureScreenRoboImage("../../docs/diseno-app/15_renombrar_carpeta.png")
    }
}
