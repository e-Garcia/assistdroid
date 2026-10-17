package dev.egarcia.harness.diag

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Lets the share sheet read exported reports, and nothing else. Not exported: access exists only through the
 * temporary URI grant on the share intent. Files are looked up by bare name inside the export folder, so a path
 * such as `../shared_prefs/x` cannot escape it. Read-only.
 */
class DiagFileProvider : ContentProvider() {

    override fun onCreate() = true

    private fun fileFor(uri: Uri): File {
        val name = uri.lastPathSegment.orEmpty()
        if (!SAFE_NAME.matches(name)) throw FileNotFoundException("bad name")
        val file = File(DiagStore.exportDir(context!!), name)
        if (!file.isFile) throw FileNotFoundException(name)
        return file
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        val file = fileFor(uri)
        android.util.Log.i("HarnessDiag", "serving ${file.name} to another app")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = fileFor(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { if (it == OpenableColumns.SIZE) file.length() else file.name })
        }
    }

    override fun getType(uri: Uri) = "text/plain"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()

    companion object {
        private val SAFE_NAME = Regex("harness-diagnostics-[0-9-]+\\.txt")

        fun uriFor(packageName: String, file: File): Uri = Uri.parse("content://$packageName.diag/${file.name}")
    }
}
