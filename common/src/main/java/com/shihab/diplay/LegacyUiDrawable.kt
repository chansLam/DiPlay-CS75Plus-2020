package com.shihab.diplay

import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable

internal fun compatibleRipple(colors: ColorStateList, content: Drawable?, mask: Drawable?): Drawable =
    StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(colors.defaultColor))
        addState(intArrayOf(android.R.attr.state_focused), ColorDrawable(colors.defaultColor))
        addState(intArrayOf(), content ?: ColorDrawable(android.graphics.Color.TRANSPARENT))
    }
