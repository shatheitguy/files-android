package com.photos.files.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Properties
import java.util.UUID

data class SmbServer(
    val id: String,
    val host: String,
    val share: String,
    val user: String,
    val password: String,
    val domain: String,
    val guest: Boolean,
) {
    val title: String get() = if (share.isBlank()) host else "$host/$share"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("host", host).put("share", share).put("user", user)
        .put("password", password).put("domain", domain).put("guest", guest)

    companion object {
        fun fromJson(o: JSONObject) = SmbServer(
            o.getString("id"), o.getString("host"), o.optString("share"), o.optString("user"),
            o.optString("password"), o.optString("domain"), o.optBoolean("guest"),
        )

        /** Accepts "192.168.1.5", "smb://nas/share", "\\\\nas\\share" etc. */
        fun parse(input: String): Pair<String, String>? {
            var s = input.trim().replace('\\', '/')
            s = s.removePrefix("smb://").removePrefix("SMB://").trim('/')
            if (s.isBlank()) return null
            val host = s.substringBefore('/')
            val share = s.substringAfter('/', "").trim('/')
            if (host.isBlank() || host.any { it.isWhitespace() }) return null
            return host to share
        }
    }
}

data class SmbEntry(val name: String, val path: String, val isDir: Boolean, val size: Long, val modified: Long) {
    /** A FileItem so the shared grid/list can show it. The "smb:" path keeps keys unique. */
    fun asItem() = FileItem("smb:$path", name, isDir, size, modified)
}

