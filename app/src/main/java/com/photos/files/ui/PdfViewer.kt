package com.photos.files.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.LruCache
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photos.files.PdfActivity
import com.photos.files.data.Mark
import com.photos.files.data.PdfMarkup
import com.photos.files.data.Signatures
import com.photos.files.data.Stamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** PdfRenderer isn't thread-safe, so every page render goes through one thread. */
@OptIn(ExperimentalCoroutinesApi::class)
private val pdfThread = Dispatchers.IO.limitedParallelism(1)

private class PdfDoc(private val pfd: ParcelFileDescriptor, private val r: PdfRenderer) {
    /** Page sizes in points, as shown (rotation already applied). */
    val sizes: List<Pair<Float, Float>> = (0 until r.pageCount).map { i -> r.openPage(i).use { it.width.toFloat() to it.height.toFloat() } }
    val count get() = sizes.size
    private var closed = false
    val cache = object : LruCache<Int, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }

    suspend fun render(i: Int, widthPx: Int): Bitmap? = withContext(pdfThread) {
        if (closed) return@withContext null
        runCatching {
            r.openPage(i).use { p ->
                val w = widthPx.coerceAtLeast(1)
                val h = (w.toFloat() * p.height / p.width).roundToInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        }.getOrNull()
    }

    /** Must run on [pdfThread]. */
    fun closeNow() {
        if (closed) return
        closed = true
        runCatching { r.close() }
        runCatching { pfd.close() }
    }

    fun close() { CoroutineScope(pdfThread).launch { closeNow() } }

    companion object {
        fun open(ctx: Context, uri: Uri, path: String?): PdfDoc {
            val pfd = if (path != null) ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
            else ctx.contentResolver.openFileDescriptor(uri, "r") ?: error("Couldn't open this PDF.")
            return try {
                PdfDoc(pfd, PdfRenderer(pfd))
            } catch (e: SecurityException) {
                pfd.close(); throw e
            } catch (e: Exception) {
                // Some apps hand over a stream that can't seek; read it into a local copy.
                pfd.close()
                if (path != null) throw e
                val f = File(ctx.cacheDir, "pdf-view.pdf")
                ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
                val p2 = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                PdfDoc(p2, PdfRenderer(p2))
            }
        }
    }
}

fun pdfDisplayName(ctx: Context, uri: Uri, path: String?): String {
    if (path != null) return File(path).name
    runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { return it }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Document.pdf"
}

private enum class PdfSheet { None, Signatures, Pad, Stamps }

