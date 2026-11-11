package eu.siacs.conversations.xmpp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import eu.siacs.conversations.xml.Element;
import im.conversations.android.xmpp.model.stanza.Iq;
import java.util.Base64;
import org.junit.Test;

public class PushGatewayProtocolTest {
    private static final Jid SERVICE = Jid.of("push.example.org");
    private static final String NODE = "0123456789abcdef";
    private static final String ENDPOINT = "https://fcm.googleapis.com/fcm/send/redacted";

    private static final String REAL_PUBLIC_KEY =
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String REAL_AUTH = "BTBZMqHH6r4Tts7J_aSIgg";

    private static Iq response(final Iq.Type type) {
        final Iq iq = new Iq(type);
        iq.setId("request-1");
        iq.setFrom(SERVICE);
        return iq;
    }

    private static void rejected(final Runnable action) {
        try {
            action.run();
            fail("Expected invalid registration input");
        } catch (final IllegalArgumentException expected) {
            // Invalid input must fail before an IQ can be sent.
        }
    }

    @Test
    public void customRegistrationIsDistinctFromXep0357AndHasRequiredFields() {
        final Iq iq = PushGatewayProtocol.register(
                SERVICE, "com.librespan", ENDPOINT, REAL_PUBLIC_KEY, REAL_AUTH);
        assertEquals("set", iq.getAttribute("type"));
        assertEquals(SERVICE, iq.getTo());
        final Element command = iq.findChild("register", PushGatewayProtocol.NAMESPACE);
        assertNotNull(command);
        assertEquals("com.librespan", command.getAttribute("app"));
        assertEquals(3, command.getChildren().size());
        assertEquals(ENDPOINT, command.findChildContent("endpoint", PushGatewayProtocol.NAMESPACE));
        assertNotNull(command.findChildContent("p256dh", PushGatewayProtocol.NAMESPACE));
        assertNotNull(command.findChildContent("auth", PushGatewayProtocol.NAMESPACE));
        assertNull(iq.findChild("enable", "urn:xmpp:push:0"));
    }

    @Test
    public void invalidServiceEndpointAndKeysCannotCreateAnIq() {
        rejected(() -> PushGatewayProtocol.serviceJid("https://push.example.org"));
        rejected(() -> PushGatewayProtocol.serviceJid("push.example.org/resource"));
        rejected(() -> PushGatewayProtocol.serviceJid(" push.example.org "));
        rejected(() -> PushGatewayProtocol.register(SERVICE, "com.librespan", "http://example.org/x",
                REAL_PUBLIC_KEY, REAL_AUTH));
        rejected(() -> PushGatewayProtocol.register(SERVICE, "com.librespan", ENDPOINT,
                "", REAL_AUTH));
        rejected(() -> PushGatewayProtocol.register(SERVICE, "com.librespan", ENDPOINT,
                REAL_PUBLIC_KEY, ""));
        final byte[] invalidPoint = new byte[65];
        invalidPoint[0] = 4;
        final String invalid = Base64.getUrlEncoder().withoutPadding().encodeToString(invalidPoint);
        rejected(() -> PushGatewayProtocol.register(SERVICE, "com.librespan", ENDPOINT,
                invalid, REAL_AUTH));
    }

    @Test
    public void onlyCorrelatedServerConfirmationProvidesNodeAndSecret() {
        final Iq good = response(Iq.Type.RESULT);
        final Element registered = good.addChild("registered", PushGatewayProtocol.NAMESPACE);
        registered.setAttribute("node", NODE);
        registered.setAttribute("secret", "secret-value");
        final PushGatewayProtocol.Registration result =
                PushGatewayProtocol.confirmedRegistration(good, SERVICE, "request-1");
        assertNotNull(result);
        assertEquals(NODE, result.node);
        assertEquals("secret-value", result.secret);
        assertNull(PushGatewayProtocol.confirmedRegistration(good, SERVICE, "old-request"));
        assertNull(PushGatewayProtocol.confirmedRegistration(good, Jid.of("other.example.org"), "request-1"));
        good.addChild("registered", PushGatewayProtocol.NAMESPACE);
        assertNull(PushGatewayProtocol.confirmedRegistration(good, SERVICE, "request-1"));
    }

    @Test
    public void errorTimeoutAndIncompleteResponseNeverBecomeSuccess() {
        for (final Iq.Type type : new Iq.Type[] {Iq.Type.ERROR, Iq.Type.TIMEOUT}) {
            final Iq failed = response(type);
            failed.addChild("registered", PushGatewayProtocol.NAMESPACE)
                    .setAttribute("node", NODE).setAttribute("secret", "secret-value");
            assertNull(PushGatewayProtocol.confirmedRegistration(failed, SERVICE, "request-1"));
        }
        final Iq missingSecret = response(Iq.Type.RESULT);
        missingSecret.addChild("registered", PushGatewayProtocol.NAMESPACE).setAttribute("node", NODE);
        assertNull(PushGatewayProtocol.confirmedRegistration(missingSecret, SERVICE, "request-1"));
    }

    @Test
    public void unregisterRequiresRealAcknowledgement() {
        final Iq request = PushGatewayProtocol.unregister(SERVICE, "com.librespan", NODE);
        final Element command = request.findChild("unregister", PushGatewayProtocol.NAMESPACE);
        assertNotNull(command);
        assertEquals(NODE, command.getAttribute("node"));
        final Iq good = response(Iq.Type.RESULT);
        good.addChild("unregistered", PushGatewayProtocol.NAMESPACE);
        assertTrue(PushGatewayProtocol.confirmedUnregistration(good, SERVICE, "request-1"));
        assertFalse(PushGatewayProtocol.confirmedUnregistration(good, SERVICE, "old-request"));
        assertFalse(PushGatewayProtocol.confirmedUnregistration(response(Iq.Type.ERROR), SERVICE, "request-1"));
    }

    @Test
    public void lostRegistrationReplyCanBeCancelledByEndpoint() {
        final Iq request = PushGatewayProtocol.unregisterEndpoint(
                SERVICE, "com.librespan.debug", ENDPOINT);
        final Element command = request.findChild("unregister", PushGatewayProtocol.NAMESPACE);
        assertNotNull(command);
        assertEquals(ENDPOINT, command.getAttribute("endpoint"));
        assertNull(command.getAttribute("node"));
        rejected(() -> PushGatewayProtocol.unregisterEndpoint(
                SERVICE, "com.librespan", "http://invalid.example/x"));
    }

    @Test
    public void serverExpiryIsAcceptedOnlyFromCorrelatedResult() {
        final Iq good = response(Iq.Type.RESULT);
        good.addChild("registered", PushGatewayProtocol.NAMESPACE)
                .setAttribute("node", NODE).setAttribute("secret", "secret")
                .setAttribute("expires", "2000000000");
        assertEquals(2000000000000L,
                PushGatewayProtocol.confirmedRegistration(good, SERVICE, "request-1").expiresAtMillis);
        assertNull(PushGatewayProtocol.confirmedRegistration(good, SERVICE, "old-request"));
        good.findChild("registered", PushGatewayProtocol.NAMESPACE).setAttribute("expires", "invalid");
        assertNull(PushGatewayProtocol.confirmedRegistration(good, SERVICE, "request-1"));
    }
}
