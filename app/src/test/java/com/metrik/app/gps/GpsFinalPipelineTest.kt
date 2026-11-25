package com.metrik.app.gps

import com.metrik.app.core.geometry.Geometry
import com.metrik.app.testing.EvidenceRule
import com.metrik.app.testing.Evidencia
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * La cadena completa que corre la app al tocar Finalizar (GpsFeature.finishWithTraverse): limpieza de esquinas de
 * más → ajuste con deriva corregida → ida y vuelta por el puente regularizada. Escenario del video «puente parque».
 */
class GpsFinalPipelineTest {
    @get:Rule val ev = EvidenceRule()

    private fun pipeline(run: IdaVueltaScenario.Run): GpsTraverse.Result {
        val kept = GpsCornerCleanup.keep(run.track, run.cornerIdx, run.anchors)
        val adjusted = GpsTraverse.adjust(
            run.track, kept.map { run.cornerIdx[it] }, kept.map { run.anchors[it] }, run.end, run.startSigmaM, gps = run.gps,
        )
        val c = if (adjusted.closed) GpsCorridor.regularize(adjusted.vertices, adjusted.sigmasM) else null
        return if (c != null && c.vertices != adjusted.vertices && c.sigmasM != null &&
            runCatching { Geometry.isSimple(c.vertices) }.getOrDefault(false)
        ) adjusted.copy(vertices = c.vertices, sigmasM = c.sigmasM) else adjusted
    }

    @Test
    @Evidencia(
        id = "FU-112", nombre = "El final del video «puente parque» sale limpio con la cadena completa", requisito = "RF-08, RF-19",
        objetivo = "Verificar que, con las esquinas de más marcadas al volver por el puente, la figura final no tiene " +
            "zigzag, no se cruza, cierra, y el inicio y el cuerpo de la medición no empeoran.",
        entrada = "Escenario del video (puente 35 m ida y vuelta + rectángulo con escalón de 4,62 m, M7..M10 de más), 10 semillas",
        esperado = "10/10 simples y cerradas, 0 giros > 120° en el final, error de área medio ≤ al de antes, cuerpo no peor (+0,5 m)",
    )
    fun finalLimpio() {
        val before = mutableListOf<IdaVueltaScenario.Metrics>()
        val after = mutableListOf<IdaVueltaScenario.Metrics>()
        for (seed in 1L..10L) {
            val run = IdaVueltaScenario.generate(seed)
            before += IdaVueltaScenario.measure(run.adjust())
            after += IdaVueltaScenario.measure(pipeline(run))
        }
        fun mean(xs: List<Double>) = xs.average()
        ev.obtenido = "antes: giros>120 ${before.map { it.reversals }}, cortos ${before.map { it.shortReturnSides }}, " +
            "err. área ${"%.1f".format(mean(before.map { it.areaErrorPct }))} %, cuerpo ${"%.1f".format(mean(before.map { it.bodyMaxErrM }))} m | " +
            "ahora: simples ${after.count { it.simple }}/10, cerradas ${after.count { it.closed }}/10, giros>120 ${after.map { it.reversals }}, " +
            "cortos ${after.map { it.shortReturnSides }}, err. área ${"%.1f".format(mean(after.map { it.areaErrorPct }))} %, " +
            "cuerpo ${"%.1f".format(mean(after.map { it.bodyMaxErrM }))} m, cruza ida ${after.count { it.returnCrossesOutbound }}/10 | " +
            "semilla 1: ${after.first().summary()}"
        println(ev.obtenido)
        assertTrue("se cruza", after.all { it.simple })
        assertTrue("no cerró", after.all { it.closed })
        assertTrue("zigzag", after.all { it.reversals == 0 })
        assertTrue("cruza la ida", after.none { it.returnCrossesOutbound })
        assertTrue("área peor", mean(after.map { it.areaErrorPct }) <= mean(before.map { it.areaErrorPct }) + 0.5)
        before.zip(after).forEach { (b, a) -> assertTrue("cuerpo peor: ${b.bodyMaxErrM} → ${a.bodyMaxErrM}", a.bodyMaxErrM <= b.bodyMaxErrM + 0.5) }
    }
}
