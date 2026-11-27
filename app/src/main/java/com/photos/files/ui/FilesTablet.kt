package com.photos.files.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.photos.files.data.SmbStore
import com.photos.files.data.TagColor
import java.io.File

/** The iPad Files sidebar: Recents, Search, Locations, Shared, Favorites and Tags. */
@Composable
fun FilesSidebar() {
    val app = LocalApp.current
    val store = LocalFileStore.current
    val ctx = LocalContext.current
    val volumes = remember(store.version) { store.volumes() }
    val smb = remember { SmbStore.get(ctx) }
    var editing by remember { mutableStateOf(false) }
    val side = app.side
    val onTab = side == null
    Column(Modifier.fillMaxSize()) {
        SidebarHeader("Files") {
            BrowseMoreButton(editing, onEdit = { editing = !editing }, onConnect = { app.connectServer = true })
        }
        SidebarList {
            item { SidebarRow("Recents", IosIcons.Clock, selected = onTab && app.tab == FilesTab.Recents) { app.selectTab(FilesTab.Recents) } }
            item { SidebarRow("Search", IosIcons.Search, selected = onTab && app.tab == FilesTab.Search) { app.selectTab(FilesTab.Search) } }
            sidebarSection("loc", "Locations") {
                volumes.forEachIndexed { i, v ->
                    val icon = when { v.usb -> IosIcons.Usb; v.removable -> IosIcons.SdCard; else -> IosIcons.Phone }
                    if (i == 0) {
                        SidebarRow(v.title, icon, selected = onTab && app.tab == FilesTab.Browse) { app.selectTab(FilesTab.Browse) }
                    } else {
                        val r = Route.Folder(v.path)
                        SidebarRow(v.title, icon, selected = side == r) { app.selectSide(r) }
                    }
                }
                SidebarRow("Recently Deleted", IosIcons.Trash, selected = side == Route.Trash) { app.selectSide(Route.Trash) }
            }
            val servers = smb.servers
            if (servers.isNotEmpty()) {
                sidebarSection("shared", "Shared") {
                    servers.forEach { s ->
                        val r = Route.Smb(s.id, "")
                        SidebarRow(
                            s.title, IosIcons.Server, selected = side == r,
                            leading = if (editing) ({ RemoveDot { smb.remove(s.id) } }) else null,
                        ) { if (!editing) app.selectSide(r) }
                    }
                }
            }
            val favs = store.favorites.filter { File(it).isDirectory }
            if (favs.isNotEmpty()) {
                sidebarSection("fav", "Favorites") {
                    favs.forEach { p ->
                        val r = Route.Folder(p)
                        SidebarRow(
                            store.displayName(p), if (p.endsWith("/Download")) IosIcons.Download else IosIcons.Folder,
                            selected = side == r,
                            leading = if (editing) ({ RemoveDot { store.toggleFavorite(p) } }) else null,
                        ) { if (!editing) app.selectSide(r) }
                    }
                }
            }
            sidebarSection("tags", "Tags") {
                TagColor.entries.forEach { t ->
                    val r = Route.Tag(t)
                    SidebarRow(
                        t.label, selected = side == r,
                        leading = { Box(Modifier.size(14.dp).clip(CircleShape).background(Color(t.argb))) },
                    ) { app.selectSide(r) }
                }
            }
        }
    }
}

/** The right-hand side of the iPad layout: what the sidebar picked, with folders opening on top of it. */
@Composable
fun FilesPane() {
    val app = LocalApp.current
    val store = LocalFileStore.current
    Box(Modifier.fillMaxSize()) {
        Crossfade(app.side to app.tab, animationSpec = tween(180), label = "pane") { (s, tab) ->
            CompositionLocalProvider(LocalPaneRoot provides true) {
                when {
                    s is Route.Folder -> FolderScreen(s.path)
                    s is Route.Tag -> TagScreen(s.tag)
                    s == Route.Trash -> TrashScreen()
                    s is Route.Smb -> SmbScreen(s.serverId, s.path)
                    tab == FilesTab.Recents -> RecentsScreen()
                    tab == FilesTab.Search -> SearchScreen()
                    else -> FolderScreen(store.root)
                }
            }
        }
        app.stack.forEach { e ->
            key(e.id) { if (e.route !is Route.Picker) RouteHost(e) }
        }
    }
}
