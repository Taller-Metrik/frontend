# MetriK — App Android (Kotlin + Jetpack Compose)

Odómetro digital para levantar planos: recorre un ambiente con el celular y obtén su plano a
escala con área, perímetro y longitud de cada pared. Funciona sin conexión y sincroniza con el
backend cuando hay red.

## Requisitos

<!-- AUTO-GENERATED: gradle/libs.versions.toml, app/build.gradle.kts, gradle-wrapper.properties -->
| Componente | Versión |
|------------|---------|
| Android Studio | 2026.1+ (incluye el JDK) |
| Gradle (wrapper) | 9.8.2 |
| Android Gradle Plugin | 9.4.0 |
| Kotlin | 2.4.21 |
| `compileSdk` / `targetSdk` | 37 |
| `minSdk` | 26 (Android 8.0) |
| Java | 17 |
| ARCore SDK | 1.57.0 |
| Compose BOM | 2026.09.00 |
<!-- /AUTO-GENERATED -->

- **Modo cámara (ARCore):** el celular debe tener **Google Play Services for AR** actualizado.
  Si falta, la app pide instalarlo. Si el celular no es compatible, usa el odómetro por pasos.
- Celular de referencia del proyecto: **Samsung Galaxy A25 5G (SM-A256E)**, Android 16.

## Configuración

### URL de la API

La URL del backend **se fija al compilar**: no se muestra ni se edita en la app. Se define en
`mobile-android/local.properties`, que no se sube a git:

<!-- AUTO-GENERATED: app/build.gradle.kts (defaultConfig) -->
| Propiedad | Requerida | Por defecto | Ejemplo |
|-----------|-----------|-------------|---------|
| `metrik.api.url` | No | `http://10.0.2.2:8000/api/v1/` (backend local desde el emulador) | `https://<servicio>.run.app/api/v1/` |
<!-- /AUTO-GENERATED -->

```properties
# mobile-android/local.properties
sdk.dir=C\:\\Users\\<usuario>\\AppData\\Local\\Android\\Sdk
metrik.api.url=http://192.168.1.50:8000/api/v1/
```

- Se guarda en `BuildConfig.API_BASE_URL` y es el valor inicial de la sesión (`SessionStore`).
- Para un backend local desde un **celular físico**, usa la IP de tu PC en la misma red Wi-Fi y
  permite el puerto 8000 en el firewall. El backend se levanta con
  `uvicorn app.main:app --host 0.0.0.0 --port 8000`.
- `res/xml/network_security_config.xml` permite HTTP (sin cifrar) para desarrollo. En
  producción usa HTTPS.

### Ajustes dentro de la app

En **Ajustes** se elige el modo de medición. Se guarda en DataStore (`metrik_session` →
`odometry_mode`):

| Opción | Clave | Qué hace |
|--------|-------|----------|
| Automático | `auto` | Usa la cámara (ARCore) si el celular la soporta; si no, los pasos. |
| Cámara (ARCore) | `arcore` | Más preciso. Mide con la mira sobre el piso. |
| Pasos | `steps` | Sin cámara: cuenta pasos (acelerómetro) y giros (giroscopio). |

La pantalla **Calibración** mide tu largo de paso (`step_length_m`), la deriva del giroscopio y
la precisión del GPS. Conviene hacerla una vez por celular.

## Compilar e instalar

<!-- AUTO-GENERATED: tareas Gradle del módulo :app -->
| Comando (desde `mobile-android/`) | Qué hace |
|-----------------------------------|----------|
| `.\gradlew.bat :app:assembleDebug` | Genera `app/build/outputs/apk/debug/app-debug.apk` |
| `.\gradlew.bat :app:installDebug` | Compila e instala en el celular conectado |
| `.\gradlew.bat :app:testDebugUnitTest` | Pruebas JVM: unitarias (FU) e integración con API simulada (FI) |
| `.\gradlew.bat :app:compileDebugAndroidTestKotlin` | Compila las pruebas que corren en el celular |
<!-- /AUTO-GENERATED -->

Instalar un APK ya compilado, conservando los datos de la app:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices -l                                   # el celular debe aparecer como "device"
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Para conectar el celular por USB:
1. Activa el **modo desarrollador**: Ajustes → Acerca del teléfono → Información de software →
   toca 7 veces "Número de compilación".
