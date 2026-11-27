package com.robomorse.app.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Writes all practice data to a JSON file in the app's cache and returns a share sheet
 * for it. Nothing is sent anywhere unless the learner picks an app to send it with.
 */
suspend fun practiceDataShareIntent(context: Context, repository: Repository): Intent = withContext(Dispatchers.IO) {
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    val json = repository.exportJson(version ?: "?")
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() } // keep only the latest export
    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
    val file = File(dir, "robo-morse-data-$stamp.json").apply { writeText(json) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Robo Morse practice data")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    Intent.createChooser(send, "Share practice data")
}
