package com.shihab.diplay.glance

import com.shihab.diplay.iap2.body.Iap2BodyReader
import com.shihab.diplay.iap2.wire.Iap2Frame

/** Now-playing card text for widgets; title (1) and artist (12) from iAP2 0x5001. */
internal data class NowPlayingSong(val text: String, val playing: Boolean, val line: String = text)

/**
 * Parses CarPlay NowPlayingUpdate (0x5001) for [CarPlayGlance]. Independent of BYD dashboard output.
 */
internal class NowPlayingState {
    private var title: String? = null
    private var artist: String? = null
    private var playing = false
    private var last: NowPlayingSong? = null

    fun accept(frame: Iap2Frame): NowPlayingSong? {
        if (frame.messageId != NOW_PLAYING_UPDATE) return null
        val body = runCatching { Iap2BodyReader.of(frame) }.getOrNull() ?: return null
        runCatching { body.optionalGroup(ITEM) }.getOrNull()?.let { item ->
            val nextTitle = runCatching { item.optionalString(TITLE) }.getOrNull()
            if (nextTitle != null) {
                title = nextTitle
                if (nextTitle.isBlank()) artist = null
            }
            if (nextTitle?.isBlank() != true) {
                runCatching { item.optionalString(ARTIST) }.getOrNull()?.let { artist = it }
            }
        }
        runCatching { body.optionalGroup(PLAYBACK)?.optionalU8(STATUS) }.getOrNull()?.let { status ->
            playing = status == STATUS_PLAYING || status == STATUS_SEEK_FORWARD || status == STATUS_SEEK_BACKWARD
        }
        val next = text(title, artist)?.let { NowPlayingSong(it, playing, title!!.trim()) }
        if (next == last) return null
        last = next
        return next
    }

    fun current(): NowPlayingSong? = last

    fun clear() {
        title = null
        artist = null
        playing = false
        last = null
    }

    companion object {
        const val NOW_PLAYING_UPDATE = 0x5001
        private const val ITEM = 0
        private const val TITLE = 1
        private const val ARTIST = 12
        private const val PLAYBACK = 1
        private const val STATUS = 0
        private const val STATUS_PLAYING = 1
        private const val STATUS_SEEK_FORWARD = 3
        private const val STATUS_SEEK_BACKWARD = 4
        const val MAX_TEXT_BYTES = 255

        fun text(title: String?, artist: String?): String? {
            val name = title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val full = artist?.trim()?.takeIf { it.isNotEmpty() }?.let { "$name — $it" } ?: name
            var end = full.length
            while (full.substring(0, end).toByteArray(Charsets.UTF_16LE).size > MAX_TEXT_BYTES) {
                end--
                if (end > 0 && Character.isLowSurrogate(full[end])) end--
            }
            return full.substring(0, end)
        }
    }
}
