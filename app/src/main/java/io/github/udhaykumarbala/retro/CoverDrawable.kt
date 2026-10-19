package io.github.udhaykumarbala.retro

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils

/**
 * Generated cover for games without art: the title on a colour derived from the ROM's CRC, with a few
 * retro stripes. Pure drawing, no bitmaps; text layout is rebuilt only when the bounds change.
 */
class CoverDrawable(
    private val title: String,
    private val color: Int,
    private val typeface: Typeface,
    private val density: Float,
) : Drawable() {

    private val bg = Paint()
    private val stripe = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var layout: StaticLayout? = null

    init {
        text.color = Color.WHITE
        text.typeface = typeface
        text.fontVariationSettings = "'wght' 600"
        label.color = 0xB3FFFFFF.toInt()
        label.typeface = typeface
        label.fontVariationSettings = "'wght' 600"
        label.letterSpacing = 0.2f
    }

    override fun onBoundsChange(b: Rect) {
        super.onBoundsChange(b)
        if (b.isEmpty) return
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        val top = Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], minOf(1f, hsv[2] * 1.05f)))
        val bottom = Color.HSVToColor(floatArrayOf((hsv[0] + 24f) % 360f, minOf(1f, hsv[1] * 1.1f), hsv[2] * 0.45f))
        bg.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        stripe.color = 0x1FFFFFFF

        val w = b.width()
        val pad = (w * 0.09f).toInt()
        text.textSize = w * 0.12f
        label.textSize = w * 0.055f
        val width = w - 2 * pad
        layout = StaticLayout.Builder.obtain(title, 0, title.length, text, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(3)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setLineSpacing(0f, 0.95f)
            .setIncludePad(false)
            .build()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        canvas.save()
        canvas.clipRect(b)
        canvas.drawRect(b, bg)

        // Three diagonal stripes in the bottom right corner.
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val sw = w * 0.05f
        for (i in 0 until 3) {
            val x0 = b.left + w * (0.62f + i * 0.1f)
            canvas.save()
            canvas.translate(x0, b.top + h)
            canvas.rotate(-35f)
            canvas.drawRect(0f, -h * 1.2f, sw, 0f, stripe)
            canvas.restore()
        }

        val pad = w * 0.09f
        canvas.drawText("NES", b.left + pad, b.top + pad + label.textSize, label)
        layout?.let {
            canvas.save()
            canvas.translate(b.left + pad, b.bottom - pad - it.height)
            it.draw(canvas)
            canvas.restore()
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}
