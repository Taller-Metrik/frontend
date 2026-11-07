package com.metrik.app.feature.projects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Satellite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrik.app.domain.SurveyMode
import com.metrik.app.ui.components.ErrorBanner
import com.metrik.app.ui.components.MetrikTopBar
import com.metrik.app.ui.components.RenameDialog
import com.metrik.app.ui.components.StepsGuide
import com.metrik.app.ui.components.m
import com.metrik.app.ui.components.m2

@Composable
fun ProjectsScreen(
    vm: ProjectsViewModel,
    userName: String?,
    onOpen: (id: Int, name: String) -> Unit,
    onSettings: () -> Unit,
    onLoggedOut: () -> Unit,
    onCalibrate: () -> Unit = {},
) {
    val s by vm.state.collectAsStateWithLifecycle()
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Pair<Int, String>?>(null) }
    renaming?.let { (id, name) ->
        RenameDialog("Cambiar nombre de la carpeta", name, onConfirm = { vm.rename(id, it); renaming = null }, onDismiss = { renaming = null })
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(s.message) { s.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    Scaffold(
        topBar = {
            MetrikTopBar(title = "Proyectos", actions = {
                IconButton(onClick = vm::syncPending, modifier = Modifier.testTag("sync_pending")) { Icon(Icons.Filled.Sync, "Sincronizar") }
                IconButton(onClick = onCalibrate, modifier = Modifier.testTag("open_calibration")) { Icon(Icons.Filled.Tune, "Calibrar sensores") }
                IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Ajustes") }
                IconButton(onClick = { vm.logout(onLoggedOut) }, modifier = Modifier.testTag("logout")) {
                    Icon(Icons.AutoMirrored.Filled.Logout, "Cerrar sesión")
                }
            })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreate = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Nuevo proyecto") },
                modifier = Modifier.testTag("new_project"),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!userName.isNullOrBlank()) Text("Hola, $userName", Modifier.padding(16.dp, 12.dp, 16.dp, 0.dp), style = MaterialTheme.typography.titleMedium)
            ErrorBanner(s.error, Modifier.padding(16.dp))
            if (s.projects.isEmpty() && !s.loading) {
                StepsGuide(
                    "¿Cómo funciona MetriK?",
                    listOf(
                        "Crea un proyecto (por ejemplo, el pabellón o el terreno).",
                        "Mide: recorre el ambiente con el celular y marca cada esquina.",
                        "Revisa el plano, expórtalo a CAD y compáralo con la wincha.",
                    ),
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(s.projects, key = { it.id }) { p ->
                    Card(Modifier.fillMaxWidth().clickable { onOpen(p.id, p.name) }.testTag("project_${p.name}")) {
                        ListItem(
                            headlineContent = { Text(p.name) },
                            supportingContent = { p.description?.let { Text(it) } },
                            leadingContent = { Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = { renaming = p.id to p.name }, modifier = Modifier.testTag("rename_project_${p.id}")) {
                                        Icon(Icons.Filled.Edit, "Cambiar nombre")
                                    }
                                    IconButton(onClick = { vm.delete(p.id) }) { Icon(Icons.Filled.Delete, "Eliminar") }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (showCreate) {
        var name by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("Nuevo proyecto") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.testTag("project_name"))
                    OutlinedTextField(desc, { desc = it }, label = { Text("Descripción (opcional)") })
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.create(name, desc); showCreate = false }, modifier = Modifier.testTag("project_create")) { Text("Crear") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
fun ProjectDetailScreen(
    vm: ProjectDetailViewModel,
    projectName: String,
    onBack: () -> Unit,
    onMeasureInterior: () -> Unit,
    onMeasureExterior: () -> Unit,
    onOpenPlan: (Long) -> Unit,
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val local by vm.local.collectAsStateWithLifecycle()
    val remoteOnly = s.remote.filter { r -> local.none { it.remoteId == r.id } }
    var renaming by remember { mutableStateOf<Pair<Long, String>?>(null) }
    renaming?.let { (id, name) ->
        RenameDialog("Cambiar nombre del plano", name, onConfirm = { vm.rename(id, it); renaming = null }, onDismiss = { renaming = null })
    }

    Scaffold(topBar = {
        MetrikTopBar(projectName, onBack = onBack, actions = {
            IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, "Actualizar") }
        })
    }) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Text("Nueva medición", style = MaterialTheme.typography.titleSmall) }
            item {
                Card(Modifier.fillMaxWidth().clickable(onClick = onMeasureInterior).testTag("measure_interior")) {
                    ListItem(
                        headlineContent = { Text("Medir un ambiente") },
                        supportingContent = { Text("Aulas, cuartos, oficinas. Caminas junto a las paredes con el celular.") },
                        leadingContent = { Icon(Icons.Filled.Straighten, null, tint = MaterialTheme.colorScheme.primary) },
                    )
                }
            }
            item {
                Card(Modifier.fillMaxWidth().clickable(onClick = onMeasureExterior).testTag("measure_exterior")) {
                    ListItem(
                        headlineContent = { Text("Medir un terreno con GPS") },
                        supportingContent = { Text("Terrenos, canchas, manzanas (más de 30 m). Recorres el borde.") },
                        leadingContent = { Icon(Icons.Filled.Satellite, null, tint = MaterialTheme.colorScheme.primary) },
                    )
                }
            }
            item { if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item { ErrorBanner(s.error) }
            item { Text("Mediciones guardadas", style = MaterialTheme.typography.titleSmall) }
            if (local.isEmpty()) item { Text("Todavía no hay mediciones. Elige una opción de arriba para empezar.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(local, key = { "l${it.id}" }) { sv ->
                Card(Modifier.fillMaxWidth().clickable { onOpenPlan(sv.id) }.testTag("survey_${sv.name}")) {
                    ListItem(
                        headlineContent = { Text(sv.name) },
                        supportingContent = {
                            androidx.compose.foundation.layout.Column {
                                Text("${if (sv.mode == SurveyMode.INTERIOR) "Interior" else "Exterior"} · ${sv.areaM2.m2()} · ${sv.perimeterM.m()} · 1:${sv.scaleDenominator}")
                                Text(
                                    if (sv.synced) "✔ Guardado en el servidor" else "↑ Solo en este celular — toca la nube para subirlo",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (sv.synced) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                                )
                            }
                        },
                        leadingContent = {
                            Icon(
                                if (sv.synced) Icons.Filled.CloudDone else Icons.Filled.CloudUpload,
                                if (sv.synced) "Sincronizado" else "Pendiente",
                                tint = if (sv.synced) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                            )
                        },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { renaming = sv.id to sv.name }, modifier = Modifier.testTag("rename_survey_${sv.id}")) {
                                    Icon(Icons.Filled.Edit, "Cambiar nombre")
                                }
                                if (!sv.synced) IconButton(onClick = { vm.sync(sv.id) }) { Icon(Icons.Filled.CloudUpload, "Sincronizar") }
                                IconButton(onClick = { vm.delete(sv.id) }) { Icon(Icons.Filled.Delete, "Eliminar") }
                            }
                        },
                    )
                }
            }
            if (remoteOnly.isNotEmpty()) {
                item { Text("En el servidor (otros dispositivos)", style = MaterialTheme.typography.titleSmall) }
                items(remoteOnly, key = { "r${it.id}" }) { r ->
                    Card(Modifier.fillMaxWidth().clickable { vm.importRemote(r, onOpenPlan) }) {
                        ListItem(
                            headlineContent = { Text(r.name) },
                            supportingContent = { Text("${r.mode} · ${r.areaM2.m2()} · ${r.perimeterM.m()}") },
                            leadingContent = { Icon(Icons.Filled.CloudDownload, "Descargar") },
                        )
                    }
                }
            }
        }
    }
}