/** Saved servers and SMB file operations (SMB 2/3 via jcifs-ng). */
class SmbStore private constructor(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("servers", Context.MODE_PRIVATE)

    var servers by mutableStateOf(load())
        private set

    private fun load(): List<SmbServer> = runCatching {
        val a = JSONArray(prefs.getString("list", "[]"))
        (0 until a.length()).map { SmbServer.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun save() {
        val a = JSONArray()
        servers.forEach { a.put(it.toJson()) }
        prefs.edit().putString("list", a.toString()).apply()
    }

    fun add(host: String, share: String, user: String, password: String, guest: Boolean): SmbServer {
        val (domain, name) = if ('\\' in user) user.substringBefore('\\') to user.substringAfter('\\') else "" to user
        val existing = servers.firstOrNull { it.host.equals(host, true) && it.share == share }
        val s = SmbServer(existing?.id ?: UUID.randomUUID().toString(), host, share, name, password, domain, guest)
        servers = servers.filter { it.id != s.id } + s
        save()
        return s
    }

    fun remove(id: String) {
        servers = servers.filter { it.id != id }
        contexts.remove(id)
        save()
    }

    fun byId(id: String) = servers.firstOrNull { it.id == id }

    private val contexts = HashMap<String, CIFSContext>()

    private fun context(s: SmbServer): CIFSContext = synchronized(contexts) {
        contexts.getOrPut(s.id) {
            val p = Properties().apply {
                setProperty("jcifs.smb.client.minVersion", "SMB202")
                setProperty("jcifs.smb.client.maxVersion", "SMB311")
                setProperty("jcifs.smb.client.responseTimeout", "20000")
                setProperty("jcifs.smb.client.connTimeout", "10000")
                setProperty("jcifs.smb.client.soTimeout", "35000")
                setProperty("jcifs.resolveOrder", "DNS,BCAST")
                setProperty("jcifs.smb.client.ipcSigningEnforced", "false")
            }
            val base = BaseContext(PropertyConfiguration(p))
            when {
                s.guest -> base.withGuestCrendentials()
                else -> base.withCredentials(NtlmPasswordAuthenticator(s.domain.ifBlank { null }, s.user, s.password))
            }
        }
    }

    /** [path] is relative to the server: "" = list of shares, "share/dir/". */
    private fun url(s: SmbServer, path: String): String {
        val base = "smb://${s.host}/"
        val full = if (s.share.isNotBlank()) "${s.share}/$path" else path
        return base + full.trimStart('/')
    }

    private fun dirPath(path: String) = if (path.isEmpty() || path.endsWith("/")) path else "$path/"

    private fun friendly(e: Throwable): Exception {
        val m = (e.message ?: "").lowercase()
        val text = when {
            "logon failure" in m || "access_denied" in m || "access is denied" in m -> "The user name or password is incorrect, or you don't have access."
            "failed to connect" in m || "timed out" in m || "unknownhost" in m || e is java.net.UnknownHostException -> "Couldn't connect to the server. Check the address and that you're on the same Wi-Fi."
            "bad_network_name" in m -> "That shared folder doesn't exist on the server."
            else -> e.message ?: "Couldn't reach the server."
        }
        return Exception(text, e)
    }

    suspend fun list(s: SmbServer, path: String): List<SmbEntry> = withContext(Dispatchers.IO) {
        try {
            val dir = dirPath(path)
            SmbFile(url(s, dir), context(s)).use { f ->
                f.listFiles().mapNotNull { k ->
                    val raw = k.name
                    val isDir = raw.endsWith("/")
                    val name = raw.trimEnd('/')
                    if (name.isEmpty() || name.endsWith("$")) return@mapNotNull null // hide admin shares like IPC$
                    SmbEntry(
                        name = name,
                        path = dir + name + if (isDir) "/" else "",
                        isDir = isDir,
                        size = if (isDir) 0 else runCatching { k.length() }.getOrDefault(0),
                        modified = runCatching { k.lastModified() }.getOrDefault(0),
                    ).also { k.close() }
                }
            }.sortedWith(compareByDescending<SmbEntry> { it.isDir }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        } catch (e: Throwable) {
            throw friendly(e)
        }
    }

    suspend fun download(s: SmbServer, path: String, dest: File, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        try {
            SmbFile(url(s, path), context(s)).use { f ->
                val total = f.length()
                val part = File(dest.parentFile, dest.name + ".part")
                f.openInputStream().use { input ->
                    FileOutputStream(part).use { out ->
                        val buf = ByteArray(256 * 1024)
                        var done = 0L
                        var last = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0 && done - last > 512 * 1024) {
                                last = done
                                val pr = done.toFloat() / total
                                withContext(Dispatchers.Main) { progress(pr) }
                            }
                        }
                    }
                }
                if (dest.exists()) dest.delete()
                check(part.renameTo(dest)) { "Couldn't save “${dest.name}”." }
                if (f.lastModified() > 0) dest.setLastModified(f.lastModified())
                dest
            }
        } catch (e: Throwable) {
            throw friendly(e)
        }
    }

    suspend fun upload(s: SmbServer, dir: String, uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        var n = 0
        try {
            uris.forEach { u ->
                var name = "File"
                ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { name = it.replace('/', '_') }
                }
                var target = SmbFile(url(s, dirPath(dir) + name), context(s))
                var i = 2
                val base = name.substringBeforeLast('.', name)
                val ext = if ('.' in name) "." + name.substringAfterLast('.') else ""
                while (target.exists()) {
                    target.close()
                    target = SmbFile(url(s, dirPath(dir) + "$base $i$ext"), context(s)); i++
                }
                target.use { t ->
                    ctx.contentResolver.openInputStream(u)?.use { input ->
                        t.openOutputStream().use { out -> input.copyTo(out, 256 * 1024) }
                    } ?: error("Couldn't read a file to upload.")
                }
                n++
            }
            n
        } catch (e: Throwable) {
            throw friendly(e)
        }
    }

    suspend fun mkdir(s: SmbServer, dir: String, name: String) = withContext(Dispatchers.IO) {
        try {
            SmbFile(url(s, dirPath(dir) + name.trim() + "/"), context(s)).use { it.mkdir() }
        } catch (e: Throwable) {
            throw friendly(e)
        }
    }

    suspend fun delete(s: SmbServer, path: String) = withContext(Dispatchers.IO) {
        try {
            SmbFile(url(s, path), context(s)).use { it.delete() }
        } catch (e: Throwable) {
            throw friendly(e)
        }
    }

    companion object {
        @Volatile private var inst: SmbStore? = null
        fun get(ctx: Context): SmbStore = inst ?: synchronized(this) { inst ?: SmbStore(ctx.applicationContext).also { inst = it } }
    }
}
