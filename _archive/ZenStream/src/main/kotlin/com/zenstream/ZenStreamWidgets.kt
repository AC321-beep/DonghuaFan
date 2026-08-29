package com.zenstream

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue

object ZenStreamWidgets {

    // ---------- dp conversion ----------
    fun Int.dp(context: Context): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            toFloat(),
            context.resources.displayMetrics
        ).toInt()

    fun Float.dp(context: Context): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            this,
            context.resources.displayMetrics
        )

    // ---------- Drawable factories ----------
    fun roundRect(color: Int, radius: Float) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
    }

    fun stateDrawable(context: Context) = StateListDrawable().apply {
        addState(
            intArrayOf(android.R.attr.state_pressed),
            GradientDrawable().apply { setColor(Color.parseColor("#2A2D45")) }
        )
        addState(
            intArrayOf(),
            GradientDrawable().apply { setColor(Color.TRANSPARENT) }
        )
    }

    fun pill(bgColor: Int, borderColor: Int, radius: Float = 99f) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(bgColor)
        setStroke(1, borderColor)
    }

    // ---------- Reusable pill button (used in settings) ----------
    fun pillBtn(
        context: Context,
        label: String,
        textColor: Int,
        bgColor: Int,
        borderColor: Int,
        onClick: () -> Unit
    ) = android.widget.TextView(context).apply {
        text = label
        textSize = 11f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(textColor)
        setPadding(12.dp(context), 6.dp(context), 12.dp(context), 6.dp(context))
        background = pill(bgColor, borderColor)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }
}