@Composable
fun PdfViewer(startUri: Uri, startPath: String?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalIos.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var uri by remember { mutableStateOf(startUri) }
    var path by remember { mutableStateOf(startPath) }
    val title = remember(uri, path) { pdfDisplayName(ctx, uri, path) }
    var gen by remember { mutableIntStateOf(0) }
    var doc by remember { mutableStateOf<PdfDoc?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(gen, uri, path) {
        val r = withContext(Dispatchers.IO) { runCatching { PdfDoc.open(ctx, uri, path) } }
        r.onSuccess { doc = it; error = null }.onFailure {
            error = if (it is SecurityException) "This PDF is protected with a password." else "Couldn't open this PDF."
        }
    }
    val current = doc
    DisposableEffect(current) { onDispose { current?.close() } }

    var bars by remember { mutableStateOf(true) }
    var marking by remember { mutableStateOf(false) }
    val marks = remember { mutableStateListOf<Mark>() }
    var selected by remember { mutableStateOf<Long?>(null) }
    var sheet by remember { mutableStateOf(PdfSheet.None) }
    var saving by remember { mutableStateOf(false) }

    val list = rememberLazyListState()
    var scale by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var viewW by remember { mutableFloatStateOf(1f) }
    var viewH by remember { mutableFloatStateOf(1f) }

    fun openElsewhere() {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(if (path != null) LocalShareUri.of(ctx, path!!) else uri, "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(i, null)
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(ctx, PdfActivity::class.java)))
        runCatching { ctx.startActivity(chooser) }
    }

    fun share() {
        val u = if (path != null) LocalShareUri.of(ctx, path!!) else uri
        val i = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, u)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { ctx.startActivity(Intent.createChooser(i, null)) }
    }

    /** Where a new signature or stamp lands: the middle of what's on screen. */
    fun place(bmp: Bitmap, width: Float) {
        val d = doc ?: return
        val mid = viewH / 2
        val info = list.layoutInfo.visibleItemsInfo
        val it = info.firstOrNull { mid >= it.offset && mid <= it.offset + it.size } ?: info.firstOrNull() ?: return
        val cy = ((mid - it.offset) / it.size).coerceIn(0.1f, 0.9f)
        val cx = ((panX + viewW / 2) / (viewW * scale)).coerceIn(0.1f, 0.9f)
        val (pw, ph) = d.sizes[it.index]
        val w = min(width, 0.9f)
        val m = Mark(System.nanoTime(), it.index, bmp, cx, cy.coerceAtMost(1f - (w * pw / ph * bmp.height / bmp.width) / 2), w)
        marks.add(m)
        selected = m.id
    }

    fun update(id: Long, f: (Mark) -> Mark) {
        val i = marks.indexOfFirst { it.id == id }
        if (i >= 0) marks[i] = f(marks[i])
    }

    fun finishMarkup() {
        val d = doc ?: return
        if (marks.isEmpty()) { marking = false; return }
        saving = true
        selected = null
        scope.launch {
            val all = marks.toList()
            val r = withContext(pdfThread) {
                d.closeNow()
                runCatching { PdfMarkup.save(ctx, uri, path, title, all) }
            }
            saving = false
            r.onSuccess { newPath ->
                marks.clear()
                marking = false
                if (newPath != null) {
                    Toast.makeText(ctx, "Saved a copy to Downloads", Toast.LENGTH_SHORT).show()
                    path = newPath
                    uri = Uri.fromFile(File(newPath))
                }
                doc = null
                gen++
            }.onFailure {
                Toast.makeText(ctx, "Couldn't save this PDF.", Toast.LENGTH_SHORT).show()
                doc = null
                gen++
            }
        }
    }

    // Back closes an open sheet, then leaves Markup, and only then closes the PDF.
    androidx.activity.compose.BackHandler(enabled = sheet != PdfSheet.None || marking) {
        if (sheet != PdfSheet.None) sheet = PdfSheet.None
        else if (!saving) { marks.clear(); selected = null; marking = false }
    }

    Box(Modifier.fillMaxSize().background(if (c.dark) Color(0xFF1C1C1E) else Color(0xFFE5E5EA))) {
        val d = doc
        if (d == null) {
            if (error != null) {
                Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error!!, color = c.secondary, fontSize = 17.sp)
                    Spacer(Modifier.height(16.dp))
                    GlassPill("Open in Another App", color = c.accent) { openElsewhere() }
                }
            } else CircularProgressIndicator(Modifier.align(Alignment.Center), color = c.secondary)
        } else {
            val statusTop = WindowInsets.statusBars.getTop(density)
            val topPad = with(density) { statusTop.toDp() } + 72.dp
            BoxWithConstraints(
                Modifier.fillMaxSize().clipToBounds()
                    .pointerInput(d, marking) {
                        val decay = splineBasedDecay<Float>(this)
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val vt = VelocityTracker()
                            vt.addPosition(down.uptimeMillis, down.position)
                            var panning = false
                            var pinched = false
                            while (true) {
                                val ev = awaitPointerEvent(PointerEventPass.Initial)
                                val pressed = ev.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break
                                if (pressed.size >= 2) {
                                    pinched = true
                                    val z = ev.calculateZoom()
                                    val cen = ev.calculateCentroid(useCurrent = true)
                                    val pan = ev.calculatePan()
                                    val old = scale
                                    val new = (old * z).coerceIn(1f, 5f)
                                    val f = new / old
                                    scale = new
                                    panX = ((panX + cen.x) * f - cen.x - pan.x).coerceIn(0f, viewW * (new - 1))
                                    val dy = (list.firstVisibleItemScrollOffset + cen.y) * (f - 1) - pan.y
                                    list.dispatchRawDelta(dy)
                                    ev.changes.forEach { it.consume() }
                                } else if (!marking && !pinched && scale > 1.01f) {
                                    val ch = pressed[0]
                                    vt.addPosition(ch.uptimeMillis, ch.position)
                                    if (!panning && (ch.position - down.position).getDistance() > viewConfiguration.touchSlop) panning = true
                                    if (panning) {
                                        val delta = ch.position - ch.previousPosition
                                        panX = (panX - delta.x).coerceIn(0f, viewW * (scale - 1))
                                        list.dispatchRawDelta(-delta.y)
                                        ch.consume()
                                    }
                                }
                            }
                            if (panning) {
                                val v = vt.calculateVelocity()
                                scope.launch {
                                    list.scroll {
                                        var last = 0f
                                        AnimationState(0f, -v.y).animateDecay(decay) { scrollBy(value - last); last = value }
                                    }
                                }
                                scope.launch {
                                    AnimationState(panX, -v.x).animateDecay(decay) {
                                        val max = viewW * (scale - 1)
                                        panX = value.coerceIn(0f, max)
                                        if (value < 0f || value > max) cancelAnimation()
                                    }
                                }
                            }
                        }
                    }
                    .pointerInput(d, marking) {
                        detectTapGestures(
                            onTap = { if (marking) selected = null else bars = !bars },
                            onDoubleTap = { pos ->
                                if (scale > 1.01f) { scale = 1f; panX = 0f }
                                else {
                                    val f = 2.5f
                                    scale = f
                                    panX = ((pos.x) * f - pos.x).coerceIn(0f, viewW * (f - 1))
                                    list.dispatchRawDelta((list.firstVisibleItemScrollOffset + pos.y) * (f - 1))
                                }
                            },
                        )
                    },
            ) {
                viewW = constraints.maxWidth.toFloat()
                viewH = constraints.maxHeight.toFloat()
                val baseW = min(constraints.maxWidth, 1600)
                LazyColumn(
                    state = list,
                    modifier = Modifier.fillMaxSize().layout { m, cons ->
                        val cw = (cons.maxWidth * scale).roundToInt()
                        val p = m.measure(cons.copy(minWidth = cw, maxWidth = cw))
                        layout(cons.maxWidth, p.height) { p.place(-panX.roundToInt(), 0) }
                    },
                    contentPadding = PaddingValues(top = topPad, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(d.count, key = { it }) { i ->
                        PdfPage(d, i, baseW, scale, marks, selected, marking,
                            onSelect = { selected = it },
                            onMove = { id, dx, dy -> update(id) { m -> m.copy(cx = (m.cx + dx).coerceIn(0f, 1f), cy = (m.cy + dy).coerceIn(0f, 1f)) } },
                            onResize = { id, dw -> update(id) { m -> m.copy(w = (m.w + dw).coerceIn(0.06f, 1f)) } },
                            onDelete = { id -> marks.removeAll { it.id == id }; selected = null },
                        )
                    }
                }
            }

            // Page number while scrolling.
            val page by remember(d) {
                derivedStateOf {
                    val info = list.layoutInfo
                    val mid = info.viewportEndOffset / 2
                    (info.visibleItemsInfo.firstOrNull { mid >= it.offset && mid <= it.offset + it.size }?.index ?: list.firstVisibleItemIndex) + 1
                }
            }
            var showPage by remember { mutableStateOf(false) }
            LaunchedEffect(list.isScrollInProgress) {
                if (list.isScrollInProgress) showPage = true else { delay(1200); showPage = false }
            }
            AnimatedVisibility(
                showPage && d.count > 1 && !marking, enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp),
            ) {
                val (bg, border, fg) = glass()
                Text(
                    "$page of ${d.count}", color = fg, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.shadow(8.dp, CircleShape, clip = false).clip(CircleShape).background(bg)
                        .border(0.5.dp, border, CircleShape).padding(horizontal = 16.dp, vertical = 9.dp),
                )
            }
        }

        AnimatedVisibility(bars || marking, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            if (marking) {
                ScreenTopBar(title, true, onBack = null, leading = {
                    GlassPill("Cancel") { marks.clear(); selected = null; marking = false }
                }) {
                    GlassPill("Done", color = c.accent, enabled = !saving) { finishMarkup() }
                }
            } else {
                ScreenTopBar(title, true, onBack = onClose) {
                    if (doc != null) GlassCircle(IosIcons.Pencil) { marking = true; bars = true }
                    GlassCircle(IosIcons.Share) { share() }
                    GlassCircle(IosIcons.More) { openElsewhere() }
                }
            }
        }

        if (marking) {
            Row(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GlassPill("Signature") { sheet = if (Signatures.list(ctx).isEmpty()) PdfSheet.Pad else PdfSheet.Signatures }
                GlassPill("Stamp") { sheet = PdfSheet.Stamps }
            }
        }

        if (saving) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)).blockTouches(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        }

        when (sheet) {
            PdfSheet.Signatures -> SignaturesSheet(
                onDismiss = { sheet = PdfSheet.None },
                onPick = { bmp -> sheet = PdfSheet.None; place(bmp, 0.35f) },
                onNew = { sheet = PdfSheet.Pad },
            )
            PdfSheet.Pad -> SignaturePad(
                onCancel = { sheet = PdfSheet.None },
                onDone = { bmp ->
                    Signatures.add(ctx, bmp)
                    sheet = PdfSheet.None
                    place(bmp, 0.35f)
                },
            )
            PdfSheet.Stamps -> StampsSheet(
                onDismiss = { sheet = PdfSheet.None },
                onPick = { bmp -> sheet = PdfSheet.None; place(bmp, 0.32f) },
            )
            PdfSheet.None -> {}
        }
    }
}

