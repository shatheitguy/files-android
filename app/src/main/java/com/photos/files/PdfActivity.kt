package com.photos.files

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.FileProvider
import com.photos.files.ui.FilesTheme
import com.photos.files.ui.PdfViewer
import java.io.File

/** Opens PDFs right inside Files — from the app, or when another app opens a PDF. */
class PdfActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.isNavigationBarContrastEnforced = false
        val path = intent?.getStringExtra(ExtraPath)
        val uri = intent?.data ?: path?.let { Uri(it) }
        if (uri == null) { finish(); return }
        setContent { FilesTheme { PdfViewer(uri, path, onClose = { finish() }) } }
    }

    private fun Uri(path: String) = FileProvider.getUriForFile(this, "com.photos.files.provider", File(path))

    companion object {
        private const val ExtraPath = "path"

        fun open(ctx: Context, path: String) {
            val i = Intent(ctx, PdfActivity::class.java).putExtra(ExtraPath, path)
            if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
    }
}
