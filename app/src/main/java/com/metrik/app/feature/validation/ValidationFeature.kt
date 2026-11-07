package com.metrik.app.feature.validation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
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
import com.metrik.app.core.geometry.Geometry
import com.metrik.app.data.remote.ErrorReportDto
import com.metrik.app.data.remote.userMessage
import com.metrik.app.data.repository.SurveyRepository
import com.metrik.app.data.repository.ValidationRepository
import com.metrik.app.domain.LocalSurvey
import com.metrik.app.ui.components.ErrorBanner
import com.metrik.app.ui.components.LoadingButton
import com.metrik.app.ui.components.MetrikTopBar
import com.metrik.app.ui.components.StatRow
import com.metrik.app.ui.components.m
import com.metrik.app.ui.components.pct
import com.metrik.app.ui.theme.MetrikColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/** Reglas de entrada de las lecturas de wincha (particiones y valores límite de caja negra). */
object ReadingValidator {
    const val MAX_M = 200.0

    sealed interface Result {
        data class Ok(val value: Double) : Result
        data object Empty : Result
        data class Invalid(val reason: String) : Result
    }

    fun parse(text: String): Result {
        val t = text.trim().replace(',', '.')
        if (t.isEmpty()) return Result.Empty
        val v = t.toDoubleOrNull() ?: return Result.Invalid("No es un número")
        return when {
            v <= 0.0 -> Result.Invalid("Debe ser mayor que 0")
            v > MAX_M -> Result.Invalid("Máximo ${MAX_M.toInt()} m")
            else -> Result.Ok(v)
        }
    }
}

data class ValidationUiState(
    val survey: LocalSurvey? = null,
    val readings: Map<Int, Pair<String, String>> = emptyMap(),
    val realArea: String = "",
    val fieldErrors: Map<String, String> = emptyMap(),
    val loading: Boolean = false,
    val report: ErrorReportDto? = null,
    val warnings: List<String> = emptyList(),
    val error: String? = null,
)

class ValidationViewModel(
    private val localId: Long,
    private val surveys: SurveyRepository,
    private val repo: ValidationRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ValidationUiState())
    val state: StateFlow<ValidationUiState> = _state

    init {
        viewModelScope.launch {
            val local = surveys.getLocal(localId)
            // Los muros deben ser los mismos que tiene el servidor (la validación los compara por número).
            val rid = local?.remoteId
            val fresh = if (local != null && rid != null) {
                runCatching { surveys.downloadRemote(local.projectId, rid, local.id) }.getOrNull()?.let { surveys.getLocal(it) }
            } else null
            _state.update { it.copy(survey = fresh ?: local) }
        }
    }

    fun onReading(wall: Int, first: Boolean, text: String) = _state.update {
        val (a, b) = it.readings[wall] ?: ("" to "")
        it.copy(readings = it.readings + (wall to if (first) text to b else a to text), fieldErrors = it.fieldErrors - "w$wall")
    }

    fun onRealArea(text: String) = _state.update { it.copy(realArea = text, fieldErrors = it.fieldErrors - "area") }

    fun submit() {
        val s = _state.value
        val survey = s.survey ?: return
        val errors = mutableMapOf<String, String>()
        val readings = mutableMapOf<Int, List<Double>>()
        val wallCount = Geometry.wallLengths(survey.vertices).size
        for (i in 0 until wallCount) {
            val (a, b) = s.readings[i] ?: ("" to "")
            val parsed = listOf(ReadingValidator.parse(a), ReadingValidator.parse(b))
            val invalid = parsed.filterIsInstance<ReadingValidator.Result.Invalid>().firstOrNull()
            val values = parsed.filterIsInstance<ReadingValidator.Result.Ok>().map { it.value }
            when {
                invalid != null -> errors["w$i"] = invalid.reason
                values.isNotEmpty() -> readings[i] = values
            }
        }
        if (readings.isEmpty() && errors.isEmpty()) errors["general"] = "Ingresa al menos la medida de un muro"
        val area = when (val r = ReadingValidator.parse(s.realArea)) {
            is ReadingValidator.Result.Ok -> r.value
            is ReadingValidator.Result.Invalid -> { errors["area"] = r.reason; null }
            ReadingValidator.Result.Empty -> null
        }
        if (errors.isNotEmpty()) { _state.update { it.copy(fieldErrors = errors, error = errors["general"]) }; return }

        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching {
                val synced = if (survey.synced) survey else surveys.sync(survey.id)
                if (Geometry.wallLengths(synced.vertices).size != wallCount) {
                    // Al sincronizar, el servidor ajustó el plano: se muestran sus muros para volver a anotar
                    _state.update { it.copy(survey = synced, readings = emptyMap()) }
                    error("El plano se ajustó al sincronizar y ahora tiene ${Geometry.wallLengths(synced.vertices).size} muros: revisa y vuelve a anotar las medidas")
                }
                repo.submit(requireNotNull(synced.remoteId), readings, area)
            }.onSuccess { r -> _state.update { it.copy(loading = false, report = r.report, warnings = r.warnings) } }
                .onFailure { t -> _state.update { it.copy(loading = false, error = t.userMessage()) } }
        }
    }
}