/** content:// address for a local file, so other apps can read it. */
private object LocalShareUri {
    fun of(ctx: Context, path: String): Uri =
        androidx.core.content.FileProvider.getUriForFile(ctx, "com.photos.files.provider", File(path))
}

@Composable
private fun PdfPage(
    d: PdfDoc, i: Int, baseW: Int, scale: Float, marks: List<Mark>, selected: Long?, marking: Boolean,
    onSelect: (Long) -> Unit, onMove: (Long, Float, Float) -> Unit, onResize: (Long, Float) -> Unit, onDelete: (Long) -> Unit,
) {
    val (pw, ph) = d.sizes[i]
    var base by remember(d, i) { mutableStateOf(d.cache.get(i)) }
    var sharp by remember(d, i) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(d, i) {
        if (base == null) d.render(i, baseW)?.let { d.cache.put(i, it); base = it }
    }
    // Zoomed in: a sharper copy of this page while it's on screen.
    val level = if (scale < 1.4f) 1 else min(ceil(scale).toInt(), 3)
    LaunchedEffect(d, i, level) {
        if (level == 1) { sharp = null; return@LaunchedEffect }
        delay(150)
        var w = baseW * level
        val maxPx = 16_000_000f
        val h = w * ph / pw
        if (w * h > maxPx) w = sqrt(maxPx * pw / ph).toInt()
        d.render(i, w)?.let { sharp = it }
    }
    BoxWithConstraints(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp).aspectRatio(pw / ph)
            .shadow(2.dp, RoundedCornerShape(2.dp)).background(Color.White),
    ) {
        val bmp = sharp ?: base
        if (bmp != null) {
            Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.FillBounds)
        }
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        for (m in marks) if (m.page == i) {
            androidx.compose.runtime.key(m.id) {
                MarkView(m, boxW, boxH, pw, ph, selected == m.id, marking, onSelect, onMove, onResize, onDelete)
            }
        }
    }
}

