package com.radin.unrot

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** The palette: a dark "game" look. Gold = your time, mint = earning, orange = streaks, violet = levels/XP. */
object Col {
    const val BG = 0xFF0E0C1F.toInt()
    const val CARD = 0xFF1B1836.toInt()
    const val CARD2 = 0xFF28234B.toInt()
    const val STROKE = 0xFF353063.toInt()
    const val TEXT = 0xFFF5F3FF.toInt()
    const val MUTED = 0xFFA9A4D0.toInt()
    const val ACCENT = 0xFF3DDC97.toInt()
    const val ACCENT_DARK = 0xFF0B3B27.toInt()
    const val VIOLET = 0xFF8B5CF6.toInt()
    const val VIOLET_DARK = 0xFF5A33C4.toInt()
    const val GOLD = 0xFFFFC94D.toInt()
    const val FIRE = 0xFFFF8A3D.toInt()
    const val BLUE = 0xFF4FC3F7.toInt()
    const val INDIGO = 0xFF7C9CFF.toInt()
    const val WARN = 0xFFFFB454.toInt()
    const val DANGER = 0xFFFF5C7A.toInt()

    /** [c] with alpha [a] (0–255). */
    fun alpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)
}

/** Fredoka for headings and big numbers, Nunito for everything else. Bundled in assets/fonts. */
object Fonts {
    private val cache = HashMap<String, Typeface>()

    private fun get(ctx: Context, name: String): Typeface = synchronized(cache) {
        cache.getOrPut(name) {
            runCatching { Typeface.createFromAsset(ctx.assets, "fonts/$name.ttf") }.getOrDefault(Typeface.DEFAULT)
        }
    }

    fun display(ctx: Context) = get(ctx, "Fredoka-SemiBold")
    fun displayBold(ctx: Context) = get(ctx, "Fredoka-Bold")
    fun body(ctx: Context) = get(ctx, "Nunito-Regular")
    fun semi(ctx: Context) = get(ctx, "Nunito-SemiBold")
    fun heavy(ctx: Context) = get(ctx, "Nunito-ExtraBold")
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

class Ui(val ctx: Context) {
    enum class Style { PRIMARY, SECONDARY, GHOST, DANGER, SUCCESS }

    fun col(padding: Int = 0): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        if (padding > 0) setPadding(ctx.dp(padding), ctx.dp(padding), ctx.dp(padding), ctx.dp(padding))
    }

