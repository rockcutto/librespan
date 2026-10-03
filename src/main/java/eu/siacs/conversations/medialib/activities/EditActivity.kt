package eu.siacs.conversations.medialib.activities

import android.annotation.TargetApi
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.canhub.cropper.CropImageView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import eu.siacs.conversations.Config
import eu.siacs.conversations.R
import eu.siacs.conversations.databinding.ActivityEditBinding
import eu.siacs.conversations.medialib.extensions.config
import eu.siacs.conversations.medialib.extensions.copyNonDimensionAttributesTo
import eu.siacs.conversations.medialib.extensions.ensureBackgroundThread
import eu.siacs.conversations.medialib.extensions.getCompressionFormat
import eu.siacs.conversations.medialib.extensions.isNougatPlus
import eu.siacs.conversations.medialib.extensions.toast
import eu.siacs.conversations.medialib.views.EditorDrawCanvas
import eu.siacs.conversations.persistance.FileBackend
import eu.siacs.conversations.persistance.name
import eu.siacs.conversations.ui.BaseActivity
import eu.siacs.conversations.ui.util.ImageAttachmentStaging
import eu.siacs.conversations.ui.util.SettingsUtils
import eu.siacs.conversations.utils.ThemeHelper
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.math.max

class EditActivity : BaseActivity(), CropImageView.OnCropImageCompleteListener {

    companion object {
        const val KEY_CHAT_NAME = "editActivity_chatName"
        const val KEY_EDITED_URI = "editActivity_edited_uri"
    }

    private enum class EditorMode {
        MARKER,
        HIGHLIGHTER,
        PIXELATE,
        TEXT,
        CROP,
    }

    private enum class PendingCropAction {
        SAVE,
        MARKER,
        HIGHLIGHTER,
        PIXELATE,
        TEXT,
    }

    private val ASPECT_X = "aspectX"
    private val ASPECT_Y = "aspectY"

    private lateinit var binding: ActivityEditBinding
    private val paletteViews = mutableListOf<MaterialCardView>()
    private val textBackgroundViews =
        mutableListOf<Pair<EditorDrawCanvas.TextBackground, MaterialCardView>>()
    private val editorColors: IntArray by lazy {
        intArrayOf(
            ContextCompat.getColor(this, R.color.editor_palette_white),
            ContextCompat.getColor(this, R.color.editor_palette_dark),
            ContextCompat.getColor(this, R.color.editor_palette_red),
            ContextCompat.getColor(this, R.color.editor_palette_green),
            ContextCompat.getColor(this, R.color.editor_palette_purple),
        )
    }
    private var originalUri: Uri? = null
    private var mode = EditorMode.MARKER
    private var preCropMode = EditorMode.MARKER
    private var preCropBitmap: Bitmap? = null
    private var pendingCropAction: PendingCropAction? = null
    private var oldExif: ExifInterface? = null
    private var drawColor: Int = Color.BLACK
    private var highlighterColor: Int = Color.RED
    private var imageReady = false
    private var imageChangedByTransform = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsUtils.applyScreenshotSetting(this)

        setTheme(ThemeHelper.find(this))
        ThemeHelper.findThemeOverrideStyle(this)?.let { theme.applyStyle(it, true) }

        binding = ActivityEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()

