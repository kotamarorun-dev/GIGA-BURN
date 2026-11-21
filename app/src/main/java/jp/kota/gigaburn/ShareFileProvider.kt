package jp.kota.gigaburn

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
 * CSV共有専用の最小ContentProvider（androidxのFileProviderの代替、自前実装）。
 * content://jp.kota.gigaburn.fileprovider/share/<ファイル名> → cacheDir/share/<ファイル名>
 * を読み取り専用で公開する。パストラバーサルは弾く。
 */
class ShareFileProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "jp.kota.gigaburn.fileprovider"

        fun uriFor(file: File): Uri =
            Uri.parse("content://$AUTHORITY/share/${file.name}")
    }

    private fun resolve(uri: Uri): File {
        val name = uri.lastPathSegment ?: throw FileNotFoundException("no name")
        if (name.contains("/") || name.contains("..")) throw FileNotFoundException("bad name")
        val ctx = context ?: throw FileNotFoundException("no context")
        return File(File(ctx.cacheDir, "share"), name)
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = resolve(uri)
        if (!f.exists()) throw FileNotFoundException(f.path)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor {
        val f = resolve(uri)
        val cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        cursor.addRow(arrayOf<Any>(f.name, if (f.exists()) f.length() else 0L))
        return cursor
    }

    override fun getType(uri: Uri): String =
        if (uri.lastPathSegment?.endsWith(".csv") == true) "text/csv" else "application/octet-stream"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
