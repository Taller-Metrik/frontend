package com.metrik.app.core.geometry

/** Escalas de plano (docs/03-algoritmos.md §6). */
object Scale {
    val STANDARD = listOf(20, 25, 50, 75, 100, 200, 250, 500, 1000, 2000)

    /** Área útil de dibujo en una hoja A3 horizontal con márgenes y rótulo (mm). */
    private const val SHEET_W_MM = 380.0
    private const val SHEET_H_MM = 230.0

    fun validate(denominator: Int): Int {
        if (denominator !in STANDARD) throw GeometryException("Escala 1:$denominator no soportada")
        return denominator
    }

    fun toPaperMm(realM: Double, denominator: Int): Double = realM * 1000.0 / validate(denominator)

    fun toRealM(paperMm: Double, denominator: Int): Double = paperMm * validate(denominator) / 1000.0

    fun suggest(widthM: Double, heightM: Double): Int = STANDARD.firstOrNull { d ->
        val w = toPaperMm(widthM, d)
        val h = toPaperMm(heightM, d)
        (w <= SHEET_W_MM && h <= SHEET_H_MM) || (w <= SHEET_H_MM && h <= SHEET_W_MM)
    } ?: STANDARD.last()

    fun suggestFor(vertices: List<Point>): Int =
        suggest(vertices.maxOf { it.x } - vertices.minOf { it.x }, vertices.maxOf { it.y } - vertices.minOf { it.y })

    /** Calibración de un plano importado con una distancia real conocida (RF-11). */
    fun metersPerPixel(p1: Point, p2: Point, realDistanceM: Double): Double {
        if (realDistanceM <= 0) throw GeometryException("La distancia real debe ser mayor que cero")
        val px = Geometry.euclidean(p1, p2)
        if (px == 0.0) throw GeometryException("Los dos puntos de calibración no pueden coincidir")
        return realDistanceM / px
    }
}