    fun row(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** Body text. Bold + big (20sp+) becomes a Fredoka heading automatically. */
    fun text(s: CharSequence, size: Float = 16f, color: Int = Col.TEXT, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            textSize = size
            setTextColor(color)
            typeface = when {
                bold && size >= 20f -> Fonts.display(ctx)
                bold -> Fonts.heavy(ctx)
                size <= 13f -> Fonts.semi(ctx)
                else -> Fonts.body(ctx)
            }
            setLineSpacing(0f, 1.15f)
        }

    /** Fredoka heading / big number. */
    fun title(s: CharSequence, size: Float = 22f, color: Int = Col.TEXT): TextView =
        TextView(ctx).apply {
            text = s
            textSize = size
            setTextColor(color)
            typeface = Fonts.display(ctx)
            includeFontPadding = false
        }

    fun rounded(color: Int, radiusDp: Int = 16, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = ctx.dp(radiusDp).toFloat()
        if (stroke != null) setStroke(ctx.dp(1), stroke)
    }

    /** Chunky "game" button: a darker edge underneath that the face presses down into. */
    private fun chunky(face: Int, edge: Int, radius: Int = 16): Drawable {
        val d = ctx.dp(4)
        fun layers(pressed: Boolean) = LayerDrawable(arrayOf(rounded(edge, radius), rounded(face, radius))).apply {
            if (pressed) setLayerInset(1, 0, d, 0, 0) else setLayerInset(1, 0, 0, 0, d)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), layers(true))
            addState(intArrayOf(-android.R.attr.state_enabled), layers(false).apply { alpha = 110 })
            addState(intArrayOf(), layers(false))
        }
    }

    fun button(label: String, style: Style = Style.SECONDARY, icon: Int = 0, onClick: () -> Unit): Button =
        Button(ctx).apply {
            text = label
            isAllCaps = false
            textSize = 15.5f
            typeface = Fonts.heavy(ctx)
            stateListAnimator = null
            minHeight = ctx.dp(if (style == Style.GHOST) 44 else 54)
            minimumHeight = minHeight
            minWidth = 0
            minimumWidth = 0
            val bottomPad = if (style == Style.GHOST) 8 else 12
            setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), ctx.dp(bottomPad))
            val fg = when (style) {
                Style.PRIMARY -> { background = chunky(Col.VIOLET, Col.VIOLET_DARK); 0xFFFFFFFF.toInt() }
                Style.SUCCESS -> { background = chunky(Col.ACCENT, 0xFF1E9E66.toInt()); 0xFF05271A.toInt() }
                Style.SECONDARY -> { background = chunky(Col.CARD2, 0xFF151229.toInt()); Col.TEXT }
                Style.DANGER -> { background = chunky(0xFF3A1730.toInt(), 0xFF240D1D.toInt()); Col.DANGER }
                Style.GHOST -> { background = rounded(0x00000000, 14, Col.STROKE); Col.MUTED }
            }
            setTextColor(fg)
            if (icon != 0) {
                val dr = ctx.getDrawable(icon)?.mutate()
                dr?.setTint(fg)
                dr?.setBounds(0, 0, ctx.dp(20), ctx.dp(20))
                setCompoundDrawablesRelative(dr, null, null, null)
                compoundDrawablePadding = ctx.dp(8)
            }
            setOnClickListener { onClick() }
        }

    fun card(): LinearLayout = col(18).apply { background = rounded(Col.CARD, 22, Col.STROKE) }

    fun space(h: Int = 12): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(1, ctx.dp(h))
    }

    fun icon(res: Int, color: Int, sizeDp: Int = 22): ImageView = ImageView(ctx).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
        layoutParams = LinearLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp))
    }

    /** An icon in a soft tinted circle. */
    fun iconBadge(res: Int, color: Int, sizeDp: Int = 42): ImageView = ImageView(ctx).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Col.alpha(color, 0x2E))
        }
        val p = sizeDp / 4
        setPadding(ctx.dp(p), ctx.dp(p), ctx.dp(p), ctx.dp(p))
        layoutParams = LinearLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp))
    }

    /** Small rounded label, e.g. "+10 min". */
    fun chip(s: String, color: Int, filled: Boolean = false): TextView = TextView(ctx).apply {
        text = s
        textSize = 12.5f
        typeface = Fonts.heavy(ctx)
        setTextColor(if (filled) Col.BG else color)
        background = rounded(if (filled) color else Col.alpha(color, 0x29), 50)
        setPadding(ctx.dp(10), ctx.dp(4), ctx.dp(10), ctx.dp(5))
        includeFontPadding = false
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun progress(fraction: Float, color: Int, heightDp: Int = 10): ProgressBarView =
        ProgressBarView(ctx).apply {
            this.fraction = fraction
            this.color = color
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(heightDp))
        }

    /** Icon badge + title (+ subtitle) + optional trailing view, in a row. */
    fun listRow(iconRes: Int, color: Int, title: String, subtitle: String? = null, trailing: View? = null): LinearLayout {
        val r = row()
        r.addView(iconBadge(iconRes, color))
        val t = col()
        t.setPadding(ctx.dp(12), 0, ctx.dp(8), 0)
        t.addView(text(title, 16.5f, bold = true))
        if (!subtitle.isNullOrEmpty()) t.addView(text(subtitle, 13.5f, Col.MUTED))
        r.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (trailing != null) r.addView(trailing)
        return r
    }

    /** Big icon in a glowing circle, for the top of full-screen prompts. */
    fun heroIcon(res: Int, color: Int): View {
        val f = FrameLayout(ctx)
        val glow = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Col.alpha(color, 0x1F))
            }
        }
        f.addView(glow, FrameLayout.LayoutParams(ctx.dp(96), ctx.dp(96)))
        val inner = ImageView(ctx).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(color)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Col.alpha(color, 0x33))
            }
            setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16))
        }
        f.addView(inner, FrameLayout.LayoutParams(ctx.dp(68), ctx.dp(68), Gravity.CENTER))
        f.layoutParams = LinearLayout.LayoutParams(ctx.dp(96), ctx.dp(96))
        return f
    }

    fun divider(): View = View(ctx).apply {
        setBackgroundColor(Col.STROKE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(1))
    }

    fun input(hint: String, numeric: Boolean = false, multiline: Boolean = false): EditText = EditText(ctx).apply {
        this.hint = hint
        setHintTextColor(Col.MUTED)
        setTextColor(Col.TEXT)
        textSize = 17f
        typeface = Fonts.semi(ctx)
        background = rounded(Col.CARD2, 14, Col.STROKE)
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
    // Keep a fixed height if the view has one (spacers, bars). A plain View set to WRAP_CONTENT
    // stretches to fill all remaining space on short screens and pushes everything off-screen.
    val fixed = v.layoutParams?.height?.takeIf { it > 0 }
    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, fixed ?: ViewGroup.LayoutParams.WRAP_CONTENT)
    lp.bottomMargin = context.dp(gapDp)
    addView(v, lp)
    return v
}

