package eu.siacs.conversations.ui.actions.reactions

sealed interface QuickReactionItem {

    data class Reaction(
        val reaction: QuickReaction
    ) : QuickReactionItem


    data object More : QuickReactionItem
}