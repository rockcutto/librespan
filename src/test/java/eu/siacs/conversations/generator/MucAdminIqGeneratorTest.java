package eu.siacs.conversations.generator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import im.conversations.android.xmpp.model.stanza.Iq;
import org.junit.Test;

public class MucAdminIqGeneratorTest {

    private static final Jid ROOM = Jid.of("room@conference.example");
    private static final Jid TARGET = Jid.of("other@example.test");

    private static Conversation conversation() {
        return new Conversation(
                "muc-admin-iq",
                new Account(Jid.of("me@example.test"), ""),
                ROOM,
                Conversation.MODE_MULTI,
                null);
    }

    @Test
    public void affiliationChangeUsesBareJidAndMucAdminNamespace() {
        final Iq iq =
                new IqGenerator(null)
                        .changeAffiliation(conversation(), TARGET.withResource("device"), "admin");

        assertEquals("set", iq.getAttribute("type"));
        assertEquals(ROOM.toString(), iq.getAttribute("to"));
        final Element query = iq.findChild("query", Namespace.MUC_ADMIN);
        assertNotNull(query);
        final Element item = query.findChild("item");
        assertNotNull(item);
        assertEquals(TARGET.toString(), item.getAttribute("jid"));
        assertEquals("admin", item.getAttribute("affiliation"));
    }

    @Test
    public void ownerListQueryUsesMucAdminGetShape() {
        final Iq iq = new IqGenerator(null).queryAffiliation(conversation(), "owner");

        assertEquals("get", iq.getAttribute("type"));
        assertEquals(ROOM.toString(), iq.getAttribute("to"));
        final Element query = iq.findChild("query", Namespace.MUC_ADMIN);
        assertNotNull(query);
        final Element item = query.findChild("item");
        assertNotNull(item);
        assertEquals("owner", item.getAttribute("affiliation"));
    }
}
