package com.photos.files.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.core.content.FileProvider
import com.photos.files.data.FileItem
import com.photos.files.data.FileKind
import com.photos.files.data.FileStore
import com.photos.files.data.TagColor
import com.photos.files.data.TrashEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

sealed interface Route {
    data class Folder(val path: String) : Route
    data class Tag(val tag: TagColor) : Route
    data object Trash : Route
    data class Picker(val mode: PickMode, val paths: List<String>) : Route
    data class QuickLook(val path: String) : Route
    data class Smb(val serverId: String, val path: String) : Route
    data class Settings(val about: Boolean = false) : Route
}

enum class PickMode(val verb: String) { Move("Move"), Copy("Copy") }

private var routeIds = 0L

class RouteEntry(val route: Route) {
    val id = routeIds++
    var closing by mutableStateOf(false)
    /** Put back when returning to a tab: shown straight away, without sliding in again. */
    var instant = false
}

val LocalApp = staticCompositionLocalOf<AppState> { error("no app state") }
val LocalFileStore = staticCompositionLocalOf<FileStore> { error("no store") }

const val PhotosPackage = "com.photos.gallery"

class AppState(private val ctx: Context, val store: FileStore, private val scope: CoroutineScope) {
    var tab by mutableStateOf(FilesTab.Browse)
    /** iPad layout: the place picked in the sidebar (a folder, tag, Recently Deleted or a server). Null means [tab]. */
    var side by mutableStateOf<Route?>(null)
        private set
    val stack = mutableStateListOf<RouteEntry>()

    var selecting by mutableStateOf(false)
        private set
    var selected by mutableStateOf(emptySet<String>())
        private set

    // Dialogs and sheets
    var renameTarget by mutableStateOf<FileItem?>(null)
    var newFolderIn by mutableStateOf<String?>(null)
    var infoItem by mutableStateOf<FileItem?>(null)
    var tagTargets by mutableStateOf<List<String>?>(null)
    var confirmDelete by mutableStateOf<List<TrashEntry>?>(null)
    var connectServer by mutableStateOf(false)

    /** The progress pill shown at the top while moving, copying, duplicating or compressing. */
    class Transfer(val working: String, val done: String) {
        var finished by mutableStateOf(false)
        var failed by mutableStateOf(false)
    }
    var transfer by mutableStateOf<Transfer?>(null)

    private fun <T> withProgress(working: String, done: (T) -> String, block: suspend () -> Result<T>, after: (T) -> Unit = {}) {
        val t = Transfer(working, "")
        transfer = t
        scope.launch {
            val started = System.currentTimeMillis()
            val r = block()
            // Let the pill be seen for a moment even when the job is instant.
            val left = 450 - (System.currentTimeMillis() - started)
            if (left > 0) kotlinx.coroutines.delay(left)
            r.onSuccess { v ->
                after(v)
                transfer = Transfer(working, done(v)).also { it.finished = true }
            }.onFailure {
                transfer = null
                toast(it.message ?: "Something went wrong.")
            }
            kotlinx.coroutines.delay(1300)
            if (transfer?.finished == true) transfer = null
        }
    }

    val top: Route? get() = stack.lastOrNull { !it.closing }?.route
    val canPop: Boolean get() = stack.any { !it.closing }

    fun push(r: Route) {
        clearSelection()
        stack += RouteEntry(r)
        saveNav()
    }

    fun pop() {
        clearSelection()
        stack.lastOrNull { !it.closing }?.let(::close)
    }

    /** Slides a screen away, then removes it. */
    fun close(e: RouteEntry) {
        if (e.closing) return
        e.closing = true
        scope.launch {
            kotlinx.coroutines.delay(250)
            stack.remove(e)
            saveNav()
        }
    }

    private val saved = mutableMapOf<FilesTab, List<RouteEntry>>()

