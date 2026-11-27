package com.photos.files

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import com.photos.files.data.FileStore
import com.photos.files.ui.AccessGate
import com.photos.files.ui.FilesRoot
import com.photos.files.ui.FilesTheme

class MainActivity : ComponentActivity() {
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.isNavigationBarContrastEnforced = false
        useFastestRefreshRate()
        val store = FileStore.get(this)
        setContent {
            FilesTheme {
                AccessGate(resumeTick.intValue) { FilesRoot(store) }
            }
        }
    }

    private val mountReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: android.content.Intent) { FileStore.get(c).changed() }
    }

    override fun onStart() {
        super.onStart()
        val f = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_MEDIA_MOUNTED)
            addAction(android.content.Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(android.content.Intent.ACTION_MEDIA_EJECT)
            addAction(android.content.Intent.ACTION_MEDIA_REMOVED)
            addAction(android.content.Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        androidx.core.content.ContextCompat.registerReceiver(this, mountReceiver, f, androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(mountReceiver) }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
        FileStore.get(this).changed()
    }

    /** Ask for the screen's highest refresh rate (90/120 Hz) at the current resolution, so everything moves smoothly. */
    private fun useFastestRefreshRate() {
        runCatching {
            val d = if (android.os.Build.VERSION.SDK_INT >= 30) display else @Suppress("DEPRECATION") windowManager.defaultDisplay
            d ?: return
            val cur = d.mode
            val best = d.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .maxByOrNull { it.refreshRate } ?: return
            window.attributes = window.attributes.apply {
                preferredDisplayModeId = best.modeId
                preferredRefreshRate = best.refreshRate
            }
        }
    }
}
