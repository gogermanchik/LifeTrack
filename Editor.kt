package com.lifetrack.data

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.File

object FoodPhotoProcessor {
    fun prepare(context: Context, uri: Uri): File {
        val bitmap =
            if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(context.contentResolver, uri)
                ) { decoder, info, _ ->
                    val max = maxOf(info.size.width, info.size.height)
                    if (max > 1024)
                        decoder.setTargetSize(
                            (info.size.width * 1024L / max).toInt(),
                            (info.size.height * 1024L / max).toInt(),
                        )
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
                require(options.outWidth > 0 && options.outHeight > 0) {
                    "Не удалось прочитать фото"
                }
                var sample = 1
                while (maxOf(options.outWidth, options.outHeight) / sample > 1024) sample *= 2
                val decoded =
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(
                            it,
                            null,
                            BitmapFactory.Options().apply { inSampleSize = sample },
                        )
                    } ?: error("Не удалось прочитать фото")
                val orientation =
                    context.contentResolver.openInputStream(uri)?.use {
                        ExifInterface(it)
                            .getAttributeInt(
                                ExifInterface.TAG_ORIENTATION,
                                ExifInterface.ORIENTATION_NORMAL,
                            )
                    } ?: 1
                val matrix = Matrix()
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> {
                        matrix.setRotate(90f)
                        matrix.postScale(-1f, 1f)
                    }
                    ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> {
                        matrix.setRotate(270f)
                        matrix.postScale(-1f, 1f)
                    }
                    ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
                }
                if (matrix.isIdentity) decoded
                else
                    Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                        .also { if (it != decoded) decoded.recycle() }
            }
        require(bitmap.width > 0 && bitmap.height > 0)
        val dir = File(context.cacheDir, "food_photos").apply { mkdirs() }
        val file = File.createTempFile("prepared-", ".jpg", dir)
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        } finally {
            bitmap.recycle()
        }
        return file
    }
}
