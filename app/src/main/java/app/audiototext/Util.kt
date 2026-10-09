package app.audiototext

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Util {

    /** "file2" comes before "file10". */
    fun natural(a: String, b: String): Int {
        val ra = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
        val rb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
        val n = minOf(ra.size, rb.size)
        for (i in 0 until n) {
            val x = ra[i]
            val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                val d = x.toBigInteger().compareTo(y.toBigInteger())
                if (d != 0) d else x.length.compareTo(y.length)
            } else {
                x.compareTo(y)
            }
            if (c != 0) return c
        }
        return ra.size.compareTo(rb.size)
    }

    fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun displayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrEmpty()) return n
                }
            }
        } catch (e: Exception) {
            // fall through
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "audio"
    }

    fun joinParts(parts: List<String>): String =
        if (parts.size == 1) parts[0]
        else parts.mapIndexed { i, t -> "--- Part ${i + 1} ---\n$t" }.joinToString("\n\n")

    fun buildCombined(items: List<Pair<String, String>>): String {
        val sb = StringBuilder()
        sb.append("Audio to text\n")
        sb.append("Files: ").append(items.size).append("\n")
        sb.append("(Approximate transcript; some words may be wrong)\n")
        items.forEachIndexed { i, it ->
            sb.append("\n==================================================\n")
            sb.append("File ").append(i + 1).append(": ").append(it.first).append("\n")
            sb.append("==================================================\n")
            sb.append(it.second).append("\n")
        }
        return sb.toString()
    }

    /** Saves a text file into the public Downloads folder and returns its name. */
    fun saveToDownloads(ctx: Context, text: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "audio-text-$stamp.txt"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create a file in Downloads")
        val out = ctx.contentResolver.openOutputStream(uri) ?: throw IOException("Could not open the new file")
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return name
    }
}