@Composable
private fun MarkView(
    m: Mark, boxW: Float, boxH: Float, pw: Float, ph: Float, isSelected: Boolean, marking: Boolean,
    onSelect: (Long) -> Unit, onMove: (Long, Float, Float) -> Unit, onResize: (Long, Float) -> Unit, onDelete: (Long) -> Unit,
) {
    val c = LocalIos.current
    val density = LocalDensity.current
    val margin = 16.dp
    val marginPx = with(density) { margin.toPx() }
    val wPx = m.w * boxW
    val hPx = wPx * m.bmp.height / m.bmp.width
    val img = remember(m.bmp) { m.bmp.asImageBitmap() }
    Box(
        Modifier
            .offset { IntOffset((m.cx * boxW - wPx / 2 - marginPx).roundToInt(), (m.cy * boxH - hPx / 2 - marginPx).roundToInt()) }
            .size(with(density) { (wPx + marginPx * 2).toDp() }, with(density) { (hPx + marginPx * 2).toDp() })
            .then(
                if (marking) Modifier
                    .pointerInput(m.id, boxW, boxH) {
                        detectDragGestures(onDragStart = { onSelect(m.id) }) { ch, drag ->
                            ch.consume()
                            onMove(m.id, drag.x / boxW, drag.y / boxH)
                        }
                    }
                    .pointerInput(m.id) { detectTapGestures { onSelect(m.id) } }
                else Modifier,
            ),
    ) {
        Image(
            img, null,
            Modifier.padding(margin).fillMaxSize()
                .then(if (isSelected && marking) Modifier.border(1.dp, c.accent, RoundedCornerShape(3.dp)) else Modifier),
        )
        if (isSelected && marking) {
            // Delete, top-left.
            Box(
                Modifier.align(Alignment.TopStart).padding(3.dp).size(26.dp).clip(CircleShape).background(c.red)
                    .pointerInput(m.id) { detectTapGestures { onDelete(m.id) } },
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.size(width = 12.dp, height = 2.dp).background(Color.White)) }
            // Resize, bottom-right.
            Box(
                Modifier.align(Alignment.BottomEnd).padding(3.dp).size(26.dp).clip(CircleShape).background(c.accent)
                    .border(2.dp, Color.White, CircleShape)
                    .pointerInput(m.id, boxW) {
                        detectDragGestures { ch, drag ->
                            ch.consume()
                            onResize(m.id, 2 * drag.x / boxW)
                        }
                    },
            )
        }
    }
}

