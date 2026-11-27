package com.photos.files.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photos.files.data.FileItem
import com.photos.files.data.FileKind

class MenuAction(
    val label: String,
    val icon: ImageVector?,
    val destructive: Boolean = false,
    val divider: Boolean = false,
    val checked: Boolean = false,
    val run: () -> Unit,
)

/** The long-press menu for a file or folder. */
fun AppState.menuFor(item: FileItem): List<MenuAction> {
    val p = listOf(item.path)
    val out = mutableListOf<MenuAction>()
    out += MenuAction("Get Info", IosIcons.Info) { infoItem = item }
    out += MenuAction("Rename", IosIcons.Pencil) { renameTarget = item }
    if (item.ext == "zip") out += MenuAction("Uncompress", IosIcons.Zip) { uncompress(item) }
    else out += MenuAction("Compress", IosIcons.Zip) { compress(p) }
    out += MenuAction("Duplicate", IosIcons.Duplicate) { duplicate(p) }
    out += MenuAction("Tags…", IosIcons.Tag) { tagTargets = p }
    if (item.isDir) {
        val fav = store.isFavorite(item.path)
        out += MenuAction(if (fav) "Remove from Favorites" else "Add to Favorites", IosIcons.Star) { store.toggleFavorite(item.path) }
    }
    out += MenuAction("Copy", IosIcons.Copy, divider = true) { push(Route.Picker(PickMode.Copy, p)) }
    out += MenuAction("Move", IosIcons.Move) { push(Route.Picker(PickMode.Move, p)) }
    if (!item.isDir) out += MenuAction("Share", IosIcons.Share) { share(p) }
    out += MenuAction("Delete", IosIcons.Trash, destructive = true, divider = true) { trash(p) }
    return out
}

@Composable
fun IosMenu(expanded: Boolean, onDismiss: () -> Unit, actions: List<MenuAction>, offset: DpOffset = DpOffset(0.dp, 0.dp)) {
    IosPopupMenu(expanded, onDismiss, actions.map { PopupItem(it.label, it.icon, it.destructive, it.divider, it.checked, it.run) })
}

/**
 * Icons or list of files. [header] items go above the files, spanning the full width.
 */
