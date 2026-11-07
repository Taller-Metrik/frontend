package com.metrik.app.feature.calibration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.metrik.app.data.remote.userMessage
import com.metrik.app.data.repository.CalibrationRepository
import com.metrik.app.data.session.SessionStore
import com.metrik.app.domain.GpsSample
import com.metrik.app.gps.GpsMath
import com.metrik.app.gps.LocationSource
import com.metrik.app.ui.components.ErrorBanner
import com.metrik.app.ui.components.MetrikTopBar
import com.metrik.app.ui.components.StatRow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

enum class CalPhase { IDLE, RUNNING, DONE }

data class CalibrationUiState(
    val stepPhase: CalPhase = CalPhase.IDLE,
    val steps: Int = 0,
    val distanceText: String = "10",
    val stepLengthM: Double? = null,
    val gyroPhase: CalPhase = CalPhase.IDLE,
    val gyroDriftDegPerMin: Double? = null,
    val gpsPhase: CalPhase = CalPhase.IDLE,
    val gpsReadings: Int = 0,
    val gps: GpsMath.StaticError? = null,
    val message: String? = null,
    val error: String? = null,
)

/** Calibración de sensores: largo de paso (acelerómetro), deriva del giroscopio y precisión del GPS. */
class CalibrationViewModel(
    private val session: SessionStore,
    private val repo: CalibrationRepository,
    private val steps: Flow<Unit>,
    private val heading: Flow<Double>?,
    private val location: LocationSource,
    private val gyroSeconds: Long = 10,
    private val gpsReadings: Int = 15,
) : ViewModel() {
    private val _state = MutableStateFlow(CalibrationUiState())
    val state: StateFlow<CalibrationUiState> = _state
    private var stepJob: Job? = null

    fun onDistance(text: String) = _state.update { it.copy(distanceText = text) }

    fun startSteps() {
        stepJob?.cancel()
        _state.update { it.copy(stepPhase = CalPhase.RUNNING, steps = 0, stepLengthM = null, error = null) }
        stepJob = viewModelScope.launch { steps.collect { _state.update { s -> s.copy(steps = s.steps + 1) } } }
    }

    /** Largo de paso = distancia real caminada / pasos detectados. */
    fun finishSteps() {
        stepJob?.cancel()
        val s = _state.value
        val distance = s.distanceText.replace(',', '.').toDoubleOrNull()
        val length = if (distance != null && s.steps > 0) distance / s.steps else null
        if (length == null || length !in 0.3..1.2) {
            _state.update { it.copy(stepPhase = CalPhase.IDLE, error = "Camina al menos 5 m en línea recta (largo de paso entre 0,3 y 1,2 m)") }
            return
        }
        _state.update { it.copy(stepPhase = CalPhase.DONE, stepLengthM = length) }
        save("step_length", mapOf("step_length_m" to length, "steps" to s.steps.toDouble(), "distance_m" to distance!!)) {
            session.setStepLength(length)
        }
    }

    /** Deriva del giroscopio: cuánto cambia el rumbo con el celular quieto. */
    fun startGyro() {
        val flow = heading ?: run { _state.update { it.copy(error = "Este celular no tiene giroscopio") }; return }
        _state.update { it.copy(gyroPhase = CalPhase.RUNNING, error = null) }
        viewModelScope.launch {
            val values = mutableListOf<Double>()
            withTimeoutOrNull(gyroSeconds * 1000) { flow.collect { values += it } }
            var drift = 0.0
            for (i in 1 until values.size) {
                var d = values[i] - values[i - 1]
                while (d > 180) d -= 360
                while (d < -180) d += 360
                drift += d
            }
            val perMin = drift * 60.0 / gyroSeconds
            _state.update { it.copy(gyroPhase = CalPhase.DONE, gyroDriftDegPerMin = perMin) }
            save("gyro_drift", mapOf("drift_deg_per_min" to perMin, "seconds" to gyroSeconds.toDouble())) {}
        }
    }

    /** Precisión del GPS con el celular quieto (CEP). */
    fun startGps() {
        _state.update { it.copy(gpsPhase = CalPhase.RUNNING, gpsReadings = 0, error = null) }
        viewModelScope.launch {
            val samples = mutableListOf<GpsSample>()
            runCatching {
                location.samples.collect { s ->
                    samples += s
                    _state.update { it.copy(gpsReadings = samples.size) }
                    if (samples.size >= gpsReadings) throw StopCollecting()
                }
            }
            val err = GpsMath.staticError(samples)
            _state.update { it.copy(gpsPhase = CalPhase.DONE, gps = err) }
            save("gps_static", mapOf("cep50_m" to err.cep50M, "r95_m" to err.r95M, "readings" to err.n.toDouble())) {}
        }
    }

    private fun save(kind: String, params: Map<String, Double>, local: suspend () -> Unit) {
        viewModelScope.launch {
            local()
            runCatching { repo.save(kind, params) }
                .onSuccess { _state.update { it.copy(message = "Calibración guardada") } }
                .onFailure { t -> _state.update { it.copy(message = "Guardada en el celular (sin conexión: ${t.userMessage()})") } }
        }
    }

    private class StopCollecting : RuntimeException()
}