@Composable
private fun Sheet(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val c = LocalIos.current
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    ) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(c.grouped.takeIf { !c.dark } ?: c.card)
                .pointerInput(Unit) { detectTapGestures { } }
                .navigationBarsPadding().padding(16.dp),
        ) {
            Box(Modifier.fillMaxWidth().height(44.dp)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label, modifier = Modifier.align(Alignment.Center))
                Box(Modifier.align(Alignment.CenterEnd)) { GlassPill("Done", height = 38.dp, onClick = onDismiss) }
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun SignaturesSheet(onDismiss: () -> Unit, onPick: (Bitmap) -> Unit, onNew: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalIos.current
    var files by remember { mutableStateOf(Signatures.list(ctx)) }
    Sheet("Signatures", onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (f in files) {
                val bmp = remember(f) { Signatures.load(f) } ?: continue
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White)
                        .tap { onPick(bmp) }.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(bmp.asImageBitmap(), null, Modifier.weight(1f).height(64.dp))
                    RemoveDot { Signatures.remove(f); files = Signatures.list(ctx) }
                }
            }
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.card).tap(onClick = onNew).padding(16.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Add Signature", color = c.accent, fontSize = 17.sp) }
        }
    }
}

@Composable
private fun StampsSheet(onDismiss: () -> Unit, onPick: (Bitmap) -> Unit) {
    val stamps = remember { Stamp.entries.map { it.bitmap() } }
    Sheet("Stamps", onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            stamps.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { bmp ->
                        Box(
                            Modifier.weight(1f).height(78.dp).clip(RoundedCornerShape(14.dp)).background(Color.White)
                                .tap { onPick(bmp) }.padding(12.dp),
                            contentAlignment = Alignment.Center,
                        ) { Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize()) }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** Draw a signature with a finger. Ink is black on white, like paper. */
@Composable
private fun SignaturePad(onCancel: () -> Unit, onDone: (Bitmap) -> Unit) {
    val c = LocalIos.current
    val density = LocalDensity.current
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var cur by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val ink = with(density) { 3.dp.toPx() }
    Box(Modifier.fillMaxSize().background(Color.White).pointerInput(Unit) { detectTapGestures { } }) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { cur = listOf(it) },
                    onDragEnd = { if (cur.size > 1) strokes.add(cur); cur = emptyList() },
                    onDragCancel = { cur = emptyList() },
                ) { ch, _ -> ch.consume(); cur = cur + ch.position }
            },
        ) {
            val baseline = size.height * 0.62f
            drawLine(Color(0xFFC7C7CC), Offset(40f, baseline), Offset(size.width - 40f, baseline), strokeWidth = 2f)
            for (s in strokes + listOf(cur)) if (s.size > 1) {
                drawPath(smooth(s), Color.Black, style = Stroke(ink, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        Text(
            "Sign with your finger", color = Color(0xFF8E8E93), fontSize = 15.sp,
            modifier = Modifier.align(Alignment.Center).offset(y = 60.dp),
        )
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LightPill("Cancel", Color.Black, onCancel)
            Spacer(Modifier.weight(1f))
            LightPill("Clear", Color.Black) { strokes.clear() }
            Spacer(Modifier.width(8.dp))
            LightPill("Done", c.accent) {
                if (strokes.isNotEmpty()) onDone(exportSignature(strokes, ink)) else onCancel()
            }
        }
    }
}

@Composable
private fun LightPill(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text, color = color, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Clip,
        modifier = Modifier.clip(CircleShape).background(Color(0xFFF2F2F7)).tap(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
    )
}

private fun smooth(pts: List<Offset>): Path = Path().apply {
    moveTo(pts[0].x, pts[0].y)
    for (k in 1 until pts.size) {
        val a = pts[k - 1]
        val b = pts[k]
        quadraticBezierTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2)
    }
    lineTo(pts.last().x, pts.last().y)
}

