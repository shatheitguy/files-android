package com.photos.files.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photos.files.data.FileItem
import com.photos.files.data.FileKind
import com.photos.files.data.TagColor
import com.photos.files.data.rememberThumb
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

val TabBarSpace = 96.dp

fun Modifier.tap(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        enabled = enabled,
        onClick = onClick,
    )
}

fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope { while (true) awaitPointerEvent() }
}

object Fmt {
    fun size(b: Long): String {
        if (b < 1000) return "$b bytes"
        val units = listOf("KB", "MB", "GB", "TB")
        var v = b.toDouble()
        var i = -1
        while (v >= 1000 && i < units.lastIndex) { v /= 1000; i++ }
        return if (v >= 100) "%.0f %s".format(v, units[i]) else "%.1f %s".format(v, units[i])
    }

    fun date(ms: Long): String {
        val now = Calendar.getInstance()
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))
        val sameYear = now.get(Calendar.YEAR) == c.get(Calendar.YEAR)
        val dayDiff = if (sameYear) now.get(Calendar.DAY_OF_YEAR) - c.get(Calendar.DAY_OF_YEAR) else 99
        return when (dayDiff) {
            0 -> "Today at $time"
            1 -> "Yesterday at $time"
            else -> SimpleDateFormat("d/M/yy", Locale.getDefault()).format(Date(ms))
        }
    }

    fun full(ms: Long): String = SimpleDateFormat("d MMMM yyyy 'at' h:mm a", Locale.getDefault()).format(Date(ms))

    fun items(n: Int) = if (n == 1) "1 item" else "$n items"

    fun subtitle(item: FileItem): String =
        if (item.isDir) {
            if (item.childCount >= 0) Fmt.items(item.childCount) else Fmt.date(item.modified)
        } else "${Fmt.date(item.modified)} · ${Fmt.size(item.size)}"
}

@Composable
fun glass(): Triple<Color, Color, Color> {
    val c = LocalIos.current
    return if (c.dark) Triple(Color(0xF22A2A2D), Color(0x26FFFFFF), Color.White)
    else Triple(Color(0xF8FFFFFF), Color(0x14000000), Color.Black)
}

