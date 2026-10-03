package eu.siacs.conversations.ui.appearance

/** Pure policy for the one-time legacy [AppSettings.LARGE_FONT] migration. */
object TextScaleMigrationPolicy {
    fun candidate(hasCanonicalValue: Boolean, legacyLargeFont: Boolean): TextScale? =
        if (hasCanonicalValue) null
        else if (legacyLargeFont) TextScale.LARGE
        else TextScale.DEFAULT
}
