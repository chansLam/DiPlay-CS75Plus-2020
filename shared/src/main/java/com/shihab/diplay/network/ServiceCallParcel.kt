package com.shihab.diplay.network

/** Parses `service call` Parcel hex dumps used by optional ADB helpers. */
internal object ServiceCallParcel {
    fun words(output: String?): List<Long> {
        if (output.isNullOrBlank()) return emptyList()
        val match = Regex("""Parcel\(([^)]*)\)""").find(output) ?: return emptyList()
        return match.groupValues[1]
            .split(Regex("""\s+"""))
            .mapNotNull { token ->
                token.takeIf { it.length == 8 && it.all { ch -> ch.isDigit() || ch in 'a'..'f' || ch in 'A'..'F' } }
                    ?.toLongOrNull(16)
            }
    }

    /** Second word of a successful void/int reply, or null. */
    fun value(output: String?): Long? = words(output).getOrNull(1)
}