2. Activa **Opciones de desarrollador → Depuración por USB**.
3. Conecta el cable USB y acepta el mensaje "¿Permitir depuración USB?".

## Cómo se mide

| Modo | Cómo |
|------|------|
| **Interior · ARCore** | Ver la sección siguiente. |
| **Interior · Pasos** | Camina pegado a las paredes con el celular al frente; toca **Esquina** en cada esquina. La distancia es pasos × largo de paso calibrado y el giro viene del giroscopio. Menos preciso. |
| **Exterior · GPS** | Quédate quieto **en el punto de partida** para la calibración (15 lecturas): su promedio ponderado por precisión es el inicio del recorrido. Recorre el borde del terreno y finaliza. **A pie usa pasos + brújula** (`compassSteps` + `PdrGpsFusion`): cada paso agrega un punto en la dirección de la brújula (norte real, `TYPE_ROTATION_VECTOR`) y el GPS ajusta la figura entera por mínimos cuadrados (escala, giro y ubicación), corrigiendo el largo de paso y el desvío de la brújula sin redondear esquinas. Quieto no hay pasos y el GPS se ignora. En bici, moto o carro se usa solo el GPS: Si una lectura rebotada en un edificio quedó como ancla, 3 lecturas precisas y coherentes la reemplazan (`GpsTrackFilter`). Quieto, el GPS "pasea" varios metros: a pie no se agregan puntos si el acelerómetro no detecta pasos en 3 s, y en cualquier transporte un punto nuevo debe estar a más de 3 m y más lejos que la precisión de la lectura. Filtra lecturas según el transporte: caminata (≤ 11 km/h, precisión ≤ 10 m), bici (≤ 43 km/h, ≤ 12 m), moto (≤ 108 km/h, ≤ 15 m) y carro (≤ 144 km/h, ≤ 15 m). |

Después de medir: **plano a escala** (1:20 a 1:2000) con cotas, distancia Euclidiana y
Manhattan entre vértices, **exportar DXF** (Descargas/MetriK), **análisis ML** y
**validación con wincha**. Los recorridos se guardan en el celular (Room) y se sincronizan
cuando hay conexión.

### Modo ARCore: la mira como rueda de medir

La app mide **el punto del piso donde apunta la mira** (centro de la pantalla), no la posición
del celular. Si llevas la mira por la línea donde el piso toca la pared, el trazo es el
perímetro real del ambiente. El camino del celular va 30–50 cm por dentro de las paredes y
achicaba el plano.

1. Apunta al piso a 1–2 m y **desliza el celular de lado a lado, sin girarlo**, hasta que salga
   **"Piso ✔"**. ARCore necesita ver la escena desde varios puntos; con el celular quieto no
   termina de reconocerla.
2. Pon la mira en una esquina del piso y toca **Iniciar**. El botón se habilita solo cuando el
   seguimiento está estable y ya se conoce el piso.
3. Camina llevando la mira por el borde piso–pared. Con la mira en cada esquina, toca **Esquina**.
4. Lleva la mira de vuelta a la esquina inicial y toca **Finalizar**.

Cómo funciona por dentro (`sensors/`):

| Pieza | Archivo | Qué hace |
|-------|---------|----------|
| `TrackingGate` | `Odometry.kt` | Usa la pose solo tras 1,5 s de seguimiento continuo. Los cortes de menos de 0,3 s no reinician la espera. |
| `FloorEstimator` | `FloorAim.kt` | Altura del piso desde tres fuentes, en orden de prioridad: **plano** de ARCore (el de mayor área), **puntos** (altura más densa de la nube de puntos, entre 0,5 y 2,2 m bajo el celular) o **estimado** (a 1,30 m bajo el celular si no hay datos en 5 s; menos preciso, aviso en ámbar). |
| `FloorAim` | `FloorAim.kt` | Intersección del rayo de la mira con el piso. Se descarta si apunta a menos de ~10° bajo el horizonte o a más de 4 m. |
| `OdometerAccumulator.walking()` | `Odometry.kt` | Suma la distancia de la mira con zona muerta de 3 cm. **Detección de quietud (ZUPT):** si tu cuerpo no avanzó 15 cm netos en 1 s, el temblor no suma, salvo un barrido deliberado de la mira (≥ 30 cm). Los saltos imposibles (> 1 m o > 2,5 m/s) y los regresos tras perder el seguimiento se reenganchan sin sumar. |
| `MessageDebouncer` | `Odometry.kt` | Un aviso solo aparece si dura 0,7 s, para que no parpadee. |
| `ArDiagnostics` | `ArDiagnostics.kt` | Registro CSV por frame durante el recorrido (ver Solución de problemas). |

