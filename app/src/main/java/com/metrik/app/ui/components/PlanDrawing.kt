package com.metrik.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.metrik.app.core.geometry.Geometry
import com.metrik.app.core.geometry.Point
import com.metrik.app.ui.theme.MetrikColors
import kotlin.math.min

/**
 * Dibuja el esbozo o el plano: trazo crudo (gris), esquinas marcadas, polígono suavizado y cotas.
 * El eje Y se invierte para que el norte del recorrido quede hacia arriba.
 */
@Composable
fun PlanDrawing(
    modifier: Modifier = Modifier,
    track: List<Point> = emptyList(),
    corners: List<Point> = emptyList(),
    polygon: List<Point> = emptyList(),
    current: Point? = null,
    showDimensions: Boolean = true,
    numberWalls: Boolean = false, // "M1 · 6.00 m" y punto verde en la esquina de inicio (validación con wincha)
) {
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurface
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxSize().testTag("plan_drawing")) {
        val all = track + corners + polygon + listOfNotNull(current)
        if (all.isEmpty()) return@Canvas
        val pad = 110f // margen para que las cotas de los muros laterales no se corten
        val minX = all.minOf { it.x }
        val maxX = all.maxOf { it.x }
        val minY = all.minOf { it.y }
        val maxY = all.maxOf { it.y }
        val spanX = (maxX - minX).coerceAtLeast(1.0)
        val spanY = (maxY - minY).coerceAtLeast(1.0)
        val s = min((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanY).toFloat()
        val offX = (size.width - spanX.toFloat() * s) / 2
        val offY = (size.height - spanY.toFloat() * s) / 2
        fun map(p: Point) = Offset(offX + ((p.x - minX) * s).toFloat(), size.height - offY - ((p.y - minY) * s).toFloat())

        drawGrid(s, gridColor)

        if (track.size > 1) {
            val path = Path().apply {
                moveTo(map(track[0]).x, map(track[0]).y)
                track.drop(1).forEach { lineTo(map(it).x, map(it).y) }
            }
            drawPath(path, MetrikColors.Track, style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        if (polygon.size >= 3) {
            val path = Path().apply {
                moveTo(map(polygon[0]).x, map(polygon[0]).y)
                polygon.drop(1).forEach { lineTo(map(it).x, map(it).y) }
                close()
            }
            drawPath(path, MetrikColors.Wall.copy(alpha = 0.08f))
            drawPath(path, MetrikColors.Wall, style = Stroke(width = 7f, join = StrokeJoin.Miter))
            if (showDimensions) {
                for (i in polygon.indices) {
                    val a = polygon[i]
                    val b = polygon[(i + 1) % polygon.size]
                    val mid = map(Point((a.x + b.x) / 2, (a.y + b.y) / 2))
                    val label = measurer.measure(
                        (if (numberWalls) "M${i + 1} · " else "") + Geometry.euclidean(a, b).m(),
                        TextStyle(fontSize = 12.sp, color = labelColor, background = MetrikColors.Amber.copy(alpha = 0.85f)),
                    )
                    drawText(label, topLeft = Offset(mid.x - label.size.width / 2, mid.y - label.size.height / 2))
                }
            }
        }

        if (numberWalls && polygon.isNotEmpty()) drawCircle(MetrikColors.Ok, radius = 12f, center = map(polygon[0]))
        corners.forEach { drawCircle(MetrikColors.Corner, radius = 9f, center = map(it)) }
        current?.let {
            drawCircle(MetrikColors.Amber, radius = 14f, center = map(it))
            drawCircle(MetrikColors.Petrol, radius = 14f, center = map(it), style = Stroke(3f))
        }
    }
}

/** Cuadrícula de 1 m. */
private fun DrawScope.drawGrid(pxPerMeter: Float, color: androidx.compose.ui.graphics.Color) {
    if (pxPerMeter < 6f) return
    val effect = PathEffect.dashPathEffect(floatArrayOf(4f, 8f))
    var x = 0f
    while (x < size.width) { drawLine(color, Offset(x, 0f), Offset(x, size.height), 1f, pathEffect = effect); x += pxPerMeter }
    var y = 0f
    while (y < size.height) { drawLine(color, Offset(0f, y), Offset(size.width, y), 1f, pathEffect = effect); y += pxPerMeter }
}
