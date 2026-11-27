package com.photos.files.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.provider.DocumentsContract
import androidx.core.graphics.drawable.toBitmap

/** A cloud app on the phone (Nextcloud, OneDrive, Google Drive, Dropbox…) that shares its files with other apps. */
data class CloudDrive(val authority: String, val pkg: String, val title: String, val icon: Bitmap?)

/**
 * Cloud drives, like the ones iOS Files lists under Locations. Only apps that are installed show up,
 * and tapping one opens that app.
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
}
