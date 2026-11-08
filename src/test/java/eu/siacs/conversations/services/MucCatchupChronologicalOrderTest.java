package eu.siacs.conversations.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.ui.util.DateSeparator;
import eu.siacs.conversations.xmpp.Jid;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class MucCatchupChronologicalOrderTest {
    private static final long DAY = 24L * 60L * 60L * 1000L;

    private final Account account = new Account(Jid.of("sender@example.test"), "");

    private Conversation room() {
        return new Conversation("room", account,
                Jid.of("room@conference.example"), Conversation.MODE_MULTI, null);
    }

    private static Message at(Conversation conversation, long time) {
        final Message message = new Message(conversation, "text", Message.ENCRYPTION_NONE);
        message.setTime(time);
        return message;
    }

    @Test
    public void olderMamMessagesStayBeforeNewLiveMessagesAndKeepTwoDateSeparators() {
        final Conversation room = room();
        final long nov5 = 1_793_836_800_000L;
        final Message initial = at(room, nov5);
        final Message live = at(room, nov5 + 3 * DAY);
        final Message archived1 = at(room, nov5 + 60_000L);
        final Message archived2 = at(room, nov5 + 120_000L);

        room.add(initial);
        room.add(live);
        room.addMucCatchupMessage(archived1);
        room.addMucCatchupMessage(archived2);

        assertEquals(Arrays.asList(initial, archived1, archived2, live), room.snapshotMessages());
        assertSame(live, room.getLatestMessage());

        final List<Message> rendered = new ArrayList<>();
        room.populateWithMessages(rendered);
        DateSeparator.addAll(rendered);
        assertEquals(6, rendered.size()); // Nov 5 + 3 rows + Today + 1 row
        assertEquals(Message.TYPE_STATUS, rendered.get(0).getType());
        assertSame(initial, rendered.get(1));
        assertSame(archived1, rendered.get(2));
        assertSame(archived2, rendered.get(3));
        assertEquals(Message.TYPE_STATUS, rendered.get(4).getType());
        assertSame(live, rendered.get(5));
    }

    @Test
    public void equalTimestampsRemainStableAndLaterLiveMessageStaysLast() {
        final Conversation room = room();
        final Message first = at(room, DAY);
        final Message live = at(room, DAY + 100L);
        final Message sameTime = at(room, DAY);
        final Message older = at(room, DAY - 100L);
        room.add(first);
        room.add(live);
        room.addMucCatchupMessage(sameTime);
        room.addMucCatchupMessage(older);
        assertEquals(Arrays.asList(older, first, sameTime, live), room.snapshotMessages());
        assertSame(live, room.getLatestMessage());
    }

    @Test
    public void noCrossTalkIntoSingleChatsOrPrivateMucConversations() {
        final Conversation single = new Conversation("direct", account,
                Jid.of("person@example.test"), Conversation.MODE_SINGLE, null);
        single.addMucCatchupMessage(at(single, DAY));
        assertTrue(single.snapshotMessages().isEmpty());

        final Conversation pm = new Conversation("pm", account,
                Jid.of("room@conference.example"), Conversation.MODE_MULTI,
                Jid.of("room@conference.example/occupant"));
        pm.addMucCatchupMessage(at(pm, DAY));
        assertTrue(pm.snapshotMessages().isEmpty());

        final Conversation room = room();
        final Message privateMessage = at(room, DAY);
        privateMessage.setType(Message.TYPE_PRIVATE);
        room.addMucCatchupMessage(privateMessage);
        assertTrue(room.snapshotMessages().isEmpty());
    }
}
