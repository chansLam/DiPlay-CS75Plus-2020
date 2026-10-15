package com.shihab.diplay

import android.content.Context
import com.shihab.diplay.airplay.CarPlaySize
import com.shihab.diplay.host.R
import com.shihab.diplay.orchestration.ManualHotspotValidation

internal fun CarPlaySize.localizedLabel(context: Context): String = context.getString(when (this) {
    CarPlaySize.LARGE -> R.string.option_size_large
    CarPlaySize.MEDIUM -> R.string.option_size_medium
    CarPlaySize.SMALL -> R.string.option_size_small
})

internal fun ManualHotspotValidation.Error.messageResource(): Int = when (this) {
    ManualHotspotValidation.Error.EMPTY_NAME -> R.string.hotspot_error_empty_name
    ManualHotspotValidation.Error.LONG_NAME -> R.string.hotspot_error_long_name
    ManualHotspotValidation.Error.INVALID_CHARACTER -> R.string.hotspot_error_invalid_character
    ManualHotspotValidation.Error.PASSWORD_LENGTH -> R.string.hotspot_error_password_length
}
