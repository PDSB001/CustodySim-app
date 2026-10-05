package com.custodysim.readerbenchmark

import android.util.Log
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.traceprocessor.ExperimentalTraceProcessorApi
import androidx.benchmark.traceprocessor.TraceProcessor

/** Presence audit, not an ownership guess: validate these PIDs against ActivityManager bindings. */
@OptIn(ExperimentalMetricApi::class, ExperimentalTraceProcessorApi::class)
internal class RendererTraceMetric : TraceMetric() {
    override fun getMeasurements(captureInfo: Metric.CaptureInfo, traceSession: TraceProcessor.Session): List<Metric.Measurement> {
        val renderers = traceSession.query("""
            SELECT p.pid, p.name, COUNT(s.id) AS scheduled
            FROM process p JOIN thread t USING(upid) JOIN sched s USING(utid)
            WHERE p.name GLOB '*:sandboxed_process*' GROUP BY p.upid
        """.trimIndent()).toList()
        renderers.forEach { row -> Log.i("ReaderRendererAudit", "pid=${row.long("pid")} name=${row.string("name")} sched=${row.long("scheduled")}") }
        return listOf(Metric.Measurement("rendererProcessCount", renderers.size.toDouble()),
            Metric.Measurement("rendererScheduledSlices", renderers.sumOf { it.long("scheduled") }.toDouble()))
    }
}