        setupToolbar()
        setupControls()
        initEditor()
    }

    private fun setupToolbar() {
        val onSurface =
            MaterialColors.getColor(
                binding.editorToolbar,
                com.google.android.material.R.attr.colorOnSurface,
            )
        binding.editorToolbar.title = getString(R.string.editor_photo_title)
        binding.editorToolbar.setTitleTextColor(onSurface)
        binding.editorToolbar.setNavigationIconTint(onSurface)
        binding.editorToolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupControls() {
        setupPalette()
        setupTextBackgroundSelector()

        binding.editorDrawCanvas.setOnEditStateChangedListener {
            updateEditorChrome()
        }

        binding.editorUndo.setOnClickListener {
            if (mode == EditorMode.CROP) {
                cancelCrop()
            } else {
                binding.editorDrawCanvas.undo()
            }
        }

        binding.brushWidthSlider.setLabelFormatter { value ->
            brushWidthLabel(value.toInt())
        }
        binding.brushWidthSlider.addOnChangeListener { _, value, fromUser ->
            val level = value.toInt()
            updateBrushWidthPresentation(level)
            if (!fromUser) {
                return@addOnChangeListener
            }
            brushToolForMode()?.let { tool ->
                binding.editorDrawCanvas.setBrushWidthLevel(tool, level)
            }
        }

        binding.cropRotate.setOnClickListener {
            if (mode == EditorMode.CROP) {
                binding.cropImageView.rotateImage(90)
            }
        }

        binding.toolMarker.setOnClickListener {
            selectAnnotationMode(EditorMode.MARKER)
        }
        binding.toolHighlighter.setOnClickListener {
            selectAnnotationMode(EditorMode.HIGHLIGHTER)
        }
        binding.toolPixelate.setOnClickListener {
            selectAnnotationMode(EditorMode.PIXELATE)
        }
        binding.toolText.setOnClickListener {
            selectAnnotationMode(EditorMode.TEXT, openTextInput = true)
        }
        binding.toolCrop.setOnClickListener {
            enterCrop()
        }
        binding.editorDone.setOnClickListener {
            saveImage()
        }
    }

    private fun setupPalette() {
        binding.editorPaletteColors.removeAllViews()
        val outline =
            MaterialColors.getColor(
                binding.root,
                com.google.android.material.R.attr.colorOutlineVariant,
            )
        editorColors.forEach { color ->
            val swatch =
                MaterialCardView(this).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(dp(24), dp(24)).also {
                            it.marginStart = dp(3)
                            it.marginEnd = dp(3)
                        }
                    radius = dp(12).toFloat()
                    cardElevation = 0f
                    strokeWidth = dp(1)
                    strokeColor = outline
                    setCardBackgroundColor(color)
                    isClickable = true
                    isFocusable = true
                    contentDescription =
                        getString(R.string.change_color) +
                            " #" +
                            String.format("%06X", 0xFFFFFF and color)
                    setOnClickListener {
                        selectPaletteColor(color)
                    }
                }
            paletteViews += swatch
            binding.editorPaletteColors.addView(swatch)
        }
    }

    private fun setupTextBackgroundSelector() {
        binding.editorTextBackgrounds.removeAllViews()
        val outline =
            MaterialColors.getColor(
                binding.root,
                com.google.android.material.R.attr.colorOutlineVariant,
            )
        val quiet =
            MaterialColors.getColor(
                binding.root,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
            )
        val options =
            listOf(
                Triple(
                    EditorDrawCanvas.TextBackground.NONE,
                    Color.TRANSPARENT,
                    R.string.editor_text_background_none,
                ),
                Triple(
                    EditorDrawCanvas.TextBackground.LIGHT,
                    ContextCompat.getColor(this, R.color.white87),
                    R.string.editor_text_background_light,
                ),
                Triple(
                    EditorDrawCanvas.TextBackground.DARK,
                    ContextCompat.getColor(this, R.color.black87),
                    R.string.editor_text_background_dark,
                ),
            )

        options.forEach { (background, color, label) ->
            val swatch =
                MaterialCardView(this).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(dp(24), dp(24)).also {
                            it.marginStart = dp(2)
                            it.marginEnd = dp(2)
                        }
                    radius = dp(8).toFloat()
                    cardElevation = 0f
                    strokeWidth = dp(1)
                    strokeColor = outline
                    setCardBackgroundColor(color)
                    isClickable = true
                    isFocusable = true
                    contentDescription = getString(label)
                    setOnClickListener {
                        binding.editorDrawCanvas.setTextBackground(background)
                        refreshTextBackgroundSelector()
                    }
                }

            if (background == EditorDrawCanvas.TextBackground.NONE) {
                val icon =
                    ImageView(this).apply {
                        layoutParams =
                            android.widget.FrameLayout.LayoutParams(
                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            )
                        setPadding(dp(3), dp(3), dp(3), dp(3))
                        setImageResource(R.drawable.ic_text_background_none_24dp)
                        imageTintList = ColorStateList.valueOf(quiet)
                        contentDescription = null
                    }
                swatch.addView(icon)
            }

            textBackgroundViews += background to swatch
            binding.editorTextBackgrounds.addView(swatch)
        }
    }

    private fun initEditor() {
        val inputUri = intent.data
        if (inputUri == null) {
            toast(R.string.invalid_image_path)
            finish()
            return
        }
        if (inputUri.scheme != "file" && inputUri.scheme != "content") {
            toast(R.string.unknown_file_location)
            finish()
            return
        }

        originalUri = inputUri
        drawColor = closestPaletteColor(config.lastEditorDrawColor)
        highlighterColor = ContextCompat.getColor(this, R.color.editor_palette_red)
        binding.editorDrawCanvas.updateColor(drawColor)
        binding.editorDrawCanvas.setTool(EditorDrawCanvas.Tool.MARKER)

        binding.cropImageView.apply {
            setOnCropImageCompleteListener(this@EditActivity)
            guidelines = CropImageView.Guidelines.ON
        }

        mode = EditorMode.MARKER
        imageReady = false
        updateEditorChrome()
        loadInitialBitmap(inputUri)
    }

    private fun loadInitialBitmap(inputUri: Uri) {
        binding.editorStage.post {
            val stageLongSide = max(binding.editorStage.width, binding.editorStage.height)
            val targetSize = (stageLongSide * 2).coerceIn(1024, 2048)
            ensureBackgroundThread {
                try {
                    val options =
                        RequestOptions()
                            .format(DecodeFormat.PREFER_ARGB_8888)
                            .skipMemoryCache(true)
                            .diskCacheStrategy(DiskCacheStrategy.NONE)
                    val bitmap =
                        Glide.with(applicationContext)
                            .asBitmap()
                            .load(inputUri)
                            .apply(options)
                            .submit(targetSize, targetSize)
                            .get()
                    runOnUiThread {
                        if (isFinishing || isDestroyed) {
                            return@runOnUiThread
                        }
                        binding.editorDrawCanvas.updateBackgroundBitmap(bitmap, true)
                        binding.editorDrawCanvas.visibility = View.VISIBLE
                        binding.cropImageView.visibility = View.GONE
                        imageReady = true
                        updateEditorChrome()
                    }
                } catch (_: Exception) {
                    runOnUiThread {
                        toast(R.string.image_editing_failed)
                        finish()
                    }
                } catch (_: OutOfMemoryError) {
                    runOnUiThread {
                        toast(R.string.out_of_memory_error)
                        finish()
                    }
                }
            }
        }
    }

    private fun selectAnnotationMode(
        targetMode: EditorMode,
        openTextInput: Boolean = false,
    ) {
        if (!imageReady || targetMode == EditorMode.CROP) {
            return
        }
        if (mode == EditorMode.CROP) {
            pendingCropAction =
                when (targetMode) {
                    EditorMode.MARKER -> PendingCropAction.MARKER
                    EditorMode.HIGHLIGHTER -> PendingCropAction.HIGHLIGHTER
                    EditorMode.PIXELATE -> PendingCropAction.PIXELATE
                    EditorMode.TEXT -> PendingCropAction.TEXT
                    EditorMode.CROP -> return
                }
            binding.cropImageView.getCroppedImageAsync()
            return
        }

        applyAnnotationMode(targetMode, openTextInput)
    }

    private fun applyAnnotationMode(
        targetMode: EditorMode,
        openTextInput: Boolean,
    ) {
        mode = targetMode
        when (targetMode) {
            EditorMode.MARKER -> {
                binding.editorDrawCanvas.updateColor(drawColor)
                binding.editorDrawCanvas.setTool(EditorDrawCanvas.Tool.MARKER)
            }

            EditorMode.HIGHLIGHTER -> {
                binding.editorDrawCanvas.updateColor(highlighterColor)
                binding.editorDrawCanvas.setTool(EditorDrawCanvas.Tool.HIGHLIGHTER)
            }

            EditorMode.PIXELATE -> {
                binding.editorDrawCanvas.setTool(EditorDrawCanvas.Tool.PIXELATE)
            }

            EditorMode.TEXT -> {
                binding.editorDrawCanvas.updateColor(drawColor)
                binding.editorDrawCanvas.setTool(EditorDrawCanvas.Tool.TEXT)
            }

            EditorMode.CROP -> return
        }
        updateEditorChrome()
        if (targetMode == EditorMode.TEXT && openTextInput) {
            showTextDialog()
        }
    }

    private fun enterCrop() {
        if (!imageReady || mode == EditorMode.CROP) {
            return
        }
        preCropMode = mode
        preCropBitmap = binding.editorDrawCanvas.getBitmap()
        pendingCropAction = null

        binding.editorDrawCanvas.visibility = View.GONE
        binding.cropImageView.visibility = View.VISIBLE
        binding.cropImageView.setImageBitmap(preCropBitmap)
        if (shouldCropSquare()) {
            binding.cropImageView.setFixedAspectRatio(true)
            binding.cropImageView.setAspectRatio(1, 1)
        } else {
            binding.cropImageView.setFixedAspectRatio(false)
        }
        mode = EditorMode.CROP
        updateEditorChrome()
    }

    private fun cancelCrop() {
        if (preCropBitmap == null) {
            return
        }
        binding.cropImageView.visibility = View.GONE
        binding.editorDrawCanvas.visibility = View.VISIBLE
        preCropBitmap = null
        pendingCropAction = null
        applyAnnotationMode(preCropMode, openTextInput = false)
    }

    private fun showTextDialog() {
        val input =
            EditText(this).apply {
                hint = getString(R.string.editor_text_hint)
                inputType =
                    InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                minLines = 1
                maxLines = 4
                setPadding(dp(20), dp(8), dp(20), dp(8))
            }
        val dialog =
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.editor_tool_text)
                .setView(input)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.done) { _, _ ->
                    binding.editorDrawCanvas.addText(input.text?.toString().orEmpty())
                }
                .create()
        dialog.setOnShowListener {
            input.requestFocus()
            dialog.window?.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
            )
        }
        dialog.show()
    }

    private fun selectPaletteColor(color: Int) {
        when (mode) {
            EditorMode.HIGHLIGHTER -> {
                highlighterColor = color
                binding.editorDrawCanvas.updateColor(color)
            }

            EditorMode.MARKER -> {
                drawColor = color
                config.lastEditorDrawColor = color
                binding.editorDrawCanvas.updateColor(color)
            }

            EditorMode.TEXT -> {
                drawColor = color
                config.lastEditorDrawColor = color
                binding.editorDrawCanvas.updateColor(color)
                binding.editorDrawCanvas.updateSelectedTextColor(color)
            }

            else -> return
        }
        refreshPalette()
    }

    @TargetApi(Build.VERSION_CODES.N)
    private fun saveImage() {
        if (!imageReady) {
            return
        }
        setOldExif()
        if (mode == EditorMode.CROP) {
            pendingCropAction = PendingCropAction.SAVE
            binding.cropImageView.getCroppedImageAsync()
        } else if (!imageChangedByTransform && !binding.editorDrawCanvas.hasEdits()) {
            returnOriginalImage()
        } else {
            saveBitmapToFile(binding.editorDrawCanvas.getBitmap())
        }
    }

    @TargetApi(Build.VERSION_CODES.N)
    private fun setOldExif() {
        val source = originalUri ?: return
        try {
            if (isNougatPlus()) {
                contentResolver.openInputStream(source)?.use { inputStream ->
                    oldExif = ExifInterface(inputStream)
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun onCropImageComplete(
        view: CropImageView,
        result: CropImageView.CropResult,
    ) {
        if (result.error != null) {
            pendingCropAction = null
            toast("${getString(R.string.image_editing_failed)}: ${result.error?.message}")
            return
        }

        val bitmap =
            result.bitmap ?: run {
                pendingCropAction = null
                toast(R.string.image_editing_failed)
                return
            }

        val action = pendingCropAction ?: PendingCropAction.MARKER
        pendingCropAction = null
        if (action == PendingCropAction.SAVE) {
            saveBitmapToFile(bitmap)
            return
        }

        binding.cropImageView.visibility = View.GONE
        binding.editorDrawCanvas.visibility = View.VISIBLE
        binding.editorDrawCanvas.updateBackgroundBitmap(bitmap, true)
        preCropBitmap = null
        imageChangedByTransform = true

        val targetMode =
            when (action) {
                PendingCropAction.MARKER -> EditorMode.MARKER
                PendingCropAction.HIGHLIGHTER -> EditorMode.HIGHLIGHTER
                PendingCropAction.PIXELATE -> EditorMode.PIXELATE
                PendingCropAction.TEXT -> EditorMode.TEXT
                PendingCropAction.SAVE -> EditorMode.MARKER
            }
        applyAnnotationMode(targetMode, openTextInput = targetMode == EditorMode.TEXT)
    }

    private fun updateEditorChrome() {
        val primary =
            MaterialColors.getColor(
                binding.root,
                com.google.android.material.R.attr.colorPrimary,
            )
        val quiet =
            MaterialColors.getColor(
                binding.root,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
            )

        styleTool(
            binding.toolMarkerIcon,
            binding.toolMarkerLabel,
            mode == EditorMode.MARKER,
            primary,
            quiet,
        )
        styleTool(
            binding.toolHighlighterIcon,
            binding.toolHighlighterLabel,
            mode == EditorMode.HIGHLIGHTER,
            primary,
            quiet,
        )
        styleTool(
            binding.toolPixelateIcon,
            binding.toolPixelateLabel,
            mode == EditorMode.PIXELATE,
            primary,
            quiet,
        )
        styleTool(
            binding.toolTextIcon,
            binding.toolTextLabel,
            mode == EditorMode.TEXT,
            primary,
            quiet,
        )
        styleTool(
            binding.toolCropIcon,
            binding.toolCropLabel,
            mode == EditorMode.CROP,
            primary,
            quiet,
        )

        val showPalette =
            mode == EditorMode.MARKER ||
                mode == EditorMode.HIGHLIGHTER ||
                mode == EditorMode.TEXT
        val brushTool = brushToolForMode()
        binding.editorPaletteColors.visibility = if (showPalette) View.VISIBLE else View.GONE
        binding.editorTextBackgrounds.visibility =
            if (mode == EditorMode.TEXT) View.VISIBLE else View.GONE
        binding.brushWidthSlider.visibility = if (brushTool != null) View.VISIBLE else View.GONE
        binding.brushWidthValue.visibility = if (brushTool != null) View.VISIBLE else View.GONE
        binding.cropRotate.visibility = if (mode == EditorMode.CROP) View.VISIBLE else View.GONE

        if (brushTool != null) {
            val level = binding.editorDrawCanvas.getBrushWidthLevel(brushTool)
            binding.brushWidthSlider.value = level.toFloat()
            updateBrushWidthPresentation(level)
        }

        binding.editorUndo.isEnabled =
            imageReady &&
                if (mode == EditorMode.CROP) {
                    preCropBitmap != null
                } else {
                    binding.editorDrawCanvas.canUndo()
                }
        binding.editorUndo.alpha = if (binding.editorUndo.isEnabled) 1f else 0.38f
        binding.editorDone.isEnabled = imageReady
        binding.editorDone.alpha = if (imageReady) 1f else 0.38f
        refreshPalette()
        refreshTextBackgroundSelector()
    }

    private fun brushToolForMode(): EditorDrawCanvas.Tool? =
        when (mode) {
            EditorMode.MARKER -> EditorDrawCanvas.Tool.MARKER
            EditorMode.HIGHLIGHTER -> EditorDrawCanvas.Tool.HIGHLIGHTER
            EditorMode.PIXELATE -> EditorDrawCanvas.Tool.PIXELATE
            else -> null
        }

    private fun brushWidthLabel(level: Int): String =
        when (level.coerceIn(0, 2)) {
            0 -> getString(R.string.editor_brush_thin)
            2 -> getString(R.string.editor_brush_thick)
            else -> getString(R.string.editor_brush_medium)
        }

    private fun updateBrushWidthPresentation(level: Int) {
        val label = brushWidthLabel(level)
        binding.brushWidthValue.text = label
        binding.brushWidthSlider.contentDescription =
            getString(R.string.editor_brush_width) + ": " + label
    }

    private fun refreshPalette() {
        if (paletteViews.isEmpty()) {
            return
        }
        val selected =
            when (mode) {
                EditorMode.HIGHLIGHTER -> highlighterColor
                EditorMode.MARKER, EditorMode.TEXT -> drawColor
                else -> return
            }
        val primary =
            MaterialColors.getColor(
                binding.root,
                com.google.android.material.R.attr.colorPrimary,
            )
        val outline =
            MaterialColors.getColor(
                binding.root,
                com.google.android.material.R.attr.colorOutlineVariant,
            )
        paletteViews.forEachIndexed { index, swatch ->
            val active = editorColors[index] == selected
            swatch.strokeWidth = dp(if (active) 2 else 1)
            swatch.strokeColor = if (active) primary else outline
            swatch.scaleX = if (active) 1f else 0.96f
            swatch.scaleY = if (active) 1f else 0.96f
        }
    }

    private fun refreshTextBackgroundSelector() {
        if (textBackgroundViews.isEmpty() || mode != EditorMode.TEXT) {
            return
        }
        val selected = binding.editorDrawCanvas.getTextBackground()
        val primary =
            MaterialColors.getColor(
                binding.root,
                com.google.android.material.R.attr.colorPrimary,
            )
        val outline =
            MaterialColors.getColor(
                binding.root,
                com.google.android.material.R.attr.colorOutlineVariant,
            )
        textBackgroundViews.forEach { (background, swatch) ->
            val active = background == selected
            swatch.strokeWidth = dp(if (active) 2 else 1)
            swatch.strokeColor = if (active) primary else outline
            swatch.scaleX = if (active) 1f else 0.96f
            swatch.scaleY = if (active) 1f else 0.96f
        }
    }

    private fun styleTool(
        icon: ImageView,
        label: TextView,
        selected: Boolean,
        primary: Int,
        quiet: Int,
    ) {
        val color = if (selected) primary else quiet
        icon.imageTintList = ColorStateList.valueOf(color)
        label.setTextColor(color)
        label.alpha = if (selected) 1f else 0.78f
        icon.alpha = if (selected) 1f else 0.78f
    }

    private fun closestPaletteColor(color: Int): Int {
        var best = editorColors[0]
        var bestDistance = Long.MAX_VALUE
        for (candidate in editorColors) {
            val red = Color.red(color) - Color.red(candidate)
            val green = Color.green(color) - Color.green(candidate)
            val blue = Color.blue(color) - Color.blue(candidate)
            val distance =
                red.toLong() * red +
                    green.toLong() * green +
                    blue.toLong() * blue
            if (distance < bestDistance) {
                bestDistance = distance
                best = candidate
            }
        }
        return best
    }

    private fun shouldCropSquare(): Boolean {
        val extras = intent.extras
        return extras != null &&
            extras.containsKey(ASPECT_X) &&
            extras.containsKey(ASPECT_Y) &&
            extras.getInt(ASPECT_X) == extras.getInt(ASPECT_Y)
    }

    private fun returnOriginalImage() {
        val source = originalUri ?: return
        intent.putExtra(KEY_EDITED_URI, source)
        setResult(Activity.RESULT_OK, intent)
        finish()
    }

    private fun saveBitmapToFile(bitmap: Bitmap) {
        val name = originalUri?.name(this) ?: UUID.randomUUID().toString()
        var file =
            if (Config.SECURE_CONTENT_MEDIA_ROLLOUT) {
                ImageAttachmentStaging.createEditorOutputFile(this, name)
            } else {
                File(filesDir, "Images/${name}.jpg")
            }

        var counter = 1
        while (!Config.SECURE_CONTENT_MEDIA_ROLLOUT && file.exists()) {
            file = File(filesDir, "Images/${name}(${counter}).jpg")
            counter++
        }

        file.deleteRecursively()
        file.parentFile?.mkdirs()

        ensureBackgroundThread {
            try {
                FileOutputStream(file).use { output ->
                    saveBitmap(file, bitmap, output)
                }
            } catch (_: OutOfMemoryError) {
                toast(R.string.out_of_memory_error)
            } catch (_: Exception) {
                toast(R.string.image_editing_failed)
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.N)
    private fun saveBitmap(
        file: File,
        bitmap: Bitmap,
        out: OutputStream,
    ) {
        bitmap.compress(file.absolutePath.getCompressionFormat(), 90, out)

        try {
            if (isNougatPlus()) {
                val newExif = ExifInterface(file.absolutePath)
                oldExif?.copyNonDimensionAttributesTo(newExif)
            }
        } catch (_: Exception) {
        }

        val editedUri =
            if (Config.SECURE_CONTENT_MEDIA_ROLLOUT) {
                FileBackend.getUriForFile(this, file)
            } else {
                file.toUri()
            }

        runOnUiThread {
            intent.putExtra(KEY_EDITED_URI, editedUri)
            setResult(Activity.RESULT_OK, intent)
            finish()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
