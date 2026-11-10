package eu.siacs.conversations.entities;

/** The real message boundary shared by all timeline pagination entry points. */
public final class HistoryPageBoundary {
    private HistoryPageBoundary() {}
    public static long timestamp(Conversation conversation, boolean forward) {
        long result = forward ? 0 : Long.MAX_VALUE;
        // Keep the same source of rows as ConversationFragment's displayed timeline.
        // A search jump displays historyPartMessages, not the recent canonical page.
        final java.util.List<Message> displayed = new java.util.ArrayList<>();
        conversation.populateWithMessages(displayed);
        for (Message message : displayed) {
            if (message.getType() == Message.TYPE_STATUS || message.getTimeSent() <= 0) continue;
            if (conversation.getMode() == Conversation.MODE_MULTI
                    && conversation.getNextCounterpart() == null && message.isPrivateMessage()) continue;
            result = forward ? Math.max(result, message.getTimeSent()) : Math.min(result, message.getTimeSent());
        }
        return result == Long.MAX_VALUE ? 0 : result;
    }
}
