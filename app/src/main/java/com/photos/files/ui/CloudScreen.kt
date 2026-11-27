package com.photos.files.ui

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photos.files.data.Cloud
import com.photos.files.data.CloudDrive
import com.photos.files.data.CloudEntry
import com.photos.files.data.FileItem
import kotlinx.coroutines.launch
import java.io.File

/** The cloud app's own icon, rounded like the Locations icons in iOS Files. */
@Composable
fun CloudIcon(d: CloudDrive, size: Dp = 24.dp) {
    val img = remember(d.pkg) { d.icon?.asImageBitmap() }
    if (img != null) Image(img, null, Modifier.size(size).clip(RoundedCornerShape(size / 4.5f)))
    else androidx.compose.material3.Icon(IosIcons.Server, null, tint = LocalIos.current.label, modifier = Modifier.size(size))
}

/** Cloud drives that are on this phone. Re-read when Files comes back to the front, in case one was installed. */
@Composable
fun rememberCloudDrives(): List<CloudDrive> {
    val ctx = LocalContext.current
    val store = LocalFileStore.current
    return remember(store.version) { Cloud.drives(ctx) }
}

/** A folder in a cloud drive (Nextcloud, OneDrive, Google Drive…). [dirId] empty = the folder you picked for it. */
@Composable
fun CloudScreen(authority: String, title: String, dirId: String, name: String) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val c = LocalIos.current
    val store = LocalFileStore.current
    val scope = rememberCoroutineScope()
    var tree by remember(authority) { mutableStateOf(Cloud.tree(ctx, authority)) }
    var entries by remember { mutableStateOf<List<CloudEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<CloudEntry?>(null) }
    val root = dirId.isEmpty()

    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (uri.authority != authority) { app.toast("That folder isn't in $title. Pick one from $title."); return@rememberLauncherForActivityResult }
        Cloud.keep(ctx, uri)
        tree = Cloud.tree(ctx, authority) ?: uri
    }
    fun pickFolder() = runCatching { choose.launch(DocumentsContract.buildRootsUri(authority)) }
        .onFailure { app.toast("Couldn't open the folder picker.") }
    // First visit: go straight to the picker, like signing in to a location on iOS.
    LaunchedEffect(Unit) { if (tree == null && root) pickFolder() }

    val t = tree
    val here = if (t == null) "" else dirId.ifEmpty { Cloud.rootId(t) }
    LaunchedEffect(t, here, reload) {
        if (t == null) return@LaunchedEffect
        error = null
        runCatching { Cloud.list(ctx, t, here) }
            .onSuccess { entries = it.sortedWith(compareBy<CloudEntry>({ !it.isDir }, { it.name.lowercase() })) }
            .onFailure { error = it.message ?: "Couldn't open this folder."; entries = null }
    }

    val screenTitle = if (root) title else name
    val byKey = remember(entries) { entries.orEmpty().associateBy { "cloud:" + it.docId } }
    val items = remember(entries) { entries.orEmpty().map { it.asItem() } }

    fun run(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = label
        progress = null
        scope.launch {
            runCatching { block() }.onFailure { app.toast(it.message ?: "Something went wrong.") }
            busy = null
            progress = null
        }
    }

    fun openEntry(e: CloudEntry) {
        val tr = tree ?: return
        if (e.isDir) { app.push(Route.Cloud(authority, title, e.docId, e.name)); return }
        run("Opening “${e.name}”…") {
            val dir = File(ctx.cacheDir, "cloud").apply { mkdirs() }
            val local = Cloud.download(ctx, tr, e, File(dir, e.name)) { progress = it }
            app.open(FileItem.of(local))
        }
    }

    fun download(e: CloudEntry) {
        val tr = tree ?: return
        run("Downloading “${e.name}”…") {
            val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
            val target = app.store.uniqueTarget(dl, e.name)
            Cloud.download(ctx, tr, e, target) { progress = it }
            app.store.scan(listOf(target.absolutePath))
            app.store.changed()
            app.toast("Saved to Downloads")
        }
    }

    val upload = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val tr = tree
        if (uris.isNotEmpty() && tr != null) run("Uploading ${Fmt.items(uris.size)}…") {
            val n = Cloud.upload(ctx, tr, here, uris)
            app.toast("Uploaded ${Fmt.items(n)}")
            reload++
        }
    }

    val grid = rememberLazyGridState()
    val scrolled by remember { derivedStateOf { grid.firstVisibleItemIndex > 0 } }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        if (t != null) FileCollection(
            items, store.listView, grid,
            footer = if (entries?.isNotEmpty() == true) Fmt.items(items.size) else null,
            subtitle = { if (it.isDir) "Folder" else "${Fmt.date(it.modified)} · ${Fmt.size(it.size)}" },
            menu = { item ->
                val e = byKey[item.path]
                if (e == null) emptyList() else buildList {
                    if (e.isDir) add(MenuAction("Open", IosIcons.Folder) { openEntry(e) })
                    else {
                        add(MenuAction("Open", IosIcons.Doc) { openEntry(e) })
                        add(MenuAction("Download to Phone", IosIcons.Download) { download(e) })
                    }
                    add(MenuAction("Delete", IosIcons.Trash, destructive = true, divider = true) { confirmDelete = e })
                }
            },
            onOpen = { item -> byKey[item.path]?.let(::openEntry) },
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { LargeHeader(screenTitle, null) }
        }
        when {
            t == null -> Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                rememberCloudDrives().firstOrNull { it.authority == authority }?.let { CloudIcon(it, 64.dp) }
                Spacer(Modifier.height(16.dp))
                Text("Connect $title", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.label)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Pick the $title folder Files can open. You only do this once.\n" +
                        "If $title doesn't let you pick a folder, open it in the $title app.",
                    fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(18.dp))
                GlassPill("Choose Folder", color = c.label) { pickFolder() }
                val launch = remember(authority) {
                    rememberCloudPkg(ctx, authority)?.let { ctx.packageManager.getLaunchIntentForPackage(it) }
                }
                if (launch != null) {
                    Spacer(Modifier.height(10.dp))
                    GlassPill("Open $title", color = c.label) {
                        runCatching { ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                }
            }
            error != null -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Can't Open $title", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.label)
                Spacer(Modifier.height(6.dp))
                Text(error!!, fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                GlassPill("Try Again", color = c.label) { reload++ }
            }
            entries == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = c.secondary)
            items.isEmpty() -> EmptyState("Folder Is Empty", "Tap ••• to upload files.")
        }
        ScreenTopBar(screenTitle, scrolled, onBack = backUnlessRoot { app.pop() }) {
            var open by remember { mutableStateOf(false) }
            Box {
                GlassCircle(IosIcons.More) { open = true }
                IosMenu(open, { open = false }, buildList {
                    if (t != null) {
                        add(MenuAction("Upload Files", IosIcons.Share) { upload.launch("*/*") })
                        add(MenuAction("New Folder", IosIcons.FolderPlus) { newFolder = true })
                        add(MenuAction("Icons" + if (!store.listView) "  ✓" else "", IosIcons.Grid, divider = true) { if (store.listView) store.toggleListView() })
                        add(MenuAction("List" + if (store.listView) "  ✓" else "", IosIcons.ListIcon) { if (!store.listView) store.toggleListView() })
                        add(MenuAction("Refresh", IosIcons.Clock) { reload++ })
                    }
                    if (root) {
                        add(MenuAction("Choose Another Folder", IosIcons.Folder, divider = t != null) { pickFolder() })
                        if (t != null) add(MenuAction("Disconnect $title", IosIcons.Trash, destructive = true) {
                            Cloud.forget(ctx, authority); tree = null; entries = null
                        })
                    }
                })
            }
        }
        busy?.let { label ->
            Box(Modifier.fillMaxSize().background(Color(0x66000000)).blockTouches(), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.padding(40.dp).clip(RoundedCornerShape(16.dp))
                        .background(if (c.dark) Color(0xFF2C2C2E) else Color.White).padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(label, fontSize = 15.sp, color = c.label, maxLines = 2)
                    Spacer(Modifier.height(14.dp))
                    val p = progress
                    if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.width(200.dp), color = c.label, trackColor = c.fill)
                    else LinearProgressIndicator(modifier = Modifier.width(200.dp), color = c.label, trackColor = c.fill)
                }
            }
        }
    }
    if (newFolder) {
        NameDialog("New Folder", "untitled folder", "Create", onDismiss = { newFolder = false }) { n ->
            newFolder = false
            val tr = tree ?: return@NameDialog
            run("Creating folder…") { Cloud.mkdir(ctx, tr, here, n); reload++ }
        }
    }
    confirmDelete?.let { e ->
        ConfirmDialog(
            "Delete “${e.name}”?", "It will be deleted from $title. This can't be undone.", "Delete",
            onDismiss = { confirmDelete = null },
        ) {
            confirmDelete = null
            val tr = tree ?: return@ConfirmDialog
            run("Deleting…") { Cloud.delete(ctx, tr, e); reload++ }
        }
    }
}

private fun rememberCloudPkg(ctx: android.content.Context, authority: String): String? =
    runCatching { ctx.packageManager.resolveContentProvider(authority, 0)?.packageName }.getOrNull()
