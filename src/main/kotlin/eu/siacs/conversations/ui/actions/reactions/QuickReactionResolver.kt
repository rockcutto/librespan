package eu.siacs.conversations.ui.actions.reactions

class QuickReactionResolver {

    fun resolve(): List<QuickReactionItem> {

        return listOf(
            QuickReactionItem.Reaction(
                QuickReaction("👍")
            ),
            QuickReactionItem.Reaction(
                QuickReaction("❤️")
            ),
            QuickReactionItem.Reaction(
                QuickReaction("😂")
            ),
            QuickReactionItem.Reaction(
                QuickReaction("🔥")
            ),
            QuickReactionItem.Reaction(
                QuickReaction("😢")
            ),
            QuickReactionItem.More
        )
    }
}