ARCore se configura con planos horizontales y verticales, `LATEST_CAMERA_IMAGE`, autoenfoque
y estimación de luz desactivada (`ArSupport.createSession` en `ArCamera.kt`).

## Permisos

<!-- AUTO-GENERATED: app/src/main/AndroidManifest.xml -->
| Permiso | Para qué |
|---------|----------|
| `INTERNET` | API del backend |
| `CAMERA` | ARCore (se pide al entrar a Medir) |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | Modo GPS |
| `ACTIVITY_RECOGNITION` | Detector de pasos |
| `HIGH_SAMPLING_RATE_SENSORS` | Lectura de acelerómetro y giroscopio a más de 200 Hz |
<!-- /AUTO-GENERATED -->

> **`HIGH_SAMPLING_RATE_SENSORS` es imprescindible para ARCore.** Desde Android 12 una app
> no puede leer los sensores a más de 200 Hz sin este permiso, y ARCore los necesita más
> rápido. Sin él, en el Galaxy A25 con Android 16, ARCore no recibía datos de los sensores
> (`IMU buffer is empty`, `Failed to register sensor`): no detectaba el piso y el mapa se
> disparaba. Es un permiso normal: Android lo concede al instalar, sin preguntar. Para
> verificarlo: `adb shell dumpsys package com.metrik.app | findstr HIGH_SAMPLING` debe
> mostrar `granted=true`.

`AndroidManifest.xml` declara ARCore como **opcional** (`com.google.ar.core` = `optional`),
así que la app se instala también en celulares sin ARCore.

## Estructura

<!-- AUTO-GENERATED: app/src/main/java/com/metrik/app -->
```
app/src/main/java/com/metrik/app/
├── MainActivity.kt, MetrikApp.kt, AppContainer.kt   entrada y dependencias
├── core/geometry/   Geometry (área, ángulos, distancias), Smoothing (suavizado), Scale
├── sensors/
│   ├── ArCamera.kt       sesión ARCore, vista de cámara, renderizador (mira + piso)
│   ├── FloorAim.kt       FloorEstimator (altura del piso) y FloorAim (rayo de la mira)
│   ├── Odometry.kt       OdometerAccumulator, TrackingGate, MessageDebouncer, TurnTracker, StepDetector
│   ├── SensorSources.kt  HeadingSensor, StepOdometrySource, ArOdometrySource
│   └── ArDiagnostics.kt  registro CSV de diagnóstico
├── gps/             Fused Location, transporte, Haversine, ENU, CEP, área
├── data/            remote (Retrofit), local (Room), session (DataStore), repository
├── domain/          modelos
├── feature/         auth, dashboard, projects, measure, gps, plan, validation, calibration, settings
└── ui/              tema, navegación, componentes (dibujo del plano)
```
<!-- /AUTO-GENERATED -->

## Pruebas

| Tipo | Dónde | Cómo |
|------|-------|------|
| Unitarias (FU) | `src/test` | `.\gradlew.bat :app:testDebugUnitTest` |
| Integración (FI) | `src/test` (API simulada con MockWebServer) y `src/androidTest` (Room) | igual / en el celular |
| Caja negra (FC) | `src/androidTest/.../blackbox` | en el celular |
| End-to-End (FE) | `src/androidTest/.../e2e` | en el celular |
| Capturas de diseño | `src/test/.../screenshots` (Robolectric + Roborazzi) | con las unitarias |

<!-- AUTO-GENERATED: anotaciones @Evidencia en src/test y src/androidTest -->
Pruebas del odómetro y la cámara (`SensorsTest.kt`):

