package com.metrik.app.feature

import com.metrik.app.core.geometry.Point
import com.metrik.app.feature.plan.ScaledMap
import com.metrik.app.testing.EvidenceRule
import com.metrik.app.testing.Evidencia
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Plano de referencia a escala: metros por píxel, centímetros en el plano y ubicación de los hitos sobre la imagen. */
class ScaledMapTest {
    @get:Rule val ev = EvidenceRule()

    @Test
    @Evidencia(
        id = "FU-101", nombre = "Plano importado a escala: 1 cm del plano = 1 m del terreno a 1:100", requisito = "RF-10",
        objetivo = "Verificar que un plano escaneado a escala se lleva a metros con su escala y hoja, que cada lindero se "
            + "convierte a centímetros del plano y que los hitos medidos caen en el píxel correcto de la imagen.",
        entrada = "Hoja A4 (29,7 cm) escaneada a 2970 px; escalas 1:100 y 1:500; lindero de 12,73 m; hito 10 m al este y "
            + "5 m al norte del centro, sin giro y con giro de 90°",
        esperado = "1:100 → 0,01 m/px y 12,73 cm; 1:500 → 0,05 m/px y 2,55 cm; hito en (2500, 500) px; con 90° en (1000, 0) px",
    )
    fun escalaDelPlano() {
        val mpp100 = ScaledMap.metersPerPixel(100, 29.7, 2970)
        val mpp500 = ScaledMap.metersPerPixel(500, 29.7, 2970)
        val cm100 = ScaledMap.paperCm(12.73, 100)
        val cm500 = ScaledMap.paperCm(12.73, 500)
        val center = Point(0.0, 0.0)
        val px = ScaledMap.toImage(Point(10.0, 5.0), center, 0.0, mpp100, Point(1500.0, 1000.0))
        val rotated = ScaledMap.toImage(Point(10.0, 5.0), center, Math.PI / 2, mpp100, Point(1500.0, 1000.0))
        ev.obtenido = "1:100 → ${"%.3f".format(mpp100)} m/px, ${"%.2f".format(cm100)} cm; 1:500 → ${"%.3f".format(mpp500)} m/px, " +
            "${"%.2f".format(cm500)} cm; hito (${"%.0f".format(px.x)}, ${"%.0f".format(px.y)}) px; girado (${"%.0f".format(rotated.x)}, ${"%.0f".format(rotated.y)}) px"
        assertEquals(0.01, mpp100, 1e-9)
        assertEquals(0.05, mpp500, 1e-9)
        assertEquals(12.73, cm100, 1e-9)
        assertEquals(2.546, cm500, 1e-9)
        assertEquals(2500.0, px.x, 1e-6)
        assertEquals(500.0, px.y, 1e-6)
        assertEquals(1000.0, rotated.x, 1e-6)
        assertEquals(0.0, rotated.y, 1e-6)
    }
}
