package eu.siacs.conversations.parser;

import static org.junit.Assert.*;

import android.util.Log;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.generator.MessageGenerator;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xml.SecretStanzaLog;
import eu.siacs.conversations.xmpp.Jid;
import java.lang.reflect.Method;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLog;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = 34)
public class MessageParserInviteLogTest {
    @Before public void clearLog() { ShadowLog.clear(); }

    @Test public void mediatedInvitePreservesPasswordWithoutLoggingPayload() throws Exception {
        String secret = "mediated-sensitive-secret";
        Element message = mediated(secret, false);
        String before = message.toString();
        Object invite = extract(message);
        assertNotNull(invite);
        assertEquals(secret, field(invite, "password"));
        assertEquals(false, field(invite, "direct"));
        assertEquals(before, message.toString());
        assertTrue(ShadowLog.getLogsForTag(Config.LOGTAG).isEmpty());
    }

    @Test public void reflectedOutgoingInviteLogsOnlyFixedReason() throws Exception {
        String secret = "reflected-sensitive-secret";
        assertNull(extract(mediated(secret, true)));
        assertEquals(1, ShadowLog.getLogsForTag(Config.LOGTAG).size());
        assertEquals("do not parse outgoing mediated invite", ShadowLog.getLogsForTag(Config.LOGTAG).get(0).msg);
        assertNoSecretOrXml(secret);
    }

    @Test public void ignoredInviteIsNeverSerializedEvenWithoutPassword() throws Exception {
        Element message = new Element("message") {
            @Override public String toString() { throw new AssertionError("Invite must not be serialized for logging"); }
        };
        message.setAttribute("from", "room@conference.example");
        message.addChild("x", Namespace.MUC_USER).addChild("invite").setAttribute("to", "friend@example.test");
        assertNull(extract(message));
        assertEquals("do not parse outgoing mediated invite", ShadowLog.getLogsForTag(Config.LOGTAG).get(0).msg);
    }

    @Test public void directInviteKeepsPasswordAttributeAndDoesNotLogIt() throws Exception {
        String secret = "direct-sensitive-secret";
        Element message = direct(secret);
        Object invite = extract(message);
        assertNotNull(invite);
        assertEquals(secret, field(invite, "password"));
        assertEquals(true, field(invite, "direct"));
        assertEquals("sender@example.test", field(invite, "inviter").toString());
        assertTrue(ShadowLog.getLogsForTag(Config.LOGTAG).isEmpty());
    }

    @Test public void missingPasswordsRemainOptionalForBothInvitationProtocols() throws Exception {
        for (Element message : new Element[] {mediated(null, false), direct(null)}) {
            Object invite = extract(message);
            assertNotNull(invite);
            assertNull(field(invite, "password"));
        }
        assertNull(extract(mediated(null, true)));
        assertEquals("do not parse outgoing mediated invite", ShadowLog.getLogsForTag(Config.LOGTAG).get(0).msg);
    }

    @Test public void unusualPasswordCharactersNeverEnterRealDiagnosticText() throws Exception {
        String secret = "  <&\"'\nпароль 🚀 e\u0301  ";
        assertNull(extract(mediated(secret, true)));
        assertNoSecretOrXml(secret);
        assertEquals(secret, field(extract(mediated(secret, false)), "password"));
        assertEquals(secret, field(extract(direct(secret)), "password"));
    }

    @Test public void existingStanzaRedactorCoversJoinRoomsecretDirectAndErrorSecrets() {
        String secret = "other-sensitive-secret";
        Element join = new Element("presence");
        join.addChild("x", "http://jabber.org/protocol/muc").addChild("password").setContent(secret);
        Element form = new Element("iq");
        form.addChild("x", Namespace.DATA).addChild("field").setAttribute("var", "muc#roomconfig_roomsecret")
                .addChild("value").setContent(secret);
        Element error = new Element("message"); error.setAttribute("type", "error");
        error.addChild("error").addChild("text").setContent(secret);
        for (Element stanza : new Element[] {join, form, error, mediated(secret, false), direct(secret)}) {
            String before = stanza.toString();
            Log.d(Config.LOGTAG, SecretStanzaLog.forLog(stanza));
            assertEquals(before, stanza.toString());
        }
        assertEquals(5, ShadowLog.getLogsForTag(Config.LOGTAG).size());
        assertNoSecretOrXml(secret);
    }

    @Test public void outgoingDirectInvitationPayloadIsUnchangedByDiagnosticRedaction() {
        String secret = "outgoing-sensitive-secret";
        Account account = new Account(Jid.of("sender@example.test"), "");
        Conversation room = new Conversation("room", account, Jid.of("room@conference.example"), Conversation.MODE_MULTI, null);
        room.getMucOptions().setPassword(secret);
        Element message = new MessageGenerator(null).directInvite(room, Jid.of("friend@example.test"));
        String before = message.toString();
        assertEquals(secret, message.findChild("x", "jabber:x:conference").getAttribute("password"));
        Log.d(Config.LOGTAG, SecretStanzaLog.forLog(message));
        assertEquals(before, message.toString());
        assertNoSecretOrXml(secret);
    }

    private static Object extract(Element message) throws Exception {
        Method method = MessageParser.class.getDeclaredMethod("extractInvite", Element.class);
        method.setAccessible(true);
        return method.invoke(new MessageParser(null, null), message);
    }

    private static Object field(Object invite, String name) throws Exception {
        var field = invite.getClass().getDeclaredField(name); field.setAccessible(true);
        return field.get(invite);
    }

    private static Element mediated(String password, boolean reflected) {
        Element message = new Element("message");
        message.setAttribute("from", "room@conference.example");
        Element x = message.addChild("x", Namespace.MUC_USER);
        x.addChild("invite").setAttribute(reflected ? "to" : "from", "friend@example.test");
        if (password != null) x.addChild("password").setContent(password);
        return message;
    }

    private static Element direct(String password) {
        Element message = new Element("message"); message.setAttribute("from", "sender@example.test");
        Element x = message.addChild("x", "jabber:x:conference"); x.setAttribute("jid", "room@conference.example");
        if (password != null) x.setAttribute("password", password);
        return message;
    }

    private static void assertNoSecretOrXml(String secret) {
        for (ShadowLog.LogItem item : ShadowLog.getLogsForTag(Config.LOGTAG)) {
            assertFalse(item.msg.contains(secret));
            assertFalse(item.msg.contains("<message"));
            assertFalse(item.msg.contains("<password"));
            assertFalse(item.msg.contains("<value"));
        }
    }
}
