package eu.siacs.conversations.medialib.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import eu.siacs.conversations.R
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class EditorDrawCanvas(context: Context, attrs: AttributeSet) : View(context, attrs) {

    enum class Tool {
        TEXT,
        MARKER,
        HIGHLIGHTER,
        PIXELATE,
    }

    enum class TextBackground {
        NONE,
        LIGHT,
        DARK,
    }

    private sealed class Operation {
        data class Stroke(
            val path: Path,
            val color: Int,
            val strokeWidth: Float,
            val alpha: Int,
        ) : Operation()

        data class Pixelate(
            val path: Path,
            val strokeWidth: Float,
        ) : Operation()

        data class Text(
            val value: String,
            var x: Float,
            var y: Float,
            var color: Int,
            var sizePx: Float,
            var rotationDegrees: Float = 0f,
            var background: TextBackground = TextBackground.NONE,
        ) : Operation()
    }

    private data class ImageTransform(
        val scale: Float,
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
    )

    private val operations = mutableListOf<Operation>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var currentPath = Path()
    private var currentColor = context.getColor(R.color.editor_draw_default_color)
    private var currentTool = Tool.MARKER
    private var currentStrokeWidthSource = 0f
    private var draggedText: Operation.Text? = null
    private var activeText: Operation.Text? = null
    private var currentTextBackground = TextBackground.NONE
    private val textBackgroundLight = context.getColor(R.color.white87)
    private val textBackgroundDark = context.getColor(R.color.black87)
    private var textDragOffsetX = 0f
    private var textDragOffsetY = 0f
    private var textTransformGesture = false
    private var textTransformInitialDistance = 0f
    private var textTransformInitialAngle = 0f
    private var textTransformInitialSize = 0f
    private var textTransformInitialRotation = 0f
    private var textTransformInitialX = 0f
    private var textTransformInitialY = 0f
    private var textTransformInitialCentroid = PointF()
    private var currentX = 0f
    private var currentY = 0f
    private var startX = 0f
    private var startY = 0f
    private var wasMultitouch = false
    private var backgroundBitmap: Bitmap? = null
    private var pixelatedSample: Bitmap? = null
    private var pixelateShader: BitmapShader? = null
    private var editStateChangedListener: (() -> Unit)? = null

    private var viewportScale = 1f
    private var viewportOffsetX = 0f
    private var viewportOffsetY = 0f
    private var navigationGesture = false
    private var panX = 0f
    private var panY = 0f
    private val maxViewportScale = 4f

    private val scaleGestureDetector =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    navigationGesture = true
                    currentPath = Path()
                    draggedText = null
                    return backgroundBitmap != null
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val bitmap = backgroundBitmap ?: return false
                    val before = imageTransform(bitmap) ?: return false
                    val focusX = detector.focusX
                    val focusY = detector.focusY
                    val imageX = (focusX - before.left) / before.scale
                    val imageY = (focusY - before.top) / before.scale

                    viewportScale =
                        (viewportScale * detector.scaleFactor)
                            .coerceIn(1f, maxViewportScale)

                    val baseScale = baseImageScale(bitmap)
                    val newScale = baseScale * viewportScale
                    val centeredLeft = (width - bitmap.width * newScale) / 2f
                    val centeredTop = (height - bitmap.height * newScale) / 2f
                    viewportOffsetX = focusX - imageX * newScale - centeredLeft
                    viewportOffsetY = focusY - imageY * newScale - centeredTop
                    clampViewport(bitmap)
                    invalidate()
                    return true
                }
            },
        )

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private var markerWidthLevel = 1
    private var highlighterWidthLevel = 1
    private var pixelateWidthLevel = 1

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = backgroundBitmap ?: return
        val transform = imageTransform(bitmap) ?: return
        val destination =
            RectF(
                transform.left,
                transform.top,
                transform.left + transform.width,
                transform.top + transform.height,
            )
        paint.reset()
        paint.isAntiAlias = true
        canvas.drawBitmap(bitmap, null, destination, paint)

        canvas.save()
        canvas.translate(transform.left, transform.top)
        canvas.scale(transform.scale, transform.scale)
        operations.forEach { drawOperation(canvas, it) }
        if (!currentPath.isEmpty) {
            when (currentTool) {
                Tool.PIXELATE ->
                    drawPixelate(canvas, currentPath, currentStrokeWidthSource)
                Tool.MARKER, Tool.HIGHLIGHTER ->
                    drawStroke(
                        canvas,
                        currentPath,
                        currentColor,
                        currentStrokeWidthSource,
                        alphaFor(currentTool),
                    )
                Tool.TEXT -> Unit
            }
        }
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bitmap = backgroundBitmap ?: return false

        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN &&
            currentTool == Tool.TEXT &&
            beginTextTransformIfNeeded(event, bitmap)
        ) {
            return true
        }
        if (!textTransformGesture) {
            scaleGestureDetector.onTouchEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                navigationGesture = false
                wasMultitouch = false
                panX = event.x
                panY = event.y

                val transform = imageTransform(bitmap) ?: return true
                if (currentTool == Tool.TEXT) {
                    handleTextTouch(event, transform)
                    return true
                }

                val point = mapToImage(event.x, event.y, transform, clamp = false)
                    ?: return true
                startX = point.x
                startY = point.y
                currentStrokeWidthSource = brushWidthViewPx(currentTool) / transform.scale
                currentPath.reset()
                currentPath.moveTo(point.x, point.y)
                currentX = point.x
                currentY = point.y
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (textTransformGesture) {
                    return true
                }
                navigationGesture = true
                wasMultitouch = true
                currentPath = Path()
                draggedText = null
                val centroid = pointerCentroid(event)
                panX = centroid.x
                panY = centroid.y
            }

            MotionEvent.ACTION_MOVE -> {
                if (textTransformGesture) {
                    updateTextTransform(event, bitmap)
                    return true
                }
                if (navigationGesture) {
                    val centroid = pointerCentroid(event)
                    val dx = centroid.x - panX
                    val dy = centroid.y - panY
                    if (viewportScale > 1f) {
                        viewportOffsetX += dx
                        viewportOffsetY += dy
                        clampViewport(bitmap)
                    }
                    panX = centroid.x
                    panY = centroid.y
                    invalidate()
                    return true
                }

                val transform = imageTransform(bitmap) ?: return true
                if (currentTool == Tool.TEXT) {
                    handleTextTouch(event, transform)
                    return true
                }

                val point = mapToImage(event.x, event.y, transform, clamp = true)
                    ?: return true
                if (event.pointerCount == 1 && !wasMultitouch && !currentPath.isEmpty) {
                    currentPath.quadTo(
                        currentX,
                        currentY,
                        (point.x + currentX) / 2f,
                        (point.y + currentY) / 2f,
                    )
                    currentX = point.x
                    currentY = point.y
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (textTransformGesture) {
                    editStateChangedListener?.invoke()
                    return true
                }
                navigationGesture = true
                wasMultitouch = true
                val remaining = pointerCentroid(event, event.actionIndex)
                panX = remaining.x
                panY = remaining.y
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (textTransformGesture) {
                    textTransformGesture = false
                    navigationGesture = false
                    wasMultitouch = false
                    draggedText = null
                    return true
                }
                if (navigationGesture) {
                    navigationGesture = false
                    wasMultitouch = false
                    currentPath = Path()
                    draggedText = null
                    return true
                }

                val transform = imageTransform(bitmap) ?: return true
                if (currentTool == Tool.TEXT) {
                    handleTextTouch(event, transform)
                    return true
                }

                if (!wasMultitouch && !currentPath.isEmpty) {
                    val point = mapToImage(event.x, event.y, transform, clamp = true)
                    if (point != null) {
                        currentX = point.x
                        currentY = point.y
                    }
                    currentPath.lineTo(currentX, currentY)
                    if (startX == currentX && startY == currentY) {
                        currentPath.lineTo(currentX, currentY + (1f / transform.scale))
                    }
                    val completed = Path(currentPath)
                    operations +=
                        if (currentTool == Tool.PIXELATE) {
                            Operation.Pixelate(completed, currentStrokeWidthSource)
                        } else {
                            Operation.Stroke(
                                completed,
                                currentColor,
                                currentStrokeWidthSource,
                                alphaFor(currentTool),
                            )
                        }
                    editStateChangedListener?.invoke()
                }
                currentPath = Path()
                wasMultitouch = false
            }
        }

        invalidate()
        return true
    }

    private fun handleTextTouch(event: MotionEvent, transform: ImageTransform): Boolean {
        val point = mapToImage(event.x, event.y, transform, clamp = true) ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val candidate = findTextAt(point.x, point.y, transform) ?: return false
                draggedText = candidate
                activeText = candidate
                currentTextBackground = candidate.background
                editStateChangedListener?.invoke()
                textDragOffsetX = point.x - candidate.x
                textDragOffsetY = point.y - candidate.y
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val text = draggedText ?: return false
                val bitmap = backgroundBitmap ?: return false
                text.x = (point.x - textDragOffsetX).coerceIn(0f, bitmap.width.toFloat())
                text.y =
                    (point.y - textDragOffsetY)
                        .coerceIn(text.sizePx, bitmap.height.toFloat())
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val hadText = draggedText != null
                draggedText = null
                return hadText
            }
        }
        return false
    }

    private fun findTextAt(
        x: Float,
        y: Float,
        transform: ImageTransform,
    ): Operation.Text? {
        for (index in operations.indices.reversed()) {
            val text = operations[index] as? Operation.Text ?: continue
            if (pointHitsText(text, x, y, transform, 14f)) {
                return text
            }
        }
        return null
    }

    private fun pointHitsText(
        text: Operation.Text,
        x: Float,
        y: Float,
        transform: ImageTransform,
        paddingDp: Float,
    ): Boolean {
        val radians = Math.toRadians((-text.rotationDegrees).toDouble())
        val dx = x - text.x
        val dy = y - text.y
        val localX = (dx * cos(radians) - dy * sin(radians)).toFloat()
        val localY = (dx * sin(radians) + dy * cos(radians)).toFloat()

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textPaint.textSize = text.sizePx
        val lines = text.value.split('\n')
        val maxWidth = lines.maxOfOrNull { textPaint.measureText(it) } ?: 0f
        val lineHeight = text.sizePx * 1.18f
        val totalHeight = text.sizePx + (lines.size - 1) * lineHeight
        val padding = (paddingDp * density) / transform.scale
        return localX in (-maxWidth / 2f - padding)..(maxWidth / 2f + padding) &&
            localY in (-totalHeight / 2f - padding)..(totalHeight / 2f + padding)
    }

    private fun beginTextTransformIfNeeded(
        event: MotionEvent,
        bitmap: Bitmap,
    ): Boolean {
        if (event.pointerCount < 2) {
            return false
        }
        val text = activeText ?: return false
        val transform = imageTransform(bitmap) ?: return false
        val centroidView = pointerCentroid(event)
        val centroidImage =
            mapToImage(centroidView.x, centroidView.y, transform, clamp = true) ?: return false
        if (!pointHitsText(text, centroidImage.x, centroidImage.y, transform, 36f)) {
            return false
        }

        textTransformGesture = true
        navigationGesture = false
        wasMultitouch = true
        draggedText = null
        currentPath = Path()
        textTransformInitialDistance = pointerDistance(event).coerceAtLeast(1f)
        textTransformInitialAngle = pointerAngleDegrees(event)
        textTransformInitialSize = text.sizePx
        textTransformInitialRotation = text.rotationDegrees
        textTransformInitialX = text.x
        textTransformInitialY = text.y
        textTransformInitialCentroid = centroidImage
        return true
    }

    private fun updateTextTransform(
        event: MotionEvent,
        bitmap: Bitmap,
    ) {
        if (event.pointerCount < 2) {
            return
        }
        val text = activeText ?: return
        val transform = imageTransform(bitmap) ?: return
        val distance = pointerDistance(event).coerceAtLeast(1f)
        val scale = distance / textTransformInitialDistance
        val baseScale = baseImageScale(bitmap)
        val minTextSize = (14f * scaledDensity) / baseScale
        val maxTextSize = (96f * scaledDensity) / baseScale
        text.sizePx =
            (textTransformInitialSize * scale)
                .coerceIn(minTextSize, maxTextSize)

        val angleDelta = pointerAngleDegrees(event) - textTransformInitialAngle
        text.rotationDegrees = normalizeDegrees(textTransformInitialRotation + angleDelta)

        val centroidView = pointerCentroid(event)
        val centroidImage =
            mapToImage(centroidView.x, centroidView.y, transform, clamp = true) ?: return
        text.x =
            (textTransformInitialX + centroidImage.x - textTransformInitialCentroid.x)
                .coerceIn(0f, bitmap.width.toFloat())
        text.y =
            (textTransformInitialY + centroidImage.y - textTransformInitialCentroid.y)
                .coerceIn(0f, bitmap.height.toFloat())
        invalidate()
    }

    private fun pointerDistance(event: MotionEvent): Float {
        if (event.pointerCount < 2) {
            return 0f
        }
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return sqrt(dx * dx + dy * dy)
    }

    private fun pointerAngleDegrees(event: MotionEvent): Float {
        if (event.pointerCount < 2) {
            return 0f
        }
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
    }

    private fun normalizeDegrees(value: Float): Float {
        var result = value % 360f
        if (result > 180f) {
            result -= 360f
        } else if (result < -180f) {
            result += 360f
        }
        return result
    }

    private fun drawOperation(canvas: Canvas, operation: Operation) {
        when (operation) {
            is Operation.Stroke -> drawStroke(canvas, operation)
            is Operation.Pixelate -> drawPixelate(canvas, operation)
            is Operation.Text -> drawText(canvas, operation)
        }
    }

    private fun drawStroke(canvas: Canvas, stroke: Operation.Stroke) {
        drawStroke(
            canvas,
            stroke.path,
            stroke.color,
            stroke.strokeWidth,
            stroke.alpha,
        )
    }

    private fun drawStroke(
        canvas: Canvas,
        path: Path,
        color: Int,
        strokeWidth: Float,
        alpha: Int,
    ) {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = color
        paint.alpha = alpha
        paint.strokeWidth = strokeWidth
        canvas.drawPath(path, paint)
    }

    private fun drawPixelate(canvas: Canvas, pixelate: Operation.Pixelate) {
        drawPixelate(canvas, pixelate.path, pixelate.strokeWidth)
    }

    private fun drawPixelate(
        canvas: Canvas,
        path: Path,
        strokeWidth: Float,
    ) {
        val shader = pixelateShader ?: return
        paint.reset()
        paint.isAntiAlias = false
        paint.isFilterBitmap = false
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = strokeWidth
        paint.shader = shader
        canvas.drawPath(path, paint)
        paint.shader = null
    }

    private fun drawText(canvas: Canvas, text: Operation.Text) {
        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = text.sizePx
        paint.style = Paint.Style.FILL

        val lines = text.value.split('\n')
        val lineHeight = text.sizePx * 1.18f
        val metrics = paint.fontMetrics
        val maxWidth = lines.maxOfOrNull { paint.measureText(it) } ?: 0f
        val blockHeight =
            (lines.size - 1) * lineHeight +
                (metrics.descent - metrics.ascent)
        val firstBaseline =
            -((lines.size - 1) * lineHeight / 2f) -
                ((metrics.ascent + metrics.descent) / 2f)

        canvas.save()
        canvas.translate(text.x, text.y)
        canvas.rotate(text.rotationDegrees)

        if (text.background != TextBackground.NONE) {
            val horizontalPadding = text.sizePx * 0.32f
            val verticalPadding = text.sizePx * 0.18f
            paint.clearShadowLayer()
            paint.color =
                if (text.background == TextBackground.LIGHT) {
                    textBackgroundLight
                } else {
                    textBackgroundDark
                }
            paint.alpha = 255
            val rect =
                RectF(
                    -maxWidth / 2f - horizontalPadding,
                    -blockHeight / 2f - verticalPadding,
                    maxWidth / 2f + horizontalPadding,
                    blockHeight / 2f + verticalPadding,
                )
            val radius = text.sizePx * 0.22f
            canvas.drawRoundRect(rect, radius, radius, paint)
        }

        paint.color = text.color
        paint.alpha = 255
        if (text.background == TextBackground.NONE) {
            paint.setShadowLayer(
                text.sizePx * 0.045f,
                0f,
                text.sizePx * 0.025f,
                0x66000000,
            )
        } else {
            paint.clearShadowLayer()
        }

        lines.forEachIndexed { index, line ->
            val baseline = firstBaseline + index * lineHeight
            canvas.drawText(line, 0f, baseline, paint)
        }
        canvas.restore()
        paint.clearShadowLayer()
    }

    fun setTool(tool: Tool) {
        currentTool = tool
        currentPath = Path()
        invalidate()
    }

    fun updateColor(color: Int) {
        currentColor = color
        invalidate()
    }

    fun setBrushWidthLevel(tool: Tool, level: Int) {
        val normalized = level.coerceIn(0, 2)
        when (tool) {
            Tool.MARKER -> markerWidthLevel = normalized
            Tool.HIGHLIGHTER -> highlighterWidthLevel = normalized
            Tool.PIXELATE -> pixelateWidthLevel = normalized
            Tool.TEXT -> Unit
        }
        invalidate()
    }

    fun getBrushWidthLevel(tool: Tool): Int =
        when (tool) {
            Tool.MARKER -> markerWidthLevel
            Tool.HIGHLIGHTER -> highlighterWidthLevel
            Tool.PIXELATE -> pixelateWidthLevel
            Tool.TEXT -> 1
        }

    fun addText(value: String) {
        val bitmap = backgroundBitmap ?: return
        val transform = imageTransform(bitmap) ?: return
        val normalized = value.trim()
        if (normalized.isEmpty()) {
            return
        }
        val text =
            Operation.Text(
                normalized,
                bitmap.width / 2f,
                bitmap.height / 2f,
                currentColor,
                (28f * scaledDensity) / transform.scale,
                background = currentTextBackground,
            )
        operations += text
        activeText = text
        draggedText = null
        editStateChangedListener?.invoke()
        invalidate()
    }

    fun setOnEditStateChangedListener(listener: (() -> Unit)?) {
        editStateChangedListener = listener
    }

    fun updateSelectedTextColor(color: Int): Boolean {
        val text = activeText ?: return false
        text.color = color
        editStateChangedListener?.invoke()
        invalidate()
        return true
    }

    fun setTextBackground(background: TextBackground) {
        currentTextBackground = background
        activeText?.background = background
        editStateChangedListener?.invoke()
        invalidate()
    }

    fun getTextBackground(): TextBackground =
        activeText?.background ?: currentTextBackground

    fun updateBackgroundBitmap(bitmap: Bitmap, clearEdits: Boolean = true) {
        backgroundBitmap = bitmap
        resetViewport()
        pixelateShader = null
        pixelatedSample?.takeIf { !it.isRecycled }?.recycle()
        val pixelation = createPixelationShader(bitmap)
        pixelatedSample = pixelation.first
        pixelateShader = pixelation.second
        if (clearEdits) {
            operations.clear()
            activeText = null
            currentPath = Path()
            editStateChangedListener?.invoke()
        }
        invalidate()
    }

    fun getBitmap(): Bitmap {
        val source = backgroundBitmap
            ?: return Bitmap.createBitmap(
                width.coerceAtLeast(1),
                height.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
        val bitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawBitmap(source, 0f, 0f, null)
        operations.forEach { drawOperation(canvas, it) }
        return bitmap
    }

    fun undo() {
        if (operations.isNotEmpty()) {
            val removed = operations.removeAt(operations.lastIndex)
            if (removed === activeText) {
                activeText = operations.asReversed().filterIsInstance<Operation.Text>().firstOrNull()
            }
            editStateChangedListener?.invoke()
            invalidate()
        }
    }

    fun canUndo(): Boolean = operations.isNotEmpty()

    fun hasEdits(): Boolean = operations.isNotEmpty()

    fun clearEdits() {
        operations.clear()
        activeText = null
        currentPath = Path()
        editStateChangedListener?.invoke()
        invalidate()
    }

    private fun imageTransform(bitmap: Bitmap): ImageTransform? {
        if (width <= 0 || height <= 0 || bitmap.width <= 0 || bitmap.height <= 0) {
            return null
        }
        val scale = baseImageScale(bitmap) * viewportScale
        val displayWidth = bitmap.width * scale
        val displayHeight = bitmap.height * scale
        return ImageTransform(
            scale,
            (width - displayWidth) / 2f + viewportOffsetX,
            (height - displayHeight) / 2f + viewportOffsetY,
            displayWidth,
            displayHeight,
        )
    }

    private fun baseImageScale(bitmap: Bitmap): Float =
        min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)

    private fun clampViewport(bitmap: Bitmap) {
        val baseScale = baseImageScale(bitmap)
        val displayWidth = bitmap.width * baseScale * viewportScale
        val displayHeight = bitmap.height * baseScale * viewportScale
        val maxX = max(0f, (displayWidth - width) / 2f)
        val maxY = max(0f, (displayHeight - height) / 2f)
        viewportOffsetX = viewportOffsetX.coerceIn(-maxX, maxX)
        viewportOffsetY = viewportOffsetY.coerceIn(-maxY, maxY)
    }

    private fun resetViewport() {
        viewportScale = 1f
        viewportOffsetX = 0f
        viewportOffsetY = 0f
        navigationGesture = false
        panX = 0f
        panY = 0f
    }

    private fun pointerCentroid(event: MotionEvent, skipIndex: Int = -1): PointF {
        var x = 0f
        var y = 0f
        var count = 0
        for (index in 0 until event.pointerCount) {
            if (index == skipIndex) {
                continue
            }
            x += event.getX(index)
            y += event.getY(index)
            count++
        }
        if (count == 0) {
            return PointF(event.x, event.y)
        }
        return PointF(x / count, y / count)
    }

    private fun mapToImage(
        x: Float,
        y: Float,
        transform: ImageTransform,
        clamp: Boolean,
    ): PointF? {
        val right = transform.left + transform.width
        val bottom = transform.top + transform.height
        if (!clamp && (x < transform.left || x > right || y < transform.top || y > bottom)) {
            return null
        }
        val mappedX =
            ((x.coerceIn(transform.left, right) - transform.left) / transform.scale)
        val mappedY =
            ((y.coerceIn(transform.top, bottom) - transform.top) / transform.scale)
        return PointF(mappedX, mappedY)
    }

    private fun brushWidthViewPx(tool: Tool): Float {
        val dp =
            when (tool) {
                Tool.MARKER -> intArrayOf(3, 6, 10)[markerWidthLevel]
                Tool.HIGHLIGHTER -> intArrayOf(14, 22, 34)[highlighterWidthLevel]
                Tool.PIXELATE -> intArrayOf(24, 36, 52)[pixelateWidthLevel]
                Tool.TEXT -> 6
            }
        return dp * density
    }

    private fun alphaFor(tool: Tool): Int =
        when (tool) {
            Tool.HIGHLIGHTER -> 96
            else -> 255
        }

    private fun createPixelationShader(bitmap: Bitmap): Pair<Bitmap, BitmapShader> {
        // Privacy brush: keep only a tiny averaged texture and enlarge it with nearest-neighbour
        // sampling. This is both stronger than the old fine mosaic and far cheaper than keeping
        // another full-size bitmap in memory.
        val minSide = min(bitmap.width, bitmap.height).coerceAtLeast(1)
        val blockSize = (minSide / 32).coerceIn(24, 56)
        val smallWidth = ((bitmap.width + blockSize - 1) / blockSize).coerceAtLeast(1)
        val smallHeight = ((bitmap.height + blockSize - 1) / blockSize).coerceAtLeast(1)
        val sample = Bitmap.createScaledBitmap(bitmap, smallWidth, smallHeight, true)
        val shader = BitmapShader(sample, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val matrix = Matrix()
        matrix.setScale(
            bitmap.width.toFloat() / smallWidth,
            bitmap.height.toFloat() / smallHeight,
        )
        shader.setLocalMatrix(matrix)
        return sample to shader
    }
}
