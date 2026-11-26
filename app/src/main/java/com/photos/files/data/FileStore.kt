package com.photos.files.data

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

enum class FileKind { Folder, Image, Video, Audio, Pdf, Text, Document, Spreadsheet, Presentation, Archive, App, Code, Other }

enum class SortBy(val label: String) { Name("Name"), Kind("Kind"), Date("Date"), Size("Size") }

enum class TagColor(val label: String, val argb: Long) {
    Red("Red", 0xFFFF3B30), Orange("Orange", 0xFFFF9500), Yellow("Yellow", 0xFFFFCC00),
    Green("Green", 0xFF34C759), Blue("Blue", 0xFF007AFF), Purple("Purple", 0xFFAF52DE), Gray("Gray", 0xFF8E8E93),
}

data class FileItem(
    val path: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val childCount: Int = -1,
) {
    val file get() = File(path)
    val ext: String get() = if (isDir) "" else name.substringAfterLast('.', "").lowercase()
    val mime: String get() = mimeOf(ext)
    val kind: FileKind get() = kindOf(isDir, ext, mime)
    val isHidden get() = name.startsWith(".")

    companion object {
        fun of(f: File, countChildren: Boolean = false): FileItem {
            val dir = f.isDirectory
            return FileItem(
                path = f.absolutePath,
                name = f.name,
                isDir = dir,
                size = if (dir) 0 else f.length(),
                modified = f.lastModified(),
                childCount = if (dir && countChildren) (f.list()?.count { !it.startsWith(".") } ?: 0) else -1,
            )
        }
    }
}

fun mimeOf(ext: String): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
        "heic", "heif" -> "image/heif"
        "webp" -> "image/webp"
        "mkv" -> "video/x-matroska"
        "kt", "kts", "java", "py", "js", "ts", "json", "xml", "md", "log", "csv", "yml", "yaml" -> "text/plain"
        else -> "*/*"
    }

fun kindOf(isDir: Boolean, ext: String, mime: String): FileKind = when {
    isDir -> FileKind.Folder
    mime.startsWith("image/") -> FileKind.Image
    mime.startsWith("video/") -> FileKind.Video
    mime.startsWith("audio/") -> FileKind.Audio
    ext == "pdf" -> FileKind.Pdf
    ext == "apk" -> FileKind.App
    ext in setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz") -> FileKind.Archive
    ext in setOf("doc", "docx", "odt", "rtf", "pages") -> FileKind.Document
    ext in setOf("xls", "xlsx", "ods", "numbers") -> FileKind.Spreadsheet
    ext in setOf("ppt", "pptx", "odp", "key") -> FileKind.Presentation
    ext in setOf("kt", "kts", "java", "py", "js", "ts", "html", "css", "json", "xml", "sh", "c", "cpp", "h", "yml", "yaml") -> FileKind.Code
    mime.startsWith("text/") || ext in setOf("txt", "md", "log", "csv") -> FileKind.Text
    else -> FileKind.Other
}

fun kindLabel(item: FileItem): String = when (item.kind) {
    FileKind.Folder -> "Folder"
    FileKind.Image -> "${item.ext.uppercase()} image"
    FileKind.Video -> "${item.ext.uppercase()} video"
    FileKind.Audio -> "${item.ext.uppercase()} audio"
    FileKind.Pdf -> "PDF document"
    FileKind.App -> "Android app"
    FileKind.Archive -> "${item.ext.uppercase()} archive"
    FileKind.Document -> "Document"
    FileKind.Spreadsheet -> "Spreadsheet"
    FileKind.Presentation -> "Presentation"
    FileKind.Code -> "Source code"
    FileKind.Text -> "Plain text"
    FileKind.Other -> if (item.ext.isNotEmpty()) "${item.ext.uppercase()} file" else "Document"
}

data class Volume(val title: String, val path: String, val removable: Boolean, val usb: Boolean = false)

data class TrashEntry(val trashPath: String, val original: String, val deletedAt: Long) {
    val item get() = FileItem.of(File(trashPath))
}

