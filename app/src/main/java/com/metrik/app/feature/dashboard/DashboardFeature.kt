package com.metrik.app.feature.dashboard

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.metrik.app.core.geometry.Geometry
import com.metrik.app.data.remote.ErrorReportDto
import com.metrik.app.data.remote.MlAnalysisDto
import com.metrik.app.data.remote.userMessage
import com.metrik.app.data.repository.PlanRepository
import com.metrik.app.data.repository.SurveyRepository
import com.metrik.app.data.repository.ValidationRepository
import com.metrik.app.domain.LocalSurvey
import com.metrik.app.domain.SurveyMode
import com.metrik.app.ui.components.ErrorBanner
import com.metrik.app.ui.components.MetrikTopBar
import com.metrik.app.ui.theme.MetrikColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

/** Una fila de la comparación por muro. */
data class WallComparisonRow(
    val index: Int,
    val appM: Double,
    val modelM: Double?,
    val realM: Double?,
) {
    val appErrorPct: Double? get() = realM?.let { 100 * abs(appM - it) / it }
    val modelErrorPct: Double? get() = if (realM != null && modelM != null) 100 * abs(modelM - realM) / realM else null
}

data class DashboardUiState(
    val loading: Boolean = true,
    val survey: LocalSurvey? = null,
    val rows: List<WallComparisonRow> = emptyList(),
    val report: ErrorReportDto? = null,
    val ml: MlAnalysisDto? = null,
    val error: String? = null,
) {
    val hasReferences: Boolean get() = report != null
    /** Fuente única: el reporte de validación del backend; si no hay, se calcula con las filas. */
    val appErrorPct: Double? get() = report?.meanRelErrorPct ?: rows.mapNotNull { it.appErrorPct }.takeIf { it.isNotEmpty() }?.average()
    val modelErrorPct: Double? get() = rows.mapNotNull { it.modelErrorPct }.takeIf { it.isNotEmpty() }?.average()
    val precisionPct: Double? get() = appErrorPct?.let { (100 - it).coerceAtLeast(0.0) }
    val maeAppM: Double? get() = report?.maeM
        ?: rows.mapNotNull { r -> r.realM?.let { abs(r.appM - it) } }.takeIf { it.isNotEmpty() }?.average()
}

/**
 * Dashboard de precisión de un recorrido: medida de la app vs wincha (valor real) vs modelo de ML,
 * margen de error, precisión y algoritmos usados.
 */
class DashboardViewModel(
    private val localId: Long,
    private val surveys: SurveyRepository,
    private val plans: PlanRepository,
    private val validation: ValidationRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state

    init { load() }

    fun load() = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        runCatching {
            val local = requireNotNull(surveys.getLocal(localId)) { "Recorrido no encontrado" }
            val survey = if (local.synced) local else surveys.sync(local.id)
            val remoteId = requireNotNull(survey.remoteId)
            val report = validation.get(remoteId)?.report
            val ml = if (survey.mode == SurveyMode.INTERIOR) runCatching { plans.ml(remoteId) }.getOrNull() else null
            val rows = Geometry.wallLengths(survey.vertices).mapIndexed { i, app ->
                WallComparisonRow(
                    index = i,
                    appM = app,
                    modelM = ml?.takeIf { it.correctionApplied }?.walls?.firstOrNull { it.wallIndex == i }?.correctedM,
                    realM = report?.walls?.firstOrNull { it.wallIndex == i }?.realM,
                )
            }
            DashboardUiState(loading = false, survey = survey, rows = rows, report = report, ml = ml)
        }.onSuccess { s -> _state.value = s }
            .onFailure { t -> _state.update { it.copy(loading = false, error = t.userMessage()) } }
    }
}

