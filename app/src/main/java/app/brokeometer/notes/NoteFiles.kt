package app.brokeometer.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.LruCache
import java.io.File

/**
 * Notes' own files on the phone: photos picked as a background (made smaller first) and voice
 * recordings. They live in the app's private folder and never leave the phone.
 */
object NoteFiles {

    /** The longest side a background photo is kept at. */
    private const val PHOTO_SIDE = 1600

    fun dir(context: Context): File = File(context.filesDir, "notes").apply { mkdirs() }

    /** Copies the photo at [uri] in, smaller and the right way up. Its path, or null if it couldn't be read. Slow: not on the main thread. */
    fun savePhoto(context: Context, uri: Uri): String? = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PHOTO_SIDE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val turn = resolver.openInputStream(uri)?.use { exifTurn(ExifInterface(it)) } ?: 0f
        val scale = minOf(1f, PHOTO_SIDE.toFloat() / maxOf(decoded.width, decoded.height))
        val m = Matrix().apply {
            postScale(scale, scale)
            postRotate(turn)
        }
        val out = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        val file = File(dir(context), "photo_${System.currentTimeMillis()}.jpg")
        file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        file.absolutePath
    }.getOrNull()

    private fun exifTurn(exif: ExifInterface): Float = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }

    /** Copies a voice recording in. Its path, or null. Slow: not on the main thread. */
    fun saveRecording(context: Context, uri: Uri): String? = runCatching {
        val type = context.contentResolver.getType(uri).orEmpty()
        val ext = when {
            "amr" in type -> "amr"
            "3gp" in type -> "3gp"
            "ogg" in type -> "ogg"
            "mp4" in type || "m4a" in type || "aac" in type -> "m4a"
            "wav" in type -> "wav"
            else -> "amr"
        }
        val file = File(dir(context), "voice_${System.currentTimeMillis()}.$ext")
        val copied = context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: 0L
        if (copied <= 0L) {
            file.delete()
            null
        } else {
            file.absolutePath
        }
    }.getOrNull()

    /** Deletes files no note uses any more (and that aren't brand new, in case a note is still being saved). */
    fun sweep(context: Context, used: Set<String>, now: Long = System.currentTimeMillis()) {
        dir(context).listFiles()?.forEach { f ->
            if (f.absolutePath !in used && now - f.lastModified() > 3_600_000L) f.delete()
        }
    }

    private val cache = LruCache<String, Bitmap>(24)

    /** A photo at no more than about [side] pixels across, for showing. Slow the first time: not on the main thread. */
    fun load(path: String, side: Int): Bitmap? {
        val key = "$path@$side"
        cache.get(key)?.let { return it }
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()?.also { cache.put(key, it) }
    }
}
