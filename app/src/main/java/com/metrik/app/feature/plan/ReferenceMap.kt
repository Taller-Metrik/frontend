package com.metrik.app.feature.plan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrik.app.core.geometry.Geometry
import com.metrik.app.core.geometry.Point
import com.metrik.app.ui.components.StatRow
import com.metrik.app.ui.components.m
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Plano de referencia a escala (indicación del profesor): se importa un plano ya escalado (1:50, 1:100, 1:200, 1:500),
 * la medición caminada se pone encima con su tamaño real según esa escala y se ubican los **hitos** (los vértices que
 * marcan los límites del área) frente al plano, para los colindantes. Con 1:100, 1 cm del plano = 1 m del terreno.
 */
object ScaledMap {
    /** Escalas de plano habituales para terrenos. */
    val SCALES = listOf(50, 100, 200, 500)

    /** Hojas: lado largo en cm (el plano escaneado o exportado ocupa la hoja entera). */
    val PAPERS = listOf("A4" to 29.7, "A3" to 42.0, "A2" to 59.4, "A1" to 84.1)

    /** Metros de terreno por píxel de la imagen: la hoja mide [paperLongCm] en su lado largo, que son [imageLongPx] píxeles. */
    fun metersPerPixel(scaleDenominator: Int, paperLongCm: Double, imageLongPx: Int): Double {
        require(scaleDenominator > 0 && paperLongCm > 0 && imageLongPx > 0)
        return paperLongCm / 100.0 * scaleDenominator / imageLongPx
    }

    /** Lo que mide en el plano (cm) un largo real [lengthM] a escala 1:[scaleDenominator]. */
    fun paperCm(lengthM: Double, scaleDenominator: Int): Double = lengthM * 100.0 / scaleDenominator

    /** Centro de los hitos (la medición gira alrededor de él al alinearla). */
    fun centroid(vertices: List<Point>) = Point(vertices.sumOf { it.x } / vertices.size, vertices.sumOf { it.y } / vertices.size)

    /**
     * Píxel de la imagen donde cae el hito [p] (metros, x = este, y = norte): la medición se gira [rotationRad] alrededor
     * de [center], se pasa a píxeles con [metersPerPixel] y su centro se pone en [centerPx] (y de la imagen hacia abajo).
     */
    fun toImage(p: Point, center: Point, rotationRad: Double, metersPerPixel: Double, centerPx: Point): Point {
        val dx = p.x - center.x
        val dy = p.y - center.y
        val rx = dx * cos(rotationRad) - dy * sin(rotationRad)
        val ry = dx * sin(rotationRad) + dy * cos(rotationRad)
        return Point(centerPx.x + rx / metersPerPixel, centerPx.y - ry / metersPerPixel)
    }

    /** Lee la imagen achicándola a lo sumo a [maxPx] de lado (un escaneo de 300 ppp no entra en memoria entero). */
    fun decode(context: Context, uri: Uri, maxPx: Int = 2048): Bitmap? = runCatching {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxPx) sample *= 2
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }.getOrNull()
}

/**
 * Tarjeta del plano de referencia: importar la imagen, elegir su escala y hoja, alinear la medición encima (arrastrar y
 * girar con dos dedos; el tamaño no cambia porque lo fija la escala) y la tabla de hitos y linderos con su colindante.
 */
