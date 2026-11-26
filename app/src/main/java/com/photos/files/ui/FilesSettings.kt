package com.photos.files.ui

import android.app.Activity
import android.content.Context
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FilesPrefKeys {
    const val OpenInPhotos = "openInPhotos"
    const val AspectThumbs = "aspectThumbs"
}

/** Scans paper documents with the camera and saves them as a PDF in Documents/Scans. */
@Composable
fun rememberDocumentScanner(): () -> Unit {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val pdf = GmsDocumentScanningResult.fromActivityResultIntent(r.data)?.pdf ?: return@rememberLauncherForActivityResult
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Scans").apply { mkdirs() }
                    val name = "Scanned Document " + SimpleDateFormat("yyyy-MM-dd 'at' HH.mm.ss", Locale.getDefault()).format(Date()) + ".pdf"
                    val out = app.store.uniqueTarget(dir, name)
                    ctx.contentResolver.openInputStream(pdf.uri)?.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                        ?: error("Couldn't save the scan.")
                    out
                }
            }
            saved.onSuccess { f ->
                app.store.scan(listOf(f.absolutePath))
                app.store.changed()
                app.toast("Saved to Documents › Scans")
                app.push(Route.Folder(f.parent!!))
            }.onFailure { app.toast(it.message ?: "Couldn't save the scan.") }
        }
    }
    return remember {
        {
            val opts = GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build()
            val act = ctx.findActivity()
            if (act == null) app.toast("Scanner isn't available.")
            else GmsDocumentScanning.getClient(opts).getStartScanIntent(act)
                .addOnSuccessListener { launcher.launch(IntentSenderRequest.Builder(it).build()) }
                .addOnFailureListener { app.toast("Document scanning needs Google Play services.") }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** The ••• button on Browse: Edit, Connect to Server, Scan Documents, Settings… */
@Composable
fun BrowseMoreButton(editing: Boolean, onEdit: () -> Unit, onConnect: () -> Unit) {
    val app = LocalApp.current
    val scan = rememberDocumentScanner()
    var open by remember { mutableStateOf(false) }
    if (editing) {
        GlassPill("Done", color = LocalIos.current.label) { onEdit() }
        return
    }
    Box {
        GlassCircle(IosIcons.More) { open = true }
        IosMenu(
            open, { open = false },
            listOf(
                MenuAction("Edit", IosIcons.Pencil) { onEdit() },
                MenuAction("Connect to Server", IosIcons.Phone) { onConnect() },
                MenuAction("Scan Documents", IosIcons.Doc) { scan() },
                MenuAction("Settings", IosIcons.Sliders, divider = true) { app.push(Route.Settings()) },
                MenuAction("Check for Updates", IosIcons.Download) { app.push(Route.Settings()) },
            ),
        )
    }
}

@Composable
fun FilesSettingsScreen(about: Boolean) {
    val app = LocalApp.current
    val store = LocalFileStore.current
    val prefs = AppPrefs.get(LocalContext.current)
    SettingsPage("Settings", { app.pop() }, scrollToEnd = about) {
        item { SettingsGroup(header = "Appearance") { AppearanceRow(prefs) } }
        item {
            SettingsGroup(header = "Browsing") {
                SettingsToggle("Show Hidden Files", store.showHidden) { store.toggleHidden() }
                SettingsToggle("Show as List", store.listView) { store.toggleListView() }
                SettingsToggle("Aspect Ratio Thumbnails", prefs.flag(FilesPrefKeys.AspectThumbs, true), last = true) {
                    prefs.setFlag(FilesPrefKeys.AspectThumbs, it)
                }
            }
        }
        item {
            SettingsGroup(
                header = "Photos",
                footer = "When this is on, photos and videos open in the Photos app so you can swipe through the folder.",
            ) {
                SettingsToggle("Open in Photos", prefs.flag(FilesPrefKeys.OpenInPhotos, true), last = true) {
                    prefs.setFlag(FilesPrefKeys.OpenInPhotos, it)
                }
            }
        }
        commonSettings("Files", prefs)
    }
}
