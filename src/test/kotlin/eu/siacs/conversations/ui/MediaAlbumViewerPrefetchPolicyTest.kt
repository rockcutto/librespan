package eu.siacs.conversations.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class MediaAlbumViewerPrefetchPolicyTest {
    @Test
    fun prefetchesOnlyAdjacentPages() {
        assertArrayEquals(intArrayOf(1), MediaAlbumActivity.viewerPrefetchPositions(0, 4))
        assertArrayEquals(intArrayOf(1, 3), MediaAlbumActivity.viewerPrefetchPositions(2, 5))
        assertArrayEquals(intArrayOf(2), MediaAlbumActivity.viewerPrefetchPositions(3, 4))
    }

    @Test
    fun doesNotPrefetchForInvalidOrSinglePageAlbum() {
        assertArrayEquals(intArrayOf(), MediaAlbumActivity.viewerPrefetchPositions(-1, 3))
        assertArrayEquals(intArrayOf(), MediaAlbumActivity.viewerPrefetchPositions(0, 1))
    }
}