@Composable
fun GlassCircle(icon: ImageVector, size: Dp = 46.dp, tint: Color? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val (bg, border, fg) = glass()
    val col = tint ?: fg
    Box(
        Modifier.size(size).shadow(8.dp, CircleShape, clip = false).clip(CircleShape).background(bg)
            .border(0.5.dp, border, CircleShape).tap(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (enabled) col else col.copy(alpha = 0.3f), modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun GlassPill(text: String, color: Color? = null, enabled: Boolean = true, height: Dp = 46.dp, onClick: () -> Unit) {
    val (bg, border, fg) = glass()
    val col = color ?: fg
    Box(
        Modifier.height(height).shadow(8.dp, CircleShape, clip = false).clip(CircleShape).background(bg)
            .border(0.5.dp, border, CircleShape).tap(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) col else col.copy(alpha = 0.3f), fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
fun GlassCapsule(content: @Composable RowScope.() -> Unit) {
    val (bg, border, _) = glass()
    Row(
        Modifier.height(50.dp).shadow(8.dp, CircleShape, clip = false).clip(CircleShape).background(bg)
            .border(0.5.dp, border, CircleShape).blockTouches().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun CapsuleIcon(icon: ImageVector, enabled: Boolean = true, tint: Color? = null, onClick: () -> Unit) {
    val (_, _, fg) = glass()
    val col = tint ?: fg
    Box(Modifier.size(width = 48.dp, height = 50.dp).tap(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (enabled) col else col.copy(alpha = 0.3f), modifier = Modifier.size(23.dp))
    }
}

enum class FilesTab(val label: String) { Recents("Recents"), Browse("Browse"), Search("Search") }

@Composable
fun FloatingTabBar(current: FilesTab, onSelect: (FilesTab) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalIos.current
    val (container, border, _) = glass()
    val selectedBg = systemAccent()
    Box(
        modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Recents and Browse sit in the centre; Search floats on the right.
        Row(
            Modifier.shadow(14.dp, CircleShape, clip = false).clip(CircleShape).background(container)
                .border(0.5.dp, border, CircleShape).blockTouches().padding(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tabs = listOf(FilesTab.Recents, FilesTab.Browse)
            SlidingTabs(
                count = tabs.size,
                selected = tabs.indexOf(current),
                itemWidth = 88.dp,
                itemHeight = 56.dp,
                highlight = selectedBg,
                onSelect = { onSelect(tabs[it]) },
            ) { i, active ->
                val t = tabs[i]
                val col = if (active) Color.White else c.label
                Column(
                    Modifier.fillMaxSize().clip(CircleShape).tap { onSelect(t) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(if (t == FilesTab.Recents) IosIcons.Clock else IosIcons.Folder, null, tint = col, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(t.label, fontSize = 12.sp, lineHeight = 14.sp, color = col, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
        }
        Box(
            Modifier.align(Alignment.CenterEnd).size(60.dp).shadow(14.dp, CircleShape, clip = false).clip(CircleShape)
                .background(container).border(0.5.dp, border, CircleShape).tap { onSelect(FilesTab.Search) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(IosIcons.Search, null, tint = c.label, modifier = Modifier.size(26.dp))
        }
    }
}

// ---------- File icons ----------

private val FolderTop = Color(0xFF8FD3FE)
private val FolderBack = Color(0xFF5AB6F5)
private val FolderFront = Color(0xFF7CC8FB)

/** iOS-style blue folder, drawn in code. */
@Composable
fun FolderIcon(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val top = h * 0.16f
        val bodyTop = h * 0.26f
        val bottom = h * 0.86f
        val r = w * 0.07f
        val back = Path().apply {
            moveTo(w * 0.04f, top + r)
            quadraticBezierTo(w * 0.04f, top, w * 0.04f + r, top)
            lineTo(w * 0.36f, top)
            quadraticBezierTo(w * 0.40f, top, w * 0.43f, top + h * 0.05f)
            lineTo(w * 0.46f, bodyTop)
            lineTo(w * 0.96f - r, bodyTop)
            quadraticBezierTo(w * 0.96f, bodyTop, w * 0.96f, bodyTop + r)
            lineTo(w * 0.96f, bottom)
            lineTo(w * 0.04f, bottom)
            close()
        }
        drawPath(back, FolderBack)
        drawRoundRect(
            Brush.verticalGradient(listOf(FolderTop, FolderFront), startY = h * 0.33f, endY = bottom),
            topLeft = Offset(w * 0.04f, h * 0.33f),
            size = Size(w * 0.92f, bottom - h * 0.33f),
            cornerRadius = CornerRadius(r, r),
        )
    }
}

private fun kindColor(kind: FileKind, ext: String): Color = when (kind) {
    FileKind.Pdf -> Color(0xFFE5453B)
    FileKind.Document -> Color(0xFF2F7BF5)
    FileKind.Spreadsheet -> Color(0xFF2FA84F)
    FileKind.Presentation -> Color(0xFFF08A24)
    FileKind.Archive -> Color(0xFF8E8E93)
    FileKind.App -> Color(0xFF34C759)
    FileKind.Audio -> Color(0xFFFF2D55)
    FileKind.Video -> Color(0xFF5856D6)
    FileKind.Code -> Color(0xFF5E5CE6)
    FileKind.Text -> Color(0xFF636366)
    else -> if (ext.isEmpty()) Color(0xFF8E8E93) else Color(0xFF636366)
}

/** A white document with a folded corner and a coloured label. */
@Composable
fun DocIcon(item: FileItem, modifier: Modifier) {
    val c = LocalIos.current
    val label = item.ext.uppercase().take(4)
    val col = kindColor(item.kind, item.ext)
    val pageBg = if (c.dark) Color(0xFF2C2C2E) else Color.White
    val edge = if (c.dark) Color(0xFF48484A) else Color(0xFFD1D1D6)
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val w = maxWidth
        val pageW = w * 0.72f
        val pageH = maxHeight * 0.9f
        Box(Modifier.size(pageW, pageH)) {
            Canvas(Modifier.fillMaxSize()) {
                val fold = size.width * 0.28f
                val p = Path().apply {
                    moveTo(0f, 3f)
                    quadraticBezierTo(0f, 0f, 3f, 0f)
                    lineTo(size.width - fold, 0f)
                    lineTo(size.width, fold)
                    lineTo(size.width, size.height - 3f)
                    quadraticBezierTo(size.width, size.height, size.width - 3f, size.height)
                    lineTo(3f, size.height)
                    quadraticBezierTo(0f, size.height, 0f, size.height - 3f)
                    close()
                }
                drawPath(p, pageBg)
                drawPath(p, edge, style = Stroke(1.2f))
                val f = Path().apply {
                    moveTo(size.width - fold, 0f)
                    lineTo(size.width - fold, fold)
                    lineTo(size.width, fold)
                }
                drawPath(f, edge, style = Stroke(1.2f))
                val lines = 4
                for (i in 0 until lines) {
                    val y = size.height * (0.30f + i * 0.09f)
                    drawLine(edge, Offset(size.width * 0.18f, y), Offset(size.width * (if (i == lines - 1) 0.6f else 0.82f), y), 1.5f)
                }
            }
            if (label.isNotEmpty()) {
                val fs = with(LocalDensity.current) { (pageW * 0.22f).toSp() }
                Text(
                    label, color = col, fontSize = fs, fontWeight = FontWeight.Bold, maxLines = 1,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = pageH * 0.1f),
                )
            }
        }
    }
}

/** Thumbnail if one can be made, otherwise a folder or document icon. */
@Composable
fun FileIcon(item: FileItem, modifier: Modifier, px: Int = 256, rounded: Dp = 6.dp) {
    if (item.isDir) {
        FolderIcon(modifier)
        return
    }
    val bmp = rememberThumb(item, px)
    BoxWithConstraints(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        if (bmp != null) {
            val c = LocalIos.current
            val aspect = (bmp.width.toFloat() / bmp.height).coerceIn(0.5f, 2f)
            // Fit the picture inside the icon box so it never spills onto the name below.
            val box = minOf(maxWidth, maxHeight) * 0.96f
            val keepAspect = AppPrefs.get(androidx.compose.ui.platform.LocalContext.current).flag(FilesPrefKeys.AspectThumbs, true)
            val w = if (!keepAspect) box * 0.92f else if (aspect >= 1f) box else box * aspect
            val h = if (!keepAspect) box * 0.92f else if (aspect >= 1f) box / aspect else box
            Image(
                bmp, null,
                Modifier.size(w, h)
                    .clip(RoundedCornerShape(rounded)).border(0.5.dp, c.separator, RoundedCornerShape(rounded)),
                contentScale = ContentScale.Crop,
            )
            if (item.kind == FileKind.Video) {
                Box(
                    Modifier.align(Alignment.Center).size(26.dp).clip(CircleShape).background(Color(0x88000000)),
                    contentAlignment = Alignment.Center,
                ) { Icon(IosIcons.Play, null, tint = Color.White, modifier = Modifier.size(13.dp)) }
            }
        } else {
            DocIcon(item, Modifier.fillMaxSize())
        }
    }
}

@Composable
fun TagDots(tags: Set<TagColor>, size: Dp = 9.dp) {
    if (tags.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy((-3).dp)) {
        tags.sortedBy { it.ordinal }.forEach { t ->
            Box(Modifier.size(size).clip(CircleShape).background(LocalIos.current.bg).padding(1.dp).clip(CircleShape).background(Color(t.argb)))
        }
    }
}

@Composable
fun SelectCircle(selected: Boolean, modifier: Modifier = Modifier) {
    val c = LocalIos.current
    if (selected) {
        Box(modifier.size(24.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
            Icon(IosIcons.Check, null, tint = Color.White, modifier = Modifier.size(15.dp))
        }
    } else {
        Box(modifier.size(24.dp).clip(CircleShape).border(1.5.dp, c.tertiary, CircleShape))
    }
}

// ---------- Grouped list ----------

@Composable
fun GroupCard(content: @Composable ColumnScope.() -> Unit) {
    val c = LocalIos.current
    Column(
        Modifier.widthIn(max = 720.dp).padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (c.dark) c.card else Color.White),
        content = content,
    )
}

@Composable
fun GroupRow(
    icon: @Composable () -> Unit,
    title: String,
    detail: String? = null,
    last: Boolean = false,
    chevron: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val c = LocalIos.current
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).combinedTap(onClick, onLongClick).padding(start = 16.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { icon() }
            Spacer(Modifier.width(14.dp))
            Text(title, fontSize = 17.sp, color = c.label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, fontSize = 15.sp, color = c.secondary, maxLines = 1)
            if (chevron) {
                Spacer(Modifier.width(6.dp))
                Icon(IosIcons.ChevronRight, null, tint = c.tertiary, modifier = Modifier.size(14.dp))
            }
        }
        if (!last) Box(Modifier.padding(start = 58.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.combinedTap(onClick: () -> Unit, onLongClick: (() -> Unit)?): Modifier = composed {
    combinedClickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
        onLongClick = onLongClick,
    )
}

@Composable
fun SectionTitle(text: String, collapsible: Boolean = false, trailing: (@Composable () -> Unit)? = null) {
    val c = LocalIos.current
    val prefs = AppPrefs.get(androidx.compose.ui.platform.LocalContext.current)
    val collapsed = collapsible && prefs.flag("collapsed_$text", false)
    val turn by androidx.compose.animation.core.animateFloatAsState(if (collapsed) -90f else 0f, label = "chev")
    Row(
        Modifier.widthIn(max = 720.dp).fillMaxWidth()
            .then(if (collapsible) Modifier.tap { prefs.setFlag("collapsed_$text", !collapsed) } else Modifier)
            .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c.label, modifier = Modifier.weight(1f))
        trailing?.invoke()
        if (collapsible) {
            Icon(
                IosIcons.ChevronRight, null, tint = c.label,
                modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = 90f + turn },
            )
        }
    }
}

/** Content under a collapsible [SectionTitle]; slides open and closed. */
@Composable
fun Collapsible(title: String, content: @Composable () -> Unit) {
    val prefs = AppPrefs.get(androidx.compose.ui.platform.LocalContext.current)
    androidx.compose.animation.AnimatedVisibility(
        visible = !prefs.flag("collapsed_$title", false),
        enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
    ) { content() }
}

// ---------- Dialogs ----------

@Composable
fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val c = LocalIos.current
    val dot = initial.lastIndexOf('.').takeIf { it > 0 } ?: initial.length
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, dot))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = if (c.dark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7),
        shape = RoundedCornerShape(16.dp),
        title = { Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
        text = {
            BasicTextField(
                value, { value = it },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, color = c.label),
                cursorBrush = SolidColor(c.label),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (value.text.isNotBlank()) onConfirm(value.text) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).clip(RoundedCornerShape(8.dp))
                    .background(if (c.dark) Color(0xFF1C1C1E) else Color.White).padding(horizontal = 10.dp, vertical = 10.dp),
            )
        },
        confirmButton = {
            Text(
                confirm, color = if (value.text.isNotBlank()) c.label else c.tertiary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.tap(enabled = value.text.isNotBlank()) { onConfirm(value.text) }.padding(10.dp),
            )
        },
        dismissButton = {
            Text("Cancel", color = c.label, fontSize = 17.sp, modifier = Modifier.tap(onClick = onDismiss).padding(10.dp))
        },
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, destructive: Boolean = true, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val c = LocalIos.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = if (c.dark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7),
        shape = RoundedCornerShape(16.dp),
        title = { Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label) },
        text = { Text(message, fontSize = 14.sp, color = c.secondary) },
        confirmButton = {
            Text(
                confirm, color = if (destructive) c.red else c.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.tap(onClick = onConfirm).padding(10.dp),
            )
        },
        dismissButton = {
            Text("Cancel", color = c.label, fontSize = 17.sp, modifier = Modifier.tap(onClick = onDismiss).padding(10.dp))
        },
    )
}