private fun f(v: Double, unit: String) = String.format(Locale.US, "%.2f %s", v, unit)

@Composable
fun CalibrationScreen(vm: CalibrationViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { MetrikTopBar("Calibrar sensores", onBack = onBack) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Calibra una vez por celular. Mejora la medición por pasos y te dice si el GPS está listo.",
                style = MaterialTheme.typography.bodyMedium)
            ErrorBanner(s.error)
            s.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            CalCard("1. Largo de paso (acelerómetro)", "Marca dos puntos en el piso, mide la distancia con la wincha y camina de uno a otro.") {
                OutlinedTextField(
                    s.distanceText, vm::onDistance, label = { Text("Distancia real (m)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().testTag("cal_distance"),
                )
                when (s.stepPhase) {
                    CalPhase.RUNNING -> {
                        StatRow("Pasos detectados", s.steps.toString())
                        Button(onClick = vm::finishSteps, modifier = Modifier.fillMaxWidth().testTag("cal_steps_finish")) { Text("Llegué al punto final") }
                    }
                    else -> Button(onClick = vm::startSteps, modifier = Modifier.fillMaxWidth().testTag("cal_steps_start")) { Text("Empezar a caminar") }
                }
                s.stepLengthM?.let { StatRow("Tu largo de paso", f(it, "m")) }
            }

            CalCard("2. Giroscopio (deriva en reposo)", "Deja el celular quieto sobre una mesa durante 10 segundos.") {
                if (s.gyroPhase == CalPhase.RUNNING) LinearProgressIndicator(Modifier.fillMaxWidth())
                else Button(onClick = vm::startGyro, modifier = Modifier.fillMaxWidth().testTag("cal_gyro")) { Text("Medir deriva") }
                s.gyroDriftDegPerMin?.let {
                    StatRow("Deriva", f(it, "°/min"))
                    Text(if (kotlin.math.abs(it) < 1.0) "✔ Giroscopio estable" else "⚠ Deriva alta: marca bien cada esquina al medir",
                        style = MaterialTheme.typography.bodySmall)
                }
            }

            CalCard("3. GPS (precisión)", "Al aire libre, quédate quieto mientras se toman 15 lecturas.") {
                if (s.gpsPhase == CalPhase.RUNNING) {
                    LinearProgressIndicator(Modifier.fillMaxWidth()); Text("${s.gpsReadings} lecturas")
                } else Button(onClick = vm::startGps, modifier = Modifier.fillMaxWidth().testTag("cal_gps")) { Text("Medir precisión") }
                s.gps?.let {
                    StatRow("Error típico (CEP 50 %)", f(it.cep50M, "m"))
                    StatRow("Error máximo habitual (R95)", f(it.r95M, "m"))
                    Text(if (it.needsCalibration) "⚠ Señal débil: busca un lugar más abierto" else "✔ GPS listo para medir terrenos",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun CalCard(title: String, help: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}