@Composable
fun FileCollection(
    items: List<FileItem>,
    listView: Boolean,
    state: LazyGridState = rememberLazyGridState(),
    footer: String? = null,
    subtitle: ((FileItem) -> String)? = null,
    menu: ((FileItem) -> List<MenuAction>)? = null,
    onOpen: ((FileItem) -> Unit)? = null,
    header: LazyGridScope.() -> Unit = {},
) {
    val app = LocalApp.current
    val c = LocalIos.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val index = remember(items) { HashMap<String, Int>(items.size).also { m -> items.forEachIndexed { i, f -> m[f.path] = i } } }
    fun indexAt(x: Float, y: Float): Int {
        val info = state.layoutInfo
        val yy = y + info.viewportStartOffset
        val hit = info.visibleItemsInfo.firstOrNull {
            x >= it.offset.x && x < it.offset.x + it.size.width && yy >= it.offset.y && yy < it.offset.y + it.size.height
        } ?: return -1
        return (hit.key as? String)?.let { index[it] } ?: -1
    }
    // Swipe to select (like iOS): while selecting, swipe sideways across files, or press and hold then drag,
    // to select a range. Dragging near the top or bottom edge scrolls and keeps selecting.
    val dragSelect = Modifier.pointerInput(items, app.selecting) {
        if (!app.selecting) return@pointerInput
        val slop = viewConfiguration.touchSlop
        val edge = 70.dp.toPx()
        val maxSpeed = 26.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var pos = down.position
            val decided = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                    if (!ch.pressed || ch.isConsumed || ev.changes.count { it.pressed } > 1) return@withTimeoutOrNull false
                    val d = ch.position - down.position
                    if (abs(d.x) > slop && abs(d.x) > abs(d.y)) {
                        ch.consume(); pos = ch.position
                        return@withTimeoutOrNull true
                    }
                    if (d.getDistance() > slop) return@withTimeoutOrNull false
                }
                @Suppress("UNREACHABLE_CODE")
                false
            } ?: true
            if (!decided) return@awaitEachGesture
            val start = indexAt(down.position.x, down.position.y).takeIf { it >= 0 }
                ?: indexAt(pos.x, pos.y).takeIf { it >= 0 } ?: return@awaitEachGesture
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            val base = app.selected
            val adding = items[start].path !in base
            var lastEnd = -1
            fun apply(end: Int) {
                if (end < 0 || end == lastEnd) return
                lastEnd = end
                val range = (minOf(start, end)..maxOf(start, end)).map { items[it].path }
                app.setSelection(if (adding) base + range else base - range.toSet())
            }
            apply(start)
            var last = pos
            val auto = scope.launch {
                while (isActive) {
                    val info = state.layoutInfo
                    val topZone = info.beforeContentPadding + edge
                    val bottomZone = size.height - info.afterContentPadding - edge
                    val speed = when {
                        last.y < topZone -> -maxSpeed * ((topZone - last.y) / edge).coerceIn(0.2f, 1.5f)
                        last.y > bottomZone -> maxSpeed * ((last.y - bottomZone) / edge).coerceIn(0.2f, 1.5f)
                        else -> 0f
                    }
                    if (speed != 0f) {
                        state.scrollBy(speed)
                        apply(indexAt(last.x, last.y.coerceIn(topZone - edge + 1f, bottomZone + edge - 1f)))
                    }
                    delay(16)
                }
            }
            try {
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    ev.changes.forEach { it.consume() }
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    last = ch.position
                    apply(indexAt(last.x, last.y))
                }
            } finally {
                auto.cancel()
            }
        }
    }
    LazyVerticalGrid(
        columns = if (listView) GridCells.Fixed(1) else GridCells.Adaptive(108.dp),
        state = state,
        modifier = Modifier.fillMaxSize().then(dragSelect),
        contentPadding = PaddingValues(start = if (listView) 0.dp else 8.dp, end = if (listView) 0.dp else 8.dp, bottom = TabBarSpace + 40.dp),
    ) {
        header()
        items(items, key = { it.path }) { item ->
            val sel = item.path in app.selected
            var menuOpen by remember { mutableStateOf(false) }
            val open = { if (app.selecting) app.toggle(item.path) else (onOpen ?: app::open)(item) }
            val long: (() -> Unit)? = if (app.selecting) null else ({ menuOpen = true })
            val lift by androidx.compose.animation.core.animateFloatAsState(
                if (menuOpen && !listView) 1.06f else 1f,
                androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 500f), label = "lift",
            )
            Box(Modifier.animateItem().graphicsLayer { scaleX = lift; scaleY = lift }) {
                if (listView) ListCell(item, sel, subtitle, open, long) else IconCell(item, sel, subtitle, open, long)
                IosMenu(menuOpen, { menuOpen = false }, if (menuOpen) (menu ?: app::menuFor)(item) else emptyList())
            }
        }
        if (footer != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    footer, fontSize = 13.sp, color = c.secondary, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                )
            }
        }
    }
}