class FileStore private constructor(private val ctx: Context) {
    private val prefs: SharedPreferences = ctx.getSharedPreferences("files", Context.MODE_PRIVATE)

    /** Bumped after every change so lists reload. */
    var version by mutableIntStateOf(0)
        private set
    var sortBy by mutableStateOf(SortBy.entries[prefs.getInt("sort", 0).coerceIn(0, 3)])
        private set
    var ascending by mutableStateOf(prefs.getBoolean("asc", true))
        private set
    var listView by mutableStateOf(prefs.getBoolean("list", false))
        private set
    var showHidden by mutableStateOf(prefs.getBoolean("hidden", false))
        private set
    var favorites by mutableStateOf(loadFavorites())
        private set
    var tags by mutableStateOf(loadTags())
        private set

    val root: String = Environment.getExternalStorageDirectory().absolutePath
    private val trashDir get() = File(root, ".Trash-Files").apply {
        if (!exists()) mkdirs()
        val nm = File(this, ".nomedia")
        if (!nm.exists()) runCatching { nm.createNewFile() }
    }

    fun changed() { version++ }

    fun setSort(s: SortBy) {
        if (s == sortBy) ascending = !ascending else { sortBy = s; ascending = s == SortBy.Name || s == SortBy.Kind }
        prefs.edit().putInt("sort", sortBy.ordinal).putBoolean("asc", ascending).apply()
    }

    fun toggleListView() { listView = !listView; prefs.edit().putBoolean("list", listView).apply() }
    fun toggleHidden() { showHidden = !showHidden; prefs.edit().putBoolean("hidden", showHidden).apply() }

    // ---------- Volumes ----------

    fun volumes(): List<Volume> {
        val out = mutableListOf(Volume("On My Phone", root, false))
        runCatching {
            val sm = ctx.getSystemService(StorageManager::class.java)
            sm.storageVolumes.filter { it.isRemovable }.forEach { v ->
                val dir = v.directory ?: return@forEach
                if (v.state != Environment.MEDIA_MOUNTED && v.state != Environment.MEDIA_MOUNTED_READ_ONLY) return@forEach
                if (!dir.canRead()) return@forEach
                val desc = v.getDescription(ctx) ?: ""
                val usb = desc.contains("usb", true) || desc.contains("otg", true)
                out += Volume(desc.ifBlank { if (usb) "USB Drive" else "SD Card" }, dir.absolutePath, true, usb)
            }
        }
        return out
    }

    fun freeSpace(path: String): Long = runCatching { StatFs(path).availableBytes }.getOrDefault(0)
    fun totalSpace(path: String): Long = runCatching { StatFs(path).totalBytes }.getOrDefault(0)

    fun displayName(path: String): String {
        volumes().firstOrNull { it.path == path }?.let { return it.title }
        return File(path).name
    }

    // ---------- Listing ----------

    suspend fun list(dir: String): List<FileItem> = withContext(Dispatchers.IO) {
        val files = File(dir).listFiles() ?: emptyArray()
        sort(files.filter { showHidden || !it.name.startsWith(".") }.map { FileItem.of(it, countChildren = true) })
    }

    fun sort(items: List<FileItem>): List<FileItem> {
        val cmp: Comparator<FileItem> = when (sortBy) {
            SortBy.Name -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortBy.Kind -> compareBy<FileItem> { it.kind.ordinal }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortBy.Date -> compareBy { it.modified }
            SortBy.Size -> compareBy { if (it.isDir) -1L else it.size }
        }
        return items.sortedWith(if (ascending) cmp else cmp.reversed())
    }

