package eu.siacs.conversations.xmpp;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.MucOptions;

/** Pure eligibility rules for XEP-0425 moderation actions. */
public final class MucModerationPolicy {
    private MucModerationPolicy() {}

    public static boolean canModerate(
            final Conversation conversation, final Message message) {
        if (conversation == null
                || message == null
                || message.getConversation() != conversation
                || conversation.getMode() != Conversation.MODE_MULTI
                || conversation.getAccount().getStatus() != Account.State.ONLINE
                || !conversation.getMucOptions().online()
                || !conversation.getMucOptions().supportsMessageModeration()
                || !conversation
                        .getMucOptions()
                        .getSelf()
                        .getRole()
                        .ranks(MucOptions.Role.MODERATOR)
                || message.getStatus() != Message.STATUS_RECEIVED
                || message.getCounterpart() == null
                || conversation.getMucOptions().isSelf(message.getCounterpart())
                || (message.getOccupantId() != null
                        && conversation.getMucOptions().isSelf(message.getOccupantId()))
                || (message.getTrueCounterpart() != null
                        && message.getTrueCounterpart()
                                .asBareJid()
                                .equals(conversation.getAccount().getJid().asBareJid()))
                || message.isModerated()
                || message.getRoomStanzaId() == null
                || message.getRoomStanzaId().isEmpty()
                || message.getType() == Message.TYPE_STATUS
                || message.getType() == Message.TYPE_RTP_SESSION
                || message.isPrivateMessage()) {
            return false;
        }
        return !message.getBody().isEmpty()
                || message.isFileOrImage()
                || message.hasProtectedTextPayload();
    }
}
