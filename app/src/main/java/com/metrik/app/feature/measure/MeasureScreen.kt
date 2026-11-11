package com.metrik.app.feature.measure

import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.FlowRow
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocation
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ar.core.Session
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.rememberCoroutineScope
import com.metrik.app.feature.plan.saveToDownloads
import com.metrik.app.sensors.ArDiagnostics
import kotlinx.coroutines.launch
import com.metrik.app.sensors.ArCameraView
import com.metrik.app.sensors.ArOdometrySource
import com.metrik.app.sensors.ArStatus
import com.metrik.app.sensors.ArSupport
import com.metrik.app.sensors.OdometrySource
import com.metrik.app.sensors.HeadingSensor
import com.metrik.app.sensors.StepOdometrySource
import com.metrik.app.ui.components.ErrorBanner
import com.metrik.app.ui.components.MetrikTopBar
import com.metrik.app.ui.components.PlanDrawing
import com.metrik.app.ui.components.StatRow
import com.metrik.app.ui.components.StepsGuide
import com.metrik.app.ui.components.deg
import com.metrik.app.ui.components.m
import com.metrik.app.ui.components.m2
import com.metrik.app.ui.theme.MetrikColors

/** Modo de odómetro resuelto según Ajustes y el soporte del celular. */
fun resolveOdometryMode(preference: String, arSupported: Boolean): String = when (preference) {
    "arcore" -> if (arSupported) "arcore" else "steps"
    "steps" -> "steps"
    else -> if (arSupported) "arcore" else "steps"
}

