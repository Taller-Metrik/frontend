package com.metrik.app.ui.components

import com.metrik.app.testing.EvidenceRule
import com.metrik.app.testing.Evidencia
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Componentes comunes de la interfaz. */
class CommonTest {
    @get:Rule val ev = EvidenceRule()

    @Test
    @Evidencia(
        id = "FU-122", nombre = "Fila de dato con valor largo", requisito = "RNF (usabilidad)",
        objetivo = "Verificar que una fila con un valor largo (p. ej. un muro con su medida en papel y su ángulo) pone el "
            + "valor debajo de la etiqueta, para que la etiqueta no quede partida en «Mur / o 1».",
        entrada = "«27.42 m · 274.2 mm en papel · ángulo 120°» y «S/ 240.00»",
        esperado = "El valor largo va debajo; el corto sigue a la derecha",
    )
    fun valorLargoDebajo() {
        val largo = statRowStacked("27.42 m · 274.2 mm en papel · ángulo 120°")
        val corto = statRowStacked("S/ 240.00")
        ev.obtenido = "largo debajo=$largo, corto debajo=$corto"
        assertTrue(largo)
        assertFalse(corto)
    }
}
