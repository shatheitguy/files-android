package com.photos.files.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A cloud app on the phone (Nextcloud, OneDrive, Google Drive, Dropbox…) that shares its files with other apps. */
data class CloudDrive(val authority: String, val pkg: String, val title: String, val icon: Bitmap?)

data class CloudEntry(val docId: String, val name: String, val isDir: Boolean, val size: Long, val modified: Long) {
    fun asItem() = FileItem("cloud:$docId", name, isDir, size, modified)
}

/**
 * Cloud drives, like the ones iOS Files lists under Locations. Only apps that are installed show up.
 * Files gets into one after you pick its folder once in Android's picker; that access is kept.
 */
object Cloud {
    /** Places on the phone itself, which Files already shows, and apps that aren't cloud drives. */
    private val local = listOf(
        "com.android.", "com.google.android.apps.photos", "com.samsung.android.", "com.sec.android.",
        "com.photos.", "com.google.android.providers.", "com.mediatek.", "com.miui.", "com.coloros.",
    )

    private val names = mapOf(
        "com.google.android.apps.docs" to "Google Drive",
        "com.microsoft.skydrive" to "OneDrive",
        "com.nextcloud.client" to "Nextcloud",
        "com.dropbox.android" to "Dropbox",
        "com.box.android" to "Box",
        "ch.protonmail.android.drive" to "Proton Drive",
        "me.proton.android.drive" to "Proton Drive",
        "mega.privacy.android.app" to "MEGA",
        "com.owncloud.android" to "ownCloud",
        "com.pcloud.pcloud" to "pCloud",
    )

    fun drives(ctx: Context): List<CloudDrive> = runCatching {
        val pm = ctx.packageManager
        pm.queryIntentContentProviders(Intent(DocumentsContract.PROVIDER_INTERFACE), 0)
            .mapNotNull { it.providerInfo }
            .filter { p -> p.exported && local.none { p.authority.startsWith(it) || p.packageName.startsWith(it) } }
            .distinctBy { it.packageName }
            .map { p ->
                val app = p.applicationInfo
                val title = names[p.packageName] ?: app.loadLabel(pm).toString()
                val icon = runCatching { app.loadIcon(pm).toBitmap(96, 96) }.getOrNull()
                CloudDrive(p.authority, p.packageName, title, icon)
            }
            .sortedBy { it.title.lowercase() }
    }.getOrDefault(emptyList())

    /** The folder you picked for this drive, if any. */
    fun tree(ctx: Context, authority: String): Uri? =
        ctx.contentResolver.persistedUriPermissions
            .firstOrNull { it.isReadPermission && it.uri.authority == authority && DocumentsContract.isTreeUri(it.uri) }?.uri

    fun keep(ctx: Context, tree: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { ctx.contentResolver.takePersistableUriPermission(tree, flags) }
            .onFailure { runCatching { ctx.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
    }

    fun forget(ctx: Context, authority: String) {
        ctx.contentResolver.persistedUriPermissions.filter { it.uri.authority == authority }.forEach {
            runCatching {
                ctx.contentResolver.releasePersistableUriPermission(
                    it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
    }

    fun rootId(tree: Uri): String = DocumentsContract.getTreeDocumentId(tree)

    private fun doc(tree: Uri, id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    suspend fun list(ctx: Context, tree: Uri, dirId: String): List<CloudEntry> = withContext(Dispatchers.IO) {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId)
        val cols = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
        )
        val out = mutableListOf<CloudEntry>()
        ctx.contentResolver.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                out += CloudEntry(
                    docId = c.getString(0) ?: continue,
                    name = name,
                    isDir = c.getString(2) == Document.MIME_TYPE_DIR,
                    size = if (c.isNull(3)) 0 else c.getLong(3),
                    modified = if (c.isNull(4)) 0 else c.getLong(4),
                )
            }
        } ?: error("Couldn't open this folder.")
        out
    }

    suspend fun download(ctx: Context, tree: Uri, e: CloudEntry, target: File, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        ctx.contentResolver.openInputStream(doc(tree, e.docId))?.use { inp ->
            target.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (e.size > 0) progress((done.toFloat() / e.size).coerceIn(0f, 1f))
                }
            }
        } ?: error("Couldn't download “${e.name}”.")
        target
    }

    suspend fun upload(ctx: Context, tree: Uri, dirId: String, uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        var n = 0
        uris.forEach { src ->
            val name = runCatching {
                ctx.contentResolver.query(src, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull() ?: "file"
            val mime = ctx.contentResolver.getType(src) ?: "application/octet-stream"
            val dest = DocumentsContract.createDocument(ctx.contentResolver, doc(tree, dirId), mime, name) ?: return@forEach
            ctx.contentResolver.openInputStream(src)?.use { i -> ctx.contentResolver.openOutputStream(dest)?.use { o -> i.copyTo(o) } }
            n++
        }
        n
    }

    suspend fun mkdir(ctx: Context, tree: Uri, dirId: String, name: String) = withContext(Dispatchers.IO) {
        DocumentsContract.createDocument(ctx.contentResolver, doc(tree, dirId), Document.MIME_TYPE_DIR, name)
            ?: error("Couldn't create the folder.")
    }

    suspend fun delete(ctx: Context, tree: Uri, e: CloudEntry) = withContext(Dispatchers.IO) {
        if (!DocumentsContract.deleteDocument(ctx.contentResolver, doc(tree, e.docId))) error("Couldn't delete “${e.name}”.")
    }
}
