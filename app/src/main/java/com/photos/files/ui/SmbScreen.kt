package com.photos.files.ui

import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photos.files.data.FileItem
import com.photos.files.data.SmbEntry
import com.photos.files.data.SmbServer
import com.photos.files.data.SmbStore
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun SmbScreen(serverId: String, path: String) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val smb = remember { SmbStore.get(ctx) }
    val c = LocalIos.current
    val scope = rememberCoroutineScope()
    val server = smb.byId(serverId)
    var entries by remember { mutableStateOf<List<SmbEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<SmbEntry?>(null) }

    LaunchedEffect(serverId, path, reload) {
        if (server == null) { error = "This server was removed."; return@LaunchedEffect }
        error = null
        runCatching { smb.list(server, path) }
            .onSuccess { entries = it }
            .onFailure { error = it.message; entries = null }
    }

    val title = if (path.isEmpty()) server?.title ?: "Server" else path.trimEnd('/').substringAfterLast('/')
    val byKey = remember(entries) { entries.orEmpty().associateBy { "smb:" + it.path } }
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

    fun openEntry(e: SmbEntry) {
        if (server == null) return
        if (e.isDir) { app.push(Route.Smb(serverId, e.path)); return }
        run("Opening “${e.name}”…") {
            val dir = File(ctx.cacheDir, "smb").apply { mkdirs() }
            val local = smb.download(server, e.path, File(dir, e.name)) { progress = it }
            app.open(FileItem.of(local))
        }
    }

    fun download(e: SmbEntry) {
        if (server == null) return
        run("Downloading “${e.name}”…") {
            val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
            val target = app.store.uniqueTarget(dl, e.name)
            smb.download(server, e.path, target) { progress = it }
            app.store.scan(listOf(target.absolutePath))
            app.store.changed()
            app.toast("Saved to Downloads")
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty() && server != null) {
            run("Uploading ${Fmt.items(uris.size)}…") {
                val n = smb.upload(server, path, uris)
                app.toast("Uploaded ${Fmt.items(n)}")
                reload++
            }
        }
    }

    val grid = rememberLazyGridState()
    val scrolled by remember { derivedStateOf { grid.firstVisibleItemIndex > 0 } }
    val store = LocalFileStore.current
    val canWrite = server != null && (path.isNotEmpty() || server.share.isNotBlank())

    Box(Modifier.fillMaxSize().background(c.bg)) {
        FileCollection(
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
                    if (canWrite) add(MenuAction("Delete", IosIcons.Trash, destructive = true, divider = true) { confirmDelete = e })
                }
            },
            onOpen = { item -> byKey[item.path]?.let(::openEntry) },
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { LargeHeader(title, if (path.isEmpty()) "Connected" else null) }
        }
        when {
            error != null -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Can't Open Server", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.label)
                Spacer(Modifier.height(6.dp))
                Text(error!!, fontSize = 15.sp, color = c.secondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                GlassPill("Try Again", color = c.label) { reload++ }
            }
            entries == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = c.secondary)
            items.isEmpty() -> EmptyState("Folder Is Empty", if (canWrite) "Tap ••• to upload files." else "")
        }
        ScreenTopBar(title, scrolled, onBack = { app.pop() }) {
            var open by remember { mutableStateOf(false) }
            Box {
                GlassCircle(IosIcons.More) { open = true }
                IosMenu(open, { open = false }, buildList {
                    if (canWrite) {
                        add(MenuAction("Upload Files", IosIcons.Share) { picker.launch("*/*") })
                        add(MenuAction("New Folder", IosIcons.FolderPlus) { newFolder = true })
                    }
                    add(MenuAction("Icons" + if (!store.listView) "  ✓" else "", IosIcons.Grid, divider = canWrite) { if (store.listView) store.toggleListView() })
                    add(MenuAction("List" + if (store.listView) "  ✓" else "", IosIcons.ListIcon) { if (!store.listView) store.toggleListView() })
                    add(MenuAction("Refresh", IosIcons.Clock) { reload++ })
                    if (path.isEmpty()) add(MenuAction("Remove Server", IosIcons.Trash, destructive = true, divider = true) {
                        smb.remove(serverId); app.pop()
                    })
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
    if (newFolder && server != null) {
        NameDialog("New Folder", "untitled folder", "Create", onDismiss = { newFolder = false }) { name ->
            newFolder = false
            run("Creating folder…") { smb.mkdir(server, path, name); reload++ }
        }
    }
    confirmDelete?.let { e ->
        ConfirmDialog(
            "Delete “${e.name}”?", "It will be deleted from the server. This can't be undone.", "Delete",
            onDismiss = { confirmDelete = null },
        ) {
            confirmDelete = null
            if (server != null) run("Deleting…") { smb.delete(server, e.path); reload++ }
        }
    }
}

/** "Connect to Server" sheet: address, user name and password, or guest. */
@Composable
fun ConnectServerDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val c = LocalIos.current
    val smb = remember { SmbStore.get(ctx) }
    var address by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var guest by remember { mutableStateOf(false) }
    val parsed = SmbServer.parse(address)
    val ok = parsed != null && (guest || user.isNotBlank())
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = if (c.dark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7),
        shape = RoundedCornerShape(16.dp),
        title = { Text("Connect to Server", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Server", address, "smb://192.168.1.10/share") { address = it }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.tap { guest = !guest }) {
                    SelectCircle(guest, Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Connect as Guest", fontSize = 15.sp, color = c.label)
                }
                if (!guest) {
                    Field("Name", user, "user name") { user = it }
                    Field("Password", pass, "password", password = true) { pass = it }
                }
                Text(
                    "Use the address of a Windows PC, Mac or NAS on your Wi-Fi. Leave out the folder to see every shared folder.",
                    fontSize = 12.sp, color = c.secondary,
                )
            }
        },
        confirmButton = {
            Text(
                "Connect", color = if (ok) c.label else c.tertiary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.tap(enabled = ok) {
                    val (host, share) = parsed ?: return@tap
                    val s = smb.add(host, share, user.trim(), pass, guest)
                    onDismiss()
                    app.push(Route.Smb(s.id, ""))
                }.padding(10.dp),
            )
        },
        dismissButton = {
            Text("Cancel", color = c.label, fontSize = 17.sp, modifier = Modifier.tap(onClick = onDismiss).padding(10.dp))
        },
    )
}


@Composable
private fun Field(label: String, value: String, hint: String, password: Boolean = false, onChange: (String) -> Unit) {
    val c = LocalIos.current
    Column {
        Text(label, fontSize = 12.sp, color = c.secondary, modifier = Modifier.padding(start = 4.dp, bottom = 3.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (c.dark) Color(0xFF1C1C1E) else Color.White)
                .padding(horizontal = 10.dp, vertical = 10.dp),
        ) {
            if (value.isEmpty()) Text(hint, fontSize = 16.sp, color = c.tertiary)
            BasicTextField(
                value, onChange, singleLine = true,
                textStyle = TextStyle(fontSize = 16.sp, color = c.label),
                cursorBrush = SolidColor(c.label),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (password) KeyboardType.Password else KeyboardType.Uri,
                    autoCorrectEnabled = false,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
