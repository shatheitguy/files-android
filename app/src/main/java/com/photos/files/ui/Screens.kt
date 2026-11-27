package com.photos.files.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photos.files.data.FileItem
import com.photos.files.data.TagColor
import com.photos.files.data.TrashEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

// ---------- Browse ----------

@Composable
fun BrowseScreen() {
    val app = LocalApp.current
    val store = LocalFileStore.current
    val c = LocalIos.current
    val volumes = remember(store.version) { store.volumes() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val smb = remember { com.photos.files.data.SmbStore.get(ctx) }
    val clouds = rememberCloudDrives()
    var editing by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(if (c.dark) c.bg else c.grouped)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = TabBarSpace + 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Box(Modifier.fillMaxWidth()) {
                    LargeHeader("Browse")
                    Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 8.dp, end = 16.dp)) {
                        BrowseMoreButton(editing, onEdit = { editing = !editing }, onConnect = { app.connectServer = true })
                    }
                }
            }
            item {
                SearchFieldStub { app.selectTab(FilesTab.Search) }
            }
            item { SectionTitle("Locations", collapsible = true) }
            item {
                Collapsible("Locations") {
                GroupCard {
                    val locs = orderedLocations(volumes, clouds)
                    locs.forEachIndexed { i, l ->
                        val arrows: (@Composable () -> Unit)? = if (editing) ({ MoveArrows(locs, i) }) else null
                        when (l) {
                            is Location.Vol -> GroupRow(
                                icon = {
                                    Icon(
                                        when { l.v.usb -> IosIcons.Usb; l.v.removable -> IosIcons.SdCard; else -> IosIcons.Phone }, null,
                                        tint = c.label, modifier = Modifier.size(24.dp),
                                    )
                                },
                                title = l.v.title,
                                detail = "${Fmt.size(store.freeSpace(l.v.path))} free",
                                trailing = arrows,
                            ) { if (!editing) app.push(Route.Folder(l.v.path)) }
                            // Cloud drives on this phone (Nextcloud, OneDrive, Google Drive…), only when installed.
                            is Location.Drive -> GroupRow(icon = { CloudIcon(l.d) }, title = l.d.title, trailing = arrows) {
                                if (!editing) openCloud(ctx, l.d) { app.toast(it) }
                            }
                        }
                    }
                    GroupRow(
                        icon = { Icon(IosIcons.Trash, null, tint = c.label, modifier = Modifier.size(24.dp)) },
                        title = "Recently Deleted",
                        last = true,
                    ) { app.push(Route.Trash) }
                }
            }
            }
            val servers = smb.servers
            if (servers.isNotEmpty()) {
                item { SectionTitle("Shared", collapsible = true) }
                item {
                Collapsible("Shared") {
                    GroupCard {
                        servers.forEachIndexed { i, s ->
                            GroupRow(
                                icon = {
                                    if (editing) RemoveDot { smb.remove(s.id) }
                                    else Icon(IosIcons.Server, null, tint = c.label, modifier = Modifier.size(24.dp))
                                },
                                title = s.title,
                                detail = if (s.guest) "Guest" else s.user,
                                last = i == servers.lastIndex,
                                chevron = !editing,
                            ) { if (!editing) app.push(Route.Smb(s.id, "")) }
                        }
                    }
                }
            }
            }
            val favs = store.favorites.filter { File(it).isDirectory }
            if (favs.isNotEmpty()) {
                item { SectionTitle("Favorites", collapsible = true) }
                item {
                Collapsible("Favorites") {
                    GroupCard {
                        favs.forEachIndexed { i, p ->
                            var menu by remember { mutableStateOf(false) }
                            Box {
                                GroupRow(
                                    icon = {
                                        if (editing) RemoveDot { store.toggleFavorite(p) }
                                        else Icon(
                                            if (p.endsWith("/Download")) IosIcons.Download else IosIcons.Folder, null,
                                            tint = c.label, modifier = Modifier.size(24.dp),
                                        )
                                    },
                                    title = store.displayName(p),
                                    last = i == favs.lastIndex,
                                    chevron = !editing,
                                    onLongClick = { menu = true },
                                ) { if (!editing) app.push(Route.Folder(p)) }
                                IosMenu(menu, { menu = false }, listOf(
                                    MenuAction("Remove from Favorites", IosIcons.Star, destructive = true) { store.toggleFavorite(p) },
                                ), spotlight = true)
                            }
                        }
                    }
                }
            }
            }
            item { SectionTitle("Tags", collapsible = true) }
            item {
                Collapsible("Tags") {
                GroupCard {
                    TagColor.entries.forEachIndexed { i, t ->
                        GroupRow(
                            icon = { Box(Modifier.size(14.dp).clip(CircleShape).background(Color(t.argb))) },
                            title = t.label,
                            last = i == TagColor.entries.lastIndex,
                        ) { app.push(Route.Tag(t)) }
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun SearchFieldStub(onClick: () -> Unit) {
    val c = LocalIos.current
    Row(
        Modifier.widthIn(max = 720.dp).padding(horizontal = 16.dp).fillMaxWidth().height(40.dp).clip(RoundedCornerShape(12.dp))
            .background(if (c.dark) Color(0xFF1C1C1E) else Color(0xFFE3E3E8)).tap(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(IosIcons.Search, null, tint = c.secondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Search", fontSize = 17.sp, color = c.secondary)
    }
}

// ---------- Recents ----------

@Composable
fun RecentsScreen() {
    val store = LocalFileStore.current
    val app = LocalApp.current
    val c = LocalIos.current
    var items by remember { mutableStateOf<List<FileItem>?>(null) }
    LaunchedEffect(store.version) { items = store.recents() }
    val grid = rememberLazyGridState()
    val scrolled by remember { derivedStateOf { grid.firstVisibleItemIndex > 0 } }
    Box(Modifier.fillMaxSize().background(c.bg)) {
        val list = items ?: emptyList()
        FileCollection(list, store.listView, grid) {
            item(span = { GridItemSpan(maxLineSpan) }) { LargeHeader("Recents") }
        }
        if (items != null && list.isEmpty()) EmptyState("No Recent Files", "Files you open or change will appear here.")
        ScreenTopBar("Recents", scrolled, null, leading = if (app.selecting) ({ SelectAllPill(list) }) else null) {
            if (app.selecting) GlassPill("Done") { app.clearSelection() }
            else {
                GlassPill("Select") { app.startSelecting() }
                ViewMenuButton(showSort = false, folder = null)
            }
        }
        if (app.selecting) SelectionBar(list, Modifier.align(Alignment.BottomCenter))
    }
}

// ---------- Folder ----------

@Composable
fun FolderScreen(path: String) {
    val store = LocalFileStore.current
    val app = LocalApp.current
    val c = LocalIos.current
    var items by remember { mutableStateOf<List<FileItem>?>(null) }
    var missing by remember { mutableStateOf(false) }
    LaunchedEffect(path, store.version, store.sortBy, store.ascending, store.showHidden) {
        missing = !withContext(Dispatchers.IO) { File(path).isDirectory }
        items = store.list(path)
    }
    val title = remember(path) { store.displayName(path) }
    val grid = rememberLazyGridState()
    val scrolled by remember { derivedStateOf { grid.firstVisibleItemIndex > 0 } }
    val list = items ?: emptyList()
    val footer = remember(list, store.version) { "${Fmt.items(list.size)}, ${Fmt.size(store.freeSpace(path))} available" }
    Box(Modifier.fillMaxSize().background(c.bg)) {
        FileCollection(list, store.listView, grid, footer = if (items != null && list.isNotEmpty()) footer else null) {
            item(span = { GridItemSpan(maxLineSpan) }) { LargeHeader(title) }
        }
        if (items != null && list.isEmpty()) {
            EmptyState(if (missing) "Folder Not Found" else "Folder Is Empty", if (missing) "It may have been moved or deleted." else footer)
        }
        ScreenTopBar(title, scrolled, onBack = backUnlessRoot { app.pop() }, leading = if (app.selecting) ({ SelectAllPill(list) }) else null) {
            if (app.selecting) {
                GlassPill("Done") { app.clearSelection() }
            } else {
                GlassPill("Select", enabled = list.isNotEmpty()) { app.startSelecting() }
                ViewMenuButton(showSort = true, folder = path)
            }
        }
        if (app.selecting) SelectionBar(list, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
fun ViewMenuButton(showSort: Boolean, folder: String?) {
    val store = LocalFileStore.current
    val prefs = AppPrefs.get(androidx.compose.ui.platform.LocalContext.current)
    val app = LocalApp.current
    var open by remember { mutableStateOf(false) }
    Box {
        GlassCircle(IosIcons.More) { open = true }
        val actions = buildList {
            if (folder != null) add(MenuAction("New Folder", IosIcons.FolderPlus) { app.newFolderIn = folder })
            add(MenuAction("Icons", IosIcons.Grid, divider = true, checked = !store.listView) { if (store.listView) store.toggleListView() })
            add(MenuAction("List", IosIcons.ListIcon, checked = store.listView) { if (!store.listView) store.toggleListView() })
            val aspect = prefs.flag(FilesPrefKeys.AspectThumbs, true)
            add(MenuAction("Aspect Ratio Thumbnails", IosIcons.Panorama, checked = aspect) { prefs.setFlag(FilesPrefKeys.AspectThumbs, !aspect) })
            if (showSort) {
                com.photos.files.data.SortBy.entries.forEachIndexed { i, s ->
                    val mark = if (store.sortBy == s) (if (store.ascending) "  ↑" else "  ↓") else ""
                    add(MenuAction(s.label + mark, IosIcons.Sort, divider = i == 0, checked = store.sortBy == s) { store.setSort(s) })
                }
                add(MenuAction(if (store.showHidden) "Hide Hidden Files" else "Show Hidden Files", IosIcons.Eye, divider = true) { store.toggleHidden() })
            }
            if (folder != null) {
                val fav = store.isFavorite(folder)
                add(MenuAction(if (fav) "Remove from Favorites" else "Add to Favorites", IosIcons.Star) { store.toggleFavorite(folder) })
            }
        }
        IosMenu(open, { open = false }, actions)
    }
}

@Composable
fun BoxScope.EmptyState(title: String, sub: String) {
    val c = LocalIos.current
    Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.label, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(sub, fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center)
    }
}

// ---------- Search ----------

@Composable
fun SearchScreen() {
    val store = LocalFileStore.current
    val app = LocalApp.current
    val c = LocalIos.current
    var q by rememberSaveableQuery()
    var results by remember { mutableStateOf<List<FileItem>?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(q, store.version) {
        if (q.isBlank()) { results = null; return@LaunchedEffect }
        delay(250)
        busy = true
        results = store.search(q)
        busy = false
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(200); runCatching { focus.requestFocus() } }
    val list = results ?: emptyList()
    Box(Modifier.fillMaxSize().background(c.bg)) {
        FileCollection(list, true, subtitle = { it.parentFile() + " · " + Fmt.subtitle(it) }) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    LargeHeader("Search")
                    Row(
                        Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(42.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (c.dark) Color(0xFF1C1C1E) else Color(0xFFE9E9EE)).padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(IosIcons.Search, null, tint = c.secondary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.weight(1f)) {
                            if (q.isEmpty()) Text("Files and folders", fontSize = 17.sp, color = c.secondary)
                            BasicTextField(
                                q, { q = it }, singleLine = true,
                                textStyle = TextStyle(fontSize = 17.sp, color = c.label),
                                cursorBrush = SolidColor(c.label),
                                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                            )
                        }
                        if (q.isNotEmpty()) {
                            Box(Modifier.size(20.dp).clip(CircleShape).background(c.tertiary).tap { q = "" }, contentAlignment = Alignment.Center) {
                                Text("✕", fontSize = 11.sp, color = c.bg, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    if (results != null) {
                        Text(
                            if (busy) "Searching…" else "${Fmt.items(list.size)} found", fontSize = 13.sp, color = c.secondary,
                            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                }
            }
        }
        if (q.isBlank()) {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Search your files", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c.label)
                Spacer(Modifier.height(4.dp))
                Text("Find files and folders by name.", fontSize = 15.sp, color = c.secondary)
            }
        } else if (results != null && list.isEmpty() && !busy) {
            EmptyState("No Results", "Nothing matches “$q”.")
        }
        if (app.selecting) SelectionBar(list, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun rememberSaveableQuery() = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }

private fun FileItem.parentFile(): String = File(path).parentFile?.name ?: ""

// ---------- Tag ----------

@Composable
fun TagScreen(tag: TagColor) {
    val store = LocalFileStore.current
    val app = LocalApp.current
    val c = LocalIos.current
    var items by remember { mutableStateOf<List<FileItem>?>(null) }
    LaunchedEffect(store.version, store.tags) { items = withContext(Dispatchers.IO) { store.sort(store.tagged(tag)) } }
    val grid = rememberLazyGridState()
    val scrolled by remember { derivedStateOf { grid.firstVisibleItemIndex > 0 } }
    val list = items ?: emptyList()
    Box(Modifier.fillMaxSize().background(c.bg)) {
        FileCollection(list, store.listView, grid) {
            item(span = { GridItemSpan(maxLineSpan) }) { LargeHeader(tag.label) }
        }
        if (items != null && list.isEmpty()) EmptyState("No Items", "Long-press a file and choose Tags to add it here.")
        ScreenTopBar(tag.label, scrolled, onBack = backUnlessRoot { app.pop() }, leading = if (app.selecting) ({ SelectAllPill(list) }) else null) {
            if (app.selecting) GlassPill("Done") { app.clearSelection() }
            else { GlassPill("Select", enabled = list.isNotEmpty()) { app.startSelecting() }; ViewMenuButton(showSort = false, folder = null) }
        }
        if (app.selecting) SelectionBar(list, Modifier.align(Alignment.BottomCenter))
    }
}

// ---------- Recently Deleted ----------

@Composable
fun TrashScreen() {
    val store = LocalFileStore.current
    val app = LocalApp.current
    val c = LocalIos.current
    var entries by remember { mutableStateOf<List<TrashEntry>?>(null) }
    LaunchedEffect(store.version) { entries = store.trashEntries() }
    val list = entries ?: emptyList()
    val byPath = remember(list) { list.associateBy { it.trashPath } }
    val items = remember(list) { list.map { it.item.copy(name = File(it.original).name) } }
    val grid = rememberLazyGridState()
    val scrolled by remember { derivedStateOf { grid.firstVisibleItemIndex > 0 } }
    fun daysLeft(e: TrashEntry): String {
        val d = 30 - ((System.currentTimeMillis() - e.deletedAt) / 86_400_000L).toInt()
        return if (d <= 1) "1 day" else "$d days"
    }
    Box(Modifier.fillMaxSize().background(c.bg)) {
        FileCollection(
            items, store.listView, grid,
            subtitle = { byPath[it.path]?.let(::daysLeft) ?: "" },
            menu = { item ->
                val e = byPath[item.path]
                if (e == null) emptyList() else listOf(
                    MenuAction("Recover", IosIcons.Move) { app.restore(listOf(e)) },
                    MenuAction("Delete Now", IosIcons.Trash, destructive = true) { app.confirmDelete = listOf(e) },
                )
            },
            onOpen = { /* items in the trash open their menu via long press */ },
            footer = if (list.isNotEmpty()) "Items are kept for 30 days, then deleted for good." else null,
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { LargeHeader("Recently Deleted") }
        }
        if (entries != null && list.isEmpty()) EmptyState("No Recently Deleted Items", "Deleted files stay here for 30 days.")
        ScreenTopBar("Recently Deleted", scrolled, onBack = backUnlessRoot { app.pop() }) {
            if (app.selecting) GlassPill("Done") { app.clearSelection() }
            else GlassPill("Select", enabled = list.isNotEmpty()) { app.startSelecting() }
        }
        if (app.selecting) {
            val chosen = list.filter { it.trashPath in app.selected }
            val target = chosen.ifEmpty { list }
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassPill(if (chosen.isEmpty()) "Delete All" else "Delete", color = c.red, enabled = target.isNotEmpty(), height = 50.dp) {
                    app.confirmDelete = target
                }
                Spacer(Modifier.weight(1f))
                GlassPill(if (chosen.isEmpty()) "Recover All" else "Recover", color = c.label, enabled = target.isNotEmpty(), height = 50.dp) {
                    app.restore(target)
                }
            }
        }
    }
    app.confirmDelete?.let { del ->
        ConfirmDialog(
            title = if (del.size == 1) "Delete this item?" else "Delete ${del.size} items?",
            message = "This can't be undone.",
            confirm = "Delete",
            onDismiss = { app.confirmDelete = null },
        ) { app.deleteForever(del) }
    }
}

// ---------- Quick Look (text) ----------

@Composable
fun QuickLookScreen(path: String) {
    val app = LocalApp.current
    val c = LocalIos.current
    val item = remember(path) { FileItem.of(File(path)) }
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(path) {
        text = withContext(Dispatchers.IO) { runCatching { File(path).readText() }.getOrElse { "Couldn't read this file." } }
    }
    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(top = 66.dp)) {
            SelectionContainer {
                Text(
                    text ?: "", fontSize = 13.sp, lineHeight = 19.sp, color = c.label, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding(),
                )
            }
        }
        ScreenTopBar(item.name, true, onBack = { app.pop() }) {
            GlassCircle(IosIcons.Share) { app.share(listOf(path)) }
            GlassCircle(IosIcons.More) { app.launchView(app.uriFor(path), item.mime, null) }
        }
    }
}

/** "Select All" / "Deselect All" shown on the left while selecting, like iOS Files. */
@Composable
fun SelectAllPill(list: List<FileItem>) {
    val app = LocalApp.current
    val all = list.isNotEmpty() && list.all { it.path in app.selected }
    GlassPill(if (all) "Deselect All" else "Select All", enabled = list.isNotEmpty()) {
        app.setSelection(if (all) emptySet() else list.map { it.path }.toSet())
    }
}

@Composable
fun RemoveDot(onClick: () -> Unit) {
    Box(
        Modifier.size(22.dp).clip(CircleShape).background(LocalIos.current.red).tap(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(width = 11.dp, height = 2.dp).background(Color.White))
    }
}
