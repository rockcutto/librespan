package eu.siacs.conversations.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaAlbumViewerSourcePolicyTest {
    @Test
    fun rolloutDisabledKeepsLegacySource() {
        assertEquals(
            MediaAlbumActivity.ViewerSourcePolicy.LEGACY,
            MediaAlbumActivity.viewerSourcePolicy(false, true, false),
        )
    }

    @Test
    fun committedSecureRelationOwnsTheViewerSource() {
        assertEquals(
            MediaAlbumActivity.ViewerSourcePolicy.SECURE,
            MediaAlbumActivity.viewerSourcePolicy(true, true, false),
        )
    }

    @Test
    fun cleanlyMissingSecureRelationFallsBackToHistoricalLegacySource() {
        assertEquals(
            MediaAlbumActivity.ViewerSourcePolicy.LEGACY,
            MediaAlbumActivity.viewerSourcePolicy(true, false, false),
        )
    }

    @Test
    fun secureResolutionFailureNeverFallsBackToLegacyPlaintext() {
        assertEquals(
            MediaAlbumActivity.ViewerSourcePolicy.UNAVAILABLE,
            MediaAlbumActivity.viewerSourcePolicy(true, false, true),
        )
    }
}
