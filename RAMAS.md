# Plan de ramas del frontend (pendiente de commit)

El código del front está completo pero **sin commits**. Cuando se configure la cuenta git de la
persona responsable del front, se crean estas ramas desde `develop`, en este orden, y cada una se
fusiona a `develop` con `--no-ff` (igual que el backend).

> Archivos **compartidos** que crecen por rama: `MetrikNavHost.kt`, `AppContainer.kt`,
> `data/remote/Dtos.kt`, `data/remote/MetrikApi.kt`, `data/repository/Repositories.kt`.
> En cada rama se agrega solo la parte de esa funcionalidad, para que **cada commit compile**.
> Las pruebas compartidas (`ViewModelsTest`, `ApiIntegrationTest`, `BlackBoxTest`) se dividen
> por funcionalidad en ese momento.

| # | Rama | Archivos | Pruebas |
|---|------|----------|---------|
| 1 | `feature/app-base-android` | `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/`, `gradlew*`, `.gitignore`, `app/build.gradle.kts`, `proguard-rules.pro`, `AndroidManifest.xml`, `res/*`, `MetrikApp.kt`, `MainActivity.kt`, `AppContainer.kt` (sesión + API), `ui/theme/Theme.kt`, `ui/components/Common.kt`, `ui/navigation/MetrikNavHost.kt` (ajustes), `feature/settings/SettingsScreen.kt`, `data/session/SessionStore.kt`, `data/remote/ApiClient.kt`, infraestructura de pruebas (`test/testing/Evidencia.kt`, `Fakes.kt`, `androidTest/testing/DeviceEvidence.kt`), `README.md` | FI-07, FC-04 |
| 2 | `feature/app-motor-geometrico` | `core/geometry/Geometry.kt`, `Smoothing.kt`, `Scale.kt`, `test/testing/TestData.kt` | FU-01 a FU-09 |
| 3 | `feature/app-autenticacion` | `feature/auth/*`, DTOs y endpoints de `auth`, `AuthRepository`, rutas login/registro | FU-16, FU-17, FI-01, FI-02, FC-01, FC-02 |
| 4 | `feature/app-proyectos-historial` | `domain/Models.kt`, `data/local/MetrikDatabase.kt`, `ProjectRepository`, `SurveyRepository`, `feature/projects/*`, rutas de proyectos | FI-03, FI-04, FI-08, FI-09 |
| 5 | `feature/app-sensores-odometro` | `sensors/Odometry.kt`, `sensors/SensorSources.kt`, `sensors/ArCamera.kt`, `sensors/FloorAim.kt`, `sensors/ArDiagnostics.kt`, permiso `HIGH_SAMPLING_RATE_SENSORS` en `AndroidManifest.xml` | FU-10 a FU-13, FU-27 a FU-37 |
| 6 | `feature/app-recorrido-esbozo` | `ui/components/PlanDrawing.kt`, `feature/measure/*`, ruta de medición | FU-18, FU-19 |
| 7 | `feature/app-modo-gps` | `gps/Gps.kt`, `feature/gps/GpsFeature.kt`, ruta GPS | FU-14, FU-15, FU-20 |
| 8 | `feature/app-plano-exportacion` | `data/repository/PlanRepositories.kt` (plano/ML), `feature/plan/*`, ruta del plano | FU-21, FI-05, FC-05, FE-02 |
| 9 | `feature/app-validacion` | `ValidationRepository`, `feature/validation/ValidationFeature.kt`, ruta de validación | FU-22, FI-06, FC-03, FE-03 |
| 10 | `feature/app-evidencias-pruebas` | `scripts/generar_evidencias_android.py`, `androidTest/testing/FakeBackend.kt`, `androidTest/e2e/EndToEndTest.kt` (FE-01), `evidencias/frontend/` | FE-01 + evidencias de todas |

## Comandos (cuando llegue la cuenta del front)

```bash
git config --local user.name  "<nombre>"     # solo para los commits del front
git config --local user.email "<correo>"
git checkout develop
git checkout -b feature/app-base-android
# ... agregar archivos de la rama, commit, merge --no-ff a develop, siguiente rama ...
```
