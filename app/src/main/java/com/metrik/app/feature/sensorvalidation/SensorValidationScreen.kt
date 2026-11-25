package com.metrik.app.feature.sensorvalidation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrik.app.ui.components.ErrorBanner
import com.metrik.app.ui.components.MetrikTopBar
import com.metrik.app.ui.components.StatRow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun f(v: Double, unit: String, decimals: Int = 2) = String.format(Locale.US, "%.${decimals}f %s", v, unit)

private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZoneId.systemDefault())

/** Ajustes → Calibración → Validación de sensores: ensayos de GPS (RMSE), pasos y giro, con exportación a CSV. */
@Composable
fun SensorValidationScreen(vm: SensorValidationViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startGps() else vm.onLocationDenied()
    }
    val startGps = {
        if (vm.gpsInputsValid()) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (granted) vm.startGps() else locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    Scaffold(topBar = { MetrikTopBar("Validación de sensores", onBack = onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = s.tab.ordinal) {
                SensorTab.entries.forEach { tab ->
                    Tab(selected = s.tab == tab, onClick = { vm.onTab(tab) }, text = { Text(tab.label) }, modifier = Modifier.testTag("sv_tab_${tab.name}"))
                }
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ErrorBanner(s.error)
                s.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("sv_message")) }
                when (s.tab) {
                    SensorTab.GPS -> GpsTab(s, vm, startGps)
                    SensorTab.STEPS -> StepsTab(s, vm)
                    SensorTab.TURN -> TurnTab(s, vm)
                }
                History(s.trials.filter { it.kind.name == s.tab.name }, vm)
            }
        }
    }
}

@Composable
private fun GpsTab(s: SensorValidationState, vm: SensorValidationViewModel, startGps: () -> Unit) {
    Panel("GPS/GNSS — RMSE horizontal", "Escribe las coordenadas de un punto cuya posición conoces por otra fuente (plano, topografía). No las tomes con este mismo celular.") {
        OutlinedTextField(
            s.refLatText, vm::onRefLat, label = { Text("Latitud de referencia (°)") }, singleLine = true, enabled = !s.gpsRunning,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().testTag("sv_ref_lat"),
        )
        OutlinedTextField(
            s.refLonText, vm::onRefLon, label = { Text("Longitud de referencia (°)") }, singleLine = true, enabled = !s.gpsRunning,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().testTag("sv_ref_lon"),
        )
        if (s.gpsRunning) {
            Text("Coloca el celular sobre el punto y mantenlo inmóvil.", fontWeight = FontWeight.SemiBold)
            StatRow("Lecturas válidas", s.gpsValid.toString())
            StatRow("Lecturas descartadas", s.gpsDiscarded.toString())
            Actions("Finalizar", vm::finishGps, "Cancelar", vm::cancelGps, "sv_gps_finish", "sv_gps_cancel")
        } else {
            Button(onClick = startGps, modifier = Modifier.fillMaxWidth().testTag("sv_gps_start")) { Text("Iniciar") }
        }
        s.gpsNotice?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("sv_gps_notice")) }
        s.gpsResult?.let { t ->
            Text("Resultado", fontWeight = FontWeight.SemiBold)
            StatRow("RMSE horizontal", f(t.observed, "m"))
            StatRow("Duración", f(t.durationS, "s", 1))
            StatRow("Lecturas válidas", t.readings.size.toString())
            StatRow("Descartadas (inválidas/repetidas/obsoletas)", "${t.discardedInvalid}/${t.discardedDuplicate}/${t.discardedStale}")
        }
    }
}

@Composable
private fun StepsTab(s: SensorValidationState, vm: SensorValidationViewModel) {
    Panel("Acelerómetro — error en el conteo de pasos", "Camina una cantidad de pasos que cuentes tú mismo y escríbela. Se usa el mismo detector del modo interior por pasos.") {
        OutlinedTextField(
            s.refStepsText, vm::onRefSteps, label = { Text("Pasos de referencia") }, singleLine = true, enabled = !s.stepsRunning,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().testTag("sv_ref_steps"),
        )
        if (s.stepsRunning) {
            StatRow("Pasos detectados", s.stepsDetected.toString())
            Actions("Finalizar", vm::finishSteps, "Cancelar", vm::cancelSteps, "sv_steps_finish", "sv_steps_cancel")
        } else {
            Button(onClick = vm::startSteps, modifier = Modifier.fillMaxWidth().testTag("sv_steps_start")) { Text("Iniciar") }
        }
        s.stepsNotice?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("sv_steps_notice")) }
        s.stepsResult?.let { t ->
            Text("Resultado", fontWeight = FontWeight.SemiBold)
            StatRow("Pasos de referencia", t.reference.toInt().toString())
            StatRow("Pasos detectados", t.observed.toInt().toString())
            StatRow("Error relativo absoluto", f(t.error, "%"))
        }
    }
}

