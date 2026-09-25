package com.example.otrdial

import android.graphics.*
import android.graphics.drawable.Drawable

/** Original, resolution-independent studio artwork, available offline. */
class RadioArtwork(private val genre: String) : Drawable() {
    fun pngBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
        setBounds(0, 0, 240, 240)
        draw(Canvas(bitmap))
        val output = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        return output.toByteArray()
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 240f, bounds.height() / 240f)
        val colour = when {
            genre.contains("Comedy") -> "#B96717"
            genre.contains("Crime") -> "#9C3550"
            genre.contains("Mystery") -> "#6953A4"
            genre.contains("Western") -> "#B65C38"
            genre.contains("Sci-Fi") -> "#315BA6"
            else -> "#168078"
        }
        paint.shader = LinearGradient(0f, 0f, 240f, 240f, Color.parseColor(colour), Color.rgb(18, 30, 49), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(0f, 0f, 240f, 240f, 22f, 22f, paint)
        paint.shader = null
        paint.color = Color.argb(35, 255, 255, 255)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        for (r in listOf(75f, 98f, 122f)) canvas.drawCircle(120f, 100f, r, paint)
        paint.color = Color.rgb(255, 218, 137)
        canvas.drawRoundRect(12f, 12f, 228f, 228f, 16f, 16f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(91f, 47f, 149f, 132f, 28f, 28f, paint)
        paint.color = Color.rgb(28, 46, 62)
        for (y in 64..115 step 12) canvas.drawRoundRect(102f, y.toFloat(), 138f, y + 4f, 2f, 2f, paint)
        paint.color = Color.rgb(255, 218, 137)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 7f
        canvas.drawArc(78f, 68f, 162f, 151f, 0f, 180f, false, paint)
        canvas.drawLine(120f, 151f, 120f, 177f, paint)
        canvas.drawLine(96f, 179f, 144f, 179f, paint)
        paint.style = Paint.Style.FILL
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 14f
        canvas.drawText(genre.uppercase().take(23), 120f, 208f, paint)
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Deprecated in Android")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
