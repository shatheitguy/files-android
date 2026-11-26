package com.photos.files

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.photos.files.data.FileStore
import com.photos.files.ui.AccessGate
import com.photos.files.ui.FilesTheme
import com.photos.files.ui.Fmt
import com.photos.files.ui.LocalApp
import com.photos.files.ui.LocalFileStore
import com.photos.files.ui.PickerScreen
import com.photos.files.ui.rememberAppState
import kotlinx.coroutines.launch

/** "Save to Files" from any app's share sheet. */
class SaveActivity : ComponentActivity() {
    private val resumeTick = mutableIntStateOf(0)

    @Suppress("DEPRECATION")
    private fun sharedUris(): List<Uri> {
        val i = intent ?: return emptyList()
        return when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM)
            )
            Intent.ACTION_SEND_MULTIPLE ->
                (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM))
                    ?: emptyList()
            else -> emptyList()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.isNavigationBarContrastEnforced = false
        val store = FileStore.get(this)
        val uris = sharedUris()
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (uris.isEmpty() && text.isNullOrBlank()) { finish(); return }
        val count = if (uris.isNotEmpty()) uris.size else 1
        setContent {
            FilesTheme {
                AccessGate(resumeTick.intValue) {
                    val scope = rememberCoroutineScope()
                    val app = rememberAppState(LocalContext.current, store)
                    CompositionLocalProvider(LocalApp provides app, LocalFileStore provides store) {
                        PickerScreen(
                            title = "Save ${Fmt.items(count)}",
                            action = "Save",
                            onCancel = { finish() },
                        ) { dest ->
                            scope.launch {
                                val r = if (uris.isNotEmpty()) store.saveUris(uris, dest) else store.saveText(text!!, dest)
                                r.onSuccess {
                                    Toast.makeText(this@SaveActivity, "Saved ${Fmt.items(it)} to Files", Toast.LENGTH_SHORT).show()
                                    finish()
                                }.onFailure {
                                    Toast.makeText(this@SaveActivity, it.message ?: "Couldn't save.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }
}
