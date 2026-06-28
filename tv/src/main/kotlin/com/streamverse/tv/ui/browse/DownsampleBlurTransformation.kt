package com.streamverse.tv.ui.browse

import android.graphics.Bitmap
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.security.MessageDigest

/**
 * Cheap, dependency-free "blur" for channel-logo backdrops: shrink the bitmap to a tiny size, then
 * let the host ImageView (CENTER_CROP) upscale it — bilinear filtering smears it into a soft, full-
 * bleed wash of the logo's colours. Used behind the crisp logo so every TV card *fills* its box
 * Netflix-style instead of a small logo floating on empty space.
 */
class DownsampleBlurTransformation(private val sampleWidth: Int = 32) : BitmapTransformation() {

    override fun transform(
        pool: BitmapPool,
        toTransform: Bitmap,
        outWidth: Int,
        outHeight: Int,
    ): Bitmap {
        if (toTransform.width <= sampleWidth) return toTransform
        val ratio = toTransform.height.toFloat() / toTransform.width.toFloat()
        val w = sampleWidth
        val h = (w * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(toTransform, w, h, true)
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update("$ID$sampleWidth".toByteArray(Charsets.UTF_8))
    }

    override fun equals(other: Any?): Boolean =
        other is DownsampleBlurTransformation && other.sampleWidth == sampleWidth

    override fun hashCode(): Int = ID.hashCode() * 31 + sampleWidth

    private companion object {
        const val ID = "com.streamverse.tv.ui.browse.DownsampleBlurTransformation"
    }
}
