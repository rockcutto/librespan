package eu.siacs.conversations.entities;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import eu.siacs.conversations.xmpp.Jid;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class MucExplicitLeaveStateTest {

    @Test
    public void explicitLeavePersistsAcrossConversationRestore() {
        final Conversation original = rootMuc("");
        assertFalse(original.isMucExplicitlyLeft());

        assertTrue(original.setMucExplicitlyLeft(true));
        assertTrue(original.isMucExplicitlyLeft());

        final Conversation restored = rootMuc(original.getAttributes().toString());
        assertTrue(restored.isMucExplicitlyLeft());

        assertTrue(restored.setMucExplicitlyLeft(false));
        assertFalse(restored.isMucExplicitlyLeft());
    }

    @Test
    public void privateMucConversationNeverActsAsExplicitlyLeftRoot() {
        final Conversation privateChat =
                new Conversation(
                        "pm",
                        "private",
                        null,
                        "account",
                        Jid.of("room@conference.example"),
                        0,
                        Conversation.STATUS_ARCHIVED,
                        Conversation.MODE_MULTI,
                        "{\"" + Conversation.ATTRIBUTE_MUC_EXPLICITLY_LEFT + "\":\"true\"}",
                        Jid.of("room@conference.example/alice"));

        assertFalse(privateChat.isMucExplicitlyLeft());
    }

    private static Conversation rootMuc(final String attributes) {
        return new Conversation(
                "muc",
                "room",
                null,
                "account",
                Jid.of("room@conference.example"),
                0,
                Conversation.STATUS_ARCHIVED,
                Conversation.MODE_MULTI,
                attributes,
                null);
    }
}
