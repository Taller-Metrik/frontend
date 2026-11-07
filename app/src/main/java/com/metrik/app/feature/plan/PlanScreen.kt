package com.metrik.app.feature.plan

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrik.app.core.geometry.Geometry
import com.metrik.app.core.geometry.Scale
import com.metrik.app.domain.SurveyMode
import com.metrik.app.ui.components.ErrorBanner
import com.metrik.app.ui.components.MetrikTopBar
import com.metrik.app.ui.components.PlanDrawing
import com.metrik.app.ui.components.StatRow
import com.metrik.app.ui.components.deg
import com.metrik.app.ui.components.m
import com.metrik.app.ui.components.m2
import com.metrik.app.ui.components.pct
import com.metrik.app.ui.theme.MetrikColors
import java.util.Locale

@Composable
fun PlanScreen(
    vm: PlanViewModel,
    onBack: () -> Unit,
    onValidate: (Long) -> Unit,
    onDashboard: (Long) -> Unit = {},
    onGpsReference: (Long) -> Unit = {},
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(s.message) { s.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    val survey = s.survey
    val a = s.analysis
    var renaming by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (renaming && survey != null) {
        com.metrik.app.ui.components.RenameDialog("Cambiar nombre del plano", survey.name, onConfirm = { vm.rename(it); renaming = false }, onDismiss = { renaming = false })
    }

    Scaffold(
        topBar = {
            MetrikTopBar(survey?.name ?: "Plano", onBack = onBack, actions = {
                if (survey != null) androidx.compose.material3.IconButton(onClick = { renaming = true }, modifier = Modifier.testTag("rename_plan")) {
                    Icon(androidx.compose.material.icons.Icons.Filled.Edit, "Cambiar nombre")
                }
            })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            ErrorBanner(s.error)
            Card(Modifier.fillMaxWidth().height(320.dp)) { PlanDrawing(polygon = survey?.vertices.orEmpty()) }
            if (survey == null || a == null) return@Column

            Spacer(Modifier.height(12.dp))
            Button(onClick = { onDashboard(survey.id) }, modifier = Modifier.fillMaxWidth().testTag("plan_dashboard")) {
                Icon(Icons.Filled.Insights, null); Spacer(Modifier.width(6.dp)); Text("Ver precisión y margen de error")
            }
            Spacer(Modifier.height(12.dp))
            Text("Resumen", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            StatRow("Modo", if (survey.mode == SurveyMode.INTERIOR) "Interior" else "Exterior (GPS)")
            StatRow("Área", a.areaM2.m2())
            StatRow("Perímetro", a.perimeterM.m())
            StatRow("Forma", if (a.isConvex) "Convexa" else "Cóncava (${a.concaveVertices.size} vértice(s))")
            StatRow("Suma de ángulos", "${a.angleSumDeg.deg()} (esperado ${a.expectedAngleSumDeg.deg()})")
            StatRow("Estado", if (survey.synced) "Sincronizado (#${survey.remoteId})" else "Solo en el celular", Modifier.testTag("plan_sync_state"))

            Spacer(Modifier.height(12.dp))
            Text("Escala del plano", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Scale.STANDARD.forEach { d ->
                    FilterChip(selected = s.scale == d, onClick = { vm.setScale(d) }, label = { Text("1:$d") }, modifier = Modifier.testTag("scale_$d"))
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("Muros", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val paper = vm.paperMm()
            a.wallLengthsM.forEachIndexed { i, len ->
                val angle = a.interiorAnglesDeg[(i + 1) % a.vertices.size]
                StatRow(
                    "Muro ${i + 1}",
                    "${len.m()} · ${String.format(Locale.US, "%.1f mm", paper[i])} en papel · ángulo ${angle.deg()}",
                    Modifier.testTag("wall_$i"),
                )
            }

            Spacer(Modifier.height(8.dp))
            Text("Distancia entre vértices", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val n = a.vertices.size
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("A:", Modifier.padding(top = 14.dp))
                (0 until n).forEach { i -> FilterChip(s.pointA == i, { vm.setPoints(i, s.pointB) }, { Text("V${i + 1}") }) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("B:", Modifier.padding(top = 14.dp))
                (0 until n).forEach { i -> FilterChip(s.pointB == i, { vm.setPoints(s.pointA, i) }, { Text("V${i + 1}") }) }
            }
            val pa = a.vertices[s.pointA.coerceIn(0, n - 1)]
            val pb = a.vertices[s.pointB.coerceIn(0, n - 1)]
            StatRow("Euclidiana (línea recta)", Geometry.euclidean(pa, pb).m(), Modifier.testTag("dist_euclid"))
            StatRow("Manhattan (recorrido en ejes)", Geometry.manhattan(pa, pb).m(), Modifier.testTag("dist_manhattan"))

            s.ml?.let { ml ->
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Text("Análisis ML", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                StatRow("Probabilidad de confiable", (ml.reliabilityProbability * 100).pct(), Modifier.testTag("ml_probability"))
                Text(ml.recommendation, color = if (ml.reliable) MetrikColors.Ok else MetrikColors.Bad)
                if (ml.correctionNote.isNotBlank()) Text(ml.correctionNote, style = MaterialTheme.typography.bodySmall)
                if (ml.correctionApplied) {
                    ml.walls.forEach { w -> StatRow("Muro ${w.wallIndex + 1} corregido", "${w.measuredM.m()} → ${w.correctedM.m()}") }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::sync, enabled = !s.busy && !survey.synced, modifier = Modifier.weight(1f).testTag("plan_sync")) {
                    Icon(Icons.Filled.CloudUpload, null); Spacer(Modifier.width(4.dp)); Text("Sincronizar")
                }
                Button(onClick = vm::exportDxf, enabled = !s.busy, modifier = Modifier.weight(1f).testTag("plan_export_dxf")) {
                    Icon(Icons.Filled.Download, null); Spacer(Modifier.width(4.dp)); Text("Exportar CAD")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = vm::analyzeMl, enabled = !s.busy && survey.mode == SurveyMode.INTERIOR,
                    modifier = Modifier.weight(1f).testTag("plan_ml"),
                ) { Icon(Icons.Filled.Psychology, null); Spacer(Modifier.width(4.dp)); Text("Confiabilidad IA") }
                OutlinedButton(
                    onClick = { if (survey.mode == SurveyMode.EXTERIOR) onGpsReference(survey.id) else onValidate(survey.id) },
                    modifier = Modifier.weight(1f).testTag("plan_validate"),
                ) {
                    Icon(Icons.Filled.Rule, null); Spacer(Modifier.width(4.dp)); Text(if (survey.mode == SurveyMode.EXTERIOR) "Validar con referencia" else "Medir con wincha")
                }
            }
        }
    }
}
