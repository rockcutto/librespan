package eu.siacs.conversations.ui.media

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import eu.siacs.conversations.R
import kotlin.math.max
import kotlin.math.roundToInt

object MediaMessageChromeRenderer {

    private const val BACKDROP_SAMPLE_MAX_PX = 48
    private const val BACKDROP_BLUR_RADIUS = 4
    @JvmStatic
    fun applyConnectedMediaClip(view: View, radiusDp: Int) {
        if (view.getTag(R.id.media_visual_container) == radiusDp) {
            return
        }
        view.clipToOutline = true
        view.outlineProvider =
            object : ViewOutlineProvider() {
                override fun getOutline(target: View, outline: Outline) {
                    val maxRadius = minOf(target.width, target.height) / 2f
                    val radius = minOf(dp(target, radiusDp), maxRadius)
                    outline.setRoundRect(0, 0, target.width, target.height + radius.toInt(), radius)
                }
            }
        view.setTag(R.id.media_visual_container, radiusDp)
    }

    private fun applyStandaloneClip(view: View, radiusDp: Int) {
        val tag = -radiusDp
        if (view.getTag(R.id.media_visual_container) == tag) {
            return
        }
        view.clipToOutline = true
        view.outlineProvider =
            object : ViewOutlineProvider() {
                override fun getOutline(target: View, outline: Outline) {
                    val maxRadius = minOf(target.width, target.height) / 2f
                    val radius = minOf(dp(target, radiusDp), maxRadius)
                    outline.setRoundRect(0, 0, target.width, target.height, radius)
                }
            }
        view.setTag(R.id.media_visual_container, tag)
    }

    private fun applyStandaloneClipToVisibleMedia(messageBox: View) {
        val image = messageBox.findViewById<ImageView>(R.id.message_image)
        val album = messageBox.findViewById<View>(R.id.media_album)
        if (image?.visibility == View.VISIBLE) {
            applyStandaloneClip(image, 16)
        }
        if (album?.visibility == View.VISIBLE) {
            applyStandaloneClip(album, 12)
        }
    }

    @JvmStatic
    fun bind(
        messageBox: View,
        mediaFooter: LinearLayout,
        hasMediaVisual: Boolean,
        mediaWidth: Int,
        @DrawableRes bubbleBackground: Int,
        bubbleTint: ColorStateList,
    ) = bind(
        messageBox,
        mediaFooter,
        hasMediaVisual,
        true,
        mediaWidth,
        bubbleBackground,
        bubbleTint,
    )

    @JvmStatic
    fun bind(
        messageBox: View,
        mediaFooter: LinearLayout,
        hasMediaVisual: Boolean,
        hasMediaFooterSurface: Boolean,
        mediaWidth: Int,
        @DrawableRes bubbleBackground: Int,
        bubbleTint: ColorStateList,
    ) {
        val footerParams = mediaFooter.layoutParams as LinearLayout.LayoutParams
        if (hasMediaVisual) {
            messageBox.background = null
            messageBox.backgroundTintList = null
            if (hasMediaFooterSurface) {
                val footerBackground = mediaFooterBackground(bubbleBackground)
                if (mediaFooter.getTag(R.id.media_footer) != footerBackground) {
                    mediaFooter.setBackgroundResource(footerBackground)
                    mediaFooter.setTag(R.id.media_footer, footerBackground)
                }
                applyBubbleSurface(mediaFooter, bubbleTint)
                mediaFooter.minimumWidth = mediaWidth.coerceAtLeast(0)
                bindStandaloneMediaBackdrop(messageBox, mediaFooter, mediaWidth)
            } else {
                mediaFooter.background = null
                mediaFooter.backgroundTintList = null
                mediaFooter.setTag(R.id.media_footer, null)
                mediaFooter.minimumWidth = 0
                clearStandaloneMediaBackdrop(messageBox)
                applyStandaloneClipToVisibleMedia(messageBox)
            }
            if (footerParams.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
                footerParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
                mediaFooter.layoutParams = footerParams
            }
        } else {
            messageBox.setBackgroundResource(bubbleBackground)
            applyBubbleSurface(messageBox, bubbleTint)
            mediaFooter.background = null
            mediaFooter.backgroundTintList = null
            mediaFooter.setTag(R.id.media_footer, null)
            mediaFooter.minimumWidth = 0
            if (footerParams.width != ViewGroup.LayoutParams.MATCH_PARENT) {
                footerParams.width = ViewGroup.LayoutParams.MATCH_PARENT
                mediaFooter.layoutParams = footerParams
            }
            clearStandaloneMediaBackdrop(messageBox)
        }
    }