@Composable
fun ValidationScreen(vm: ValidationViewModel, onBack: () -> Unit, onDashboard: (() -> Unit)? = null) {
    val s by vm.state.collectAsStateWithLifecycle()
    val survey = s.survey
    Scaffold(topBar = { MetrikTopBar("Validar con wincha", onBack = onBack) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Mide cada muro con la wincha de esquina a esquina, a ras del piso. Anota dos lecturas por muro para detectar errores de lectura.",
                style = MaterialTheme.typography.bodyMedium,
            )
            ErrorBanner(s.error)
            if (survey == null) return@Column
            androidx.compose.material3.Card(Modifier.fillMaxWidth().height(230.dp)) {
                com.metrik.app.ui.components.PlanDrawing(polygon = survey.vertices, numberWalls = true)
            }
            Text(
                "El punto verde es la esquina donde empieza el Muro 1 (la pared donde empezaste a caminar). " +
                    "Los muros siguen en el orden en que recorriste el ambiente.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Geometry.wallLengths(survey.vertices).forEachIndexed { i, len ->
                val (a, b) = s.readings[i] ?: ("" to "")
                val err = s.fieldErrors["w$i"]
                Text("Muro ${i + 1} — app: ${len.m()}", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReadingField(a, "Lectura 1 (m)", err, { vm.onReading(i, true, it) }, Modifier.weight(1f).testTag("reading_${i}_1"))
                    ReadingField(b, "Lectura 2 (m)", err, { vm.onReading(i, false, it) }, Modifier.weight(1f).testTag("reading_${i}_2"))
                }
                err?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("reading_error_$i"))
                }
            }
            ReadingField(s.realArea, "Área real (m², opcional)", s.fieldErrors["area"], vm::onRealArea, Modifier.fillMaxWidth().testTag("real_area"))
            LoadingButton("Calcular margen de error", s.loading, vm::submit, Modifier.fillMaxWidth().testTag("validation_submit"))

            s.report?.let { r ->
                Spacer(Modifier.height(8.dp))
                Card(Modifier.fillMaxWidth().testTag("validation_report")) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Margen de error", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        StatRow("MAE", r.maeM.m(3))
                        StatRow("RMSE", r.rmseM.m(3))
                        StatRow("Sesgo", r.biasM.m(3))
                        StatRow("Error relativo medio", r.meanRelErrorPct.pct())
                        r.areaErrorPct?.let { StatRow("Error de área", it.pct()) }
                        Text(
                            if (r.meetsTarget) "Cumple el objetivo (≤ ${r.targetPct.toInt()} %)" else "No cumple el objetivo (≤ ${r.targetPct.toInt()} %)",
                            color = if (r.meetsTarget) MetrikColors.Ok else MetrikColors.Bad, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag("validation_verdict"),
                        )
                        r.walls.forEach { w ->
                            StatRow("Muro ${w.wallIndex + 1}", "${w.measuredM.m()} vs ${w.realM.m()} (${String.format(Locale.US, "%+.1f cm", w.errorM * 100)})")
                        }
                        s.warnings.forEach { Text(it, color = MetrikColors.Warn, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                if (onDashboard != null) {
                    androidx.compose.material3.Button(onClick = onDashboard, modifier = Modifier.fillMaxWidth().testTag("validation_dashboard")) {
                        Text("Ver dashboard de precisión")
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadingField(value: String, label: String, error: String?, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, isError = error != null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = modifier,
    )
}
