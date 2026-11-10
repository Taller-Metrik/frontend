package com.metrik.app.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.metrik.app.AppContainer
import com.metrik.app.feature.auth.AuthViewModel
import com.metrik.app.feature.calibration.CalibrationScreen
import com.metrik.app.feature.calibration.CalibrationViewModel
import com.metrik.app.feature.validation.GpsReferenceScreen
import com.metrik.app.feature.validation.GpsReferenceViewModel
import com.metrik.app.sensors.compassSteps
import com.metrik.app.sensors.stepEvents
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
import com.metrik.app.feature.plan.mimeTypeFor
import com.metrik.app.feature.plan.saveToDownloads
import com.metrik.app.feature.projects.ProjectDetailScreen
import com.metrik.app.feature.projects.ProjectDetailViewModel
import com.metrik.app.feature.projects.ProjectsScreen
import com.metrik.app.feature.projects.ProjectsViewModel
import com.metrik.app.feature.settings.SettingsScreen
import com.metrik.app.feature.validation.ValidationScreen
import com.metrik.app.feature.validation.ValidationViewModel

object Routes {
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val PROJECTS = "projects"
    const val SETTINGS = "settings"
    const val PROJECT = "project/{id}/{name}"
    const val MEASURE = "measure/{projectId}"
    const val GPS = "gps/{projectId}"
    const val PLAN = "plan/{localId}"
    const val VALIDATION = "validation/{localId}"
    const val DASHBOARD = "dashboard/{localId}"
    const val CALIBRATION = "calibration"
    const val GPS_REFERENCE = "gps-reference/{localId}"
    fun gpsReference(localId: Long) = "gps-reference/$localId"

    fun project(id: Int, name: String) = "project/$id/${Uri.encode(name)}"
    fun measure(projectId: Int) = "measure/$projectId"
    fun gps(projectId: Int) = "gps/$projectId"
    fun plan(localId: Long) = "plan/$localId"
    fun validation(localId: Long) = "validation/$localId"
    fun dashboard(localId: Long) = "dashboard/$localId"
}

@Composable
fun MetrikNavHost(container: AppContainer) {
    val nav = rememberNavController()
    val session by container.session.session.collectAsStateWithLifecycle()
    val start = remember { if (container.session.session.value.loggedIn) Routes.PROJECTS else Routes.LOGIN }
    val context = LocalContext.current.applicationContext

    NavHost(navController = nav, startDestination = start) {
        composable(Routes.LOGIN) {
            val vm = viewModel { AuthViewModel(container.auth) }
            LoginScreen(
                vm,
                onLoggedIn = { nav.navigate(Routes.PROJECTS) { popUpTo(Routes.LOGIN) { inclusive = true } } },
                onRegister = { nav.navigate(Routes.REGISTER) },
            )
        }
        composable(Routes.REGISTER) {
            val vm = viewModel { AuthViewModel(container.auth) }
            RegisterScreen(
                vm,
                onRegistered = { nav.navigate(Routes.PROJECTS) { popUpTo(Routes.LOGIN) { inclusive = true } } },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) { SettingsScreen(container.session, onBack = { nav.popBackStack() }) }
        composable(Routes.PROJECTS) {
            val vm = viewModel { ProjectsViewModel(container.projects, container.surveys, container.auth) }
            ProjectsScreen(
                vm, userName = session.userName,
                onOpen = { id, name -> nav.navigate(Routes.project(id, name)) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onLoggedOut = { nav.navigate(Routes.LOGIN) { popUpTo(0) } },
                onCalibrate = { nav.navigate(Routes.CALIBRATION) },
            )
        }
        composable(
            Routes.PROJECT,
            arguments = listOf(navArgument("id") { type = NavType.IntType }, navArgument("name") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments!!.getInt("id")
            val name = entry.arguments!!.getString("name").orEmpty()
            val vm = viewModel { ProjectDetailViewModel(id, container.surveys) }
            ProjectDetailScreen(
                vm, name, onBack = { nav.popBackStack() },
                onMeasureInterior = { nav.navigate(Routes.measure(id)) },
                onMeasureExterior = { nav.navigate(Routes.gps(id)) },
                onOpenPlan = { nav.navigate(Routes.plan(it)) },
            )
        }
        composable(Routes.MEASURE, arguments = listOf(navArgument("projectId") { type = NavType.IntType })) { entry ->
            val projectId = entry.arguments!!.getInt("projectId")
            val vm = viewModel { MeasureViewModel(projectId, container.surveys) }
            MeasureScreen(
                vm, session.odometryMode, container.heading, container.odometryOverride, session.stepLengthM,
                onBack = { nav.popBackStack() },
                onSaved = { localId -> nav.navigate(Routes.plan(localId)) { popUpTo(Routes.MEASURE) { inclusive = true } } },
            )
        }
        composable(Routes.GPS, arguments = listOf(navArgument("projectId") { type = NavType.IntType })) { entry ->
            val projectId = entry.arguments!!.getInt("projectId")
            val vm = viewModel {
                GpsViewModel(
                    projectId, container.location, container.surveys,
                    compassSteps = compassSteps(context), stepLengthM = container.session.session.value.stepLengthM,
                )
            }
            GpsScreen(
                vm, onBack = { nav.popBackStack() },
                onSaved = { localId -> nav.navigate(Routes.plan(localId)) { popUpTo(Routes.GPS) { inclusive = true } } },
            )
        }
        composable(Routes.PLAN, arguments = listOf(navArgument("localId") { type = NavType.LongType })) { entry ->
            val localId = entry.arguments!!.getLong("localId")
            val vm = viewModel {
                PlanViewModel(localId, container.surveys, container.plans) { name, bytes -> saveToDownloads(context, name, bytes, mimeTypeFor(name)) }
            }
            PlanScreen(
                vm, onBack = { nav.popBackStack() },
                onValidate = { nav.navigate(Routes.validation(it)) },
                onDashboard = { nav.navigate(Routes.dashboard(it)) },
                onGpsReference = { nav.navigate(Routes.gpsReference(it)) },
            )
        }
        composable(Routes.VALIDATION, arguments = listOf(navArgument("localId") { type = NavType.LongType })) { entry ->
            val localId = entry.arguments!!.getLong("localId")
            val vm = viewModel { ValidationViewModel(localId, container.surveys, container.validation) }
            ValidationScreen(vm, onBack = { nav.popBackStack() }, onDashboard = { nav.navigate(Routes.dashboard(localId)) })
        }
        composable(Routes.CALIBRATION) {
            val vm = viewModel {
                CalibrationViewModel(container.session, container.calibrations, stepEvents(context), container.heading.headingDeg, container.location)
            }
            CalibrationScreen(vm, onBack = { nav.popBackStack() })
        }
        composable(Routes.GPS_REFERENCE, arguments = listOf(navArgument("localId") { type = NavType.LongType })) { entry ->
            val localId = entry.arguments!!.getLong("localId")
            val vm = viewModel { GpsReferenceViewModel(localId, container.surveys, container.validation) }
            GpsReferenceScreen(vm, onBack = { nav.popBackStack() })
        }
        composable(Routes.DASHBOARD, arguments = listOf(navArgument("localId") { type = NavType.LongType })) { entry ->
            val localId = entry.arguments!!.getLong("localId")
            val vm = viewModel { DashboardViewModel(localId, container.surveys, container.plans, container.validation) }
            DashboardScreen(vm, onBack = { nav.popBackStack() }, onValidate = { nav.navigate(Routes.validation(it)) })
        }
    }
}
