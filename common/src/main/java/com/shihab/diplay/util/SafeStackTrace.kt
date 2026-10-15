package com.shihab.diplay.util

/**
 * Renders a [Throwable] stack trace without recursing into its cause chain.
 *
 * Issue #163: a wireless run died with a StackOverflowError inside
 * Throwable.printStackTrace — the exception being reported carried a cyclic (or
 * very deep) cause chain, so the usual printStackTrace / stackTraceToString
 * recursion never terminated and the original error was lost from the report.
 * Crash reporting must never depend on the shape of the exception it reports:
 * this walks the cause chain iteratively, stops at the first repeated instance,
 * and caps both causes and frames per cause.
 *
 * The output mimics printStackTrace ("<class>: <message>" followed by
 * "\tat <frame>" lines and "Caused by: " separators) so existing log readers
 * keep working. Suppressed exceptions are intentionally not rendered: they add
 * recursion surface for no diagnostic value in crash reports.
 */
fun Throwable.safeStackTrace(maxCauses: Int = 16, maxFramesPerCause: Int = 120): String {
    val out = StringBuilder()
    val seen = HashSet<Throwable>()
    var current: Throwable? = this
    var causes = 0
    while (current != null && causes < maxCauses) {
        if (!seen.add(current)) {
            out.append("[cause chain truncated: cyclic cause]\n")
            current = null
            break
        }
        val cause = current
        if (causes > 0) out.append("Caused by: ")
        val message = runCatching { cause.message }.getOrNull()
        out.append(cause.javaClass.name).append(": ").append(message).append('\n')
        val frames = runCatching { cause.stackTrace }.getOrNull() ?: emptyArray()
        val limit = minOf(frames.size, maxFramesPerCause)
        for (index in 0 until limit) {
            out.append("\tat ").append(frames[index]).append('\n')
        }
        if (frames.size > limit) {
            out.append("\t... ").append(frames.size - limit).append(" more\n")
        }
        current = runCatching { cause.cause }.getOrNull()
        causes++
    }
    if (current != null) {
        out.append("[cause chain truncated: too deep]\n")
    }
    return out.toString()
}