/** Adds a view at its natural size (icons, chips), with a bottom gap. */
fun LinearLayout.addWrap(v: View, gapDp: Int = 10, gravity: Int = Gravity.START): View {
    val w = v.layoutParams?.width?.takeIf { it > 0 } ?: ViewGroup.LayoutParams.WRAP_CONTENT
    val h = v.layoutParams?.height?.takeIf { it > 0 } ?: ViewGroup.LayoutParams.WRAP_CONTENT
    val lp = LinearLayout.LayoutParams(w, h)
    lp.bottomMargin = context.dp(gapDp)
    lp.gravity = gravity
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

/** Applies status/nav bar padding (Android 15 draws edge to edge). */
fun View.padForSystemBars(top: Boolean = true, bottom: Boolean = true, extraBottomDp: Int = 0) {
    val base = intArrayOf(paddingLeft, paddingTop, paddingRight, paddingBottom)
    setOnApplyWindowInsetsListener { v, insets ->
        val (l, t, r, b) = if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            listOf(i.left, i.top, i.right, i.bottom)
        } else {
            @Suppress("DEPRECATION")
            listOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }
        v.setPadding(
            base[0] + l, base[1] + (if (top) t else 0), base[2] + r,
            base[3] + (if (bottom) b else 0) + v.context.dp(extraBottomDp),
        )
        insets
    }
}

/** Sets a scrolling screen that respects the status/nav bars. */
fun Activity.setScreen(build: Ui.(LinearLayout) -> Unit): LinearLayout {
    val ui = Ui(this)
    val content = ui.col(20)
    try {
        ui.build(content)
    } catch (e: Throwable) {
        showBuildError(ui, content, e)
    }
    val scroll = ScrollView(this).apply {
        setBackgroundColor(Col.BG)
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        addView(content)
    }
    scroll.padForSystemBars()
    setContentView(scroll)
    return content
}

/** Never show a blank screen: show what went wrong so it can be reported and fixed. */
fun Activity.showBuildError(ui: Ui, content: LinearLayout, e: Throwable) {
    Store.get(this).lastCrash = "${javaClass.simpleName}: ${e.stackTraceToString().take(1500)}"
    content.removeAllViews()
    content.add(ui.text("Something broke on this screen 😬", 22f, bold = true))
    content.add(ui.text("Send this to Claude so it can be fixed:", 15f, Col.MUTED))
    content.add(ui.text(e.toString(), 13f, Col.WARN))
    content.add(ui.button("Close", Ui.Style.SECONDARY) { finish() })
}

fun Context.dialog(): AlertDialog.Builder = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
