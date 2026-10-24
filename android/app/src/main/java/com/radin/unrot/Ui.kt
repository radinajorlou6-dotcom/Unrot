package com.radin.unrot

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Tiny UI kit so every screen looks the same without XML layouts. */
object Col {
    const val BG = 0xFF0F1115.toInt()
    const val CARD = 0xFF1A1E26.toInt()
    const val CARD2 = 0xFF232833.toInt()
    const val TEXT = 0xFFECEFF4.toInt()
    const val MUTED = 0xFF9AA3B2.toInt()
    const val ACCENT = 0xFF7CD992.toInt()
    const val ACCENT_DARK = 0xFF0E2A17.toInt()
    const val WARN = 0xFFFFB454.toInt()
    const val DANGER = 0xFFFF6B6B.toInt()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

class Ui(val ctx: Context) {
    fun col(padding: Int = 0): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        if (padding > 0) setPadding(ctx.dp(padding), ctx.dp(padding), ctx.dp(padding), ctx.dp(padding))
    }

    fun row(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun text(s: CharSequence, size: Float = 16f, color: Int = Col.TEXT, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setLineSpacing(0f, 1.15f)
        }

    fun rounded(color: Int, radiusDp: Int = 16, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = ctx.dp(radiusDp).toFloat()
        if (stroke != null) setStroke(ctx.dp(1), stroke)
    }

    fun button(label: String, style: Style = Style.SECONDARY, onClick: () -> Unit): Button =
        Button(ctx).apply {
            text = label
            isAllCaps = false
            textSize = 16f
            stateListAnimator = null
            minHeight = ctx.dp(52)
            setPadding(ctx.dp(16), ctx.dp(10), ctx.dp(16), ctx.dp(10))
            when (style) {
                Style.PRIMARY -> { background = rounded(Col.ACCENT, 14); setTextColor(0xFF0B1A10.toInt()) }
                Style.SECONDARY -> { background = rounded(Col.CARD2, 14); setTextColor(Col.TEXT) }
                Style.GHOST -> { background = rounded(0x00000000, 14, Col.CARD2); setTextColor(Col.MUTED) }
                Style.DANGER -> { background = rounded(0x33FF6B6B, 14); setTextColor(Col.DANGER) }
            }
            setOnClickListener { onClick() }
        }

    enum class Style { PRIMARY, SECONDARY, GHOST, DANGER }

    fun card(): LinearLayout = col(16).apply { background = rounded(Col.CARD, 18) }

    fun space(h: Int = 12): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(1, ctx.dp(h))
    }

    fun input(hint: String, numeric: Boolean = false, multiline: Boolean = false): EditText = EditText(ctx).apply {
        this.hint = hint
        setHintTextColor(Col.MUTED)
        setTextColor(Col.TEXT)
        textSize = 17f
        background = rounded(Col.CARD2, 12)
        setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
        inputType = when {
            numeric -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            else -> InputType.TYPE_CLASS_TEXT
        }
        if (multiline) {
            minLines = 5
            gravity = Gravity.TOP or Gravity.START
        }
    }
}

/** Adds a view with full width and a bottom gap. */
fun LinearLayout.add(v: View, gapDp: Int = 10): View {
    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    lp.bottomMargin = context.dp(gapDp)
    addView(v, lp)
    return v
}

/** Adds a view that takes an equal share of a horizontal row. */
fun LinearLayout.addWeighted(v: View, gapDp: Int = 8): View {
    val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    lp.marginEnd = context.dp(gapDp)
    addView(v, lp)
    return v
}

/** Sets a scrolling screen that respects the status/nav bars (Android 15 draws edge to edge). */
fun Activity.setScreen(build: Ui.(LinearLayout) -> Unit): LinearLayout {
    val ui = Ui(this)
    val content = ui.col(20)
    ui.build(content)
    val scroll = ScrollView(this).apply {
        setBackgroundColor(Col.BG)
        isFillViewport = true
        addView(content)
    }
    scroll.setOnApplyWindowInsetsListener { v, insets ->
        if (Build.VERSION.SDK_INT >= 30) {
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
        } else {
            @Suppress("DEPRECATION")
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }
        insets
    }
    setContentView(scroll)
    return content
}

fun Context.dialog(): AlertDialog.Builder = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