/** Crops the drawing to the ink and returns it on a transparent background. */
private fun exportSignature(strokes: List<List<Offset>>, ink: Float): Bitmap {
    val all = strokes.flatten()
    val pad = ink * 2
    val minX = all.minOf { it.x } - pad
    val minY = all.minOf { it.y } - pad
    val maxX = all.maxOf { it.x } + pad
    val maxY = all.maxOf { it.y } + pad
    val s = min(2f, 1600f / max(maxX - minX, maxY - minY))
    val bmp = Bitmap.createBitmap(((maxX - minX) * s).roundToInt().coerceAtLeast(1), ((maxY - minY) * s).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val cv = android.graphics.Canvas(bmp)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK; style = android.graphics.Paint.Style.STROKE
        strokeWidth = ink * s; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
    }
    for (st in strokes) {
        val path = android.graphics.Path()
        fun x(o: Offset) = (o.x - minX) * s
        fun y(o: Offset) = (o.y - minY) * s
        path.moveTo(x(st[0]), y(st[0]))
        for (k in 1 until st.size) {
            val a = st[k - 1]; val b = st[k]
            path.quadTo(x(a), y(a), (x(a) + x(b)) / 2, (y(a) + y(b)) / 2)
        }
        path.lineTo(x(st.last()), y(st.last()))
        cv.drawPath(path, p)
    }
    return bmp
}
