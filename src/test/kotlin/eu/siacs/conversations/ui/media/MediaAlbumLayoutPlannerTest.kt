package eu.siacs.conversations.ui.media

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaAlbumLayoutPlannerTest {

    @Test
    fun plansEvenAlbumsAsTwoColumnRows() {
        assertPlan(
            2,
            1,
            tile(0, 0, 0, 1),
            tile(1, 0, 1, 1),
        )
        assertPlan(
            4,
            2,
            tile(0, 0, 0, 1),
            tile(1, 0, 1, 1),
            tile(2, 1, 0, 1),
            tile(3, 1, 1, 1),
        )
        assertPlan(
            6,
            3,
            tile(0, 0, 0, 1),
            tile(1, 0, 1, 1),
            tile(2, 1, 0, 1),
            tile(3, 1, 1, 1),
            tile(4, 2, 0, 1),
            tile(5, 2, 1, 1),
        )
    }

    @Test
    fun plansOddAlbumsWithOneWideFirstTile() {
        assertPlan(
            3,
            2,
            tile(0, 0, 0, 2),
            tile(1, 1, 0, 1),
            tile(2, 1, 1, 1),
        )
        assertPlan(
            5,
            3,
            tile(0, 0, 0, 2),
            tile(1, 1, 0, 1),
            tile(2, 1, 1, 1),
            tile(3, 2, 0, 1),
            tile(4, 2, 1, 1),
        )
        assertPlan(
            7,
            4,
            tile(0, 0, 0, 2),
            tile(1, 1, 0, 1),
            tile(2, 1, 1, 1),
            tile(3, 2, 0, 1),
            tile(4, 2, 1, 1),
            tile(5, 3, 0, 1),
            tile(6, 3, 1, 1),
        )
    }

    private fun assertPlan(count: Int, rowCount: Int, vararg expectedTiles: IntArray) {
        val plan = MediaAlbumLayoutPlanner.plan(count)

        assertEquals(rowCount, plan.rowCount)
        assertEquals(count, plan.tiles.size)
        expectedTiles.forEachIndexed { index, expected ->
            val tile = plan.tiles[index]
            assertEquals(expected[0], tile.index)
            assertEquals(expected[1], tile.row)
            assertEquals(expected[2], tile.column)
            assertEquals(expected[3], tile.columnSpan)
        }
    }

    private fun tile(index: Int, row: Int, column: Int, span: Int): IntArray =
        intArrayOf(index, row, column, span)
}