| ID | Prueba |
|----|--------|
| FU-10 | Odómetro acumula distancia y descarta temblor |
| FU-11 | Tiempo sin seguimiento y relocalización |
| FU-12 | Giro entre esquinas con cruce de ±180° |
| FU-13 | Detector de pasos del acelerómetro |
| FU-27 | Saltos del seguimiento por velocidad imposible |
| FU-28 | Deriva al recuperar el seguimiento no suma distancia |
| FU-29 | Calibración de ARCore antes de medir |
| FU-30 | Avisos sin parpadeo |
| FU-31 | Quieto con balanceo no suma distancia |
| FU-32 | Altura del piso sin plano de ARCore |
| FU-33 | Mira sobre el piso |
| FU-34 | Mira: temblor quieto no suma, barrido deliberado sí |
| FU-35 | Cortes breves del seguimiento no traban la calibración |
| FU-36 | Piso estimado en superficies sin textura |
| FU-37 | Tras un salto de la mira se sigue midiendo |
| FU-38 | GPS: se recupera de un ancla falsa (rebote de señal) |
| FU-39 | GPS: punto de partida desde la calibración |
| FU-40 | GPS: quieto no avanza aunque la señal pasee |
| FU-41 | GPS a pie: pasos + brújula corregidos con GPS |
| FU-42 | GPS a pie en el ViewModel: quieto no avanza, caminando dibuja cada paso (`ViewModelsTest.kt`) |

En total: FU-01 a FU-42 (unitarias), FI-01 a FI-10 (integración), FC-01 a FC-05 (caja negra) y
FE-01 a FE-03 (end-to-end).
<!-- /AUTO-GENERATED -->

Evidencias (captura + descripción por prueba):

```bash
../backend/.venv/Scripts/python scripts/generar_evidencias_android.py --solo-jvm   # sin celular
../backend/.venv/Scripts/python scripts/generar_evidencias_android.py              # con celular por USB
```

Resultado en [`../evidencias/frontend/`](../evidencias/frontend/README.md).

## Solución de problemas (ARCore)

### Línea de diagnóstico en pantalla

Debajo de la cámara aparece `Seguimiento: … · puntos: … · piso: …`:

| Campo | Valores | Qué indica |
|-------|---------|------------|
| Seguimiento | `OK`, `insufficient_features`, `insufficient_light`, `excessive_motion`, `camera_unavailable`… | Si ARCore está siguiendo el movimiento o por qué no |
| puntos | número | Puntos 3D que ARCore ve en ese momento; con 0 o muy pocos, la escena no tiene referencias |
| piso | `plano`, `puntos`, `estimado`, `buscando` | De dónde sale la altura del piso (`plano` y `puntos` son precisos) |

### Registro CSV del recorrido

Al tocar **Finalizar** (modo ARCore) se guarda
`Descargas/MetriK/MetriK_diagnostico_<fecha>.csv`, con una línea por frame:

```
t_ms,seguimiento,puntos,piso_origen,piso_y,cel_x,cel_y,cel_z,mira_dir_y,mira_x,mira_y,mensaje
```

Sirve para reproducir en pruebas lo que vio la cámara en el celular real.

### Mensajes internos de ARCore (logcat)

Con el celular conectado por USB:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb logcat -c
& $adb logcat -v time > logcat_arcore.txt     # hacer el recorrido y luego Ctrl+C
```

| Mensaje en el log | Causa | Solución |
|-------------------|-------|----------|
| `IMU buffer is empty`, `Failed to register sensor` | ARCore no recibe datos de acelerómetro y giroscopio | Verificar el permiso `HIGH_SAMPLING_RATE_SENSORS` (ver Permisos) |
| `Image has too few landmarks` | Escena sin textura | Incluir en la imagen el zócalo, el borde de la pared o muebles; buena luz |
| `VIO initialize failed` / `VIO_OUTPUT_NOT_TRACKING` | Seguimiento sin iniciar | Deslizar el celular despacio de lado a lado |
| `VIO initialize success` | Seguimiento iniciado correctamente | — |
| `Could not identify device type from build fingerprint` | ARCore no tiene perfil de calibración para esa versión del celular | Solo Google puede corregirlo con una actualización de ARCore; funciona con la calibración genérica |

### Otros

- **"Iniciar recorrido" no se habilita:** mira el mensaje de arriba y la línea de diagnóstico.
  Debe haber seguimiento `OK` y piso distinto de `buscando`.
- **"La cámara no responde":** cierra otras apps que usen la cámara y vuelve a entrar.
- **Se queda en "Iniciando ARCore…":** actualiza Google Play Services for AR desde Play Store.