@Composable
private fun TurnTab(s: SensorValidationState, vm: SensorValidationViewModel) {
    val active = s.turnWaiting || s.turnMarked
    Panel(
        "Giro — error absoluto del ángulo",
        "Sostén el celular como al medir en interior. Marca el inicio, gira y marca el fin. Convención: positivo = giro a la derecha (horario), negativo = a la izquierda.",
    ) {
        OutlinedTextField(
            s.refTurnText, vm::onRefTurn, label = { Text("Ángulo de referencia (°)") }, singleLine = true, enabled = !active,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().testTag("sv_ref_turn"),
        )
        TurnSense.entries.forEach { sense ->
            Row(
                Modifier.fillMaxWidth().selectable(s.turnSense == sense, enabled = !active, onClick = { vm.onTurnSense(sense) }).testTag("sv_sense_${sense.name}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = s.turnSense == sense, onClick = null, enabled = !active)
                Text(sense.label, modifier = Modifier.padding(start = 8.dp))
            }
        }
        when {
            s.turnWaiting -> Text("Esperando la primera lectura de orientación…")
            s.turnMarked -> {
                Text("Inicio marcado. Gira y toca «Marcar fin».", fontWeight = FontWeight.SemiBold)
                Actions("Marcar fin", vm::markTurnEnd, "Cancelar", vm::cancelTurn, "sv_turn_end", "sv_turn_cancel")
            }
            else -> Button(onClick = vm::markTurnStart, modifier = Modifier.fillMaxWidth().testTag("sv_turn_start")) { Text("Marcar inicio") }
        }
        s.turnNotice?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("sv_turn_notice")) }
        s.turnResult?.let { t ->
            Text("Resultado", fontWeight = FontWeight.SemiBold)
            StatRow("Giro de referencia", f(t.reference, "°"))
            StatRow("Giro estimado por MetriK", f(t.observed, "°"))
            StatRow("Error absoluto", f(t.error, "°"))
            Text("Fuente de orientación: ${t.source}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun History(trials: List<SensorTrial>, vm: SensorValidationViewModel) {
    Panel("Ensayos guardados en este celular", "Se guardan solo aquí (no se envían al servidor). El CSV va a Descargas/MetriK.") {
        if (trials.isEmpty()) Text("Todavía no hay ensayos de este tipo.", style = MaterialTheme.typography.bodySmall)
        trials.asReversed().forEach { t ->
            Column(Modifier.fillMaxWidth().testTag("sv_trial_${t.id}")) {
                Text("${dateFormat.format(Instant.ofEpochMilli(t.createdAtMs))} · ID ${t.id}", fontWeight = FontWeight.SemiBold)
                Text(summary(t), style = MaterialTheme.typography.bodyMedium)
                Text("${t.device} · v${t.appVersion}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    TextButton(onClick = { vm.exportCsv(t.id) }) { Text("Exportar CSV") }
                    TextButton(onClick = { vm.deleteTrial(t.id) }) { Text("Eliminar") }
                }
            }
        }
        if (trials.isNotEmpty()) {
            OutlinedButton(onClick = { vm.exportCsv(null) }, modifier = Modifier.fillMaxWidth().testTag("sv_export_all")) { Text("Exportar todos los ensayos (CSV)") }
        }
    }
}

private fun summary(t: SensorTrial): String = when (t.kind) {
    TrialKind.GPS -> "RMSE ${f(t.observed, "m")} con ${t.readings.size} lecturas"
    TrialKind.STEPS -> "Referencia ${t.reference.toInt()} · detectados ${t.observed.toInt()} · error ${f(t.error, "%")}"
    TrialKind.TURN -> "Referencia ${f(t.reference, "°", 1)} · estimado ${f(t.observed, "°", 1)} · error ${f(t.error, "°", 1)}"
}

@Composable
private fun Actions(primary: String, onPrimary: () -> Unit, secondary: String, onSecondary: () -> Unit, primaryTag: String, secondaryTag: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onPrimary, modifier = Modifier.weight(1f).testTag(primaryTag)) { Text(primary) }
        OutlinedButton(onClick = onSecondary, modifier = Modifier.weight(1f).testTag(secondaryTag)) { Text(secondary) }
    }
}

@Composable
private fun Panel(title: String, help: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}