@Composable
private fun IconCell(item: FileItem, selected: Boolean, subtitle: ((FileItem) -> String)?, onClick: () -> Unit, onLong: (() -> Unit)?) {
    val c = LocalIos.current
    val app = LocalApp.current
    val store = LocalFileStore.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (selected) c.accent.copy(alpha = 0.12f) else Color.Transparent)
            .combinedTap(onClick, onLong).padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(84.dp)) {
            FileIcon(item, Modifier.fillMaxSize(), px = 256)
            if (app.selecting) SelectCircle(selected, Modifier.align(Alignment.BottomEnd).offset(4.dp, 2.dp))
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.name, fontSize = 13.sp, lineHeight = 16.sp, color = c.label, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        val tags = store.tagsOf(item.path)
        if (tags.isNotEmpty()) { Spacer(Modifier.height(3.dp)); TagDots(tags) }
        Spacer(Modifier.height(2.dp))
        val sub = subtitle?.invoke(item)
        if (sub != null) {
            Text(sub, fontSize = 12.sp, color = c.secondary, maxLines = 1, textAlign = TextAlign.Center)
        } else {
            Text(Fmt.date(item.modified), fontSize = 12.sp, color = c.secondary, maxLines = 1, textAlign = TextAlign.Center)
            Text(
                if (item.isDir) (if (item.childCount >= 0) Fmt.items(item.childCount) else "") else Fmt.size(item.size),
                fontSize = 12.sp, color = c.secondary, maxLines = 1, textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ListCell(item: FileItem, selected: Boolean, subtitle: ((FileItem) -> String)?, onClick: () -> Unit, onLong: (() -> Unit)?) {
    val c = LocalIos.current
    val app = LocalApp.current
    val store = LocalFileStore.current
    Column(Modifier.fillMaxWidth().background(if (selected) c.accent.copy(alpha = 0.12f) else Color.Transparent)) {
        Row(
            Modifier.fillMaxWidth().combinedTap(onClick, onLong).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (app.selecting) { SelectCircle(selected); Spacer(Modifier.width(12.dp)) }
            FileIcon(item, Modifier.size(44.dp), px = 144, rounded = 4.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, fontSize = 16.sp, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(subtitle?.invoke(item) ?: Fmt.subtitle(item), fontSize = 13.sp, color = c.secondary, maxLines = 1)
            }
            TagDots(store.tagsOf(item.path))
            if (item.isDir && !app.selecting) {
                Spacer(Modifier.width(8.dp))
                Icon(IosIcons.ChevronRight, null, tint = c.tertiary, modifier = Modifier.size(14.dp))
            }
        }
        Box(Modifier.padding(start = if (app.selecting) 108.dp else 72.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
    }
}

/** Floating glass actions shown while selecting files. */
@Composable
fun SelectionBar(all: List<FileItem>, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val c = LocalIos.current
    val chosen = all.filter { it.path in app.selected }.map { it.path }
    val n = chosen.size
    val fade = if (c.dark) Color.Black else Color.White
    Box(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, fade.copy(alpha = 0.6f), fade.copy(alpha = 0.9f))))
            .blockTouches()
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 30.dp, bottom = 12.dp),
    ) {
        Text(
            when (n) { 0 -> "Select Items"; 1 -> "1 Item Selected"; else -> "$n Items Selected" },
            color = c.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.TopCenter).offset(y = (-26).dp),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GlassCircle(IosIcons.Share, size = 50.dp, enabled = n > 0) { app.share(chosen) }
            Spacer(Modifier.weight(1f))
            GlassCapsule {
                CapsuleIcon(IosIcons.Duplicate, n > 0) { app.duplicate(chosen) }
                CapsuleIcon(IosIcons.Move, n > 0) { app.push(Route.Picker(PickMode.Move, chosen)) }
                CapsuleIcon(IosIcons.Copy, n > 0) { app.push(Route.Picker(PickMode.Copy, chosen)) }
                CapsuleIcon(IosIcons.Tag, n > 0) { app.tagTargets = chosen }
                CapsuleIcon(IosIcons.Zip, n > 0) { app.compress(chosen) }
            }
            Spacer(Modifier.weight(1f))
            GlassCircle(IosIcons.Trash, size = 50.dp, tint = c.red, enabled = n > 0) { app.trash(chosen) }
        }
    }
}

/** Top bar for pushed screens: glass back button, title, and trailing controls. */
@Composable
fun ScreenTopBar(
    title: String,
    showTitle: Boolean,
    onBack: (() -> Unit)?,
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = LocalIos.current
    val fade = c.bg
    Box(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(fade, fade.copy(alpha = 0.85f), fade.copy(alpha = 0f))))
            .statusBarsPadding().padding(horizontal = 16.dp).padding(top = 6.dp, bottom = 14.dp).height(46.dp),
    ) {
        if (leading != null) Box(Modifier.align(Alignment.CenterStart)) { leading() }
        else if (onBack != null) Box(Modifier.align(Alignment.CenterStart)) { GlassCircle(IosIcons.ChevronLeft, onClick = onBack) }
        if (showTitle) {
            Text(
                title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 110.dp),
            )
        }
        Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

@Composable
fun LargeHeader(title: String, sub: String? = null) {
    val c = LocalIos.current
    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 62.dp, bottom = 10.dp)) {
        Text(title, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = c.label, maxLines = 2, overflow = TextOverflow.Ellipsis, letterSpacing = (-0.5).sp)
        if (sub != null) Text(sub, fontSize = 14.sp, color = c.secondary)
    }
}

val FileItem.isMedia get() = kind == FileKind.Image || kind == FileKind.Video