    private fun bindStandaloneMediaBackdrop(
        messageBox: View,
        mediaFooter: LinearLayout,
        mediaWidth: Int,
    ) {
        val mediaContainer =
            messageBox.findViewById<ViewGroup>(R.id.media_visual_container) ?: return
        val image = messageBox.findViewById<ImageView>(R.id.message_image) ?: return
        val album = messageBox.findViewById<View>(R.id.media_album)

        clearStandaloneMediaBackdrop(mediaContainer, image)
        if (mediaWidth <= 0 || image.visibility != View.VISIBLE || album?.visibility == View.VISIBLE) {
            return
        }

        val bindToken = Any()
        mediaContainer.setTag(R.id.media_footer, bindToken)

        val preDrawListener =
            object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (mediaFooter.viewTreeObserver.isAlive) {
                        mediaFooter.viewTreeObserver.removeOnPreDrawListener(this)
                    }

                    if (mediaContainer.getTag(R.id.media_footer) !== bindToken
                        || image.visibility != View.VISIBLE
                        || album?.visibility == View.VISIBLE
                    ) {
                        return true
                    }

                    val footerWidth = mediaFooter.measuredWidth
                    val backdropWidth = max(mediaWidth, footerWidth)
                    if (backdropWidth <= mediaWidth) {
                        clearStandaloneMediaBackdrop(mediaContainer, image)
                        return true
                    }

                    var geometryChanged = false
                    val containerParams = mediaContainer.layoutParams
                    if (containerParams.width != backdropWidth) {
                        containerParams.width = backdropWidth
                        mediaContainer.layoutParams = containerParams
                        geometryChanged = true
                    }

                    (image.layoutParams as? LinearLayout.LayoutParams)?.let { imageParams ->
                        if (imageParams.gravity != Gravity.CENTER_HORIZONTAL) {
                            imageParams.gravity = Gravity.CENTER_HORIZONTAL
                            image.layoutParams = imageParams
                            geometryChanged = true
                        }
                    }

                    applyConnectedMediaClip(mediaContainer, 16)
                    val backdropHeight =
                        image.height.takeIf { it > 0 }
                            ?: image.layoutParams.height.takeIf { it > 0 }

                    if (backdropHeight != null) {
                        applyBlurredBackdropWhenReady(
                            image,
                            mediaContainer,
                            bindToken,
                            backdropWidth,
                            backdropHeight,
                        )
                    }

                    return !geometryChanged
                }
            }

        mediaFooter.viewTreeObserver.addOnPreDrawListener(preDrawListener)
    }

    private fun applyBlurredBackdropWhenReady(
        image: ImageView,
        mediaContainer: ViewGroup,
        bindToken: Any,
        targetWidth: Int,
        targetHeight: Int,
    ) {
        if (mediaContainer.getTag(R.id.media_footer) !== bindToken) {
            return
        }

        val backdrop = createBlurredBackdrop(image, targetWidth, targetHeight)
        if (backdrop != null) {
            mediaContainer.background = backdrop
            return
        }

        val listener =
            object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    view: View,
                    left: Int,
                    top: Int,
                    right: Int,
                    bottom: Int,
                    oldLeft: Int,
                    oldTop: Int,
                    oldRight: Int,
                    oldBottom: Int,
                ) {
                    if (mediaContainer.getTag(R.id.media_footer) !== bindToken) {
                        image.removeOnLayoutChangeListener(this)
                        if (image.getTag(R.id.media_footer) === this) {
                            image.setTag(R.id.media_footer, null)
                        }
                        return
                    }

                    val loadedBackdrop =
                        createBlurredBackdrop(image, targetWidth, targetHeight) ?: return
                    mediaContainer.background = loadedBackdrop
                    image.removeOnLayoutChangeListener(this)
                    if (image.getTag(R.id.media_footer) === this) {
                        image.setTag(R.id.media_footer, null)
                    }
                }
            }
        image.setTag(R.id.media_footer, listener)
        image.addOnLayoutChangeListener(listener)
    }

    private fun createBlurredBackdrop(
        image: ImageView,
        targetWidth: Int,
        targetHeight: Int,
    ): BitmapDrawable? {
        val source = (image.drawable as? BitmapDrawable)?.bitmap ?: return null
        if (source.isRecycled || source.width <= 0 || source.height <= 0) {
            return null
        }

        val longestSide = max(targetWidth, targetHeight).coerceAtLeast(1)
        val scale = minOf(1f, BACKDROP_SAMPLE_MAX_PX.toFloat() / longestSide)
        val sampleWidth = max(1, (targetWidth * scale).roundToInt())
        val sampleHeight = max(1, (targetHeight * scale).roundToInt())
        val sample = Bitmap.createBitmap(sampleWidth, sampleHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sample)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val sourceRect = centerCropRect(source.width, source.height, sampleWidth, sampleHeight)
        canvas.drawBitmap(source, sourceRect, Rect(0, 0, sampleWidth, sampleHeight), paint)
        boxBlur(sample, BACKDROP_BLUR_RADIUS)

        return BitmapDrawable(image.resources, sample).apply {
            gravity = Gravity.FILL
            isFilterBitmap = true
            setAntiAlias(true)
        }
    }

    private fun centerCropRect(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Rect {
        val sourceRatio = sourceWidth.toFloat() / sourceHeight
        val targetRatio = targetWidth.toFloat() / targetHeight
        return if (sourceRatio > targetRatio) {
            val cropWidth = (sourceHeight * targetRatio).roundToInt().coerceAtLeast(1)
            val left = ((sourceWidth - cropWidth) / 2).coerceAtLeast(0)
            Rect(left, 0, (left + cropWidth).coerceAtMost(sourceWidth), sourceHeight)
        } else {
            val cropHeight = (sourceWidth / targetRatio).roundToInt().coerceAtLeast(1)
            val top = ((sourceHeight - cropHeight) / 2).coerceAtLeast(0)
            Rect(0, top, sourceWidth, (top + cropHeight).coerceAtMost(sourceHeight))
        }
    }

    private fun boxBlur(bitmap: Bitmap, radius: Int) {
        if (radius <= 0 || bitmap.width <= 1 || bitmap.height <= 1) {
            return
        }
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        val scratch = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        blurHorizontal(pixels, scratch, width, height, radius)
        blurVertical(scratch, pixels, width, height, radius)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun blurHorizontal(
        input: IntArray,
        output: IntArray,
        width: Int,
        height: Int,
        radius: Int,
    ) {
        val window = radius * 2 + 1
        for (y in 0 until height) {
            val row = y * width
            var alpha = 0
            var red = 0
            var green = 0
            var blue = 0
            for (offset in -radius..radius) {
                val color = input[row + offset.coerceIn(0, width - 1)]
                alpha += color ushr 24
                red += color ushr 16 and 0xff
                green += color ushr 8 and 0xff
                blue += color and 0xff
            }
            for (x in 0 until width) {
                output[row + x] = argb(alpha / window, red / window, green / window, blue / window)
                val remove = input[row + (x - radius).coerceIn(0, width - 1)]
                val add = input[row + (x + radius + 1).coerceIn(0, width - 1)]
                alpha += (add ushr 24) - (remove ushr 24)
                red += (add ushr 16 and 0xff) - (remove ushr 16 and 0xff)
                green += (add ushr 8 and 0xff) - (remove ushr 8 and 0xff)
                blue += (add and 0xff) - (remove and 0xff)
            }
        }
    }

    private fun blurVertical(
        input: IntArray,
        output: IntArray,
        width: Int,
        height: Int,
        radius: Int,
    ) {
        val window = radius * 2 + 1
        for (x in 0 until width) {
            var alpha = 0
            var red = 0
            var green = 0
            var blue = 0
            for (offset in -radius..radius) {
                val color = input[offset.coerceIn(0, height - 1) * width + x]
                alpha += color ushr 24
                red += color ushr 16 and 0xff
                green += color ushr 8 and 0xff
                blue += color and 0xff
            }
            for (y in 0 until height) {
                output[y * width + x] = argb(alpha / window, red / window, green / window, blue / window)
                val remove = input[(y - radius).coerceIn(0, height - 1) * width + x]
                val add = input[(y + radius + 1).coerceIn(0, height - 1) * width + x]
                alpha += (add ushr 24) - (remove ushr 24)
                red += (add ushr 16 and 0xff) - (remove ushr 16 and 0xff)
                green += (add ushr 8 and 0xff) - (remove ushr 8 and 0xff)
                blue += (add and 0xff) - (remove and 0xff)
            }
        }
    }

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        alpha shl 24 or (red shl 16) or (green shl 8) or blue

    private fun clearStandaloneMediaBackdrop(messageBox: View) {
        val mediaContainer =
            messageBox.findViewById<ViewGroup>(R.id.media_visual_container) ?: return
        val image = messageBox.findViewById<ImageView>(R.id.message_image) ?: return
        clearStandaloneMediaBackdrop(mediaContainer, image)
    }

    private fun clearStandaloneMediaBackdrop(
        mediaContainer: ViewGroup,
        image: ImageView,
    ) {
        (image.getTag(R.id.media_footer) as? View.OnLayoutChangeListener)?.let {
            image.removeOnLayoutChangeListener(it)
        }
        image.setTag(R.id.media_footer, null)
        mediaContainer.setTag(R.id.media_footer, null)
        mediaContainer.background = null
        mediaContainer.clipToOutline = false
        mediaContainer.outlineProvider = ViewOutlineProvider.BACKGROUND
        mediaContainer.setTag(R.id.media_visual_container, null)
        val containerParams = mediaContainer.layoutParams
        if (containerParams.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
            containerParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
            mediaContainer.layoutParams = containerParams
        }
        (image.layoutParams as? LinearLayout.LayoutParams)?.let { imageParams ->
            if (imageParams.gravity != -1) {
                imageParams.gravity = -1
                image.layoutParams = imageParams
            }
        }
    }

    private fun applyBubbleSurface(
        view: View,
        bubbleTint: ColorStateList,
    ) {
        val background =
            view.background?.mutate() as? GradientDrawable ?: return

        // Message depth comes from tonal separation. A 1dp outline was useful against the old
        // noisy/legacy background, but on the clean wallpaper it reads as an unrelated card
        // border, especially around incoming dark-theme bubbles.
        background.setColor(bubbleTint.defaultColor)
        background.setStroke(0, android.graphics.Color.TRANSPARENT)
        view.backgroundTintList = null
    }

    private fun dp(view: View, value: Int): Float =
        value * view.resources.displayMetrics.density

    @DrawableRes
    private fun mediaFooterBackground(@DrawableRes bubbleBackground: Int): Int =
        when (bubbleBackground) {
            R.drawable.background_message_bubble_sent,
            R.drawable.background_message_bubble_sent_last ->
                R.drawable.background_media_footer_sent
            R.drawable.background_message_bubble_received,
            R.drawable.background_message_bubble_received_last ->
                R.drawable.background_media_footer_received
            R.drawable.background_message_bubble_sent_first,
            R.drawable.background_message_bubble_sent_middle ->
                R.drawable.background_media_footer_sent_group
            R.drawable.background_message_bubble_received_first,
            R.drawable.background_message_bubble_received_middle ->
                R.drawable.background_media_footer_received_group
            else -> R.drawable.background_media_footer_non_last
        }
}
