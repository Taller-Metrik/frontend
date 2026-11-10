package com.metrik.app

import android.content.Context
import com.metrik.app.data.local.MetrikDatabase
import com.metrik.app.data.remote.ApiClient
import com.metrik.app.data.repository.AuthRepository
import com.metrik.app.data.repository.DefaultAuthRepository
import com.metrik.app.data.repository.DefaultPlanRepository
import com.metrik.app.data.repository.DefaultProjectRepository
import com.metrik.app.data.repository.DefaultSurveyRepository
import com.metrik.app.data.repository.DefaultValidationRepository
import com.metrik.app.data.repository.PlanRepository
import com.metrik.app.data.repository.ProjectRepository
import com.metrik.app.data.repository.SurveyRepository
import com.metrik.app.data.repository.ValidationRepository
import com.metrik.app.data.session.DataStoreSessionStore
import com.metrik.app.data.session.SessionStore
import com.metrik.app.gps.FusedLocationSource
import com.metrik.app.gps.LocationSource
import com.metrik.app.sensors.HeadingSensor
import com.metrik.app.sensors.OdometrySource
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Inyección de dependencias manual: una instancia por aplicación. */
class AppContainer(context: Context) {
    private val app = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val session: SessionStore = DataStoreSessionStore(app, scope)
    val api = ApiClient(
        baseUrl = { session.session.value.baseUrl },
        token = { session.session.value.token },
        logging = BuildConfig.DEBUG,
    )
    val database: MetrikDatabase by lazy { MetrikDatabase.create(app) }

    val auth: AuthRepository by lazy { DefaultAuthRepository(api, session) }
    val projects: ProjectRepository by lazy { DefaultProjectRepository(api) }
    val surveys: SurveyRepository by lazy { DefaultSurveyRepository(api, database.surveyDao()) }
    val plans: PlanRepository by lazy { DefaultPlanRepository(api) }
    val fences: com.metrik.app.data.repository.FenceRepository by lazy {
        com.metrik.app.data.repository.DefaultFenceRepository(api)
    }
    val validation: ValidationRepository by lazy { DefaultValidationRepository(api) }
    val calibrations: com.metrik.app.data.repository.CalibrationRepository by lazy {
        com.metrik.app.data.repository.DefaultCalibrationRepository(api)
    }

    val location: LocationSource by lazy { FusedLocationSource(app) }
    val heading: HeadingSensor by lazy { HeadingSensor(app) }

    /** Solo pruebas E2E: reemplaza el odómetro físico. En la app real siempre es null. */
    @VisibleForTesting
    var odometryOverride: OdometrySource? = null
}