@Composable
fun MeasureScreen(
    vm: MeasureViewModel,
    odometryPreference: String,
    heading: HeadingSensor,
    injectedSource: OdometrySource? = null,
    stepLengthM: Double = 0.70,
    onBack: () -> Unit,
    onSaved: (Long) -> Unit,
    /** Nombre ya escrito al guardar (p. ej. «Contorno de la casa»). */
    initialName: String = "",
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as Activity
    // null = consultando (la primera respuesta de ARCore suele ser "verificando")
    var arSupported by remember { mutableStateOf<Boolean?>(if (injectedSource != null) false else null) }
    LaunchedEffect(Unit) { if (arSupported == null) arSupported = ArSupport.awaitSupported(context) }
    val mode = remember(odometryPreference, arSupported) {
        injectedSource?.kind ?: arSupported?.let { resolveOdometryMode(odometryPreference, it) } ?: "checking"
    }
    var cameraGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { cameraGranted = it }
    var session by remember { mutableStateOf<Session?>(null) }
    var arError by remember { mutableStateOf<String?>(null) }
    var arStatus by remember { mutableStateOf(ArStatus()) }
    var diagnosticsPath by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // rememberSaveable: ARCore pide que estas marcas sobrevivan a la recreación de la Activity
    var installRequested by rememberSaveable { mutableStateOf(false) }
    var permissionAsked by rememberSaveable { mutableStateOf(false) }
    val arSource = remember { ArOdometrySource() }
    // Al volver de instalar "Google Play Services for AR" hay que reintentar crear la sesión
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

    LaunchedEffect(mode, cameraGranted, resumed) {
        if (injectedSource != null || !resumed || mode != "arcore") return@LaunchedEffect
        if (!cameraGranted) {
            // Una sola vez: el diálogo pausa la Activity y, si se niega, se volvía a pedir en bucle
            if (!permissionAsked) { permissionAsked = true; permission.launch(Manifest.permission.CAMERA) }
            return@LaunchedEffect
        }
        if (session == null) {
            arError = null
            session = ArSupport.createSession(
                activity,
                userRequestedInstall = !installRequested,
                onInstallRequested = { installRequested = true },
            ) { arError = it }
        }
    }
    // MeasureScreen es el único dueño de la sesión. Este efecto se declara antes que el contenido,
    // así que Compose lo descarta después que ArCameraView (que ya pausó el hilo GL y la sesión).
    DisposableEffect(Unit) { onDispose { session?.close() } }
    LaunchedEffect(s.savedLocalId) { s.savedLocalId?.let(onSaved) }

    Scaffold(topBar = { MetrikTopBar("Medir ambiente", onBack = onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Vista superior: cámara AR o información del modo
            Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
                val sess = session
                if (injectedSource == null && mode == "arcore" && sess != null) {
                    ArCameraView(sess, onSample = arSource::emit, onStatus = { arStatus = it }, modifier = Modifier.fillMaxSize())
                    // Mira: lo que se mide es el punto del piso que queda en su centro (verde = sobre el piso)
                    val aimColor = if (arStatus.aiming) MetrikColors.Ok else Color.White
                    Canvas(Modifier.align(Alignment.Center).size(56.dp).testTag("ar_crosshair")) {
                        val c = center
                        val r = size.minDimension / 2
                        drawCircle(aimColor, radius = r * 0.45f, center = c, style = Stroke(width = 3.dp.toPx()))
                        drawLine(aimColor, Offset(c.x - r, c.y), Offset(c.x - r * 0.55f, c.y), strokeWidth = 3.dp.toPx())
                        drawLine(aimColor, Offset(c.x + r * 0.55f, c.y), Offset(c.x + r, c.y), strokeWidth = 3.dp.toPx())
                        drawLine(aimColor, Offset(c.x, c.y - r), Offset(c.x, c.y - r * 0.55f), strokeWidth = 3.dp.toPx())
                        drawLine(aimColor, Offset(c.x, c.y + r * 0.55f), Offset(c.x, c.y + r), strokeWidth = 3.dp.toPx())
                        drawCircle(aimColor, radius = 3.dp.toPx(), center = c)
                    }
                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            when (arStatus.floorSource) {
                                "plano", "puntos" -> "Piso ✔  ·  Pon la mira en la línea donde el piso toca la pared y camina junto a ella"
                                "estimado" -> "Piso estimado por la altura del celular (menos preciso): mantén el celular a la altura del pecho"
                                else -> "Buscando el piso: apunta al piso a 1–2 m y desliza el celular de lado a lado (sin girarlo)"
                            },
                            color = when (arStatus.floorSource) {
                                "plano", "puntos" -> MetrikColors.Ok
                                "estimado" -> MetrikColors.Amber
                                else -> Color.White
                            },
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.testTag("ar_planes"),
                        )
                        if (arStatus.diagnostics.isNotEmpty()) {
                            Text(
                                arStatus.diagnostics, color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("ar_diagnostics"),
                            )
                        }
                    }
                } else {
                    Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            when (mode) {
                                "steps" -> "Odómetro por pasos (sin ARCore)\nCamina pegado a las paredes con el celular al frente"
                                "checking" -> "Verificando ARCore…"
                                else -> if (!cameraGranted) "Se necesita permiso de cámara" else "Iniciando ARCore…"
                            },
                            color = Color.White, style = MaterialTheme.typography.bodyLarge,
                        )
                        if (mode == "arcore" && !cameraGranted && injectedSource == null) {
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = { permission.launch(Manifest.permission.CAMERA) }) {
                                Text("Dar permiso de cámara", color = Color.White)
                            }
                        }
                    }
                }
                // Antes de iniciar, el estado de ARCore (calibrando / buscando piso); durante el recorrido, el del odómetro
                val banner = arError ?: (if (s.phase == MeasurePhase.IDLE && mode == "arcore") arStatus.message else s.message)
                if (banner != null) {
                    Text(
                        banner, color = Color.White,
                        modifier = Modifier.align(Alignment.TopCenter).padding(8.dp)
                            .background(Color(0xAA000000), MaterialTheme.shapes.small).padding(8.dp).testTag("measure_message"),
                    )
                }
                Text(
                    "Modo: ${mode.uppercase()}", color = MetrikColors.Amber,
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                )
            }

            // Esbozo en vivo / plano resultante
            Card(Modifier.fillMaxWidth().weight(1f).padding(8.dp)) {
                PlanDrawing(
                    track = s.track, corners = s.corners, polygon = s.polygon,
                    current = if (s.phase == com.metrik.app.feature.measure.MeasurePhase.MEASURING) s.current else null,
                    showDimensions = s.phase == MeasurePhase.FINISHED,
                )
            }

            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).verticalScroll(rememberScrollState())) {
                StepsGuide(
                    "Cómo medir",
                    listOf(
                        if (mode == "arcore") "Pon la mira en una esquina del piso y toca Iniciar." else "Párate en una esquina del ambiente y toca Iniciar.",
                        if (mode == "arcore") "Camina llevando la mira por el borde piso–pared; con la mira en cada esquina toca Esquina."
                        else "Camina pegado a la pared; al llegar a cada esquina toca Esquina.",
                        if (mode == "arcore") "Lleva la mira de vuelta a la esquina inicial y toca Finalizar." else "Vuelve al punto de inicio y toca Finalizar.",
                    ),
                    current = when {
                        s.phase == MeasurePhase.IDLE -> 0
                        s.phase == MeasurePhase.MEASURING -> 1
                        else -> 2
                    },
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                ErrorBanner(s.error)
                when (s.phase) {
                    MeasurePhase.IDLE -> Column {
                    if (s.availableDoors.isNotEmpty()) {
                        Text("¿Empiezas en una puerta marcada? (para unir este ambiente solo)", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(s.startDoor == null, { vm.setStartDoor(null) }, { Text("No") })
                            s.availableDoors.forEach { d ->
                                FilterChip(s.startDoor == d, { vm.setStartDoor(d) }, { Text("Puerta «$d»") }, Modifier.testTag("start_door_$d"))
                            }
                        }
                    }
                    Button(
                        onClick = {
                            val source = injectedSource ?: if (mode == "arcore") arSource else StepOdometrySource(context, stepLengthM)
                            if (source === arSource) { diagnosticsPath = null; ArDiagnostics.start() }
                            vm.start(source, if (heading.available && injectedSource == null) heading.headingDeg else null)
                        },
                        // Con ARCore solo se inicia cuando el seguimiento ya es estable: antes, iniciar
                        // mientras ARCore armaba su mapa sumaba metros sin moverse.
                        enabled = injectedSource != null || mode == "steps" || (mode == "arcore" && session != null && arStatus.ready),
                        modifier = Modifier.fillMaxWidth().testTag("measure_start"),
                    ) {
                        Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp))
                        val waiting = injectedSource == null && (mode == "checking" || (mode == "arcore" && !arStatus.ready))
                        Text(if (waiting) "Esperando a la cámara…" else "Iniciar recorrido")
                    }
                    }

                    MeasurePhase.MEASURING -> {
                        StatRow("Tramo actual", s.segmentM.m())
                        StatRow("Distancia total", s.totalM.m())
                        StatRow("Muros marcados", s.walls.size.toString())
                        s.distanceToStartM?.let { d ->
                            if (s.nearStart) Text(
                                "✔ Llegaste al punto de inicio: toca Finalizar",
                                color = MetrikColors.Ok, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("near_start"),
                            ) else StatRow("Distancia al inicio", d.m())
                        }
                        s.walls.lastOrNull()?.turnSnappedDeg?.let { StatRow("Último giro (giroscopio)", it.deg()) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                            Button(
                                onClick = vm::markCorner, modifier = Modifier.weight(1f).testTag("measure_corner"),
                                colors = ButtonDefaults.buttonColors(containerColor = MetrikColors.Corner),
                            ) { Icon(Icons.Filled.AddLocation, null); Spacer(Modifier.width(6.dp)); Text("Esquina") }
                            Button(
                                onClick = {
                                    vm.finish()
                                    // Registro de lo que vio la cámara → Descargas/MetriK (para diagnosticar en pruebas)
                                    ArDiagnostics.stop()?.let { csv ->
                                        val name = "MetriK_diagnostico_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".csv"
                                        scope.launch {
                                            diagnosticsPath = runCatching { saveToDownloads(context, name, csv.toByteArray(), "text/csv") }
                                                .getOrElse { "no se pudo guardar (${it.message})" }
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f).testTag("measure_finish"),
                            ) {
                                Icon(Icons.Filled.CheckCircle, null); Spacer(Modifier.width(6.dp)); Text("Finalizar")
                            }
                        }
                        // Pasillo (referencia): al pasar frente a la puerta de un ambiente, marcarla con su nombre
                        var doorDialog by remember { mutableStateOf(false) }
                        OutlinedButton(onClick = { doorDialog = true }, modifier = Modifier.fillMaxWidth().testTag("measure_door")) {
                            Text("Puerta aquí")
                        }
                        if (s.doors.isNotEmpty()) StatRow("Puertas marcadas", s.doors.joinToString { it.name })
                        if (doorDialog) {
                            var doorName by remember { mutableStateOf("") }
                            AlertDialog(
                                onDismissRequest = { doorDialog = false },
                                title = { Text("Puerta aquí") },
                                text = {
                                    OutlinedTextField(
                                        doorName, { doorName = it }, label = { Text("¿A qué ambiente lleva? (Sala, Cocina…)") },
                                        singleLine = true, modifier = Modifier.testTag("door_name"),
                                    )
                                },
                                confirmButton = { TextButton(onClick = { vm.markDoor(doorName); doorDialog = false }) { Text("Marcar") } },
                                dismissButton = { TextButton(onClick = { doorDialog = false }) { Text("Cancelar") } },
                            )
                        }
                    }

                    MeasurePhase.FINISHED -> {
                        diagnosticsPath?.let {
                            Text(
                                "Diagnóstico de la cámara guardado: $it",
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("diagnostics_saved"),
                            )
                        }
                        val a = s.analysis
                        if (a != null) {
                            Text("Plano suavizado", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            StatRow("Área", a.areaM2.m2())
                            StatRow("Perímetro", a.perimeterM.m())
                            StatRow("Muros", "${a.wallLengthsM.size} (${a.concaveVertices.size} esquina(s) cóncava(s))")
                            StatRow("Error de cierre", s.closureErrorM.m())
                            StatRow("Escala sugerida", "1:${s.scaleDenominator}")
                            if (!s.closed) Text(
                                "El recorrido no terminó en el punto de inicio: se cerró automáticamente (EX-01)",
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                            )
                            var name by rememberSaveable { mutableStateOf(initialName) }
                            OutlinedTextField(
                                name, { name = it }, label = { Text("Nombre del ambiente") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("survey_name"),
                            )
                            Button(onClick = { vm.save(name) }, modifier = Modifier.fillMaxWidth().testTag("survey_save")) { Text("Guardar plano") }
                        }
                        OutlinedButton(onClick = vm::reset, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Replay, null); Spacer(Modifier.width(6.dp)); Text("Repetir recorrido")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

