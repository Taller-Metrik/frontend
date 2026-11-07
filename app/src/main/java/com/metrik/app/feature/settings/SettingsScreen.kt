package com.metrik.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrik.app.data.session.SessionStore
import com.metrik.app.sensors.ArSupport
import com.metrik.app.ui.components.MetrikTopBar
import kotlinx.coroutines.launch

private val MODES = listOf(
    Triple("auto", "Automático", "Usa la cámara (ARCore) si el celular la soporta; si no, los pasos."),
    Triple("arcore", "Cámara (ARCore)", "Más preciso. Lleva la mira por el borde donde el piso toca la pared mientras caminas."),
    Triple("steps", "Pasos", "Sin cámara: cuenta pasos (acelerómetro) y giros (giroscopio). Marca cada esquina."),
)

/**
 * Ajustes del usuario. La conexión con el backend NO se configura aquí: la URL se define al
 * compilar (local.properties -> metrik.api.url).
 */
@Composable
fun SettingsScreen(session: SessionStore, onBack: () -> Unit, arSupportedOverride: Boolean? = null) {
    val current by session.session.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val arSupported = remember { arSupportedOverride ?: ArSupport.isSupported(context) }

    Scaffold(topBar = { MetrikTopBar("Ajustes", onBack = onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("¿Cómo quieres medir?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (arSupported) "✔ Tu celular es compatible con la cámara de medición (ARCore)."
                else "Tu celular no es compatible con ARCore: se medirá por pasos.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MODES.forEach { (key, title, description) ->
                Card(
                    Modifier.fillMaxWidth()
                        .selectable(current.odometryMode == key, onClick = { scope.launch { session.setOdometryMode(key) } })
                        .testTag("mode_$key"),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = current.odometryMode == key, onClick = null)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(title, fontWeight = FontWeight.SemiBold)
                            Text(description, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
