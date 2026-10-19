package io.github.udhaykumarbala.retro

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * A translucent glass slab: soft sheen fill, bright top specular, crisp rim and a faint outer glow.
 * One instance floats over the shelves and springs between tiles.
 * Ported from tv-vibehack (github.com/Gathin23/tv-vibehack, MIT) GlassView.
 */
class GlassView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val specular = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val clip = Path()
    private val density = resources.displayMetrics.density
    private var radius = 16f * density

    init {
        rim.style = Paint.Style.STROKE
        rim.strokeWidth = 1.5f * density
        rim.color = 0xCCFFFFFF.toInt()
        glow.style = Paint.Style.STROKE
    }

    fun setRadius(px: Float) {
        radius = px
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fill.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0x3DFFFFFF, 0x12FFFFFF, Shader.TileMode.CLAMP)
        specular.shader = LinearGradient(0f, 0f, 0f, h * 0.45f, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val inset = INSET_DP * density
        rect.set(inset, inset, width - inset, height - inset)

        // Outer glow: three widening, fading strokes.
        for (i in 1..3) {
            glow.strokeWidth = i * 2f * density
            glow.color = ((0x30 / i) shl 24) or 0xFFFFFF
            val o = i * density
            canvas.drawRoundRect(rect.left - o, rect.top - o, rect.right + o, rect.bottom + o, radius + o, radius + o, glow)
        }

        canvas.drawRoundRect(rect, radius, radius, fill)

        clip.reset()
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawRect(rect.left, rect.top, rect.right, rect.top + rect.height() * 0.45f, specular)
        canvas.restore()

        canvas.drawRoundRect(rect, radius, radius, rim)
    }

    companion object {
        /** Room around the slab for the glow. */
        const val INSET_DP = 6f
    }
}