@Composable
fun ReferenceMapCard(vertices: List<Point>, modifier: Modifier = Modifier) {
    if (vertices.size < 3) return
    val context = LocalContext.current
    var uri by rememberSaveable { mutableStateOf<String?>(null) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var mapScale by rememberSaveable { mutableStateOf(100) }
    var paper by rememberSaveable { mutableStateOf("A4") }
    var shiftX by remember { mutableFloatStateOf(0f) } // desplazamiento de la medición, en píxeles de la imagen
    var shiftY by remember { mutableFloatStateOf(0f) }
    var rotation by remember { mutableFloatStateOf(0f) } // grados
    val neighbors = remember(vertices.size) { mutableStateListOf(*Array(vertices.size) { "" }) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { picked ->
        if (picked != null) { uri = picked.toString(); shiftX = 0f; shiftY = 0f; rotation = 0f }
    }
    LaunchedEffect(uri) {
        val u = uri ?: return@LaunchedEffect
        val b = withContext(Dispatchers.IO) { ScaledMap.decode(context, Uri.parse(u)) }
        bitmap = b
        loadError = if (b == null) "No se pudo abrir la imagen del plano" else null
    }
    val center = remember(vertices) { ScaledMap.centroid(vertices) }
    val paperCm = ScaledMap.PAPERS.first { it.first == paper }.second
    val n = vertices.size

    Column(modifier.fillMaxWidth()) {
        Text("Plano de referencia a escala", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Importa el plano ya escalado (foto o escaneo de la hoja entera). Con 1:$mapScale, 1 cm del plano = " +
                "${String.format(Locale.US, "%.2f", mapScale / 100.0)} m del terreno. Tu medición se dibuja encima con su tamaño " +
                "real: arrástrala y gírala con dos dedos hasta que los hitos caigan sobre los del plano.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = { pick.launch("image/*") }, modifier = Modifier.fillMaxWidth().testTag("reference_import")) {
            Icon(Icons.Filled.Map, null); Spacer(Modifier.width(6.dp)); Text(if (uri == null) "Importar plano a escala" else "Cambiar plano")
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Escala:", Modifier.padding(top = 14.dp))
            ScaledMap.SCALES.forEach { d ->
                FilterChip(mapScale == d, { mapScale = d }, { Text("1:$d") }, modifier = Modifier.testTag("reference_scale_$d"))
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Hoja:", Modifier.padding(top = 14.dp))
            ScaledMap.PAPERS.forEach { (name, _) -> FilterChip(paper == name, { paper = name }, { Text(name) }) }
        }
        loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        val b = bitmap
        if (b != null) {
            val mpp = ScaledMap.metersPerPixel(mapScale, paperCm, max(b.width, b.height))
            val image = remember(b) { b.asImageBitmap() }
            val measurer = rememberTextMeasurer()
            val hitoColor = MaterialTheme.colorScheme.primary
            Card(Modifier.fillMaxWidth().height(360.dp).padding(top = 6.dp)) {
                Canvas(
                    Modifier.fillMaxWidth().height(360.dp).testTag("reference_canvas").pointerInput(b) {
                        detectTransformGestures { _, pan, _, rot ->
                            val v = min(size.width / b.width.toFloat(), size.height / b.height.toFloat())
                            shiftX += pan.x / v
                            shiftY += pan.y / v
                            rotation -= rot // en pantalla y crece hacia abajo: el giro se invierte
                        }
                    },
                ) {
                    val v = min(size.width / b.width, size.height / b.height)
                    val ox = (size.width - b.width * v) / 2
                    val oy = (size.height - b.height * v) / 2
                    drawImage(
                        image, dstOffset = IntOffset(ox.toInt(), oy.toInt()),
                        dstSize = IntSize((b.width * v).toInt(), (b.height * v).toInt()), alpha = 0.85f,
                    )
                    val centerPx = Point(b.width / 2.0 + shiftX, b.height / 2.0 + shiftY)
                    val pts = vertices.map {
                        val q = ScaledMap.toImage(it, center, Math.toRadians(rotation.toDouble()), mpp, centerPx)
                        Offset(ox + q.x.toFloat() * v, oy + q.y.toFloat() * v)
                    }
                    val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close() }
                    drawPath(path, Color(0x332E7D32))
                    drawPath(path, Color(0xFFC62828), style = Stroke(width = 4f))
                    pts.forEachIndexed { i, p ->
                        drawCircle(hitoColor, 9f, p)
                        drawText(measurer, "H${i + 1}", p + Offset(10f, -30f), TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFC62828)))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Giro ${String.format(Locale.US, "%.0f", rotation)}° · 1 píxel = ${String.format(Locale.US, "%.1f", mpp * 100)} cm del terreno",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { shiftX = 0f; shiftY = 0f; rotation = 0f }) { Text("Centrar") }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("Hitos (vértices del lindero)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        vertices.forEachIndexed { i, p ->
            StatRow("H${i + 1}", "E ${String.format(Locale.US, "%.2f", p.x)} m · N ${String.format(Locale.US, "%.2f", p.y)} m", Modifier.testTag("hito_$i"))
        }
        Spacer(Modifier.height(6.dp))
        Text("Linderos y colindantes", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        (0 until n).forEach { i ->
            val len = Geometry.euclidean(vertices[i], vertices[(i + 1) % n])
            StatRow(
                "H${i + 1} – H${(i + 1) % n + 1}",
                "${len.m()} · ${String.format(Locale.US, "%.1f", ScaledMap.paperCm(len, mapScale))} cm en el plano 1:$mapScale",
                Modifier.testTag("lindero_$i"),
            )
            OutlinedTextField(
                value = neighbors.getOrElse(i) { "" }, onValueChange = { if (i < neighbors.size) neighbors[i] = it },
                label = { Text("Colindante (vecino, calle, acequia…)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("colindante_$i"),
            )
        }
    }
}
