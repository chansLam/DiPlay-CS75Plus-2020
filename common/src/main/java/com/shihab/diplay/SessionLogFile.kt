package com.shihab.diplay

import android.os.Build
import java.io.Closeable
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** Process-wide live session log so exporters can flush before reading `files/logs`. */
internal object ActiveSessionLog {
    @Volatile
    var current: SessionLogFile? = null

    fun flush() {
        current?.flush()
    }
}

/** Bounded, private diagnostics. Each write is redacted before touching storage. */
internal class SessionLogFile(val file: File) : Closeable {
    private val lock = Any()
    @Volatile
    private var accept = true
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "diplay-session-log").apply { isDaemon = true }
    }

    fun reset(header: String) {
        val safeHeader = DiagnosticRedactor.redact(header) ?: header
        runOnWriter {
            file.parentFile?.mkdirs()
            rotate()
            file.writeText("")
            file.appendText(safeHeader + "\n")
        }
    }

    fun append(line: String) {
        val safe = DiagnosticRedactor.redact(line) ?: return
        runOnWriter {
            if (file.length() > MAX_BYTES) {
                rotate()
                file.writeText("")
            }
            file.appendText(safe + "\n")
        }
    }

    /** Blocks until previously queued writes finish (tests / export). */
    fun flush(timeoutMs: Long = 5_000L): Boolean {
        val done = CountDownLatch(1)
        try {
            writer.execute { done.countDown() }
        } catch (_: RejectedExecutionException) {
            return true
        }
        return done.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun runOnWriter(block: () -> Unit) {
        if (!accept) return
        try {
            writer.execute {
                synchronized(lock) {
                    runCatching(block)
                }
            }
        } catch (_: RejectedExecutionException) {
            // Shutting down; drop the line.
        }
    }

    private fun rotate() {
        if (!file.exists() || file.length() == 0L) return
        for (index in ARCHIVE_NAMES.lastIndex downTo 1) {
            val source = File(file.parentFile, ARCHIVE_NAMES[index - 1])
            val destination = File(file.parentFile, ARCHIVE_NAMES[index])
            if (source.exists()) source.copyTo(destination, overwrite = true)
        }
        file.copyTo(File(file.parentFile, ARCHIVE_NAMES.first()), overwrite = true)
    }

    override fun close() {
        accept = false
        writer.shutdown()
        runCatching { writer.awaitTermination(5, TimeUnit.SECONDS) }
    }

    companion object {
        const val MAX_BYTES = 512 * 1024L
        // KitKat flash + 2 GB RAM: fewer rotated copies (each rotate copies whole files).
        private val ARCHIVE_NAMES: List<String> =
            if (Build.VERSION.SDK_INT < 21) {
                listOf("previous.log", "previous-2.log")
            } else {
                listOf("previous.log") + (2..7).map { "previous-$it.log" }
            }
        val REPORT_NAMES = ARCHIVE_NAMES.reversed() + "diplay.log"
    }
}
