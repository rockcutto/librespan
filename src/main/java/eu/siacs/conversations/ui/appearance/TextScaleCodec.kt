package eu.siacs.conversations.ui.appearance

object TextScaleCodec {
    fun decode(value: String?): TextScale? = when (value) {
        "small" -> TextScale.SMALL
        "default" -> TextScale.DEFAULT
        "large" -> TextScale.LARGE
        "extra_large" -> TextScale.EXTRA_LARGE
        else -> null
    }

    fun encode(value: TextScale): String = when (value) {
        TextScale.SMALL -> "small"
        TextScale.DEFAULT -> "default"
        TextScale.LARGE -> "large"
        TextScale.EXTRA_LARGE -> "extra_large"
    }
}
