package eu.siacs.conversations.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaAlbumViewerSourcePolicyTest {
    @Test
    fun rolloutDisabledFailsClosed() {
        assertEquals(
            MediaAlbumActivity.ViewerSourcePolicy.UNAVAILABLE,
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
    fun missingSecureRelationNeverFallsBackToLegacy() {
        assertEquals(
            MediaAlbumActivity.ViewerSourcePolicy.UNAVAILABLE,
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

    @Test
    fun resolutionFailureOverridesExistingRelation() {
        assertEquals(
            MediaAlbumActivity.ViewerSourcePolicy.UNAVAILABLE,
            MediaAlbumActivity.viewerSourcePolicy(true, true, true),
        )
    }
}
