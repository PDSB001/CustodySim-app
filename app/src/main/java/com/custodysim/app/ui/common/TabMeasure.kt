package com.custodysim.app.ui.common

/** Fit measured intrinsic widths, reserving touch space before sharing the remaining pixels. */
internal fun fitTabWidths(desired: List<Int>, available: Int, minimum: Int): List<Int> {
    if (desired.isEmpty()) return emptyList()
    if (desired.sum() <= available) return desired
    val base = minimum.coerceAtMost(available / desired.size).coerceAtLeast(0)
    val spare = (available - base * desired.size).coerceAtLeast(0)
    val weights = desired.map { (it - base).coerceAtLeast(0).toLong() }
    val total = weights.sum()
    var cumulative = 0L
    var assigned = 0
    return weights.mapIndexed { index, weight ->
        cumulative += weight
        val end = if (index == weights.lastIndex) spare else if (total == 0L) spare * (index + 1) / desired.size else (spare * cumulative / total).toInt()
        (base + end - assigned).also { assigned = end }
    }
}
