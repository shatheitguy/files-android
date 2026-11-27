package com.photos.files.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * Something placed on a PDF page: a signature or a stamp.
 * [cx]/[cy] are the centre as a fraction of the page as shown; [w] is the width as a fraction of the page width.
 */
data class Mark(val id: Long, val page: Int, val bmp: Bitmap, val cx: Float, val cy: Float, val w: Float)

/** Signatures the user drew once and can reuse, like iPhone Markup. */
object Signatures {
    private fun dir(ctx: Context) = File(ctx.filesDir, "signatures").apply { mkdirs() }

    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.extension == "png" }?.sortedBy { it.name } ?: emptyList()

    fun load(f: File): Bitmap? = runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()

    fun add(ctx: Context, bmp: Bitmap): File {
        val f = File(dir(ctx), "${System.currentTimeMillis()}.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    fun remove(f: File) { f.delete() }
}

enum class Stamp(val label: String, val color: Int, val dated: Boolean = false) {
    Approved("APPROVED", 0xFF1E9E4A.toInt()),
    Rejected("REJECTED", 0xFFD93025.toInt()),
    Draft("DRAFT", 0xFF6E6E73.toInt()),
    Confidential("CONFIDENTIAL", 0xFFD93025.toInt()),
    Paid("PAID", 0xFF1E9E4A.toInt()),
    Final("FINAL", 0xFF0A6CFF.toInt()),
    Received("RECEIVED", 0xFF0A6CFF.toInt(), dated = true),
    Reviewed("REVIEWED", 0xFF0A6CFF.toInt(), dated = true),
    Signed("SIGNED", 0xFF1E9E4A.toInt(), dated = true),
    Today("", 0xFF0A6CFF.toInt(), dated = true);

    /** Draws the stamp as a bordered rubber-stamp style image. */
    fun bitmap(): Bitmap {
        val date = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date()).uppercase(Locale.getDefault())
        val main = if (this == Today) date else label
        val sub = if (dated && this != Today) date else null
        val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = this@Stamp.color; typeface = bold; textSize = 96f; letterSpacing = 0.08f; textAlign = Paint.Align.CENTER
        }
        val small = Paint(big).apply { textSize = 44f; letterSpacing = 0.05f }
        val padX = 52f
        val padY = 34f
        val stroke = 10f
        val textW = max(big.measureText(main), sub?.let { small.measureText(it) } ?: 0f)
        val mainBase = padY + 96f * 0.73f
        val subBase = mainBase + 26f + 44f * 0.73f
        val w = (textW + padX * 2).toInt()
        val h = ((if (sub != null) subBase else mainBase) + padY).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = this@Stamp.color; style = Paint.Style.STROKE; strokeWidth = stroke }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = this@Stamp.color; alpha = 22 }
        val r = RectF(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2)
        cv.drawRoundRect(r, 26f, 26f, fill)
        cv.drawRoundRect(r, 26f, 26f, border)
        cv.drawText(main, w / 2f, mainBase, big)
        if (sub != null) cv.drawText(sub, w / 2f, subBase, small)
        return bmp
    }
}

object PdfMarkup {
    /** Draws [marks] onto [src] and writes the result to [out]. Text and pages stay as they are. */
    fun write(ctx: Context, src: File, out: File, marks: List<Mark>) {
        PDFBoxResourceLoader.init(ctx.applicationContext)
        PDDocument.load(src).use { doc ->
            if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
            val images = HashMap<Bitmap, PDImageXObject>()
            for (m in marks) {
                if (m.page !in 0 until doc.numberOfPages) continue
                val page = doc.getPage(m.page)
                val box = page.cropBox
                val rot = ((page.rotation % 360) + 360) % 360
                val sideways = rot == 90 || rot == 270
                val dw = if (sideways) box.height else box.width
                val dh = if (sideways) box.width else box.height
                val w = m.w * dw
                val h = w * m.bmp.height / m.bmp.width
                val left = m.cx * dw - w / 2
                val top = m.cy * dh - h / 2
                // Page as shown (origin top-left, y down) to PDF space, for each page rotation.
                fun pdf(x: Float, y: Float): PointF = when (rot) {
                    90 -> PointF(box.lowerLeftX + y, box.lowerLeftY + x)
                    180 -> PointF(box.upperRightX - x, box.lowerLeftY + y)
                    270 -> PointF(box.upperRightX - y, box.upperRightY - x)
                    else -> PointF(box.lowerLeftX + x, box.upperRightY - y)
                }
                val o = pdf(left, top + h)
                val ex = pdf(left + w, top + h)
                val ey = pdf(left, top)
                val img = images.getOrPut(m.bmp) { LosslessFactory.createFromImage(doc, m.bmp) }
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.drawImage(img, Matrix(ex.x - o.x, ex.y - o.y, ey.x - o.x, ey.y - o.y, o.x, o.y))
                }
            }
            doc.save(out)
        }
    }

    /**
     * Saves the marked-up PDF back where it came from. If that place can't be written,
     * a copy goes to Downloads and its path is returned.
     */
    fun save(ctx: Context, uri: Uri, path: String?, name: String, marks: List<Mark>): String? {
        val src = if (path != null) File(path) else File(ctx.cacheDir, "pdf-in.pdf").also { f ->
            ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
        }
        val out = File(ctx.cacheDir, "pdf-out-${System.nanoTime()}.pdf")
        try {
            write(ctx, src, out, marks)
            if (path != null) {
                val f = File(path)
                if (f.canWrite() && runCatching { out.copyTo(f, overwrite = true) }.isSuccess) {
                    FileStore.get(ctx).scan(listOf(path))
                    FileStore.get(ctx).changed()
                    return null
                }
            } else {
                val ok = runCatching {
                    ctx.contentResolver.openOutputStream(uri, "wt")!!.use { o -> out.inputStream().use { it.copyTo(o) } }
                }.isSuccess
                if (ok) return null
            }
            val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
            val target = FileStore.get(ctx).uniqueTarget(dl, if (name.endsWith(".pdf", true)) name else "$name.pdf")
            out.copyTo(target)
            FileStore.get(ctx).scan(listOf(target.absolutePath))
            FileStore.get(ctx).changed()
            return target.absolutePath
        } finally {
            out.delete()
            if (path == null) src.delete()
        }
    }
}
