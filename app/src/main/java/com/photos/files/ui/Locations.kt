package com.photos.files.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.photos.files.data.Cloud
import com.photos.files.data.CloudDrive
import com.photos.files.data.Volume

/** A row under Locations: storage on the phone, or a cloud drive app. */
sealed interface Location {
    val key: String
    data class Vol(val v: Volume) : Location { override val key get() = "vol:" + v.path }
    data class Drive(val d: CloudDrive) : Location { override val key get() = "cloud:" + d.pkg }
}

/** The order you put Locations in (Edit → arrows), kept after Files closes. */
object LocationOrder {
    var version by mutableIntStateOf(0)
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("locations", Context.MODE_PRIVATE)

    fun saved(ctx: Context): List<String> =
        prefs(ctx).getString("order", "").orEmpty().split('\n').filter { it.isNotEmpty() }

    fun save(ctx: Context, keys: List<String>) {
        prefs(ctx).edit().putString("order", keys.joinToString("\n")).apply()
        version++
    }
}

/** Phone storage first, then cloud drives, unless you moved them; new ones go at the end. */
@Composable
fun orderedLocations(volumes: List<Volume>, clouds: List<CloudDrive>): List<Location> {
    val ctx = LocalContext.current
    val v = LocationOrder.version
    return remember(volumes, clouds, v) {
        val all = volumes.map { Location.Vol(it) } + clouds.map { Location.Drive(it) }
        val order = LocationOrder.saved(ctx)
        all.withIndex().sortedWith(compareBy({ order.indexOf(it.value.key).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.index }))
            .map { it.value }
    }
}

/** Up and down arrows shown on each Location while editing. */
@Composable
fun MoveArrows(locs: List<Location>, i: Int) {
    val ctx = LocalContext.current
    fun move(to: Int) {
        if (to !in locs.indices) return
        val keys = locs.map { it.key }.toMutableList()
        keys.add(to, keys.removeAt(i))
        LocationOrder.save(ctx, keys)
    }
    Row {
        Icon(
            Icons.Filled.KeyboardArrowUp, "Move Up", tint = systemAccent(),
            modifier = Modifier.size(32.dp).alpha(if (i > 0) 1f else 0.25f).tap(enabled = i > 0) { move(i - 1) }.padding(4.dp),
        )
        Icon(
            Icons.Filled.KeyboardArrowDown, "Move Down", tint = systemAccent(),
            modifier = Modifier.size(32.dp).alpha(if (i < locs.lastIndex) 1f else 0.25f)
                .tap(enabled = i < locs.lastIndex) { move(i + 1) }.padding(4.dp),
        )
    }
}

/** The cloud app's own icon, rounded like the Locations icons in iOS Files. */
@Composable
fun CloudIcon(d: CloudDrive, size: Dp = 24.dp) {
    val img = remember(d.pkg) { d.icon?.asImageBitmap() }
    if (img != null) Image(img, null, Modifier.size(size).clip(RoundedCornerShape(size / 4.5f)))
    else Icon(IosIcons.Server, null, tint = LocalIos.current.label, modifier = Modifier.size(size))
}

/** Cloud drives on this phone. Re-read whenever Files' contents refresh, in case one was installed. */
@Composable
fun rememberCloudDrives(): List<CloudDrive> {
    val ctx = LocalContext.current
    val store = LocalFileStore.current
    return remember(store.version) { Cloud.drives(ctx) }
}

/** Opens the cloud drive's own app. */
fun openCloud(ctx: Context, d: CloudDrive, onError: (String) -> Unit) {
    val i = ctx.packageManager.getLaunchIntentForPackage(d.pkg)
    if (i == null) { onError("Couldn't open ${d.title}."); return }
    runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { onError("Couldn't open ${d.title}.") }
}
