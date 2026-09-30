package com.cutetodo.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Foto disalin ke penyimpanan internal aplikasi (filesDir/photos) dan diperkecil, jadi tetap ada
 * walau foto asli di galeri dihapus, dan tidak butuh izin storage apa pun.
 * Di database hanya disimpan NAMA file-nya.
 */
object Photos {
    private const val DIR = "photos"
    private const val MAX_SIDE = 1280

    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, DIR).apply { mkdirs() }

    private fun file(ctx: Context, name: String): File? =
        if (name.isBlank() || name.contains('/') || name.contains('\\')) null else File(dir(ctx), name)

    fun exists(ctx: Context, name: String): Boolean = file(ctx, name)?.exists() == true

    /** File foto (untuk sinkronisasi). Null bila nama tidak valid. */
    fun fileOf(ctx: Context, name: String): File? = file(ctx, name)

    private fun sample(w: Int, h: Int, target: Int): Int {
        var s = 1
        while (maxOf(w, h) / (s * 2) >= target) s *= 2
        return s
    }

    /** Salin foto pilihan ke penyimpanan aplikasi. Mengembalikan nama file, atau null bila gagal. */
    suspend fun import(ctx: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val tmp = File(ctx.cacheDir, "pick-${UUID.randomUUID()}")
        try {
            ctx.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                ?: return@withContext null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

            val opts = BitmapFactory.Options().apply { inSampleSize = sample(bounds.outWidth, bounds.outHeight, MAX_SIDE) }
            var bmp = BitmapFactory.decodeFile(tmp.path, opts) ?: return@withContext null

            val degrees = when (ExifInterface(tmp.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (degrees != 0f) {
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
                if (rotated !== bmp) bmp.recycle()
                bmp = rotated
            }

            val name = "${UUID.randomUUID()}.jpg"
            File(dir(ctx), name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            bmp.recycle()
            name
        } catch (e: Throwable) {
            null
        } finally {
            tmp.delete()
        }
    }

    /** Muat foto (diperkecil ke [maxSide] px) dengan cache memori. */
    suspend fun load(ctx: Context, name: String, maxSide: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$name@$maxSide"
        cache.get(key)?.let { return@withContext it }
        val f = file(ctx, name)?.takeIf { it.exists() } ?: return@withContext null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            if (bounds.outWidth <= 0) return@withContext null
            val opts = BitmapFactory.Options().apply { inSampleSize = sample(bounds.outWidth, bounds.outHeight, maxSide) }
            BitmapFactory.decodeFile(f.path, opts)?.also { cache.put(key, it) }
        } catch (e: Throwable) {
            null
        }
    }

    /** Hapus file foto yang tidak lagi dipakai tugas mana pun. */
    fun cleanup(ctx: Context, keep: Set<String>) {
        val now = System.currentTimeMillis()
        // file baru (< 10 menit) dibiarkan: mungkin foto yang sedang dipilih dan belum disimpan
        dir(ctx).listFiles()?.forEach { if (it.name !in keep && now - it.lastModified() > 600_000L) it.delete() }
        cache.evictAll()
    }
}

@Composable
fun PhotoImage(
    name: String,
    maxSide: Int,
    modifier: Modifier = Modifier,
    scale: ContentScale = ContentScale.Crop
) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, name, maxSide) { value = Photos.load(ctx, name, maxSide) }
    Crossfade(bmp, label = "photo") { b ->
        if (b != null) {
            val img = remember(b) { b.asImageBitmap() }
            Image(img, contentDescription = "Foto tugas", modifier = modifier, contentScale = scale)
        } else {
            Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
        }
    }
}