    /**
     * Like iOS: each tab remembers the folders you opened, and tapping the tab you're already on goes back
     * to its top level. [keep] = false (tablet sidebar) always opens the place fresh.
     */
    fun selectTab(t: FilesTab, keep: Boolean = true) {
        clearSelection()
        val open = stack.filter { it.route !is Route.Picker && !it.closing }
        if (keep && side == null && t == tab) {
            open.forEach(::close)
            saved.remove(t)
            return
        }
        if (keep && side == null) saved[tab] = open else saved.clear()
        stack.removeAll { it.route !is Route.Picker }
        side = null
        if (keep) saved.remove(t)?.let { back ->
            back.forEach { it.instant = true }
            stack.addAll(0, back)
        }
        tab = t
        saveNav()
    }

    // ---------- Remember where you were, even after the app closes (like iOS Files) ----------

    private val navPrefs = ctx.getSharedPreferences("nav", Context.MODE_PRIVATE)

    private fun Route.code(): String? = when (this) {
        is Route.Folder -> "F:$path"
        is Route.Tag -> "T:${tag.name}"
        Route.Trash -> "X"
        else -> null
    }

    private fun decode(c: String): Route? = when {
        c.startsWith("F:") -> c.substring(2).takeIf { File(it).isDirectory }?.let { Route.Folder(it) }
        c.startsWith("T:") -> runCatching { Route.Tag(TagColor.valueOf(c.substring(2))) }.getOrNull()
        c == "X" -> Route.Trash
        else -> null
    }

    /** Saves the current tab and the folders open in Browse. */
    fun saveNav() {
        val browse = if (tab == FilesTab.Browse && side == null) stack.filter { !it.closing } else saved[FilesTab.Browse].orEmpty()
        val codes = browse.mapNotNull { it.route.code() }
        navPrefs.edit().putString("tab", tab.name).putString("browse", codes.joinToString("\u0000")).apply()
    }

    private fun restoreNav() {
        val t = runCatching { FilesTab.valueOf(navPrefs.getString("tab", "") ?: "") }.getOrNull() ?: return
        val entries = navPrefs.getString("browse", "").orEmpty().split('\u0000').filter { it.isNotEmpty() }
            .mapNotNull { decode(it) }.map { RouteEntry(it).apply { instant = true } }
        if (t == FilesTab.Search) tab = FilesTab.Browse else tab = t
        if (tab == FilesTab.Browse) stack.addAll(entries) else if (entries.isNotEmpty()) saved[FilesTab.Browse] = entries
    }

    init {
        restoreNav()
    }

    fun selectSide(r: Route) {
        clearSelection()
        stack.removeAll { it.route !is Route.Picker }
        side = r
    }

    fun startSelecting() { selecting = true; selected = emptySet() }
    fun clearSelection() { selecting = false; selected = emptySet() }
    fun toggle(path: String) { selected = if (path in selected) selected - path else selected + path }
    fun setSelection(paths: Set<String>) { selected = paths }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    private fun <T> run(block: suspend () -> Result<T>, done: (T) -> Unit = {}) {
        scope.launch {
            block().onSuccess(done).onFailure { toast(it.message ?: "Something went wrong.") }
        }
    }

    // ---------- Opening ----------

    fun uriFor(path: String): Uri = FileProvider.getUriForFile(ctx, "com.photos.files.provider", File(path))

    private fun photosInstalled() = runCatching { ctx.packageManager.getPackageInfo(PhotosPackage, 0) }.isSuccess

    fun open(item: FileItem) {
        if (selecting) { toggle(item.path); return }
        when {
            item.isDir -> push(Route.Folder(item.path))
            item.kind == FileKind.Archive && item.ext == "zip" -> uncompress(item)
            (item.kind == FileKind.Text || item.kind == FileKind.Code) && item.size < 2_000_000 -> push(Route.QuickLook(item.path))
            item.kind == FileKind.Image || item.kind == FileKind.Video -> openMedia(item)
            item.ext == "pdf" -> com.photos.files.PdfActivity.open(ctx, item.path)
            item.kind == FileKind.App -> launchView(uriFor(item.path), "application/vnd.android.package-archive", null)
            else -> launchView(uriFor(item.path), item.mime, null)
        }
    }

