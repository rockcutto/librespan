package eu.siacs.conversations.ui.actions

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

data class MessageAction(

    val type: MessageActionType,

    @StringRes
    val title: Int,

    @DrawableRes
    val icon: Int,

    val group: MessageActionGroup,

    val destructive: Boolean = false
)