/** Algoritmos usados según el tipo de recorrido (docs/03-algoritmos.md). */
fun algorithmsFor(survey: LocalSurvey, ml: MlAnalysisDto?): List<Pair<String, String>> {
    val odometry = survey.capture?.odometry
    val list = mutableListOf<Pair<String, String>>()
    if (survey.mode == SurveyMode.INTERIOR) {
        list += "Odómetro" to when (odometry) {
            "steps" -> "Pasos: detector de picos del acelerómetro (umbral 0,8 m/s², calibrado con datos reales) y rumbo integrando el giroscopio"
            else -> "ARCore: seguimiento visual-inercial (cámara + IMU) de la posición del celular, proyectada al piso"
        }
        list += "Giros" to "Giroscopio (rotation vector), ajustado a 90° si la diferencia es menor a 15°"
        list += "Suavizado" to if (odometry == "steps") {
            "Plano desde las esquinas marcadas → ortogonalización → regla del tránsito"
        } else {
            "Media móvil → Ramer-Douglas-Peucker (ε 10 cm) → ortogonalización → regla del tránsito → mínimos cuadrados"
        }
    } else {
        list += "Posición" to "GPS (Fused Location) → filtro por precisión ≤ 10 m → proyección local ENU"
        list += "Suavizado" to "Kalman + suavizador RTS → RDP adaptativo → ajuste de lados por mínimos cuadrados (PCA)"
    }
    list += "Área y perímetro" to "Fórmula de Gauss (Shoelace), válida para ambientes cóncavos y convexos"
    if (ml != null) {
        list += "Confiabilidad (IA)" to ml.classifierModel.ifBlank { "Clasificador" } + " entrenado con dataset sintético calibrado"
        list += "Corrección (IA)" to
            if (ml.correctionApplied) ml.correctorModel.ifBlank { "Regresión" } + " (odómetro ARCore)" else ml.correctionNote
    }
    list += "Margen de error" to "Comparación con la wincha: error relativo, MAE, RMSE y sesgo"
    return list
}

private fun pct(v: Double?) = v?.let { String.format(Locale.US, "%.1f %%", it) } ?: "—"
private fun meters(v: Double?) = v?.let { String.format(Locale.US, "%.2f m", it) } ?: "—"
private fun cm(v: Double?) = v?.let { String.format(Locale.US, "%.1f cm", it * 100) } ?: "—"

