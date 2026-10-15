package com.shihab.diplay.media

/**
 * Whether this head unit can encode Opus for the CarPlay microphone uplink.
 *
 * On Android 4.4.2 (API 19) there is no platform Opus encoder; the microphone uplink uses the
 * bundled native Opus encoder instead. PCM uplink works everywhere, Opus platform encoding does not.
 */
object OpusEncoderSupport {
    /** Treats an unreadable codec list as no encoder: PCM uplink works everywhere, Opus does not. */
    fun isAvailable(): Boolean = NativeOpusEncoder.isAvailable()
}
