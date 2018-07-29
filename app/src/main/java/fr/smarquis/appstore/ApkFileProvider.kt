package fr.smarquis.appstore

import android.content.Context
import android.net.Uri
import android.os.AsyncTask
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileFilter
import java.util.*
import java.util.concurrent.TimeUnit

class ApkFileProvider : FileProvider() {

    companion object {

        private const val TAG = "ApkFileProvider"

        fun uri(file: File, context: Context): Uri = getUriForFile(context, "${context.packageName}.apk_provider", file)

        private fun apkFilename(version: Version, withExtension: Boolean = true) = "${version.key}_${version.apkGeneration}${if (withExtension) ".apk" else ""}"

        fun tempApkFile(context: Context, version: Version): File = File(context.cacheDir, "~${apkFilename(version)}")

        fun apkFile(context: Context, version: Version): File {
            return File(context.filesDir, apkFilename(version)).apply {
                if (exists()) {
                    setLastModified(System.currentTimeMillis())
                }
            }
        }

        fun invalidate(context: Context) {
            val filter = FileFilter { it.endsWith(".apk") }
            AsyncTask.execute { delete(context, filter) }
        }

        fun cleanUp(context: Context) {
            val time = Date().time
            val month = TimeUnit.DAYS.toMillis(30)
            val filter = FileFilter { it.endsWith(".apk") && time - it.lastModified() > month }
            AsyncTask.execute { delete(context, filter) }
        }

        private fun delete(context: Context, fileFilter: FileFilter?) {
            deleteFiles(context.cacheDir, fileFilter)
            deleteFiles(context.filesDir, fileFilter)
        }

        private fun deleteFiles(dir: File, filter: FileFilter? = null) {
            for (file in dir.listFiles(filter) ?: return) {
                if (!file.isDirectory) {
                    if (!file.delete()) {
                        Log.w(TAG, "Failed to delete file: $file")
                    }
                }
            }
        }
    }

}