    private fun openMedia(item: FileItem) {
        val video = item.kind == FileKind.Video
        val uri = store.mediaUri(item.path, video) ?: uriFor(item.path)
        val usePhotos = AppPrefs.get(ctx).flag(FilesPrefKeys.OpenInPhotos, true) && photosInstalled()
        launchView(uri, item.mime, if (usePhotos) PhotosPackage else null)
    }

    fun launchView(uri: Uri, mime: String, pkg: String?, grant: Boolean = true) {
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            if (grant) addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (pkg != null) setPackage(pkg)
        }
        try {
            ctx.startActivity(if (pkg != null) i else Intent.createChooser(i, null))
        } catch (_: ActivityNotFoundException) {
            if (pkg != null) launchView(uri, mime, null, grant) else toast("No app can open this file.")
        } catch (_: SecurityException) {
            if (grant) launchView(uri, mime, pkg, grant = false) else toast("Couldn't open this file.")
        } catch (e: Exception) {
            toast(e.message ?: "Couldn't open this file.")
        }
    }

    fun share(paths: List<String>) {
        if (paths.isEmpty()) return
        val files = paths.map { File(it) }
        if (files.any { it.isDirectory }) { toast("Folders can't be shared. Compress them first."); return }
        val uris = ArrayList(paths.map { uriFor(it) })
        val mimes = files.map { FileItem.of(it).mime }.distinct()
        val type = mimes.singleOrNull() ?: mimes.map { it.substringBefore('/') }.distinct().singleOrNull()?.let { "$it/*" } ?: "*/*"
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        send.type = type
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { ctx.startActivity(Intent.createChooser(send, null)) }
    }

    // ---------- Operations ----------

    fun duplicate(paths: List<String>) =
        withProgress<Int>("Duplicating ${Fmt.items(paths.size)}…", { "Duplicated ${Fmt.items(it)}" }, { store.duplicate(paths) }) { clearSelection() }

    fun trash(paths: List<String>) = run({ store.trash(paths) }) { n ->
        clearSelection()
        toast(if (n == 1) "Moved to Recently Deleted" else "$n items moved to Recently Deleted")
    }

    fun compress(paths: List<String>) =
        withProgress<String>("Compressing ${Fmt.items(paths.size)}…", { "Created “${File(it).name}”" }, { store.compress(paths) }) { clearSelection() }

    fun uncompress(item: FileItem) =
        withProgress<String>("Expanding “${item.name}”…", { "Expanded “${item.name}”" }, { store.uncompress(item.path) })

    fun rename(item: FileItem, name: String) = run({ store.rename(item.path, name) }) { renameTarget = null }

    fun newFolder(dir: String, name: String) = run({ store.newFolder(dir, name) }) { newFolderIn = null }

    fun moveOrCopy(mode: PickMode, paths: List<String>, dest: String, after: () -> Unit) {
        after()
        val verb = if (mode == PickMode.Move) "Moving" else "Copying"
        val past = if (mode == PickMode.Move) "Moved" else "Copied"
        withProgress<Int>(
            "$verb ${Fmt.items(paths.size)}…", { n -> "$past ${Fmt.items(n)} to “${File(dest).name}”" },
            { if (mode == PickMode.Move) store.move(paths, dest) else store.copy(paths, dest) },
        )
    }

    fun restore(entries: List<TrashEntry>) = run({ store.restore(entries) }) { n ->
        clearSelection(); toast("Recovered ${Fmt.items(n)}")
    }

    fun deleteForever(entries: List<TrashEntry>) = run({ store.deleteForever(entries) }) {
        clearSelection(); confirmDelete = null
    }
}

@Composable
fun rememberAppState(ctx: Context, store: FileStore): AppState {
    val scope = rememberCoroutineScope()
    return remember { AppState(ctx, store, scope) }
}
