/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.edge.bar.freeform

import android.content.Context
import android.util.TypedValue
import kotlin.math.roundToInt

internal fun Context.dpToPx(dp: Int): Int = dpToPx(dp.toFloat())

internal fun Context.dpToPx(dp: Float): Int = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_DIP,
    dp,
    resources.displayMetrics,
).roundToInt()
