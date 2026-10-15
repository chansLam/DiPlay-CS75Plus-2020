package com.shihab.diplay.media

/**
 * Whether this head unit can decode Opus for CarPlay audio playback.
 *
 * Android 4.4.2 (API 19) lacks a built-in Opus decoder, so platform Opus decoding is unavailable;
 * the CarPlay audio path falls back to the bundled decoder / PCM.
 */
object OpusDecoderSupport {
    fun isAvailable(): Boolean = false
}