    /** Recently changed files, newest first, from the system media index. */
    suspend fun recents(limit: Int = 300): List<FileItem> = withContext(Dispatchers.IO) {
        val out = ArrayList<FileItem>()
        runCatching {
            val uri = MediaStore.Files.getContentUri("external")
            @Suppress("DEPRECATION")
            val proj = arrayOf(MediaStore.Files.FileColumns.DATA)
            val sel = "${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL AND ${MediaStore.Files.FileColumns.SIZE} > 0"
            ctx.contentResolver.query(uri, proj, sel, null, "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val p = c.getString(0) ?: continue
                    if ("/." in p || "/Android/data/" in p || "/Android/obb/" in p) continue
                    val f = File(p)
                    if (f.isFile) out += FileItem.of(f)
                }
            }
        }
        out
    }

    suspend fun search(query: String, limit: Int = 300): List<FileItem> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val out = ArrayList<FileItem>()
        val seen = HashSet<String>()
        runCatching {
            val uri = MediaStore.Files.getContentUri("external")
            @Suppress("DEPRECATION")
            val proj = arrayOf(MediaStore.Files.FileColumns.DATA)
            val esc = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            ctx.contentResolver.query(
                uri, proj, "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\'", arrayOf("%$esc%"),
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val p = c.getString(0) ?: continue
                    if ("/." in p || !seen.add(p)) continue
                    val f = File(p)
                    if (f.exists()) out += FileItem.of(f, countChildren = true)
                }
            }
        }
        // Folders aren't always indexed — walk the top levels of storage for matching folder names.
        val ql = q.lowercase()
        fun walk(dir: File, depth: Int) {
            if (depth > 4 || out.size >= limit) return
            val kids = dir.listFiles() ?: return
            for (k in kids) {
                if (!k.isDirectory || k.name.startsWith(".")) continue
                if (k.absolutePath.startsWith("$root/Android/data") || k.absolutePath.startsWith("$root/Android/obb")) continue
                if (ql in k.name.lowercase() && seen.add(k.absolutePath)) out += FileItem.of(k, countChildren = true)
                walk(k, depth + 1)
            }
        }
        runCatching { walk(File(root), 0) }
        out.sortedWith(compareByDescending<FileItem> { it.isDir }.thenByDescending { it.modified })
    }

    suspend fun folderSize(path: String): Pair<Long, Int> = withContext(Dispatchers.IO) {
        var size = 0L
        var count = 0
        File(path).walkTopDown().forEach { if (it.isFile) { size += it.length(); count++ } }
        size to count
    }

    // ---------- Favorites & tags ----------

    private fun loadFavorites(): List<String> {
        val raw = prefs.getString("favorites", null)
        if (raw == null) {
            val d = listOf(Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_DOCUMENTS)
                .map { File(Environment.getExternalStorageDirectory(), it).absolutePath }
            return d
        }
        return raw.split('\n').filter { it.isNotBlank() }
    }

    private fun saveFavorites() { prefs.edit().putString("favorites", favorites.joinToString("\n")).apply() }

    fun isFavorite(path: String) = path in favorites
    fun toggleFavorite(path: String) {
        favorites = if (path in favorites) favorites - path else favorites + path
        saveFavorites()
    }

    private fun loadTags(): Map<String, Set<TagColor>> = runCatching {
        val o = JSONObject(prefs.getString("tags", "{}")!!)
        o.keys().asSequence().associateWith { k ->
            o.getString(k).split(',').mapNotNull { n -> TagColor.entries.firstOrNull { it.name == n } }.toSet()
        }.filterValues { it.isNotEmpty() }
    }.getOrDefault(emptyMap())

    private fun saveTags() {
        val o = JSONObject()
        tags.forEach { (k, v) -> if (v.isNotEmpty()) o.put(k, v.joinToString(",") { it.name }) }
        prefs.edit().putString("tags", o.toString()).apply()
    }

    fun tagsOf(path: String): Set<TagColor> = tags[path] ?: emptySet()

    fun toggleTag(paths: List<String>, tag: TagColor) {
        val allHave = paths.all { tag in tagsOf(it) }
        val m = tags.toMutableMap()
        paths.forEach { p ->
            val cur = m[p] ?: emptySet()
            val next = if (allHave) cur - tag else cur + tag
            if (next.isEmpty()) m.remove(p) else m[p] = next
        }
        tags = m
        saveTags()
    }

    fun tagged(tag: TagColor): List<FileItem> =
        tags.filterValues { tag in it }.keys.map { File(it) }.filter { it.exists() }.map { FileItem.of(it, true) }

    private fun moveMeta(from: String, to: String) {
        val m = tags.toMutableMap()
        m.keys.filter { it == from || it.startsWith("$from/") }.forEach { k ->
            val v = m.remove(k)!!
            m[to + k.removePrefix(from)] = v
        }
        if (m != tags) { tags = m; saveTags() }
        if (favorites.any { it == from || it.startsWith("$from/") }) {
            favorites = favorites.map { if (it == from || it.startsWith("$from/")) to + it.removePrefix(from) else it }
            saveFavorites()
        }
    }

    // ---------- File operations ----------

    /** Tells the media index (and so the Photos app) about changed paths. */
    fun scan(paths: Collection<String>) {
        val all = ArrayList<String>()
        paths.forEach { p ->
            val f = File(p)
            if (f.isDirectory) f.walkTopDown().filter { it.isFile }.take(2000).forEach { all += it.absolutePath }
            all += p
        }
        if (all.isNotEmpty()) runCatching { MediaScannerConnection.scanFile(ctx, all.toTypedArray(), null, null) }
    }

    private fun mediaPathsUnder(p: String): List<String> {
        val f = File(p)
        return if (f.isDirectory) f.walkTopDown().filter { it.isFile }.take(2000).map { it.absolutePath }.toList() + p else listOf(p)
    }

    fun uniqueTarget(dir: File, name: String): File {
        var t = File(dir, name)
        if (!t.exists()) return t
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.') && !name.startsWith(".")) "." + name.substringAfterLast('.') else ""
        var i = 2
        while (true) {
            t = File(dir, "$base $i$ext")
            if (!t.exists()) return t
            i++
        }
    }

    fun validName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." && name.none { it == '/' || it == '\u0000' }

    suspend fun newFolder(dir: String, name: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(validName(name)) { "Invalid name" }
            val t = File(dir, name.trim())
            require(!t.exists()) { "“${t.name}” already exists." }
            check(t.mkdirs()) { "Couldn't create the folder." }
            t.absolutePath
        }.also { changed() }
    }

    suspend fun rename(path: String, newName: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val n = newName.trim()
            require(validName(n)) { "Invalid name" }
            val f = File(path)
            val t = File(f.parentFile, n)
            if (t.absolutePath == f.absolutePath) return@runCatching path
            require(!t.exists()) { "“$n” already exists." }
            val old = mediaPathsUnder(path)
            check(f.renameTo(t)) { "Couldn't rename “${f.name}”." }
            moveMeta(path, t.absolutePath)
            scan(old + t.absolutePath)
            t.absolutePath
        }.also { changed() }
    }

    private fun copyRecursive(src: File, dst: File) {
        if (src.isDirectory) {
            check(dst.mkdirs() || dst.isDirectory) { "Couldn't create ${dst.name}" }
            src.listFiles()?.forEach { copyRecursive(it, File(dst, it.name)) }
        } else {
            FileInputStream(src).use { i -> FileOutputStream(dst).use { o -> i.copyTo(o, 256 * 1024) } }
            dst.setLastModified(src.lastModified())
        }
    }

    suspend fun copy(paths: List<String>, destDir: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val dest = File(destDir)
            val made = ArrayList<String>()
            paths.forEach { p ->
                val src = File(p)
                require(!(src.isDirectory && (destDir == p || destDir.startsWith("$p/")))) { "Can't copy a folder into itself." }
                val t = uniqueTarget(dest, src.name)
                copyRecursive(src, t)
                made += t.absolutePath
            }
            scan(made)
            made.size
        }.also { changed() }
    }

    suspend fun duplicate(paths: List<String>): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val made = ArrayList<String>()
            paths.forEach { p ->
                val src = File(p)
                val base = src.name.substringBeforeLast('.', src.name)
                val ext = if (src.isFile && src.name.contains('.')) "." + src.name.substringAfterLast('.') else ""
                val t = uniqueTarget(src.parentFile!!, "$base copy$ext")
                copyRecursive(src, t)
                made += t.absolutePath
            }
            scan(made)
            made.size
        }.also { changed() }
    }

    private fun moveOne(src: File, dst: File) {
        if (src.renameTo(dst)) return
        copyRecursive(src, dst)
        check(src.deleteRecursively()) { "Moved a copy, but couldn't remove the original “${src.name}”." }
    }

    suspend fun move(paths: List<String>, destDir: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val dest = File(destDir)
            var n = 0
            val touched = ArrayList<String>()
            paths.forEach { p ->
                val src = File(p)
                if (src.parentFile?.absolutePath == dest.absolutePath) return@forEach
                require(!(src.isDirectory && (destDir == p || destDir.startsWith("$p/")))) { "Can't move a folder into itself." }
                val t = uniqueTarget(dest, src.name)
                touched += mediaPathsUnder(p)
                moveOne(src, t)
                moveMeta(p, t.absolutePath)
                touched += t.absolutePath
                n++
            }
            scan(touched)
            n
        }.also { changed() }
    }

    // ---------- Recently Deleted ----------

    private fun trashIndex(): JSONObject = runCatching { JSONObject(prefs.getString("trash", "{}")!!) }.getOrDefault(JSONObject())
    private fun saveTrash(o: JSONObject) { prefs.edit().putString("trash", o.toString()).apply() }

    suspend fun trash(paths: List<String>): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val idx = trashIndex()
            val touched = ArrayList<String>()
            var n = 0
            paths.forEach { p ->
                val src = File(p)
                if (!src.exists()) return@forEach
                touched += mediaPathsUnder(p)
                val onMain = p.startsWith("$root/")
                if (onMain) {
                    val t = File(trashDir, "${System.currentTimeMillis()}_${n}_${src.name}")
                    moveOne(src, t)
                    idx.put(t.absolutePath, JSONObject().put("from", p).put("at", System.currentTimeMillis()))
                } else {
                    check(src.deleteRecursively()) { "Couldn't delete “${src.name}”." }
                }
                n++
            }
            saveTrash(idx)
            scan(touched)
            n
        }.also { changed() }
    }

    suspend fun trashEntries(): List<TrashEntry> = withContext(Dispatchers.IO) {
        val idx = trashIndex()
        val out = ArrayList<TrashEntry>()
        val keep = JSONObject()
        val cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        idx.keys().forEach { k ->
            val o = idx.getJSONObject(k)
            val f = File(k)
            if (!f.exists()) return@forEach
            val at = o.optLong("at")
            if (at < cutoff) { f.deleteRecursively(); return@forEach }
            keep.put(k, o)
            out += TrashEntry(k, o.optString("from"), at)
        }
        saveTrash(keep)
        out.sortedByDescending { it.deletedAt }
    }

    suspend fun restore(entries: List<TrashEntry>): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val idx = trashIndex()
            val made = ArrayList<String>()
            entries.forEach { e ->
                val orig = File(e.original)
                val parent = orig.parentFile ?: File(root)
                parent.mkdirs()
                val t = uniqueTarget(parent, orig.name)
                moveOne(File(e.trashPath), t)
                idx.remove(e.trashPath)
                made += t.absolutePath
            }
            saveTrash(idx)
            scan(made)
            made.size
        }.also { changed() }
    }

    suspend fun deleteForever(entries: List<TrashEntry>): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val idx = trashIndex()
            entries.forEach { e -> File(e.trashPath).deleteRecursively(); idx.remove(e.trashPath) }
            saveTrash(idx)
            entries.size
        }.also { changed() }
    }

    // ---------- Archives ----------

    suspend fun compress(paths: List<String>): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val first = File(paths.first())
            val dir = first.parentFile!!
            val name = if (paths.size == 1) "${first.name.substringBeforeLast('.', first.name)}.zip" else "Archive.zip"
            val out = uniqueTarget(dir, name)
            ZipOutputStream(FileOutputStream(out)).use { zip ->
                paths.forEach { p ->
                    val src = File(p)
                    src.walkTopDown().forEach { f ->
                        val rel = f.absolutePath.removePrefix(dir.absolutePath + "/")
                        if (f.isDirectory) {
                            zip.putNextEntry(ZipEntry("$rel/")); zip.closeEntry()
                        } else {
                            zip.putNextEntry(ZipEntry(rel).apply { time = f.lastModified() })
                            FileInputStream(f).use { it.copyTo(zip, 256 * 1024) }
                            zip.closeEntry()
                        }
                    }
                }
            }
            scan(listOf(out.absolutePath))
            out.absolutePath
        }.also { changed() }
    }

    suspend fun uncompress(path: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val zipFile = File(path)
            val target = uniqueTarget(zipFile.parentFile!!, zipFile.name.substringBeforeLast('.'))
            target.mkdirs()
            val canon = target.canonicalPath + File.separator
            ZipInputStream(FileInputStream(zipFile)).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    val out = File(target, e.name)
                    require(out.canonicalPath.startsWith(canon)) { "This archive has unsafe paths." }
                    if (e.isDirectory) out.mkdirs()
                    else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { zin.copyTo(it, 256 * 1024) }
                        if (e.time > 0) out.setLastModified(e.time)
                    }
                    e = zin.nextEntry
                }
            }
            // Single top-level folder inside? Unwrap it.
            val kids = target.listFiles()
            if (kids != null && kids.size == 1 && kids[0].isDirectory) {
                val inner = kids[0]
                val dest = uniqueTarget(zipFile.parentFile!!, inner.name)
                if (inner.renameTo(dest)) { target.delete(); scan(listOf(dest.absolutePath)); return@runCatching dest.absolutePath }
            }
            scan(listOf(target.absolutePath))
            target.absolutePath
        }.also { changed() }
    }

    // ---------- Saving shared content ----------

    suspend fun saveUris(uris: List<Uri>, destDir: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val dest = File(destDir)
            val made = ArrayList<String>()
            uris.forEach { u ->
                var name: String? = null
                runCatching {
                    ctx.contentResolver.query(u, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) name = c.getString(0)
                    }
                }
                if (name.isNullOrBlank()) name = u.lastPathSegment?.substringAfterLast('/')
                if (name.isNullOrBlank()) name = "File"
                if (!name!!.contains('.')) {
                    val ext = ctx.contentResolver.getType(u)?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
                    if (ext != null) name = "$name.$ext"
                }
                val t = uniqueTarget(dest, name!!.replace('/', '_'))
                ctx.contentResolver.openInputStream(u)?.use { i -> FileOutputStream(t).use { o -> i.copyTo(o, 256 * 1024) } }
                    ?: error("Couldn't read a shared item.")
                made += t.absolutePath
            }
            scan(made)
            made.size
        }.also { changed() }
    }

    suspend fun saveText(text: String, destDir: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val t = uniqueTarget(File(destDir), "Text.txt")
            t.writeText(text)
            scan(listOf(t.absolutePath))
            1
        }.also { changed() }
    }

    /** MediaStore content URI for an image/video so Photos can open it inside its own library. */
    fun mediaUri(path: String, video: Boolean): Uri? = runCatching {
        val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        @Suppress("DEPRECATION")
        ctx.contentResolver.query(base, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.DATA} = ?", arrayOf(path), null)
            ?.use { c -> if (c.moveToFirst()) android.content.ContentUris.withAppendedId(base, c.getLong(0)) else null }
    }.getOrNull()

    companion object {
        @Volatile private var inst: FileStore? = null
        fun get(ctx: Context): FileStore = inst ?: synchronized(this) {
            inst ?: FileStore(ctx.applicationContext).also { inst = it }
        }
    }
}
