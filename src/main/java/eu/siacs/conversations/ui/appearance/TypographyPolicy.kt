package eu.siacs.conversations.ui.appearance

import kotlin.math.roundToInt

enum class TextScale { SMALL, DEFAULT, LARGE, EXTRA_LARGE }
enum class TypographyRole { MESSAGE, CAPTION, METADATA, COMPOSER, TITLE, SUBTITLE, BODY, BUTTON }

object TypographyPolicy {
    const val MIN_MESSAGE_TEXT_SP = 12f
    const val DEFAULT_MESSAGE_TEXT_SP = 14f
    const val MAX_MESSAGE_TEXT_SP = 32f

    fun sizeSp(role: TypographyRole, scale: TextScale): Float = when (role) {
        TypographyRole.MESSAGE, TypographyRole.CAPTION -> when (scale) {
            TextScale.SMALL -> 13f
            TextScale.DEFAULT -> DEFAULT_MESSAGE_TEXT_SP
            TextScale.LARGE -> 15f
            TextScale.EXTRA_LARGE -> 16f
        }
        else -> throw IllegalArgumentException("Role is not yet a controlled runtime typography seam: $role")
    }

    fun normalizeMessageTextSp(value: Float): Float =
        value.coerceIn(MIN_MESSAGE_TEXT_SP, MAX_MESSAGE_TEXT_SP).roundToInt().toFloat()

    fun legacyScaleForMessageSp(value: Float): TextScale = when {
        value <= 13f -> TextScale.SMALL
        value <= DEFAULT_MESSAGE_TEXT_SP -> TextScale.DEFAULT
        value <= 15f -> TextScale.LARGE
        else -> TextScale.EXTRA_LARGE
    }
}
