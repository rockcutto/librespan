package eu.siacs.conversations.ui.media

object MediaAlbumLayoutPlanner {

    data class Tile(
        val index: Int,
        val row: Int,
        val column: Int,
        val columnSpan: Int,
    )

    data class Plan(
        val rowCount: Int,
        val tiles: List<Tile>,
    )

    @JvmStatic
    fun plan(count: Int): Plan {
        require(count >= 2) { "An album requires at least two media items" }

        val wideFirst = count >= 3 && count % 2 == 1
        val rowCount = if (wideFirst) 1 + (count - 1) / 2 else count / 2
        val tiles = ArrayList<Tile>(count)

        for (index in 0 until count) {
            if (wideFirst && index == 0) {
                tiles.add(Tile(index = 0, row = 0, column = 0, columnSpan = 2))
            } else {
                val relativeIndex = if (wideFirst) index - 1 else index
                val row = if (wideFirst) 1 + relativeIndex / 2 else relativeIndex / 2
                tiles.add(
                    Tile(
                        index = index,
                        row = row,
                        column = relativeIndex % 2,
                        columnSpan = 1,
                    )
                )
            }
        }

        return Plan(rowCount = rowCount, tiles = tiles)
    }
}
