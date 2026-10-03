package eu.siacs.conversations.services;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import eu.siacs.conversations.xmpp.Jid;

/**
 * Process-local tombstone covering the short window between an explicit MUC leave and its
 * asynchronous SQLite persistence.
 *
 * <p>The durable source of truth remains Conversation.muc_explicitly_left. This guard only closes
 * the race in which the conversation has already left the live list while the DB row still says
 * AVAILABLE.
 */
final class MucExplicitLeaveGuard {

    private final Set<String> suppressed = ConcurrentHashMap.newKeySet();

    void suppress(final String accountUuid, final Jid room) {
        final String key = key(accountUuid, room);
        if (key != null) {
            suppressed.add(key);
        }
    }

    void allow(final String accountUuid, final Jid room) {
        final String key = key(accountUuid, room);
        if (key != null) {
            suppressed.remove(key);
        }
    }

    boolean contains(final String accountUuid, final Jid room) {
        final String key = key(accountUuid, room);
        return key != null && suppressed.contains(key);
    }

    private static String key(final String accountUuid, final Jid room) {
        if (accountUuid == null || accountUuid.isEmpty() || room == null) {
            return null;
        }
        return accountUuid + '\n' + room.asBareJid();
    }
}
