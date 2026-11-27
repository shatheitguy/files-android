package com.photos.files.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photos.files.data.FileItem
import com.photos.files.data.FileStore
import com.photos.files.data.TagColor
import com.photos.files.data.kindLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

fun hasAccess(ctx: android.content.Context): Boolean =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

/** Shows the permission screen until the app can read and write storage. */
@Composable
fun AccessGate(resumeTick: Int, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    var ok by remember { mutableStateOf(hasAccess(ctx)) }
    LaunchedEffect(resumeTick) { ok = hasAccess(ctx) }
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { ok = hasAccess(ctx) }
    if (ok) content() else PermissionScreen {
        if (Build.VERSION.SDK_INT >= 30) {
            val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
            runCatching { ctx.startActivity(i) }.onFailure {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
        } else {
            legacy.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
    }
}

@Composable
private fun PermissionScreen(onContinue: () -> Unit) {
    val c = LocalIos.current
    Column(
        Modifier.fillMaxSize().background(c.bg).systemBarsPadding().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AppGlyph()
        Spacer(Modifier.height(24.dp))
        Text("Welcome to Files", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = c.label, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            "To browse and organize everything on your phone, turn on “Allow access to manage all files” for Files on the next screen.",
            fontSize = 17.sp, color = c.secondary, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 520.dp),
        )
        Spacer(Modifier.height(32.dp))
        Box(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().height(50.dp).clip(RoundedCornerShape(14.dp)).background(c.accent).tap(onClick = onContinue),
            contentAlignment = Alignment.Center,
        ) {
            Text("Continue", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun AppGlyph() {
    Box(
        Modifier.size(96.dp).clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFFFB443), Color(0xFFFF5E7E), Color(0xFF8A5CFF)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(IosIcons.Folder, null, tint = Color.White, modifier = Modifier.size(58.dp))
    }
}

@Composable
fun FilesRoot(store: FileStore) {
    val ctx = LocalContext.current
    val app = rememberAppState(ctx, store)
    val updates = rememberUpdateCenter()
    LaunchedEffect(store.version) { updates.tick++ }
    CompositionLocalProvider(LocalApp provides app, LocalFileStore provides store, LocalUpdates provides updates) {
        BackHandler(enabled = app.selecting || app.canPop || app.tab != FilesTab.Browse || app.side != null) {
            when {
                app.selecting -> app.clearSelection()
                app.canPop -> app.pop()
                else -> app.selectTab(FilesTab.Browse)
            }
        }
        val c = LocalIos.current
        val tablet = isTablet()
        Box(Modifier.fillMaxSize().background(c.bg)) {
            if (tablet) {
                SplitLayout(sidebar = { FilesSidebar() }) { FilesPane() }
            } else {
                when (app.tab) {
                    FilesTab.Browse -> BrowseScreen()
                    FilesTab.Recents -> RecentsScreen()
                    FilesTab.Search -> SearchScreen()
                }
                app.stack.forEach { e ->
                    key(e.id) {
                        if (e.route !is Route.Picker) RouteHost(e)
                    }
                }
                if (!app.selecting && app.top !is Route.Settings) {
                    FloatingTabBar(app.tab, { app.selectTab(it) }, Modifier.align(Alignment.BottomCenter))
                }
            }
            app.stack.lastOrNull { it.route is Route.Picker }?.let { e ->
                key(e.id) {
                    val r = e.route as Route.Picker
                    SheetIn(e.closing) {
                        PickerScreen(
                            title = "${r.mode.verb} ${Fmt.items(r.paths.size)}",
                            action = r.mode.verb,
                            exclude = r.paths,
                            onCancel = { app.close(e) },
                        ) { dest ->
                            app.moveOrCopy(r.mode, r.paths, dest) { app.close(e); app.clearSelection() }
                        }
                    }
                }
            }
            Dialogs()
            TransferPill(Modifier.align(Alignment.TopCenter))
            AutoUpdatePrompt("Files")
        }
    }
}

@Composable
fun RouteHost(e: RouteEntry) {
    SlideIn(e.closing, e.instant) {
        when (val r = e.route) {
            is Route.Folder -> FolderScreen(r.path)
            is Route.Tag -> TagScreen(r.tag)
            Route.Trash -> TrashScreen()
            is Route.QuickLook -> QuickLookScreen(r.path)
            is Route.Smb -> SmbScreen(r.serverId, r.path)
            is Route.Cloud -> CloudScreen(r.authority, r.title, r.dirId, r.name)
            is Route.Settings -> FilesSettingsScreen(r.about)
            is Route.Picker -> Unit
        }
    }
}

@Composable
fun SlideIn(closing: Boolean = false, instant: Boolean = false, content: @Composable () -> Unit) {
    val p = remember { Animatable(if (instant) 1f else 0f) }
    LaunchedEffect(closing) {
        if (closing) p.animateTo(0f, tween(240, easing = androidx.compose.animation.core.FastOutLinearInEasing))
        else p.animateTo(1f, tween(420, easing = androidx.compose.animation.core.CubicBezierEasing(0.22f, 1f, 0.36f, 1f)))
    }
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            translationX = (1f - p.value) * size.width
            shadowElevation = 12f * p.value
        }.blockTouches(),
    ) { content() }
}

/** Picker presented like an iOS sheet: slides up over a dimmed screen with rounded corners. */
@Composable
fun SheetIn(closing: Boolean, content: @Composable () -> Unit) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(closing) {
        if (closing) p.animateTo(0f, tween(260, easing = androidx.compose.animation.core.FastOutLinearInEasing))
        else p.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.86f, stiffness = 320f))
    }
    val tablet = isTablet()
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = p.value.coerceIn(0f, 1f) }.background(Color(0x80000000)).blockTouches())
        if (tablet) {
            // iPad form sheet: a centred card that rises from the bottom.
            Box(
                Modifier.align(Alignment.Center).systemBarsPadding().padding(vertical = 24.dp)
                    .widthIn(max = 600.dp).fillMaxWidth(0.9f).fillMaxHeight(0.88f)
                    .graphicsLayer { translationY = (1f - p.value) * 1600f }
                    .shadow(30.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).blockTouches(),
            ) { content() }
            return@Box
        }
        Box(
            Modifier.fillMaxSize().statusBarsPadding().padding(top = 10.dp)
                .graphicsLayer { translationY = (1f - p.value) * size.height }
                .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)).blockTouches(),
        ) { content() }
    }
}