@Composable
fun DashboardScreen(vm: DashboardViewModel, onBack: () -> Unit, onValidate: (Long) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { MetrikTopBar("Precisión · ${s.survey?.name.orEmpty()}", onBack = onBack) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            ErrorBanner(s.error)
            val survey = s.survey ?: return@Column

            // 1. Indicadores principales
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Kpi("Margen de error", pct(s.appErrorPct), "app vs wincha", errorColor(s.appErrorPct), Modifier.weight(1f).testTag("kpi_error"))
                Kpi("Precisión", pct(s.precisionPct), "100 % − error", MetrikColors.Petrol, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Kpi("Error medio", cm(s.maeAppM), "por muro (MAE)", MetrikColors.Petrol, Modifier.weight(1f))
                Kpi(
                    "Confiabilidad IA", s.ml?.let { pct(it.reliabilityProbability * 100) } ?: "—",
                    s.ml?.let { if (it.reliable) "recorrido confiable" else "conviene repetir" } ?: "solo interiores",
                    if (s.ml?.reliable == true) MetrikColors.Ok else MetrikColors.Corner, Modifier.weight(1f),
                )
            }

            if (!s.hasReferences) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Falta la medida real", fontWeight = FontWeight.SemiBold)
                        Text("Mide cada muro con la wincha para calcular el margen de error y la precisión.")
                        Button(onClick = { onValidate(survey.id) }, modifier = Modifier.testTag("dashboard_validate")) {
                            Icon(Icons.Filled.Rule, null); Spacer(Modifier.width(6.dp)); Text("Registrar medidas con wincha")
                        }
                    }
                }
            }

            // 2. Comparación por muro
            Section("Comparación por muro") {
                Legend()
                WallBarsChart(s.rows, Modifier.fillMaxWidth().height(220.dp).testTag("dashboard_chart"))
                ComparisonTable(s.rows)
                if (s.modelErrorPct != null) {
                    Text(
                        "Con la corrección del modelo el error pasa de ${pct(s.appErrorPct)} a ${pct(s.modelErrorPct)}.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 3. Resumen del reporte de validación
            s.report?.let { r ->
                Section("Margen de error (wincha)") {
                    Stat("Error relativo medio", pct(r.meanRelErrorPct))
                    Stat("MAE · RMSE", "${cm(r.maeM)} · ${cm(r.rmseM)}")
                    Stat("Sesgo (+ mide de más, − de menos)", cm(r.biasM))
                    r.areaErrorPct?.let { Stat("Error de área", pct(it)) }
                    Text(
                        if (r.meetsTarget) "✔ Cumple el objetivo del proyecto (≤ ${r.targetPct.toInt()} %)"
                        else "✘ No cumple el objetivo (≤ ${r.targetPct.toInt()} %)",
                        color = if (r.meetsTarget) MetrikColors.Ok else MetrikColors.Bad, fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // 4. Algoritmos usados
            Section("Cómo se calculó") {
                algorithmsFor(survey, s.ml).forEach { (k, v) ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(k, style = MaterialTheme.typography.labelLarge, color = MetrikColors.Petrol)
                        Text(v, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private fun errorColor(pct: Double?): Color = when {
    pct == null -> Color.Gray
    pct <= 3 -> MetrikColors.Ok
    pct <= 10 -> MetrikColors.Warn
    else -> MetrikColors.Bad
}

@Composable
private fun Kpi(title: String, value: String, caption: String, accent: Color, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = accent)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

private val RealColor = Color(0xFF3D4A50)
private val AppColor = MetrikColors.PetrolLight
private val ModelColor = MetrikColors.Amber

@Composable
private fun Legend() {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf("Wincha (real)" to RealColor, "App" to AppColor, "Modelo IA" to ModelColor).forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(color, RoundedCornerShape(3.dp)))
                Spacer(Modifier.width(4.dp))
                Text(label, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Barras agrupadas por muro: wincha, app y modelo. */
@Composable
private fun WallBarsChart(rows: List<WallComparisonRow>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        if (rows.isEmpty()) return@Canvas
        val maxV = rows.maxOf { maxOf(it.appM, it.realM ?: 0.0, it.modelM ?: 0.0) }.coerceAtLeast(0.1) * 1.15
        val bottom = size.height - 28.dp.toPx()
        val top = 8.dp.toPx()
        val groupW = size.width / rows.size
        val barW = (groupW * 0.72f) / 3
        for (k in 0..4) {
            val y = bottom - (bottom - top) * k / 4
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f)
        }
        rows.forEachIndexed { i, r ->
            val x0 = i * groupW + groupW * 0.14f
            listOf(r.realM to RealColor, r.appM to AppColor, r.modelM to ModelColor).forEachIndexed { j, (v, color) ->
                if (v == null) return@forEachIndexed
                val h = ((v / maxV) * (bottom - top)).toFloat()
                drawRoundRect(color, Offset(x0 + j * barW, bottom - h), Size(barW * 0.9f, h), CornerRadius(6f, 6f))
            }
            val label = measurer.measure("Muro ${i + 1}", TextStyle(fontSize = 11.sp, color = labelColor, textAlign = TextAlign.Center))
            drawText(label, topLeft = Offset(i * groupW + (groupW - label.size.width) / 2, bottom + 6.dp.toPx()))
        }
    }
}

@Composable
private fun ComparisonTable(rows: List<WallComparisonRow>) {
    val header = MaterialTheme.typography.labelMedium
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            listOf("Muro", "Wincha", "App", "Error", "IA").forEach { Text(it, style = header, modifier = Modifier.weight(1f)) }
        }
        HorizontalDivider()
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${r.index + 1}", Modifier.weight(1f))
                Text(meters(r.realM), Modifier.weight(1f))
                Text(meters(r.appM), Modifier.weight(1f))
                Text(pct(r.appErrorPct), Modifier.weight(1f), color = errorColor(r.appErrorPct), fontWeight = FontWeight.SemiBold)
                Text(meters(r.modelM), Modifier.weight(1f))
            }
        }
    }
}
