package com.shounak.localmeshai.ai.tools

import android.graphics.*
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withSave
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject

internal object SkillArtwork {
    fun qr(text: String, size: Int = 512): com.google.zxing.common.BitMatrix {
        require(text.isNotBlank() && text.toByteArray(Charsets.UTF_8).size <= 1500)
        require(size in 256..1024)
        return QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 4))
    }
    fun render(data: JSONObject): Bitmap = when (data.getString("solus_skill_media")) {
        "qr_code" -> {
            val matrix = qr(SkillCatalog.text(data, "text", 1500))
            val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) Color.BLACK else Color.WHITE }
            Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        }
        "learning_card" -> {
            val title = SkillCatalog.text(data, "title", 120)
            val lesson = SkillCatalog.text(data, "lesson", 1800)
            val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 48f; typeface = Typeface.create("sans-serif", Typeface.BOLD) }
            val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(225,239,247); textSize = 30f }
            fun layout(text: String, paint: TextPaint) = StaticLayout.Builder.obtain(text, 0, text.length, paint, 776)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(10f, 1f).setIncludePad(false).build()
            val heading = layout(title, titlePaint); val body = layout(lesson, bodyPaint)
            val height = (heading.height + body.height + 260).coerceIn(600, 4000)
            val bitmap = createBitmap(900, height)
            val canvas = Canvas(bitmap)
            canvas.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 900f, height.toFloat(), Color.rgb(19,50,73), Color.rgb(58,37,87), Shader.TileMode.CLAMP) })
            canvas.withSave { translate(62f, 64f); heading.draw(this); translate(0f, heading.height + 40f); body.draw(this) }
            canvas.drawText("SOLUS • Learn something new", 62f, height - 45f, TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(158,217,245); textSize = 22f })
            bitmap
        }
        else -> error("Unsupported skill image")
    }
}