/** iOS-style progress pill: spinner while working, then a check mark. */
@Composable
private fun TransferPill(modifier: Modifier) {
    val app = LocalApp.current
    val c = LocalIos.current
    val t = app.transfer
    var last by remember { mutableStateOf<AppState.Transfer?>(null) }
    if (t != null) last = t
    androidx.compose.animation.AnimatedVisibility(
        visible = t != null,
        modifier = modifier.statusBarsPadding().padding(top = 8.dp),
        enter = androidx.compose.animation.slideInVertically { -it * 2 } + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.slideOutVertically { -it * 2 } + androidx.compose.animation.fadeOut(),
    ) {
        val shown = last ?: return@AnimatedVisibility
        val (bg, border, _) = glass()
        Row(
            Modifier.shadow(14.dp, CircleShape, clip = false).clip(CircleShape).background(bg)
                .border(0.5.dp, border, CircleShape).padding(start = 12.dp, end = 18.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.animation.Crossfade(shown.finished, label = "pill") { done ->
                if (done) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(Color(0xFF34C759)), contentAlignment = Alignment.Center) {
                        Icon(IosIcons.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                } else {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), color = c.label, strokeWidth = 2.5.dp)
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(if (shown.finished) shown.done else shown.working, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.label, maxLines = 1)
        }
    }
}

@Composable
private fun Dialogs() {
    val app = LocalApp.current
    app.renameTarget?.let { item ->
        NameDialog("Rename", item.name, "Done", onDismiss = { app.renameTarget = null }) { app.rename(item, it) }
    }
    app.newFolderIn?.let { dir ->
        NameDialog("New Folder", "untitled folder", "Create", onDismiss = { app.newFolderIn = null }) { app.newFolder(dir, it) }
    }
    app.infoItem?.let { InfoSheet(it) { app.infoItem = null } }
    app.tagTargets?.let { TagSheet(it) { app.tagTargets = null } }
    if (app.connectServer) ConnectServerDialog { app.connectServer = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfoSheet(item: FileItem, onDismiss: () -> Unit) {
    val c = LocalIos.current
    val store = LocalFileStore.current
    var folderInfo by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    LaunchedEffect(item.path) { if (item.isDir) folderInfo = store.folderSize(item.path) }
    ModalBottomSheet(
        onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = if (c.dark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            FileIcon(item, Modifier.size(120.dp), px = 384, rounded = 8.dp)
            Spacer(Modifier.height(12.dp))
            Text(item.name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.label, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(
                if (item.isDir) folderInfo?.let { "Folder · ${Fmt.size(it.first)}" } ?: "Folder · Calculating…"
                else "${kindLabel(item)} · ${Fmt.size(item.size)}",
                fontSize = 14.sp, color = c.secondary,
            )
            Spacer(Modifier.height(18.dp))
            GroupCard {
                InfoRow("Kind", kindLabel(item))
                InfoRow("Size", if (item.isDir) folderInfo?.let { "${Fmt.size(it.first)} (${Fmt.items(it.second)})" } ?: "…" else Fmt.size(item.size))
                InfoRow("Where", File(item.path).parent?.removePrefix(store.root)?.ifEmpty { "/" }?.let { "${store.deviceName}$it" } ?: "")
                InfoRow("Modified", Fmt.full(item.modified), last = true)
            }
            val tags = store.tagsOf(item.path)
            if (tags.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tags.forEach { t ->
                        Row(
                            Modifier.clip(CircleShape).background(Color(t.argb).copy(alpha = 0.15f)).padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(Color(t.argb)))
                            Spacer(Modifier.width(6.dp))
                            Text(t.label, fontSize = 13.sp, color = c.label)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, last: Boolean = false) {
    val c = LocalIos.current
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(label, fontSize = 15.sp, color = c.secondary, modifier = Modifier.width(90.dp))
            Text(value, fontSize = 15.sp, color = c.label, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
        if (!last) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagSheet(paths: List<String>, onDismiss: () -> Unit) {
    val c = LocalIos.current
    val store = LocalFileStore.current
    ModalBottomSheet(onDismiss, containerColor = if (c.dark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 20.dp)) {
            Text("Tags", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
            Text(if (paths.size == 1) File(paths[0]).name else Fmt.items(paths.size), fontSize = 13.sp, color = c.secondary,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp), maxLines = 1)
            GroupCard {
                TagColor.entries.forEachIndexed { i, t ->
                    val on = paths.all { t in store.tagsOf(it) }
                    GroupRow(
                        icon = { Box(Modifier.size(14.dp).clip(CircleShape).background(Color(t.argb))) },
                        title = t.label, last = i == TagColor.entries.lastIndex, chevron = false,
                        detail = if (on) "✓" else null,
                    ) { store.toggleTag(paths, t) }
                }
            }
        }
    }
}

// ---------- Destination picker (Move / Copy / Save to Files) ----------

@Composable
fun PickerScreen(title: String, action: String, exclude: List<String> = emptyList(), onCancel: () -> Unit, onPick: (String) -> Unit) {
    val store = LocalFileStore.current
    val c = LocalIos.current
    val stack = remember { mutableStateListOf<String>() }
    val current = stack.lastOrNull()
    var folders by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var newFolder by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(current, reload, store.version) {
        folders = if (current == null) emptyList()
        else withContext(Dispatchers.IO) { store.list(current).filter { it.isDir && it.path !in exclude } }
    }
    BackHandler { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else onCancel() }
    Box(Modifier.fillMaxSize().background(if (c.dark) c.bg else c.grouped).blockTouches()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 0.dp, bottom = 120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            item {
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 62.dp, bottom = 12.dp)) {
                    Text(current?.let { store.displayName(it) } ?: "Choose a Folder", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = c.label, maxLines = 2)
                    Text(title, fontSize = 14.sp, color = c.secondary)
                }
            }
            if (current == null) {
                item {
                    GroupCard {
                        val vols = store.volumes()
                        val favs = store.favorites.filter { File(it).isDirectory }
                        val rows = vols.map { it.title to it.path } + favs.map { store.displayName(it) to it }
                        rows.forEachIndexed { i, (name, p) ->
                            GroupRow(
                                icon = { Icon(if (i < vols.size) IosIcons.Phone else IosIcons.Folder, null, tint = c.label, modifier = Modifier.size(24.dp)) },
                                title = name, last = i == rows.lastIndex,
                            ) { stack += p }
                        }
                    }
                }
            } else {
                item {
                    GroupCard {
                        if (folders.isEmpty()) {
                            Text("No folders here", fontSize = 15.sp, color = c.secondary, modifier = Modifier.padding(16.dp))
                        }
                        folders.forEachIndexed { i, f ->
                            GroupRow(
                                icon = { FolderIcon(Modifier.size(28.dp)) },
                                title = f.name, last = i == folders.lastIndex,
                            ) { stack += f.path }
                        }
                    }
                }
            }
        }
        ScreenTopBar(title, false, onBack = if (stack.isNotEmpty()) ({ stack.removeAt(stack.lastIndex) }) else null) {
            GlassPill("Cancel") { onCancel() }
        }
        if (current != null) {
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassCircle(IosIcons.FolderPlus, size = 50.dp) { newFolder = true }
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier.weight(1f).height(50.dp).clip(CircleShape).background(c.accent).tap { onPick(current) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(action, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    if (newFolder && current != null) {
        val app = LocalApp.current
        NameDialog("New Folder", "untitled folder", "Create", onDismiss = { newFolder = false }) { name ->
            newFolder = false
            app.newFolder(current, name)
            reload++
        }
    }
}
