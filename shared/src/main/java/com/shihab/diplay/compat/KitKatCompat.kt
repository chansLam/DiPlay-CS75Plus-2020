package com.shihab.diplay.compat

import android.os.Build

/**
 * KitKat compatibility shims for platform methods introduced after API 19, ported from
 * changan-upstream 5805824 (lint NewApi sweep). Only the helpers this tree actually calls are
 * defined here; other KitKat guards in this project are written inline against Build.VERSION.SDK_INT.
 */
object Base64Compat {
    /** java.util.Base64.getEncoder() is API 26; android.util.Base64 has existed since API 1. */
    fun encodeToString(data: ByteArray): String = try {
        android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
    } catch (_: RuntimeException) {
        // Plain JVM unit tests do not mock android.util.Base64.
        java.util.Base64.getEncoder().encodeToString(data)
    }

    /** MIME encoder with a custom line length (java.util.Base64.getMimeEncoder(width, newline)). */
    fun encodeMimeToString64(data: ByteArray, lineLength: Int = 64): String {
        val flat = encodeToString(data)
        if (lineLength <= 0) return flat
        return flat.chunked(lineLength).joinToString("\n")
    }

    /** MIME decoding (lenient), which also accepts the forms iPhones send. */
    fun decode(text: String): ByteArray = try {
        android.util.Base64.decode(text, android.util.Base64.DEFAULT)
    } catch (_: RuntimeException) {
        java.util.Base64.getDecoder().decode(text)
    }
}

/** MediaCodec#getInputBuffer(int) is API 21; the deprecated buffers array covers KitKat. */
fun android.media.MediaCodec.inputBufferCompat(index: Int): java.nio.ByteBuffer? =
    if (Build.VERSION.SDK_INT >= 21) getInputBuffer(index)
    else @Suppress("DEPRECATION") inputBuffers?.get(index)

/** MediaCodec#getOutputBuffer(int) is API 21; the deprecated buffers array covers KitKat. */
fun android.media.MediaCodec.outputBufferCompat(index: Int): java.nio.ByteBuffer? =
    if (Build.VERSION.SDK_INT >= 21) getOutputBuffer(index)
    else @Suppress("DEPRECATION") outputBuffers?.get(index)
