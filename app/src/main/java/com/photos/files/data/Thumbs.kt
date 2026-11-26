package com.photos.files.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.ThumbnailUtils
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.io.File

object Thumbs {
    private val cache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private val missing = HashSet<String>()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(4)

    fun canThumb(item: FileItem) = item.kind in setOf(FileKind.Image, FileKind.Video, FileKind.Pdf, FileKind.App, FileKind.Audio)

    fun key(item: FileItem, px: Int) = "${item.path}|${item.modified}|$px"
    fun cached(item: FileItem, px: Int): ImageBitmap? = cache.get(key(item, px))

    suspend fun load(ctx: Context, item: FileItem, px: Int): ImageBitmap? {
        val k = key(item, px)
        cache.get(k)?.let { return it }
        synchronized(missing) { if (k in missing) return null }
        val bmp = withContext(io) {
            runCatching { make(ctx, item, px) }.getOrNull()
        }
        if (bmp != null) cache.put(k, bmp.asImageBitmap()) else synchronized(missing) { missing += k }
        return cache.get(k)
    }

    private fun make(ctx: Context, item: FileItem, px: Int): Bitmap? {
        val f = File(item.path)
        val size = Size(px, px)
        return when (item.kind) {
            FileKind.Image -> ThumbnailUtils.createImageThumbnail(f, size, null)
            FileKind.Video -> ThumbnailUtils.createVideoThumbnail(f, size, null)
            FileKind.Audio -> runCatching { ThumbnailUtils.createAudioThumbnail(f, size, null) }.getOrNull()
            FileKind.Pdf -> ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { r ->
                    if (r.pageCount == 0) return null
                    r.openPage(0).use { p ->
                        val scale = px.toFloat() / maxOf(p.width, p.height)
                        val b = Bitmap.createBitmap((p.width * scale).toInt().coerceAtLeast(1), (p.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        Canvas(b).drawColor(Color.WHITE)
                        p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        b
                    }
                }
            }
            FileKind.App -> {
                val pm = ctx.packageManager
                val info = pm.getPackageArchiveInfo(item.path, 0) ?: return null
                val ai = info.applicationInfo ?: return null
                ai.sourceDir = item.path
                ai.publicSourceDir = item.path
                ai.loadIcon(pm).toBitmap(px, px)
            }
            else -> null
        }
    }
}

@Composable
fun rememberThumb(item: FileItem, px: Int): ImageBitmap? {
    val ctx = LocalContext.current
    var bmp by remember(item.path, item.modified, px) { mutableStateOf(Thumbs.cached(item, px)) }
    if (Thumbs.canThumb(item)) {
        LaunchedEffect(item.path, item.modified, px) {
            if (bmp == null) bmp = Thumbs.load(ctx, item, px)
        }
    }
    return bmp
}
