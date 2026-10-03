package eu.siacs.conversations.ui.actions

import eu.siacs.conversations.entities.Message

class MessageInteractionResolver {

    fun resolveTap(
        message: Message
    ): MessageInteraction {

        return when {

            message.isFileOrImage() ->
                MessageInteraction.OPEN_MEDIA

            message.treatAsDownloadable() ->
                MessageInteraction.OPEN_FILE

            message.isGeoUri() ->
                MessageInteraction.OPEN_FILE

            else ->
                MessageInteraction.SHOW_ACTIONS
        }
    }


    fun resolveLongPress(
        message: Message
    ): MessageInteraction {

        return MessageInteraction.SHOW_ACTIONS
    }
}